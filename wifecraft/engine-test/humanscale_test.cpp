// Human scale on the game's own models (engine/humanscale.h, anim/HumanScale.kt): build/humanscale_test FILES_DIR [KT_FILE]
// FILES_DIR holds the game's files named by their ids (the playable races' models and skeletons); KT_FILE is
// anim/HumanScale.kt (default: the app's), whose numbers must be the ones measured here. Prints, per race, the standing
// height, the scale that makes it a human of its sex, where its head and pelvis then are, how far its feet go under the floor
// as modelled; then a partner pose (both reach a hand to the other's) on pairs of races, old sizes against human scale:
// how far apart the two hands land; then dwarves and gnomes at their own size beside a human-scale partner, on the human
// slots and fitted about the contact point (simulation/PairFit). Also checks HumanScale.kt's dwarf and gnome ratios. No GL context. Exit 0 when all checks pass; files that aren't there are skipped.
#include "../android/app/src/main/cpp/engine/humanscale.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL: " __VA_ARGS__); std::printf("\n"); } } while (0)

namespace {

struct Race { const char* name; uint32_t male, female; };
// ChrModel's DisplayID -> CreatureDisplayInfo (CreatureModelScale 1 for every playable race) -> CreatureModelData's file
const Race kRaces[] = {
    {"human", 1011653, 1000764}, {"orc", 917116, 949470}, {"dwarf", 878772, 950080}, {"night elf", 974343, 921844},
    {"undead", 959310, 997378}, {"tauren", 968705, 986648}, {"gnome", 900914, 940356}, {"troll", 1022938, 1018060},
    {"goblin", 119376, 119369}, {"blood elf", 1100087, 1100258}, {"draenei", 1005887, 1022598}, {"worgen", 307454, 307453},
    {"pandaren", 535052, 589715}, {"zandalari", 1630447, 1662187}, {"kul tiran", 1721003, 1886724},
    {"vulpera", 1890761, 1890759}, {"mechagnome", 2622502, 2564806},
};

float ktNumber(const std::string& text, const std::string& name) {
    std::smatch m;
    std::regex re("\\b" + name + "\\s*=\\s*([0-9.]+)f");
    if (!std::regex_search(text, m, re)) return -1;
    return std::stof(m[1].str());
}

// A pose as the importer writes one, on the reference skeleton: each named bone's direction in model axes (x forward,
// y left, z up); every other bone the clips aim at its reference rest. Returned as one-key rotation tracks (local to the
// nearest tracked bone above, as Retarget makes them), ready for fitBoneAnim.
BoneAnim posed(const M2Model& ref, const std::vector<std::pair<std::string, V3>>& aims) {
    std::map<std::string, Quat> global;
    for (const AnimRest& r : kAnimRest) global[r.bone] = Quat();
    for (const AnimRest& r : kAnimRest) global[r.aim] = global.count(r.aim) ? global[r.aim] : Quat();
    for (const auto& [bone, dir] : aims)
        for (const AnimRest& r : kAnimRest)
            if (bone == r.bone) global[bone] = quatArc(V3(r.x, r.y, r.z).normalized(), dir.normalized());
    BoneAnim anim;
    for (const auto& [bone, g] : global) {
        int b = ref.findWowBone(bone);
        if (b < 0) continue;
        Quat parent;
        for (int i = ref.bones[size_t(b)].parent; i >= 0; i = ref.bones[size_t(i)].parent) {
            bool found = false;
            for (const auto& [n, pg] : global) if (ref.findWowBone(n) == i) { parent = pg; found = true; break; }
            if (found) break;
        }
        Quat q = quatMul(quatInv(parent), g).normalized();
        anim.tracks.push_back({bone, true, {0.0f}, {q.x, q.y, q.z, q.w}});
    }
    anim.tracks.push_back({"Root", false, {0.0f}, {0.0f, 0.0f, 0.0f}});
    return anim;
}

// Where bone `name` lands in the world for model `m` playing `clip` (fitted to it) at (x, 0, 0) facing `facing`, at `scale`;
// `human` > 0: human scale's root motion (that many yards per unit across, its own pelvis up), else the old one.
V3 boneAt(const M2Model& m, BoneAnim clip, const std::string& name, float x, float facing, float scale, float human) {
    fitBoneAnim(clip, m);
    std::vector<BonePose> pa(m.bones.size());
    int root = m.findWowBone("Root");
    float h = root >= 0 ? m.bones[size_t(root)].pivot.z : 0;
    float old = h > 0.05f ? h / kAnimRootHeight : 1.0f;
    StandFit f = measureStand(m);
    for (const BoneAnimTrack& t : clip.tracks) {
        int b = m.findWowBone(t.bone);
        if (b < 0) continue;
        if (t.rotation) { pa[size_t(b)].r = Quat(t.values[0], t.values[1], t.values[2], t.values[3]).normalized(); continue; }
        V3 p(t.values[0], t.values[1], t.values[2]);
        if (human > 0 && b == root && f.ok) {
            float xy = human / scale;
            pa[size_t(b)].t = V3(p.x * xy, p.y * xy, p.z * f.pelvis / kAnimRootHeight + (f.ground < 0 ? -f.ground : 0.0f));
        } else {
            pa[size_t(b)].t = p * old;
        }
    }
    std::vector<M4> mats;
    m.bonesToMatrices(pa, mats);
    int b = m.findWowBone(name);
    V3 local = mats[size_t(b)].point(m.bones[size_t(b)].pivot);
    M4 w = M4::translation(V3(x, 0, 0)) * M4::rotationZ(facing) * M4::scaling(V3(scale, scale, scale));
    return w.point(local);
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: humanscale_test FILES_DIR [HumanScale.kt]\n"); return 2; }
    std::string dir = argv[1];
    std::string kt = argc > 2 ? argv[2] : std::string(__FILE__).substr(0, std::string(__FILE__).rfind('/')) +
        "/../android/app/src/main/java/com/logie/wifecraft/anim/HumanScale.kt";
    FileLookup files = [&](uint32_t f) {
        std::string p = dir + "/" + std::to_string(f);
        FILE* x = std::fopen(p.c_str(), "rb");
        if (!x) return std::string();
        std::fclose(x);
        return p;
    };
    std::map<uint32_t, std::unique_ptr<M2Model>> models;
    auto get = [&](uint32_t id) -> const M2Model* {
        auto it = models.find(id);
        if (it != models.end()) return it->second.get();
        auto m = std::make_unique<M2Model>();
        if (!m->load(id, files)) { std::printf("  (model %u isn't here: %s)\n", id, m->error.c_str()); m.reset(); }
        return (models[id] = std::move(m)).get();
    };

    const M2Model* hm = get(1011653);
    const M2Model* hf = get(1000764);
    if (!hm || !hf) { std::printf("humanscale_test: the human models aren't in %s: skipped\n", dir.c_str()); return 0; }
    StandFit fm = measureStand(*hm), ff = measureStand(*hf);
    CHECK(fm.ok && ff.ok, "the humans can't be measured");
    // the skeleton the clips are converted for: the night elf woman (921844), her pelvis kAnimRootHeight. Retarget scales a
    // Sims body's root motion and a pair's offsets so its pelvis stands that high: they are in units of a Sims (human)
    // body with a 1.4407 pelvis, so human scale takes them by the humans' pelvis over that (PAIR).
    const M2Model* refModel = get(921844);
    if (!refModel) { std::printf("humanscale_test: the reference model isn't in %s: skipped\n", dir.c_str()); return 0; }
    StandFit fr = measureStand(*refModel);
    CHECK(fr.ok && std::fabs(fr.pelvis - kAnimRootHeight) < 2e-3f, "the reference's pelvis %.4f isn't kAnimRootHeight", fr.pelvis);
    float pair = 0.5f * (fm.pelvis + ff.pelvis) / kAnimRootHeight;
    std::printf("human man:   height %.4f (straightened head %.4f, own stance %.4f), pelvis %.4f\n", fm.height(), fm.head, fm.bindHead, fm.pelvis);
    std::printf("human woman: height %.4f (straightened head %.4f, own stance %.4f), pelvis %.4f\n", ff.height(), ff.head, ff.bindHead, ff.pelvis);
    std::printf("reference (night elf woman): height %.4f, pelvis %.4f\n", fr.height(), fr.pelvis);
    std::printf("pair scale (the humans' mean pelvis over the reference's): %.4f\n", pair);

    std::ifstream in(kt);
    std::stringstream text;
    text << in.rdbuf();
    if (text.str().empty()) {
        std::printf("FAIL: %s can't be read\n", kt.c_str()); ++failures;
    } else {
        float km = ktNumber(text.str(), "MALE_HEIGHT"), kf = ktNumber(text.str(), "FEMALE_HEIGHT"), kp = ktNumber(text.str(), "PAIR");
        CHECK(std::fabs(km - fm.height()) < 5e-4f, "HumanScale.kt's MALE_HEIGHT %.4f isn't the human man's %.4f", km, fm.height());
        CHECK(std::fabs(kf - ff.height()) < 5e-4f, "HumanScale.kt's FEMALE_HEIGHT %.4f isn't the human woman's %.4f", kf, ff.height());
        CHECK(std::fabs(kp - pair) < 5e-4f, "HumanScale.kt's PAIR %.4f isn't %.4f", kp, pair);
        // the dwarves' and gnomes' own size over a human's (HumanScale.bodyScale)
        const struct { const char* name; uint32_t file; bool female; } own[] = {
            {"DWARF_MALE", 878772, false}, {"DWARF_FEMALE", 950080, true}, {"GNOME_MALE", 900914, false}, {"GNOME_FEMALE", 940356, true},
        };
        for (const auto& o : own) {
            const M2Model* m = get(o.file);
            if (!m) continue;
            float measured = measureStand(*m).height() / (o.female ? ff.height() : fm.height());
            float k = ktNumber(text.str(), o.name);
            std::printf("%s: %.4f (HumanScale.kt %.4f)\n", o.name, measured, k);
            CHECK(std::fabs(k - measured) < 5e-4f, "HumanScale.kt's %s %.4f isn't %.4f", o.name, k, measured);
        }
    }

    // 1. Every race at human scale: head height in both stances (Warcraft's and the clips'), pelvis, the floor.
    std::printf("\n%-11s %-6s %7s %6s | %-24s | %-6s %-8s\n", "race", "sex", "height", "scale", "head: own / clips stance", "pelvis", "sole gap");
    for (const Race& r : kRaces) {
        for (int s = 0; s < 2; ++s) {
            const M2Model* m = get(s ? r.female : r.male);
            if (!m) continue;
            StandFit f = measureStand(*m);
            CHECK(f.ok, "%s %s can't be measured", r.name, s ? "woman" : "man");
            if (!f.ok) continue;
            float target = s ? ff.height() : fm.height();
            float k = target / f.height();
            float own = f.bindHead * k, clips = f.head * k;
            // the soles: as modelled (the lowest vertex) in Warcraft's stance, and in the clips' stance after the lift
            float sole = m->extentMin.z * k;
            std::printf("%-11s %-6s %7.3f %6.3f | %10.3f / %-11.3f | %6.3f %+.3f\n", r.name, s ? "female" : "male", f.height(), k, own, clips,
                f.pelvis * k, sole);
            CHECK(std::fabs(0.5f * (own + clips) - target) < 1e-3f, "%s: not human height", r.name);
            CHECK(std::fabs(own - target) < 0.13f * target && std::fabs(clips - target) < 0.13f * target,
                  "%s %s: a stance more than 13%% off human height (%.3f, %.3f)", r.name, s ? "woman" : "man", own, clips);
            CHECK(sole > -0.05f && sole < 0.05f, "%s %s: the lowest vertex %.3f yards off the floor", r.name, s ? "woman" : "man", sole);
            CHECK(f.ground <= 1e-3f || f.ground < 0.02f, "%s: the straightened soles above the floor", r.name);
        }
    }

    // 2. The partner pose: A at -d facing +x, B at +d facing -x, each reaching its right hand forward, down a little and in to
    // the line between them, where the other's right hand comes to meet it (the pose turned half round). The slots are where
    // the pose's author (a Sims body: human, its pelvis at kAnimRootHeight) put them: the human man's and woman's mean reach
    // in the pose, at a pelvis of 1.4407 (reference units). Human scale stands them at that times PAIR; the old way stood
    // them as they are, each body at its own size, root motion by its own Root height.
    const M2Model* ref = refModel;
    {
        BoneAnim clip = posed(*ref, {
            {"R_Clavicle", V3(0.25f, -1, 0.05f)}, {"R_UpperArm", V3(0.8f, 0.25f, -0.55f)}, {"R_Forearm", V3(1, 0.3f, 0.05f)},
            {"R_Hand", V3(1, 0.3f, 0.0f)},
        });
        V3 rm = boneAt(*hm, clip, "R_Hand", 0, 0, 1, pair), rf = boneAt(*hf, clip, "R_Hand", 0, 0, 1, pair);
        float dRef = 0.5f * (rm.x + rf.x) / pair;
        std::printf("\npartner pose: the humans' hands %.3f and %.3f forward; slots +-%.3f reference units "
                    "(human scale: +-%.3f yards)\n", rm.x, rf.x, dRef, dRef * pair);
        float dOld = dRef;
        std::printf("%-26s %-28s %s\n", "pair (A, B)", "hands apart, human scale", "old sizes");
        struct P { const char* name; uint32_t a, b; bool af, bf; float limit; };
        // limits: what uniform sizing can do (the rest is each race's proportions: a gnome's wide shoulders and short arms
        // against an orc's long ones; only stretching the limbs would close it), and at most 60% of the old gap
        const P pairs[] = {
            {"human man, human woman", 1011653, 1000764, false, true, 0.15f},
            {"gnome man, orc man", 900914, 917116, false, false, 0.5f},
            {"gnome woman, orc man", 940356, 917116, true, false, 0.45f},
            {"night elf w, blood elf m", 921844, 1100087, true, false, 0.25f},
            {"dwarf man, human woman", 878772, 1000764, false, true, 0.2f},
            {"tauren man, human woman", 968705, 1000764, false, true, 0.1f},
            {"goblin w, draenei w", 119369, 1022598, true, true, 0.3f},
        };
        for (const P& p : pairs) {
            const M2Model* a = get(p.a);
            const M2Model* b = get(p.b);
            if (!a || !b) continue;
            float ka = (p.af ? ff.height() : fm.height()) / measureStand(*a).height();
            float kb = (p.bf ? ff.height() : fm.height()) / measureStand(*b).height();
            V3 ha = boneAt(*a, clip, "R_Hand", -dRef * pair, 0, ka, pair);
            V3 hb = boneAt(*b, clip, "R_Hand", dRef * pair, 3.14159265f, kb, pair);
            V3 oa = boneAt(*a, clip, "R_Hand", -dOld, 0, 1, 0);
            V3 ob = boneAt(*b, clip, "R_Hand", dOld, 3.14159265f, 1, 0);
            float now = (ha - hb).length(), before = (oa - ob).length();
            std::printf("%-26s %.3f (A %.2f,%.2f,%.2f B %.2f,%.2f,%.2f)   %.3f\n", p.name, now, ha.x, ha.y, ha.z, hb.x, hb.y, hb.z, before);
            CHECK(now < p.limit, "%s: the hands land %.3f yards apart", p.name, now);
            CHECK(now <= 0.6f * before, "%s: human scale doesn't bring the hands closer (%.3f, old %.3f)", p.name, now, before);
        }

        // 3. Dwarves and gnomes keep their own size (HumanScale.keepsOwnSize). A, the small one, at its own size (1); B at
        // human scale. The live scene fits gnomes only (HumanScale.pairScale); the dwarves' fitted numbers are for comparison. Unfitted: A on the human slot, its root motion across the floor PAIR yards per unit as everyone's.
        // Fitted (PairFit): A's slot and root motion taken by its size ratio r about the pair's contact point (the slots'
        // midpoint), so its reach, a copy of a human's at r, meets the partner's there. Printed: how far apart the hands
        // land across the floor and up and down, and the reach correction (yards) that would close what is left across.
        std::printf("\nown size (A) with a human-scale partner (B): hands apart across / up-down; unfitted, then fitted\n");
        struct Q { const char* name; uint32_t a, b; bool af, bf; float limitAcross; };
        // limits: what fitting about the contact can do; the rest is the gnome's proportions (wide shoulders, short arms:
        // its hand lands to the side as much as short), which differ by partner (a human woman's reach is short, an orc's long)
        const Q own[] = {
            {"gnome man, human woman", 900914, 1000764, false, true, 0.22f},
            {"gnome woman, human man", 940356, 1011653, true, false, 0.2f},
            {"gnome man, orc man", 900914, 917116, false, false, 0.31f},
            {"mechagnome w, human man", 2564806, 1011653, true, false, 0.2f},
            {"dwarf man, human woman", 878772, 1000764, false, true, 0.12f},
            {"dwarf woman, human man", 950080, 1011653, true, false, 0.2f},
        };
        for (const Q& q : own) {
            const M2Model* a = get(q.a);
            const M2Model* b = get(q.b);
            if (!a || !b) continue;
            float r = measureStand(*a).height() / (q.af ? ff.height() : fm.height());
            float kb = (q.bf ? ff.height() : fm.height()) / measureStand(*b).height();
            V3 hb = boneAt(*b, clip, "R_Hand", dRef * pair, 3.14159265f, kb, pair);
            V3 hu = boneAt(*a, clip, "R_Hand", -dRef * pair, 0, 1, pair);
            V3 hf = boneAt(*a, clip, "R_Hand", -dRef * pair * r, 0, 1, pair * r);
            float acrossU = std::hypot(hu.x - hb.x, hu.y - hb.y), acrossF = std::hypot(hf.x - hb.x, hf.y - hb.y);
            std::printf("%-26s r %.4f | unfitted %.3f / %+.3f | fitted %.3f / %+.3f (A %.2f,%.2f,%.2f B %.2f,%.2f,%.2f) reach corr %+.3f\n",
                q.name, r, acrossU, hu.z - hb.z, acrossF, hf.z - hb.z, hf.x, hf.y, hf.z, hb.x, hb.y, hb.z, hb.x - hf.x);
            CHECK(acrossF < q.limitAcross, "%s: own size, fitted: the hands %.3f yards apart across the floor", q.name, acrossF);
            CHECK(acrossF <= acrossU + 1e-3f, "%s: the fit doesn't bring the hands closer across (%.3f, unfitted %.3f)", q.name, acrossF, acrossU);
        }
    }

    if (failures) { std::printf("%d failed\n", failures); return 1; }
    std::printf("humanscale_test: all passed\n");
    return 0;
}
