// The sculpt sliders' maths (sculpt.h). Pure: no GL, no files.
#include "sculpt.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

namespace wc {

namespace {

constexpr uint8_t kUnset = 255;

float smooth(float a, float b, float x) {
    if (b == a) return x >= b ? 1.0f : 0.0f;
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}

float gauss(float d, float sigma) { return std::exp(-0.5f * (d * d) / (sigma * sigma)); }

V3 pivotAt(const std::vector<V3>& p, int i) { return i >= 0 && size_t(i) < p.size() ? p[size_t(i)] : V3(); }

// The point of segment a-b nearest p (and how far along it, 0..1).
V3 nearestOn(const V3& a, const V3& b, const V3& p, float* along = nullptr) {
    V3 ab = b - a;
    float l2 = ab.dot(ab);
    if (l2 < 1e-12f) { if (along) *along = 0; return a; }
    float t = std::clamp((p - a).dot(ab) / l2, 0.0f, 1.0f);
    if (along) *along = t;
    return a + ab * t;
}

} // namespace

SculptRig sculptRig(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const SculptBones& b) {
    SculptRig r;
    const size_t n = pivots.size();
    r.group.assign(n, kUnset);
    auto seed = [&](int i, uint8_t g) { if (i >= 0 && size_t(i) < n) r.group[size_t(i)] = g; };
    seed(b.pelvis, kGroupPelvis); seed(b.waist, kGroupWaist); seed(b.chest, kGroupChest);
    seed(b.neck, kGroupNeck); seed(b.head, kGroupHead);   // (the head: no move touches it)
    for (int k = 0; k < 2; ++k) {
        seed(b.breast[k], k == 0 ? kGroupBreastL : kGroupBreastR);
        seed(b.clavicle[k], kGroupClavicle);
        seed(b.upperArm[k], k == 0 ? kGroupUpperArmL : kGroupUpperArmR);
        seed(b.forearm[k], k == 0 ? kGroupForearmL : kGroupForearmR);
        seed(b.hand[k], kGroupOther);            // the hands and fingers keep their size
        seed(b.thigh[k], k == 0 ? kGroupThighL : kGroupThighR);
        seed(b.calf[k], kGroupCalf);
        seed(b.ankle[k], kGroupFoot);            // the feet keep their size
    }
    // a bone without a group takes its nearest named ancestor's (twist bones, fingers, the jaw)
    for (size_t i = 0; i < n; ++i) {
        if (r.group[i] != kUnset) continue;
        int p = parents.size() > i ? parents[i] : -1;
        uint8_t g = kGroupOther;
        for (int guard = 0; p >= 0 && size_t(p) < n && guard < 256; ++guard) {
            if (r.group[size_t(p)] != kUnset) { g = r.group[size_t(p)]; break; }
            p = parents[size_t(p)];
        }
        r.group[i] = g == kUnset ? uint8_t(kGroupOther) : g;
    }
    r.pelvis = pivotAt(pivots, b.pelvis);
    r.waist = b.waist >= 0 ? pivotAt(pivots, b.waist) : r.pelvis + V3(0, 0, 0.1f);
    r.chest = b.chest >= 0 ? pivotAt(pivots, b.chest) : r.waist + V3(0, 0, 0.15f);
    r.neck = b.neck >= 0 ? pivotAt(pivots, b.neck) : r.chest + V3(0, 0, 0.15f);
    r.head = b.head >= 0 ? pivotAt(pivots, b.head) : r.neck + V3(0, 0, 0.1f);
    r.unit = std::max(r.pelvis.z, 0.1f);
    const float U = r.unit;
    r.hasBreastBones = b.breast[0] >= 0 && b.breast[1] >= 0;
    for (int k = 0; k < 2; ++k) {
        float side = k == 0 ? 1.0f : -1.0f;      // left is +y
        r.breast[k] = b.breast[k] >= 0 ? pivotAt(pivots, b.breast[k]) : r.chest + V3(0.09f * U, side * 0.08f * U, -0.06f * U);
        r.hip[k] = b.thigh[k] >= 0 ? pivotAt(pivots, b.thigh[k]) : r.pelvis + V3(0, side * 0.1f * U, -0.05f * U);
        r.knee[k] = b.calf[k] >= 0 ? pivotAt(pivots, b.calf[k]) : r.hip[k] + V3(0, 0, -0.45f * U);
        r.ankle[k] = b.ankle[k] >= 0 ? pivotAt(pivots, b.ankle[k]) : r.knee[k] + V3(0, 0, -0.45f * U);
        r.shoulder[k] = b.upperArm[k] >= 0 ? pivotAt(pivots, b.upperArm[k]) : r.chest + V3(0, side * 0.18f * U, 0.05f * U);
        r.elbow[k] = b.forearm[k] >= 0 ? pivotAt(pivots, b.forearm[k]) : r.shoulder[k] + V3(0, side * 0.3f * U, 0);
        r.wrist[k] = b.hand[k] >= 0 ? pivotAt(pivots, b.hand[k]) : r.elbow[k] + V3(0, side * 0.28f * U, 0);
    }
    r.ok = b.pelvis >= 0;
    return r;
}

// The rig of a model by its WoW-side bone names (M2Model::findWowBone): the game's own models and an HD figure's rig (its pivots, hdscene.cpp) alike.
SculptRig sculptRigOf(const M2Model& m) {
    SculptBones b;
    auto find = [&](const char* a, const char* alt = nullptr) { int i = m.findWowBone(a); return i >= 0 || !alt ? i : m.findWowBone(alt); };
    b.pelvis = find("Pelvis", "Root");
    b.waist = find("Spine1", "Waist");
    b.chest = find("Chest", "Spine2");
    b.neck = find("Neck1");
    b.head = find("Head");
    const char* side[2] = {"L_", "R_"};
    for (int k = 0; k < 2; ++k) {
        std::string s = side[k];
        b.breast[k] = m.findWowBone(s + "Breast");
        b.clavicle[k] = m.findWowBone(s + "Clavicle");
        b.upperArm[k] = m.findWowBone(s + "UpperArm");
        b.forearm[k] = m.findWowBone(s + "Forearm");
        b.hand[k] = m.findWowBone(s + "Hand");
        b.thigh[k] = m.findWowBone(s + "Thigh");
        b.calf[k] = m.findWowBone(s + "Calf");
        b.ankle[k] = m.findWowBone(s + "Ankle");
    }
    std::vector<V3> pivots(m.bones.size());
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) { pivots[i] = m.bones[i].pivot; parents[i] = m.bones[i].parent; }
    return sculptRig(pivots, parents, b);
}

bool sculptActive(const float* values, size_t count) {
    if (!values) return false;
    for (size_t i = 0; i < count && i < size_t(kSculptValues); ++i) {
        const size_t k = i % size_t(kSculptCount);
        if (k != kSculptHeight && k != kSculptLegLength && std::fabs(values[i]) > 1e-4f) return true;
    }
    return false;
}

namespace {

// The spine's depth at a height: an HD figure's is upright (its waist), the game's bodies follow their own curve.
float spineDepth(const SculptRig& rig, bool gameBody, float z) {
    if (!gameBody) return rig.waist.x;
    const V3 j[4] = {rig.pelvis, rig.waist, rig.chest, rig.neck};
    if (z <= j[0].z) return j[0].x;
    for (int i = 0; i < 3; ++i) {
        if (j[i + 1].z <= j[i].z + 1e-5f) return rig.waist.x;   // (joints not climbing: upright)
        if (z <= j[i + 1].z) return j[i].x + (j[i + 1].x - j[i].x) * (z - j[i].z) / (j[i + 1].z - j[i].z);
    }
    return j[3].x;
}

// A place's cell (a UV seam's or a geoset's copies at one point share it), `q` its size.
uint64_t placeKey(const V3& p, float q) {
    auto c = [&](float v) { return uint64_t(int64_t(std::floor(v / q)) & 0x1FFFFF); };
    return c(p.x) << 42 | c(p.y) << 21 | c(p.z);
}

// Whether a slider moves the mesh itself (Height and Leg Length are the actor's and the skeleton's; Body Fat and Muscle add
// shares of the others).
bool isMove(int k) { return k != kSculptHeight && k != kSculptLegLength && k != kSculptBodyFat && k != kSculptMuscle; }

// Body Fat's and Muscle's shares of the moves (the last: on the game's bodies only).
struct Share { int slider; float share; bool gameOnly; };
constexpr Share kFatShares[] = {{kSculptWaist, 0.6f, false}, {kSculptBelly, 0.7f, false}, {kSculptHips, 0.4f, false},
                                {kSculptButt, 0.4f, false}, {kSculptThighs, 0.6f, false}, {kSculptUpperArms, 0.6f, false},
                                {kSculptForearms, 0.35f, false}, {kSculptCalves, 0.4f, false}, {kSculptBust, 0.25f, false},
                                {kSculptNeck, 0.4f, true}};
constexpr Share kMuscleShares[] = {{kSculptShoulders, 0.4f, false}, {kSculptUpperArms, 0.7f, false}, {kSculptForearms, 0.5f, false},
                                   {kSculptChest, 0.7f, false}, {kSculptThighs, 0.4f, false}, {kSculptCalves, 0.5f, false},
                                   {kSculptWaist, -0.25f, false}, {kSculptBelly, -0.3f, false}, {kSculptNeck, 0.5f, true}};

// The sliders (and their balances) read as applySculpt takes them: clamped to -1..1, Height's and Leg Length's balance none.
// Returns whether any balance is set.
bool readValues(const float* values, size_t count, float* s0, float* bal) {
    bool sided = false;
    for (int i = 0; i < kSculptCount; ++i) s0[i] = bal[i] = 0;
    for (size_t i = 0; i < count && i < size_t(kSculptValues); ++i) {
        const float v = std::isfinite(values[i]) ? std::clamp(values[i], -1.0f, 1.0f) : 0.0f;
        if (i < size_t(kSculptCount)) s0[i] = v;
        else if (v != 0 && i - kSculptCount != size_t(kSculptHeight) && i - kSculptCount != size_t(kSculptLegLength)) { bal[i - kSculptCount] = v; sided = true; }
    }
    return sided;
}

// The values on one side (`side`: 1 the left, -1 the right, between them across the middle), with Body Fat and Muscle as
// shares of the other moves (the per-vertex cap still bounds the sum).
void composeSide(const float* s0, const float* bal, float side, bool gameBody, float* s) {
    for (int i = 0; i < kSculptCount; ++i) s[i] = std::clamp(s0[i] + side * bal[i], -1.0f, 1.0f);
    const float fat = s[kSculptBodyFat], mus = s[kSculptMuscle];
    auto add = [s](int k, float v) { s[k] = std::clamp(s[k] + v, -1.5f, 1.5f); };
    if (fat != 0) for (const Share& x : kFatShares) if (gameBody || !x.gameOnly) add(x.slider, x.share * fat);
    if (mus != 0) for (const Share& x : kMuscleShares) if (gameBody || !x.gameOnly) add(x.slider, x.share * mus);
    if (!gameBody) s[kSculptNeck] = 0;   // (an HD figure's neck ring meets the Warcraft head: kept)
}

// One vertex's moves for every slider at 1 (D[k], model units; the sliders that aren't moves, or that `need` leaves out: zero).
// `p` is its position before the sculpt. False: a vertex no move touches (the head, hands and feet, or no weights).
bool vertexMoves(const SculptRig& rig, const SculptOptions& opt, const M2Vertex& v, const V3& p, const bool* need, V3* D) {
    for (int k = 0; k < kSculptCount; ++k) D[k] = V3();
    float W[kGroupCount] = {};
    float total = 0;
    for (int k = 0; k < 4; ++k) {
        if (!v.weights[k]) continue;
        uint8_t g = v.bones[k] < rig.group.size() ? rig.group[v.bones[k]] : uint8_t(kGroupOther);
        float w = v.weights[k] / 255.0f;
        W[g] += w; total += w;
    }
    if (total <= 0) return false;
    for (float& w : W) w /= total;
    if (W[kGroupOther] + W[kGroupHead] + W[kGroupFoot] > 0.999f) return false;   // (the head, hands and feet: no move)
    const float U = rig.unit;
    if (opt.gameBody) {
        // at the body's middle line a side's groups are taken as both sides' mean (a model's weights there lean one way or
        // the other; the mean keeps the moves mirror-symmetric where the vertex is its own mirror image)
        const float a = smooth(0.0f, 0.03f * U, std::fabs(p.y));
        if (a < 1) {
            const uint8_t pairs[4][2] = {{kGroupBreastL, kGroupBreastR}, {kGroupThighL, kGroupThighR}, {kGroupUpperArmL, kGroupUpperArmR},
                                         {kGroupForearmL, kGroupForearmR}};
            for (const auto& pr : pairs) {
                const float mean = 0.5f * (W[pr[0]] + W[pr[1]]);
                W[pr[0]] = a * W[pr[0]] + (1 - a) * mean;
                W[pr[1]] = a * W[pr[1]] + (1 - a) * mean;
            }
        }
    }
    auto on = [need](int k) { return !need || need[k]; };
    const float spineX = spineDepth(rig, opt.gameBody, p.z);
    const float front = smooth(-0.02f * U, 0.06f * U, p.x - spineX);
    const float arms = W[kGroupUpperArmL] + W[kGroupUpperArmR] + W[kGroupForearmL] + W[kGroupForearmR];
    const float armFree = 1 - std::min(arms, 1.0f);
    const float ay = std::fabs(p.y), sgn = p.y >= 0 ? 1.0f : -1.0f;

    // the bust: grows about a centre behind each breast (outward and forward), and lifts
    float bust[2];
    for (int k = 0; k < 2; ++k) {
        if (rig.hasBreastBones && opt.gameBody) {
            // the game's women: their breast bones' weights are light and patchy (most of a breast's skin weighted under a tenth to
            // them), so their bust is a soft mound round each breast bone by place, on the chest's front, as far as it is the chest's
            const V3& b = rig.breast[k];
            const float torso = std::min(1.0f, W[kGroupChest] + W[kGroupBreastL] + W[kGroupBreastR] + 0.6f * W[kGroupClavicle] + 0.5f * W[kGroupWaist]);
            const float ry = (p.y - b.y) / (0.085f * U), rz = (p.z - b.z) / (0.085f * U);
            const float q = ry * ry + rz * rz;
            bust[k] = q < 1 ? torso * smooth(0.0f, 1.0f, 1 - q) * smooth(b.x - 0.04f * U, b.x + 0.01f * U, p.x) : 0.0f;
        } else if (rig.hasBreastBones) bust[k] = W[k == 0 ? kGroupBreastL : kGroupBreastR];
        else {
            float side = k == 0 ? smooth(-0.01f * U, 0.02f * U, p.y) : smooth(-0.01f * U, 0.02f * U, -p.y);
            bust[k] = (W[kGroupChest] + W[kGroupBreastL] + W[kGroupBreastR]) * front * side *
                      gauss(p.z - rig.breast[k].z, 0.07f * U) * gauss(std::fabs(p.y) - std::fabs(rig.breast[k].y), 0.07f * U);
        }
        if (opt.gameBody) bust[k] *= armFree;   // (an arm's skin beside the chest stays an arm)
    }
    const bool mound = rig.hasBreastBones && opt.gameBody;   // (the game's women's bust by place, above)
    for (int k = 0; k < 2; ++k) {
        if (bust[k] <= 0) continue;
        const float side = k == 0 ? 1.0f : -1.0f;
        const V3& b = rig.breast[k];
        const V3 c = b - V3(0.05f * U, 0, 0);
        const float tip = smooth(0.0f, 0.07f * U, p.x - c.x);   // (toward the tip)
        if (on(kSculptBust)) {
            V3 r = p - c;
            r.y *= 0.6f;                                     // more forward than outward: the two never meet
            // the mound grows round, as if filled (a share of its size outward from the centre and the same depth all over),
            // not drawn out to a point
            if (mound) D[kSculptBust] = D[kSculptBust] + (r.normalized() * (0.022f * U) + r * 0.14f) * bust[k];
            else D[kSculptBust] = D[kSculptBust] + r * (0.4f * bust[k]);
        }
        if (on(kSculptBustLift)) D[kSculptBustLift].z += 0.035f * U * bust[k] * std::max(front, 0.3f) * (mound ? 0.4f + 0.6f * tip : 1.0f);
        // the bust's width (each breast about its own centre, side to side) and spacing (both apart or together)
        if (on(kSculptBustWidth)) D[kSculptBustWidth].y += (p.y - b.y) * 0.6f * bust[k];
        if (on(kSculptBustSpacing))   // (the game's women: the cleavage's middle kept, so the two never cross it)
            D[kSculptBustSpacing].y += side * 0.04f * U * bust[k] * (mound ? smooth(0.0f, 0.04f * U, side * p.y) : 1.0f);
        // the designer's shapes: how far forward of the chest wall (`out`), toward the tip (`tip`), below or above the breast's
        // centre
        const float out = std::max(0.0f, p.x - c.x);
        const float below = smooth(b.z + 0.02f * U, b.z - 0.05f * U, p.z);
        const float above = smooth(b.z - 0.01f * U, b.z + 0.06f * U, p.z);
        if (on(kSculptBustProjection))                                                                 // forward from the wall
            D[kSculptBustProjection].x += mound ? 0.032f * U * bust[k] * (0.25f + 0.75f * tip) : out * 0.45f * bust[k];
        if (on(kSculptBustDroop)) {                                                                    // down, the tip leading
            // (turned down about the breast's root: the farther out of the chest, the lower; the chest round it kept)
            D[kSculptBustDroop].z -= (mound ? 0.5f * std::max(0.0f, p.x - b.x) : 0.04f * U * tip) * bust[k];
            D[kSculptBustDroop].x += 0.015f * U * bust[k] * tip * below;   // (the underside rolls out over the fold)
        }
        if (on(kSculptBustUpper))                                                                      // the upper slope filled
            D[kSculptBustUpper].x += mound ? 0.03f * U * bust[k] * above * (1 - 0.7f * tip) : out * 0.5f * bust[k] * above;
        if (on(kSculptBustRound)) {                                                                    // fuller round the tip
            D[kSculptBustRound].y += (p.y - b.y) * 0.35f * bust[k] * tip;
            D[kSculptBustRound].z += (p.z - b.z) * 0.35f * bust[k] * tip;
        }
        if (on(kSculptBustSide)) D[kSculptBustSide].y += side * 0.03f * U * bust[k] * (0.2f + 0.8f * smooth(0.0f, 0.05f * U, side * (p.y - b.y)));
        if (on(kSculptUnderbust)) {                                                                    // the underside fuller
            D[kSculptUnderbust].z -= 0.025f * U * bust[k] * below;
            D[kSculptUnderbust].x += 0.02f * U * bust[k] * below * tip;
        }
    }

    // the chest (the pectorals, a man's or a woman's above the bust): its front forward, a little wider
    if (on(kSculptChest)) {
        float m = (W[kGroupChest] + 0.5f * W[kGroupClavicle] + W[kGroupBreastL] + W[kGroupBreastR]) * front * armFree *
                  gauss(p.z - (rig.chest.z + 0.02f * U), 0.09f * U);
        D[kSculptChest].x += std::max(0.0f, p.x - spineX) * 0.35f * m;
        D[kSculptChest].y += p.y * 0.06f * m;
    }
    // the pectorals' definition: their lower half forward, a narrower band than the chest's
    if (on(kSculptPecs)) {
        float m = (W[kGroupChest] + 0.5f * W[kGroupClavicle] + W[kGroupBreastL] + W[kGroupBreastR]) * front * armFree *
                  gauss(p.z - (rig.chest.z - 0.03f * U), 0.05f * U) * gauss(ay - 0.08f * U, 0.05f * U);
        D[kSculptPecs].x += 0.045f * U * m;
    }

    // the waist: wider or narrower about the spine
    if (on(kSculptWaist)) {
        float m = W[kGroupWaist] * (1 - arms) * (1 - 0.6f * (bust[0] + bust[1]));
        D[kSculptWaist].x += (p.x - spineX) * 0.2f * m;
        D[kSculptWaist].y += p.y * 0.2f * m;
    }
    // the belly: forward, a soft band round the navel (the front scaled out from the spine: never folds)
    if (on(kSculptBelly)) {
        float m = (W[kGroupWaist] + 0.6f * W[kGroupPelvis]) * gauss(p.z - (rig.waist.z - 0.04f * U), 0.1f * U);
        if (opt.gameBody) m *= armFree;
        D[kSculptBelly].x += std::max(0.0f, p.x - spineX) * 0.55f * m;
    }
    // the abdomen's definition: the belly's front flatter, the middle line drawn in, the two columns beside it kept
    if (on(kSculptAbs)) {
        float m = (W[kGroupWaist] + 0.5f * W[kGroupPelvis] + 0.3f * W[kGroupChest]) * front * armFree * gauss(p.z - rig.waist.z, 0.1f * U);
        D[kSculptAbs].x += (-std::max(0.0f, p.x - spineX) * 0.2f - 0.006f * U * (1 - smooth(0.0f, 0.02f * U, ay)) +
                            0.005f * U * gauss(ay - 0.035f * U, 0.015f * U)) * m;
    }
    // the hips: wider across, a band round the hip joints
    const float thighs = W[kGroupThighL] + W[kGroupThighR];
    if (on(kSculptHips)) {
        float m = (W[kGroupPelvis] + thighs) * gauss(p.z - (rig.pelvis.z - 0.06f * U), 0.12f * U);
        if (opt.gameBody) m *= armFree;   // (a hand hanging by the hip stays where it is)
        D[kSculptHips].y += p.y * 0.18f * m;
    }
    // the butt: back, a band under the pelvis (the back scaled out from the spine); its lift: that band raised
    float mb = (W[kGroupPelvis] + 0.7f * thighs) * gauss(p.z - (rig.pelvis.z - 0.07f * U), 0.09f * U);
    if (opt.gameBody) mb *= armFree;
    const float back = smooth(0.0f, 0.05f * U, spineX - p.x);
    if (on(kSculptButt)) D[kSculptButt].x += std::min(0.0f, p.x - spineX) * 0.6f * mb;
    if (on(kSculptButtLift)) D[kSculptButtLift].z += 0.045f * U * mb * back;
    // the designer's butt shapes, about each cheek's centre (below the hip joints, behind)
    const float cheekY = 0.85f * 0.5f * (std::fabs(rig.hip[0].y) + std::fabs(rig.hip[1].y));
    const float cheekZ = rig.pelvis.z - 0.08f * U;
    if (mb > 0 && back > 0) {
        if (on(kSculptButtWidth)) D[kSculptButtWidth].y += p.y * 0.16f * mb * back;                 // the back half wider
        if (on(kSculptButtProjection)) D[kSculptButtProjection].x -= 0.04f * U * mb * back * gauss(ay - cheekY, 0.07f * U);
        if (on(kSculptButtRound)) {                                                                    // fuller round each cheek
            D[kSculptButtRound].y += (p.y - sgn * cheekY) * 0.22f * mb * back * smooth(0.0f, 0.03f * U, ay);
            D[kSculptButtRound].z += (p.z - cheekZ) * (p.z < cheekZ ? 0.22f : 0.08f) * mb * back;   // (mostly down: the waist above kept)
        }
        if (on(kSculptCheekGap)) D[kSculptCheekGap].x += 0.025f * U * mb * back * (1 - smooth(0.005f * U, 0.04f * U, ay));
    }
    // the hip dip: the side below the hip bone drawn in (by how far round to the side of the spine the skin lies)
    if (on(kSculptHipDip)) {
        float band = (W[kGroupPelvis] + thighs) * gauss(p.z - (rig.pelvis.z - 0.03f * U), 0.05f * U);
        if (opt.gameBody) band *= armFree;
        const float dx = p.x - spineX, r2 = dx * dx + ay * ay;
        const float lateral = r2 > 1e-12f ? ay * ay / r2 : 0.0f;
        D[kSculptHipDip].y -= sgn * 0.035f * U * band * lateral * lateral * smooth(0.04f * U, 0.1f * U, ay);
    }
    // the underbutt: the fold where the cheek meets the thigh, fuller (back and down)
    if (on(kSculptUnderbutt)) {
        float band = (W[kGroupPelvis] + thighs) * gauss(p.z - (rig.pelvis.z - 0.16f * U), 0.06f * U) * gauss(ay - cheekY, 0.08f * U);
        if (opt.gameBody) band *= armFree;
        const float behind = smooth(0.0f, 0.04f * U, spineX - p.x);
        D[kSculptUnderbutt].x -= 0.04f * U * band * behind;
        D[kSculptUnderbutt].z -= 0.015f * U * band * behind;
    }
    // the limbs: thicker or thinner about their bones' axes; on the game's bodies faded out before the wrists and
    // ankles (`fade`: from that share of the bone on)
    auto limb = [&](int slider, float scale, float w, const V3& a, const V3& b, float fade, float rise = 0) {
        if (!on(slider) || w <= 0) return;
        float t = 0;
        V3 r = p - nearestOn(a, b, p, &t);
        float k = fade < 1 ? 1 - smooth(fade, fade + 0.25f, t) : 1.0f;   // (nothing from 0.25 past it)
        if (rise > 0) k *= smooth(0.0f, rise, t);                         // (and from nothing at the joint it starts at)
        D[slider] = D[slider] + r * (0.3f * scale * w * k);
    };
    const float wristFade = opt.gameBody ? 0.6f : 1.0f;
    for (int k = 0; k < 2; ++k) {
        const float thigh = W[k == 0 ? kGroupThighL : kGroupThighR];
        const float upper = W[k == 0 ? kGroupUpperArmL : kGroupUpperArmR], fore = W[k == 0 ? kGroupForearmL : kGroupForearmR];
        limb(kSculptThighs, 1, thigh, rig.hip[k], rig.knee[k], 1.0f);
        limb(kSculptArms, 1, upper, rig.shoulder[k], rig.elbow[k], 1.0f);
        limb(kSculptArms, 1, fore, rig.elbow[k], rig.wrist[k], wristFade);
        limb(kSculptUpperArms, 1, upper, rig.shoulder[k], rig.elbow[k], opt.gameBody ? 0.75f : 1.0f);   // (the elbow kept)
        limb(kSculptForearms, 1, fore, rig.elbow[k], rig.wrist[k], 0.6f, opt.gameBody ? 0.2f : 0.0f);
        // the calves (one group for both: the side by where the vertex is, both halves at the middle line)
        const float mine = smooth(-0.03f * U, 0.03f * U, k == 0 ? p.y : -p.y);
        limb(kSculptCalves, 0.75f, W[kGroupCalf] * mine, rig.knee[k], rig.ankle[k], 0.6f, 0.35f);   // (the knee kept)
    }
    // the neck (the game's bodies): thicker or thinner about its axis, faded out before the head
    if (on(kSculptNeck) && W[kGroupNeck] > 0) {
        float t = 0;
        V3 r = p - nearestOn(rig.neck, rig.head, p, &t);
        D[kSculptNeck] = D[kSculptNeck] + r * (0.25f * W[kGroupNeck] * (1 - smooth(0.6f, 1.0f, t)));
    }
    // the shoulders: broader or narrower, nothing at the body's middle line
    if (on(kSculptShoulders)) {
        float upper = 0.8f;
        if (opt.gameBody) {   // (the upper arm leans out from the shoulder, the elbow kept: no step where the forearm starts)
            float t = 0;
            nearestOn(rig.shoulder[p.y >= 0 ? 0 : 1], rig.elbow[p.y >= 0 ? 0 : 1], p, &t);
            upper *= 1 - smooth(0.0f, 0.9f, t);
        }
        float m = W[kGroupClavicle] + upper * (W[kGroupUpperArmL] + W[kGroupUpperArmR]) +
                  W[kGroupChest] * smooth(0.06f * U, 0.16f * U, std::fabs(p.y)) * smooth(rig.chest.z - 0.1f * U, rig.chest.z + 0.05f * U, p.z);
        m = std::min(m, 1.0f) * smooth(0.0f, 0.04f * U, std::fabs(p.y));
        D[kSculptShoulders].y += sgn * 0.04f * U * m;
    }
    // the shoulders' slope: their tops lowered (or squared up), the clavicles and the upper arm's top, not the middle
    if (on(kSculptShoulderSlope)) {
        const int k = p.y >= 0 ? 0 : 1;
        float t = 0;
        nearestOn(rig.shoulder[k], rig.elbow[k], p, &t);
        float m = W[kGroupClavicle] + (W[kGroupUpperArmL] + W[kGroupUpperArmR]) * (1 - smooth(0.0f, 0.7f, t)) +
                  W[kGroupChest] * smooth(0.05f * U, 0.15f * U, std::fabs(p.y)) * smooth(rig.chest.z - 0.05f * U, rig.chest.z + 0.08f * U, p.z);
        m = std::min(m, 1.0f) * smooth(0.02f * U, 0.08f * U, std::fabs(p.y));
        D[kSculptShoulderSlope].z -= 0.055f * U * m;
    }
    return true;
}

} // namespace

size_t applySculpt(const SculptRig& rig, std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                   const float* values, size_t count, const SculptOptions& opt, SculptStats* stats) {
    if (stats) *stats = SculptStats();
    if (!rig.ok || verts.empty() || !sculptActive(values, count)) return 0;
    float s0[kSculptCount], bal[kSculptCount];
    const bool sided = readValues(values, count, s0, bal);
    float s[kSculptCount];
    composeSide(s0, bal, 0, opt.gameBody, s);
    // the moves any vertex takes (a slider at 0 on both sides, with Body Fat's and Muscle's shares: not worked out)
    bool need[kSculptCount];
    {
        float lo[kSculptCount], hi[kSculptCount];
        composeSide(s0, bal, -1, opt.gameBody, lo);
        composeSide(s0, bal, 1, opt.gameBody, hi);
        for (int k = 0; k < kSculptCount; ++k) need[k] = isMove(k) && (s[k] != 0 || lo[k] != 0 || hi[k] != 0);
    }
    const float U = rig.unit;
    const float maxMove = kSculptMaxShare * U;

    std::vector<V3> before(verts.size());
    for (size_t i = 0; i < verts.size(); ++i) before[i] = V3(verts[i].pos[0], verts[i].pos[1], verts[i].pos[2]);
    std::vector<uint8_t> pinned(verts.size(), 0);
    for (uint32_t i : opt.pinned) if (i < pinned.size()) pinned[i] = 1;

    std::vector<V3> disp(verts.size());
    std::vector<uint8_t> moves(verts.size(), 0);
    V3 D[kSculptCount];
    for (size_t vi = 0; vi < verts.size(); ++vi) {
        if (pinned[vi]) continue;
        const V3 p = before[vi];
        if (!vertexMoves(rig, opt, verts[vi], p, need, D)) continue;
        if (sided) composeSide(s0, bal, 2 * smooth(-0.015f * U, 0.015f * U, p.y) - 1, opt.gameBody, s);   // (this side's values)
        V3 d;
        for (int k = 0; k < kSculptCount; ++k) if (need[k] && s[k] != 0) d = d + D[k] * s[k];

        float len = d.length();
        if (len > maxMove) { d = d * (maxMove / len); len = maxMove; }
        if (len < 1e-7f) continue;
        disp[vi] = d;
        moves[vi] = 1;
    }

    // a vertex's place (a UV seam's or a geoset's copies at one point share it: they move alike and share a normal)
    const float q = 1e-4f * U;
    auto key = [&](const V3& p) {
        auto c = [&](float v) { return uint64_t(int64_t(std::floor(v / q)) & 0x1FFFFF); };
        return c(p.x) << 42 | c(p.y) << 21 | c(p.z);
    };
    // triangles that would turn over (or, on the game's bodies, shrink to a tenth) eased back: their corners' moves halved
    // by place, at most six times, then dropped
    size_t eased = 0;
    if (!indices.empty()) {
        std::unordered_map<uint64_t, uint32_t> placeOf;
        placeOf.reserve(verts.size() / 8 + 16);
        std::vector<uint32_t> place(verts.size(), 0);
        for (size_t i = 0; i < verts.size(); ++i) {
            if (!moves[i]) continue;   // (only a moving corner is eased)
            auto ins = placeOf.emplace(key(before[i]), uint32_t(placeOf.size()));
            place[i] = ins.first->second;
        }
        if (opt.gameBody && !placeOf.empty()) {
            // copies at one place (two geosets meeting, weighted differently) move as one: their moves averaged, a copy
            // that would stay included, so no seam opens between sections drawn together
            for (size_t i = 0; i < verts.size(); ++i) {
                if (moves[i] || pinned[i]) continue;
                auto it = placeOf.find(key(before[i]));
                if (it != placeOf.end()) { place[i] = it->second; moves[i] = 1; }
            }
            std::vector<V3> sum(placeOf.size());
            std::vector<uint16_t> n(placeOf.size(), 0);
            for (size_t i = 0; i < verts.size(); ++i) if (moves[i]) { sum[place[i]] = sum[place[i]] + disp[i]; if (n[place[i]] < 65535) ++n[place[i]]; }
            for (size_t i = 0; i < verts.size(); ++i) if (moves[i] && n[place[i]] > 1) disp[i] = sum[place[i]] * (1.0f / n[place[i]]);
        }
        std::vector<float> ease(placeOf.size() + 1, 1.0f);
        for (int round = 0; round < 7; ++round) {
            size_t bad = 0;
            for (size_t t = 0; t + 2 < indices.size(); t += 3) {
                uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
                if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
                if (!moves[a] && !moves[b] && !moves[c]) continue;
                V3 pa = before[a] + disp[a] * ease[place[a]], pb = before[b] + disp[b] * ease[place[b]], pc = before[c] + disp[c] * ease[place[c]];
                V3 n0 = (before[b] - before[a]).cross(before[c] - before[a]);
                float a0 = n0.dot(n0);
                if (a0 < 1e-20f) continue;
                float kept = (pb - pa).cross(pc - pa).dot(n0);
                if (kept >= (opt.gameBody ? 0.1f : 0.0f) * a0) continue;
                ++bad;
                for (uint32_t i : {a, b, c}) if (moves[i]) ease[place[i]] = round < 6 ? ease[place[i]] * 0.5f : 0.0f;
            }
            if (!bad) break;
            eased += bad;
        }
        for (size_t i = 0; i < verts.size(); ++i) if (moves[i]) disp[i] = disp[i] * ease[place[i]];
    }

    size_t moved = 0;
    float most = 0;
    std::vector<float> share(verts.size(), 0.0f);
    for (size_t vi = 0; vi < verts.size(); ++vi) {
        if (!moves[vi]) continue;
        float len = disp[vi].length();
        if (len < 1e-7f) continue;
        const V3 p = before[vi] + disp[vi];
        verts[vi].pos[0] = p.x; verts[vi].pos[1] = p.y; verts[vi].pos[2] = p.z;
        share[vi] = std::min(1.0f, len / (0.01f * U));
        most = std::max(most, len);
        ++moved;
    }
    if (!moved) return 0;

    // normals: made again for what moved, from the triangles round each position (UV seams' copies share them)
    size_t flipped = 0;
    if (!indices.empty()) {
        // a place's normal is shared by its copies that faced alike before (a UV seam's two, or two sections meeting), not by
        // a copy facing another way (an overlapping hidden geoset's, a fold's other side)
        struct Entry { V3 n0, acc; int next; };
        std::vector<Entry> ents;
        std::unordered_map<uint64_t, int> first;
        first.reserve(verts.size() / 4 + 16);
        auto slot = [&](uint32_t i, bool make) -> int {
            const V3 n0(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
            const uint64_t k = key(before[i]);
            auto it = first.find(k);
            int e = it == first.end() ? -1 : it->second;
            for (int x = e; x >= 0; x = ents[size_t(x)].next) if (ents[size_t(x)].n0.dot(n0) > 0.7f) return x;
            if (!make) return -1;
            ents.push_back({n0, V3(), e});
            first[k] = int(ents.size() - 1);
            return int(ents.size() - 1);
        };
        for (size_t t = 0; t + 2 < indices.size(); t += 3) {
            uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
            if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
            V3 pa(verts[a].pos[0], verts[a].pos[1], verts[a].pos[2]), pb(verts[b].pos[0], verts[b].pos[1], verts[b].pos[2]),
               pc(verts[c].pos[0], verts[c].pos[1], verts[c].pos[2]);
            V3 n = (pb - pa).cross(pc - pa);
            V3 n0 = (before[b] - before[a]).cross(before[c] - before[a]);
            if (n0.length() > 1e-10f && n.dot(n0) < 0) ++flipped;
            if (share[a] > 0 || share[b] > 0 || share[c] > 0)
                for (uint32_t i : {a, b, c}) { Entry& en = ents[size_t(slot(i, true))]; en.acc = en.acc + n; }
        }
        for (size_t i = 0; i < verts.size(); ++i) {
            if (share[i] <= 0) continue;
            int e = slot(uint32_t(i), false);
            if (e < 0 || ents[size_t(e)].acc.length() < 1e-12f) continue;
            V3 fresh = ents[size_t(e)].acc.normalized();
            V3 old(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
            V3 n = (old * (1 - share[i]) + fresh * share[i]).normalized();
            verts[i].normal[0] = n.x; verts[i].normal[1] = n.y; verts[i].normal[2] = n.z;
        }
    }
    if (stats) { stats->moved = moved; stats->maxMove = most; stats->flipped = flipped; stats->eased = eased; }
    return moved;
}

// ---- the live sculpt's fields (sculpt.h SculptMorph) ----

bool buildSculptMorph(const SculptRig& rig, const std::vector<M2Vertex>& verts, const SculptOptions& opt, SculptMorph& out) {
    out = SculptMorph();
    if (!rig.ok || verts.empty()) return false;
    const size_t n = verts.size();
    const float U = rig.unit;
    const float tiny = kSculptMorphFloor * U;
    std::vector<uint8_t> pinned(n, 0);
    for (uint32_t i : opt.pinned) if (i < n) pinned[i] = 1;
    bool need[kSculptCount];
    for (int k = 0; k < kSculptCount; ++k) need[k] = isMove(k) && (opt.gameBody || k != kSculptNeck);
    std::vector<uint32_t> first(n + 1, 0);
    std::vector<uint8_t> channel;
    std::vector<V3> delta;
    std::vector<float> side(n, 0.0f);
    channel.reserve(n * 4);
    delta.reserve(n * 4);
    V3 D[kSculptCount];
    auto posOf = [&](size_t i) { return V3(verts[i].pos[0], verts[i].pos[1], verts[i].pos[2]); };
    for (size_t vi = 0; vi < n; ++vi) {
        first[vi] = uint32_t(delta.size());
        const V3 p = posOf(vi);
        side[vi] = 2 * smooth(-0.015f * U, 0.015f * U, p.y) - 1;
        if (pinned[vi] || !vertexMoves(rig, opt, verts[vi], p, need, D)) continue;
        for (int k = 0; k < kSculptCount; ++k)
            if (need[k] && D[k].length() > tiny) { channel.push_back(uint8_t(k)); delta.push_back(D[k]); }
    }
    first[n] = uint32_t(delta.size());
    if (opt.gameBody) {
        // copies at one place (two geosets meeting, weighted differently) move as one, as applySculpt makes them: each copy's
        // fields are the mean of every copy's there (a copy that would stay counting as nothing)
        const float q = 1e-4f * U;
        std::unordered_map<uint64_t, uint32_t> placeOf;
        placeOf.reserve(n / 8 + 16);
        constexpr uint32_t kNone = 0xFFFFFFFFu;
        std::vector<uint32_t> place(n, kNone);
        for (size_t i = 0; i < n; ++i)
            if (first[i + 1] > first[i]) place[i] = placeOf.emplace(placeKey(posOf(i), q), uint32_t(placeOf.size())).first->second;
        for (size_t i = 0; i < n; ++i) {
            if (place[i] != kNone || pinned[i]) continue;
            auto it = placeOf.find(placeKey(posOf(i), q));
            if (it != placeOf.end()) place[i] = it->second;
        }
        std::vector<uint16_t> copies(placeOf.size(), 0);
        for (size_t i = 0; i < n; ++i) if (place[i] != kNone && copies[place[i]] < 65535) ++copies[place[i]];
        // the shared places' means (each its own row of kSculptCount moves)
        std::vector<uint32_t> row(placeOf.size(), kNone);
        std::vector<V3> mean;
        for (size_t i = 0; i < n; ++i) {
            if (place[i] == kNone || copies[place[i]] < 2) continue;
            uint32_t& r = row[place[i]];
            if (r == kNone) { r = uint32_t(mean.size() / kSculptCount); mean.resize(mean.size() + kSculptCount); }
            const float w = 1.0f / copies[place[i]];
            for (uint32_t e = first[i]; e < first[i + 1]; ++e) {
                V3& m = mean[size_t(r) * kSculptCount + channel[e]];
                m = m + delta[e] * w;
            }
        }
        if (!mean.empty()) {
            std::vector<uint32_t> first2(n + 1, 0);
            std::vector<uint8_t> channel2;
            std::vector<V3> delta2;
            channel2.reserve(channel.size() + mean.size() / 4);
            delta2.reserve(delta.size() + mean.size() / 4);
            for (size_t i = 0; i < n; ++i) {
                first2[i] = uint32_t(delta2.size());
                if (place[i] != kNone && copies[place[i]] >= 2) {
                    const V3* m = &mean[size_t(row[place[i]]) * kSculptCount];
                    for (int k = 0; k < kSculptCount; ++k)
                        if (m[k].length() > tiny) { channel2.push_back(uint8_t(k)); delta2.push_back(m[k]); }
                } else {
                    for (uint32_t e = first[i]; e < first[i + 1]; ++e) { channel2.push_back(channel[e]); delta2.push_back(delta[e]); }
                }
            }
            first2[n] = uint32_t(delta2.size());
            first.swap(first2); channel.swap(channel2); delta.swap(delta2);
        }
    }
    out.first.swap(first);
    out.side.swap(side);
    out.channel.swap(channel);
    out.delta.swap(delta);
    out.unit = U;
    return true;
}

void sculptMorphWeights(const float* values, size_t count, bool gameBody, float* sym, float* bal) {
    float s0[kSculptCount], b0[kSculptCount];
    if (values) readValues(values, count, s0, b0);
    else for (int k = 0; k < kSculptCount; ++k) s0[k] = b0[k] = 0;
    for (int k = 0; k < kSculptCount; ++k) { sym[k] = s0[k]; bal[k] = b0[k]; }
    for (const Share& x : kFatShares)
        if (gameBody || !x.gameOnly) { sym[x.slider] += x.share * s0[kSculptBodyFat]; bal[x.slider] += x.share * b0[kSculptBodyFat]; }
    for (const Share& x : kMuscleShares)
        if (gameBody || !x.gameOnly) { sym[x.slider] += x.share * s0[kSculptMuscle]; bal[x.slider] += x.share * b0[kSculptMuscle]; }
    for (int k = 0; k < kSculptCount; ++k) if (!isMove(k) || (!gameBody && k == kSculptNeck)) sym[k] = bal[k] = 0;
}

void applySculptMorph(const SculptMorph& m, const float* sym, const float* bal, std::vector<V3>& positions) {
    const size_t n = std::min(positions.size(), m.vertices());
    for (size_t v = 0; v < n; ++v)
        for (uint32_t e = m.first[v]; e < m.first[v + 1]; ++e) {
            const int c = m.channel[e];
            positions[v] = positions[v] + m.delta[e] * (sym[c] + m.side[v] * bal[c]);
        }
}

} // namespace wc
