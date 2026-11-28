// The toy reactions' layer (toyrig.h). No GL.
#include "toyrig.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>

#include "bonelayer.h"
#include "motionlayer.h"

namespace wc {
namespace toyrig {

namespace {
constexpr float kTwoPi = 6.2831853f;
float fin(float v) { return std::isfinite(v) ? v : 0.0f; }
float lim(float v, float m) { return std::clamp(fin(v), -m, m); }
float chase(float cur, float want, float dt, float tau) { return cur + (want - cur) * (1.0f - std::exp(-dt / tau)); }

void subtree(const std::vector<std::vector<int>>& ch, int root, std::vector<int>& out) {
    out.clear();
    if (root < 0) return;
    std::vector<int> st{root};
    while (!st.empty() && out.size() < 4096) { int b = st.back(); st.pop_back(); out.push_back(b); if (size_t(b) < ch.size()) for (int c : ch[size_t(b)]) st.push_back(c); }
}

enum Role { kPelvis, kWaist, kChest, kNeck, kHead, kLClav, kRClav, kLThigh, kRThigh, kLCalf, kRCalf, kLUp, kRUp, kLFore, kRFore, kLHand, kRHand, kRoles };

void find(State& st, const M2Model& m) {
    st.model = &m;
    auto f = [&](const char* a, const char* b = nullptr) { int i = m.findWowBone(a); return i < 0 && b ? m.findWowBone(b) : i; };
    st.pelvis = f("Pelvis", "Root"); st.waist = f("Spine1", "Waist"); st.chest = f("Chest", "Spine2"); st.neck = f("Neck1"); st.head = f("Head");
    st.lclav = f("L_Clavicle"); st.rclav = f("R_Clavicle"); st.lthigh = f("L_Thigh"); st.rthigh = f("R_Thigh"); st.lcalf = f("L_Calf"); st.rcalf = f("R_Calf");
    for (int k = 0; k < 2; ++k) {
        const char* s = k ? "R_" : "L_";
        st.uarm[k] = m.findWowBone(std::string(s) + "UpperArm"); st.farm[k] = m.findWowBone(std::string(s) + "Forearm"); st.hand[k] = m.findWowBone(std::string(s) + "Hand");
        // the hand's far end: its first child (a finger's root)
        st.finger[k] = -1;
        for (size_t i = 0; i < m.bones.size(); ++i) if (m.bones[i].parent == st.hand[k] && st.hand[k] >= 0) { st.finger[k] = int(i); break; }
    }
    std::vector<std::vector<int>> ch(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) { int p = m.bones[i].parent; if (p >= 0 && size_t(p) < m.bones.size() && size_t(p) != i) ch[size_t(p)].push_back(int(i)); }
    const int roots[kRoles] = {st.pelvis, st.waist, st.chest, st.neck, st.head, st.lclav, st.rclav, st.lthigh, st.rthigh, st.lcalf, st.rcalf,
                               st.uarm[0], st.uarm[1], st.farm[0], st.farm[1], st.hand[0], st.hand[1]};
    st.sub.assign(kRoles, {});
    for (int r = 0; r < kRoles; ++r) subtree(ch, roots[r], st.sub[size_t(r)]);
}

void turn(const State& st, const M2Model& m, std::vector<M4>& mats, int role, int bone, const V3& axis, float angle) {
    if (bone < 0 || size_t(bone) >= mats.size() || !(std::fabs(angle) > 1e-6f)) return;
    const V3 pivot = mats[size_t(bone)].point(m.bones[size_t(bone)].pivot);
    const M4 d = motion::turnAbout(pivot, axis, angle);
    for (int i : st.sub[size_t(role)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
}
} // namespace

float apply(State& st, const M2Model& m, std::vector<M4>& mats, uint32_t nowMs, const V3& entryModel) {
    if (!st.on || mats.size() != m.bones.size()) return 0;
    if (st.model != &m) { find(st, m); st.started = false; }
    float dt = st.started ? float(int32_t(nowMs - st.lastMs)) / 1000.0f : 0.0f;
    st.lastMs = nowMs; st.started = true;
    dt = std::clamp(dt, 0.0f, 0.1f);
    Pose& s = st.shown;
    const Pose& w = st.want;
    const float tau = 0.12f;
    auto ease = [&](float& a, float b) { a = dt > 0 ? chase(a, fin(b), dt, tau) : a; };
    ease(s.breath, w.breath); ease(s.breathHz, std::clamp(w.breathHz, 0.1f, 1.2f)); ease(s.flutter, w.flutter); ease(s.arch, w.arch);
    ease(s.hipTilt, w.hipTilt); ease(s.thighPart, w.thighPart); ease(s.kneeFlex, w.kneeFlex); ease(s.headBack, w.headBack);
    ease(s.shoulder, w.shoulder); ease(s.tremor, w.tremor); ease(s.reachL, w.reachL); ease(s.reachR, w.reachR);
    st.phase += kTwoPi * s.breathHz * dt;
    st.tremorPhase += kTwoPi * kTremorHz * dt;
    if (st.phase > 1e4f) st.phase = std::fmod(st.phase, kTwoPi);
    if (st.tremorPhase > 1e4f) st.tremorPhase = std::fmod(st.tremorPhase, kTwoPi);
    if (std::getenv("WC_TOYDBG")) std::fprintf(stderr, "toyrig: pelvis %d chest %d lthigh %d uarm %d fore %d hand %d finger %d reachL %.2f arch %.3f\n", st.pelvis, st.chest, st.lthigh, st.uarm[0], st.farm[0], st.hand[0], st.finger[0], s.reachL, s.arch);
    const V3 Y(0, 1, 0), X(1, 0, 0);
    const float kM = kMaxTurn;
    const float br = std::sin(st.phase) * 0.5f + 0.5f;
    const float trem = std::sin(st.tremorPhase) * s.tremor;
    const float fl = std::sin(st.tremorPhase * 1.7f + 0.8f) * s.flutter;
    // the pelvis' tilt with the legs held (its turn undone on the thighs), the spine taking half of it back
    const float tilt = lim(s.hipTilt, kM);
    if (std::fabs(tilt) > 1e-6f && st.pelvis >= 0) {
        const V3 pv = mats[size_t(st.pelvis)].point(m.bones[size_t(st.pelvis)].pivot);
        const M4 d = motion::turnAbout(pv, Y, tilt), di = motion::turnAbout(pv, Y, -tilt);
        for (int i : st.sub[kPelvis]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
        for (int r : {int(kLThigh), int(kRThigh)}) for (int i : st.sub[size_t(r)]) if (size_t(i) < mats.size()) mats[size_t(i)] = di * mats[size_t(i)];
        turn(st, m, mats, kWaist, st.waist, Y, -0.5f * tilt);
    }
    // the back: the arch leans the spine and chest back; breathing and the flutter ride on the chest, the neck takes some back
    turn(st, m, mats, kWaist, st.waist, Y, lim(-0.45f * s.arch, kM));
    turn(st, m, mats, kChest, st.chest, Y, lim(-0.55f * s.arch - s.breath * br + 0.4f * fl, kM));
    turn(st, m, mats, kLClav, st.lclav, X, lim(s.shoulder + 0.5f * s.breath * br, kM));
    turn(st, m, mats, kRClav, st.rclav, X, lim(-(s.shoulder + 0.5f * s.breath * br), kM));
    turn(st, m, mats, kHead, st.head, Y, lim(-s.headBack + 0.4f * s.breath * br, kM));
    // the thighs part (a turn about x, opposite on the two sides) and tremble, the knees bend
    turn(st, m, mats, kLThigh, st.lthigh, X, lim(s.thighPart + 0.25f * trem * 0.1f, kM));
    turn(st, m, mats, kRThigh, st.rthigh, X, lim(-s.thighPart - 0.25f * trem * 0.1f, kM));
    turn(st, m, mats, kLCalf, st.lcalf, Y, lim(s.kneeFlex + 0.3f * trem * 0.1f, kM));
    turn(st, m, mats, kRCalf, st.rcalf, Y, lim(s.kneeFlex - 0.3f * trem * 0.1f, kM));
    // the hands to the toy
    const float rw[2] = {std::clamp(s.reachL, 0.0f, 1.0f), std::clamp(s.reachR, 0.0f, 1.0f)};
    const int roles[2][3] = {{kLUp, kLFore, kLHand}, {kRUp, kRFore, kRHand}};
    for (int k = 0; k < 2; ++k) {
        if (!(rw[k] > 1e-3f) || st.uarm[k] < 0 || st.farm[k] < 0 || st.hand[k] < 0 || st.finger[k] < 0) continue;
        auto pv = [&](int b) { return mats[size_t(b)].point(m.bones[size_t(b)].pivot); };
        layer::ArmPose arm{pv(st.uarm[k]), pv(st.farm[k]), pv(st.hand[k]), pv(st.finger[k])};
        layer::ReachGoal g;
        g.target = entryModel + V3(0.02f, k ? -0.03f : 0.03f, 0.0f);
        g.along = (entryModel - arm.wrist).normalized();
        g.pole = V3(0, k ? -1.0f : 1.0f, -0.3f);
        g.weight = rw[k];
        const layer::ReachTurns t = layer::solveReach(arm, g);
        auto lay = [&](int role, const M4& d) { for (int i : st.sub[size_t(role)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)]; };
        lay(roles[k][0], layer::rotateAbout(arm.shoulder, t.upper));
        lay(roles[k][1], layer::rotateAbout(t.elbow, t.fore));
        lay(roles[k][2], layer::rotateAbout(t.wrist, t.hand));
    }
    return tilt;
}

} // namespace toyrig
} // namespace wc
