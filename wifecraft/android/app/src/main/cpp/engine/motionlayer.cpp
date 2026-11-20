#include "motionlayer.h"

#include <algorithm>
#include <cmath>

#include "m2.h"

namespace wc {
namespace motion {

namespace {
constexpr float kTau = 6.2831853f;

float clamp01(float v) { return std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; }

V3 column(const M4& m, int c) { return V3(m.at(0, c), m.at(1, c), m.at(2, c)).normalized(); }

void subtree(const std::vector<std::vector<int>>& children, int root, std::vector<int>& out) {
    out.clear();
    if (root < 0) return;
    std::vector<int> stack{root};
    while (!stack.empty() && out.size() < 4096) {
        int b = stack.back();
        stack.pop_back();
        out.push_back(b);
        if (size_t(b) < children.size()) for (int c : children[size_t(b)]) stack.push_back(c);
    }
}

void findBones(MotionState& st, const M2Model& m) {
    st.model = &m;
    st.waist = m.findWowBone("Spine1");
    if (st.waist < 0) st.waist = m.findWowBone("Waist");
    st.chest = m.findWowBone("Chest");
    if (st.chest < 0) st.chest = m.findWowBone("Spine2");
    st.neck = m.findWowBone("Neck1");
    st.head = m.findWowBone("Head");
    st.lclav = m.findWowBone("L_Clavicle");
    st.rclav = m.findWowBone("R_Clavicle");
    std::vector<std::vector<int>> children(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) {
        int p = m.bones[i].parent;
        if (p >= 0 && size_t(p) < m.bones.size() && size_t(p) != i) children[size_t(p)].push_back(int(i));
    }
    st.subtrees.assign(6, {});
    const int roots[6] = {st.waist, st.chest, st.neck, st.head, st.lclav, st.rclav};
    for (int k = 0; k < 6; ++k) subtree(children, roots[k], st.subtrees[size_t(k)]);
}

// Turns subtree k by [angle] about [axis] at its root's posed pivot.
void turn(MotionState& st, const M2Model& m, std::vector<M4>& mats, int k, int bone, const V3& axis, float angle) {
    if (bone < 0 || size_t(bone) >= mats.size() || !(std::fabs(angle) > 1e-6f) || !std::isfinite(angle)) return;
    V3 pivot = mats[size_t(bone)].point(m.bones[size_t(bone)].pivot);
    M4 d = turnAbout(pivot, axis, angle);
    for (int i : st.subtrees[size_t(k)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
}
} // namespace

float breathRate(float intensity) {
    float i = clamp01(intensity);
    return MotionLimits::kBreathMinPerS + (MotionLimits::kBreathMaxPerS - MotionLimits::kBreathMinPerS) * i;
}

float breathDepth(float intensity, float breathing) {
    float i = clamp01(intensity);
    return (MotionLimits::kBreathMinRad + (MotionLimits::kBreathMaxRad - MotionLimits::kBreathMinRad) * i) * clamp01(breathing);
}

float swayAmount(float intensity, float idle) { return MotionLimits::kSwayRad * clamp01(idle) * (1.0f - 0.7f * clamp01(intensity)); }
float tiltAmount(float intensity, float idle) { return MotionLimits::kTiltRad * clamp01(idle) * (1.0f - 0.5f * clamp01(intensity)); }

float idleNoise(float seconds, uint32_t seed, int channel) {
    static const float periods[3][3] = {{9.7f, 5.3f, 3.1f}, {7.9f, 4.4f, 2.6f}, {11.3f, 6.1f, 3.7f}};
    const float* p = periods[std::clamp(channel, 0, 2)];
    float offset = float((seed * 2654435761u) >> 8) / float(1u << 24) * kTau;
    float s = 0.55f * std::sin(kTau * seconds / p[0] + offset) + 0.3f * std::sin(kTau * seconds / p[1] + offset * 1.7f) +
              0.15f * std::sin(kTau * seconds / p[2] + offset * 2.3f);
    return std::clamp(s, -1.0f, 1.0f);
}

bool lookAngles(const V3& forward, const V3& up, const V3& toTarget, float& yaw, float& pitch) {
    yaw = pitch = 0;
    V3 u = up.normalized();
    V3 f = forward.normalized(), d = toTarget;
    if (!(d.length() > 0.05f)) return false;
    d = d.normalized();
    V3 fh = f - u * f.dot(u), dh = d - u * d.dot(u);
    if (!(fh.length() > 1e-3f) || !(dh.length() > 1e-3f)) return false;
    fh = fh.normalized(); dh = dh.normalized();
    float y = std::atan2(u.dot(fh.cross(dh)), fh.dot(dh));
    if (!std::isfinite(y) || std::fabs(y) > MotionLimits::kLookGiveUp) return false;
    float p = std::asin(std::clamp(d.dot(u), -1.0f, 1.0f)) - std::asin(std::clamp(f.dot(u), -1.0f, 1.0f));
    yaw = std::clamp(y, -MotionLimits::kLookYaw, MotionLimits::kLookYaw);
    pitch = std::clamp(p, -MotionLimits::kLookDown, MotionLimits::kLookUp);
    return true;
}

float ease(float value, float goal, float dt, float tau) {
    if (!(dt > 0) || !std::isfinite(dt)) return value;
    if (!(tau > 0)) return goal;
    return goal + (value - goal) * std::exp(-dt / tau);
}

float clipHeadWeight(float maxTurn) {
    if (!std::isfinite(maxTurn)) return 0;
    float t = std::clamp((maxTurn - 0.15f) / 0.20f, 0.0f, 1.0f);
    return 1.0f - t * t * (3 - 2 * t);
}

M4 turnAbout(const V3& pivot, const V3& axis, float angle) {
    V3 a = axis.normalized();
    float h = angle * 0.5f, s = std::sin(h);
    Quat q(a.x * s, a.y * s, a.z * s, std::cos(h));
    return M4::translation(pivot) * M4::rotation(q) * M4::translation(pivot * -1.0f);
}

float angleBetween(const Quat& a, const Quat& b) {
    float d = std::fabs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
    return 2.0f * std::acos(std::clamp(d, 0.0f, 1.0f));
}

M4 placementInverse(const V3& position, float facing, float scale) {
    float s = scale > 1e-4f ? 1.0f / scale : 1.0f;
    return M4::scaling(V3(s, s, s)) * M4::rotationZ(-facing) * M4::translation(position * -1.0f);
}

void apply(MotionState& st, const M2Model& m, std::vector<M4>& mats, const M4& worldInverse, const MotionParams& params,
           uint32_t nowMs) {
    if (!st.on || mats.size() != m.bones.size() || mats.empty()) return;
    if (st.model != &m || st.subtrees.size() != 6) findBones(st, m);
    float dt = st.started ? std::clamp(float(nowMs - st.lastMs) / 1000.0f, 0.0f, 0.1f) : 0.0f;
    st.started = true;
    st.lastMs = nowMs;
    st.shown = st.started && dt > 0 ? ease(st.shown, clamp01(st.intensity), dt, MotionLimits::kIntensityTau) : st.shown;
    st.phase = std::fmod(st.phase + kTau * breathRate(st.shown) * dt, kTau);
    const float seconds = float(nowMs % 3600000u) / 1000.0f;

    // the look-at wanted, measured on the clip's own pose (before anything of this layer is laid on)
    float goalYaw = 0, goalPitch = 0;
    if (params.lookAt && st.hasTarget && st.head >= 0 && st.neck >= 0 && size_t(st.head) < mats.size() && size_t(st.neck) < mats.size()) {
        V3 headAt = mats[size_t(st.head)].point(m.bones[size_t(st.head)].pivot);
        V3 target = worldInverse.point(st.targetPoint);
        float y, p;
        if (lookAngles(column(mats[size_t(st.head)], 0), column(mats[size_t(st.neck)], 2), target - headAt, y, p)) {
            goalYaw = y * clamp01(st.clipWeight);
            goalPitch = p * clamp01(st.clipWeight);
        }
    }
    st.yaw = ease(st.yaw, goalYaw, dt, MotionLimits::kLookTau);
    st.pitch = ease(st.pitch, goalPitch, dt, MotionLimits::kLookTau);

    // the waist's weight shift (side to side about the body's forward axis): the legs stay where the clip put them
    if (st.waist >= 0 && size_t(st.waist) < mats.size())
        turn(st, m, mats, 0, st.waist, column(mats[size_t(st.waist)], 0), swayAmount(st.shown, params.idle) * st.idleWeight * idleNoise(seconds, st.seed, 0));
    // breathing: the chest lifts and leans back a little on the in-breath, the neck takes it back so the head keeps still,
    // the shoulders rise with it
    float b = breathDepth(st.shown, params.breathing) * std::sin(st.phase);
    if (st.chest >= 0 && size_t(st.chest) < mats.size()) {
        V3 side = column(mats[size_t(st.chest)], 1), fwd = column(mats[size_t(st.chest)], 0);
        turn(st, m, mats, 1, st.chest, side, -b);
        float lift = b * MotionLimits::kShoulderShare;
        turn(st, m, mats, 4, st.lclav, fwd, std::max(0.0f, lift));    // up on the in-breath, back down after
        turn(st, m, mats, 5, st.rclav, fwd, -std::max(0.0f, lift));
    }
    if (st.neck >= 0 && size_t(st.neck) < mats.size()) {
        turn(st, m, mats, 2, st.neck, column(mats[size_t(st.neck)], 1), b * MotionLimits::kNeckTakesBack);
        // eye contact, the neck's share
        turn(st, m, mats, 2, st.neck, column(mats[size_t(st.neck)], 2), st.yaw * MotionLimits::kNeckShare);
        turn(st, m, mats, 2, st.neck, column(mats[size_t(st.neck)], 1), -st.pitch * MotionLimits::kNeckShare);
    }
    if (st.head >= 0 && size_t(st.head) < mats.size()) {
        const V3 up = st.neck >= 0 ? column(mats[size_t(st.neck)], 2) : column(mats[size_t(st.head)], 2);
        turn(st, m, mats, 3, st.head, up, st.yaw * (1.0f - MotionLimits::kNeckShare));
        turn(st, m, mats, 3, st.head, column(mats[size_t(st.head)], 1), -st.pitch * (1.0f - MotionLimits::kNeckShare));
        // the idle tilt (about the head's own forward axis), quieter while looking at someone
        float tilt = tiltAmount(st.shown, params.idle) * st.idleWeight * idleNoise(seconds, st.seed, 1) * (st.hasTarget ? 0.6f : 1.0f);
        turn(st, m, mats, 3, st.head, column(mats[size_t(st.head)], 0), tilt);
    }
}

} // namespace motion
} // namespace wc
