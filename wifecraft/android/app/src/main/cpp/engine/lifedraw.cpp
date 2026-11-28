// A person's life and gaze laid over her posed bones (life.h is the pure part): small turns of the posed bones about their own pivots, upper body
// only (nothing below the waist turns, so her feet never move), then the arm of a gesture on a point of her own head by the reach solve, the
// fingers' curl, and the eyes aimed at the camera by the bones of the eyeballs. The eyelids go to the shader (uLid, kLitFs).
#include <algorithm>
#include <cmath>

#include "bonelayer.h"
#include "m2.h"
#include "motionlayer.h"
#include "scene.h"

namespace wc {
namespace {

enum Rig { kSpine1, kSpine2, kChest, kNeck1, kHead, kLClav, kRClav, kLUp, kRUp, kLFore, kRFore, kLHand, kRHand, kLEye, kREye,
           kLIdx, kLMid, kLRing, kLPink, kRIdx, kRMid, kRRing, kRPink, kCount };
const char* const kNames[kCount] = {"Spine1", "Spine2", "Chest", "Neck1", "Head", "L_Clavicle", "R_Clavicle", "L_UpperArm", "R_UpperArm", "L_Forearm",
    "R_Forearm", "L_Hand", "R_Hand", "L_Eye", "R_Eye", "L_Index1", "L_Middle1", "L_Ring1", "L_Pinky1", "R_Index1", "R_Middle1", "R_Ring1", "R_Pinky1"};

std::vector<int> subtree(const M2Model& m, int root) {
    std::vector<int> out;
    if (root < 0 || size_t(root) >= m.bones.size()) return out;
    std::vector<uint8_t> in(m.bones.size(), 0);
    in[size_t(root)] = 1;
    for (bool grew = true; grew;) {
        grew = false;
        for (size_t i = 0; i < m.bones.size(); ++i) {
            int p = m.bones[i].parent;
            if (!in[i] && p >= 0 && size_t(p) < in.size() && in[size_t(p)]) { in[i] = 1; grew = true; }
        }
    }
    for (size_t i = 0; i < in.size(); ++i) if (in[i]) out.push_back(int(i));
    return out;
}

Quat qAxis(const V3& axis, float angle) {
    const float h = angle * 0.5f, s = std::sin(h);
    return Quat(axis.x * s, axis.y * s, axis.z * s, std::cos(h));
}
Quat mul(const Quat& a, const Quat& b) {
    return Quat(a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z);
}
// the rotation part of a matrix on a vector
V3 rot(const M4& m, const V3& v) {
    return V3(m.at(0, 0) * v.x + m.at(0, 1) * v.y + m.at(0, 2) * v.z, m.at(1, 0) * v.x + m.at(1, 1) * v.y + m.at(1, 2) * v.z,
              m.at(2, 0) * v.x + m.at(2, 1) * v.y + m.at(2, 2) * v.z);
}

} // namespace

void Scene::setLife(int id, int level, int gaze, float lookLimitDeg, uint32_t seed) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    Actor& a = it->second;
    life::Params p;
    p.level = std::clamp(level, 0, 3);
    p.gaze = std::clamp(gaze, 0, 2);
    p.lookLimitDeg = std::isfinite(lookLimitDeg) ? std::clamp(lookLimitDeg, 20.0f, 90.0f) : 70.0f;
    const bool on = p.level > 0 || p.gaze > 0;
    a.lifeSt.configure(p, seed ? seed : uint32_t(id) * 2654435761u + 5u);
    a.lifeOn = on;
    a.eyeFx = on;
    if (!on) { a.lid[0] = a.lid[1] = a.lid[2] = a.lid[3] = 0; a.lifeOut = life::Output(); }
}

void Scene::lifeEvent(int id, int kind, const std::string& bone, float strength) {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.lifeOn) return;
    Actor& a = it->second;
    float yaw = 0, pitch = 0;
    if (!bone.empty() && a.lifeModel && a.mats.size() == a.lifeModel->bones.size() && a.lifeBone[kSpine1] >= 0) {
        const M2Model& m = *a.lifeModel;
        const int b = m.findWowBone(bone);
        if (b >= 0 && size_t(b) < a.mats.size()) {
            const M4& fr = a.mats[size_t(a.lifeBone[kSpine1])];
            const bool hd = a.hd.active && a.hd.fit.rigPivots.size() == m.bones.size();
            auto pv = [&](int i) { return hd ? a.hd.fit.rigPivots[size_t(i)] : m.bones[size_t(i)].pivot; };
            const V3 head = a.mats[size_t(a.lifeBone[kHead])].point(pv(a.lifeBone[kHead]));
            const V3 d = a.mats[size_t(b)].point(pv(b)) - head;
            const V3 dl(fr.at(0, 0) * d.x + fr.at(1, 0) * d.y + fr.at(2, 0) * d.z, fr.at(0, 1) * d.x + fr.at(1, 1) * d.y + fr.at(2, 1) * d.z,
                        fr.at(0, 2) * d.x + fr.at(1, 2) * d.y + fr.at(2, 2) * d.z);
            yaw = std::atan2(dl.y, std::max(dl.x, 0.05f));
            pitch = std::atan2(dl.z, std::hypot(dl.x, dl.y));
        }
    }
    a.lifeSt.touch(kind, yaw, pitch, strength);
}

void Scene::layLife(Actor& a, uint32_t now) {
    if (!a.lifeOn || !a.character || a.ghost || !a.mesh || !a.mesh->model) return;
    const M2Model& m = *a.mesh->model;
    if (a.mats.size() != m.bones.size() || m.bones.empty()) return;
    if (a.lifeModel != &m) {
        a.lifeModel = &m;
        a.lifeSub.assign(kCount, {});
        for (int k = 0; k < kCount; ++k) { a.lifeBone[k] = m.findWowBone(kNames[k]); a.lifeSub[size_t(k)] = subtree(m, a.lifeBone[k]); }
        a.lidSkinSet = false;
    }
    if (a.lifeBone[kHead] < 0 || a.lifeBone[kSpine1] < 0) return;
    lidSkinOf(a);
    auto& mats = a.mats;
    // (an HD figure's bones turn about its own joints: hdbody.h HdFit::rigPivots)
    const std::vector<V3>* rig = a.hd.active && a.hd.fit.rigPivots.size() == m.bones.size() ? &a.hd.fit.rigPivots : nullptr;
    auto pivotOf = [&](int k) { const int b = a.lifeBone[k]; return mats[size_t(b)].point(rig ? (*rig)[size_t(b)] : m.bones[size_t(b)].pivot); };
    auto has = [&](int k) { return a.lifeBone[k] >= 0 && size_t(a.lifeBone[k]) < mats.size(); };
    // turn bone k (and everything under it) by yaw (z), pitch (y, nodding down positive) and roll (x), model axes, about its own posed pivot
    auto turn = [&](int k, float yaw, float pitch, float roll) {
        if (!has(k)) return;
        if (std::fabs(yaw) + std::fabs(pitch) + std::fabs(roll) < 1e-6f) return;
        const Quat q = mul(qAxis(V3(0, 0, 1), yaw), mul(qAxis(V3(0, 1, 0), pitch), qAxis(V3(1, 0, 0), roll)));
        const M4 d = layer::rotateAbout(pivotOf(k), q);
        for (int i : a.lifeSub[size_t(k)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
    };
    auto turnAbout = [&](int k, const V3& pivot, const V3& axis, float angle) {
        if (!has(k) || std::fabs(angle) < 1e-6f) return;
        const M4 d = layer::rotateAbout(pivot, qAxis(axis.normalized(), angle));
        for (int i : a.lifeSub[size_t(k)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
    };
    // a unit for a body of another size (the human woman's chest to head pivot is 0.32)
    const float unit = has(kChest) ? std::clamp((pivotOf(kHead) - pivotOf(kChest)).length() / 0.322f, 0.3f, 3.0f) : 1.0f;
    // the camera in her model space and as seen from her torso
    const M4 inv = motion::placementInverse(a.position, a.facing, a.scale);
    const V3 cam = inv.point(eye_);
    life::Input in;
    {
        const M4& fr = mats[size_t(a.lifeBone[kSpine1])];
        const V3 head = pivotOf(kHead);
        const V3 d = cam - head;
        in.camDist = d.length() * a.scale;
        const V3 dl(fr.at(0, 0) * d.x + fr.at(1, 0) * d.y + fr.at(2, 0) * d.z, fr.at(0, 1) * d.x + fr.at(1, 1) * d.y + fr.at(2, 1) * d.z,
                    fr.at(0, 2) * d.x + fr.at(1, 2) * d.y + fr.at(2, 2) * d.z);
        in.camYaw = std::atan2(dl.y, dl.x);
        in.camPitch = std::atan2(dl.z, std::hypot(dl.x, dl.y));
        const bool heldPose = a.boneAnim && a.boneAnim->duration <= 0.001f;
        bool standing = a.seq < 0 || size_t(a.seq) >= m.sequences.size() || m.sequences[size_t(a.seq)].id == 0 || m.sequences[size_t(a.seq)].id == a.idleAnim;
        in.armsFree = heldPose || (!a.boneAnim && standing && a.furniture == kOnFloor);
    }
    const uint32_t last = a.lifeLast ? a.lifeLast : now;
    const float dt = float(int32_t(now - last)) / 1000.0f;
    if (!a.lifeLast || now != a.lifeLast) { a.lifeOut = a.lifeSt.step(dt > 0 ? dt : 0.0f, in); a.lifeLast = now ? now : 1; }
    const life::Output& o = a.lifeOut;
    // ---- breathing, weight and noise, root of the chain first
    if (has(kSpine1)) {
        turn(kSpine1, o.waistYaw + o.spineN[1], o.spineN[0], o.waistRoll + o.spineN[2]);
        if (std::fabs(o.shiftY) > 1e-6f) {
            const M4 t = M4::translation(V3(0, o.shiftY * unit, 0));
            for (int i : a.lifeSub[kSpine1]) if (size_t(i) < mats.size()) mats[size_t(i)] = t * mats[size_t(i)];
        }
    }
    turn(kSpine2, o.spineYaw, o.spinePitch + o.impulsePitch * 0.6f, o.impulseRoll * 0.6f);
    turn(kChest, 0, o.chestPitch + o.impulsePitch * 0.4f, o.chestRoll + o.impulseRoll * 0.4f);
    turn(kLClav, 0, 0, o.clavLift);
    turn(kRClav, 0, 0, -o.clavLift);
    // a shoulder roll: each clavicle circles
    if (o.gesture == life::kShoulderRoll) {
        const float w = o.gestureWeight, ph = o.gestureWeight > 0 ? life::kPi * 2.0f * std::min(1.0f, 0.5f + 0.5f * w) : 0.0f;
        const float lift = life::rad(4.0f) * w * std::sin(ph * 0.5f + 0.2f), fwd = life::rad(3.0f) * w * std::cos(ph);
        const int k1 = o.gestureSide ? kLClav : kRClav;
        turn(k1, 0, fwd, o.gestureSide ? lift : -lift);
    }
    turn(kNeck1, o.neckYaw + o.neckN[1], o.neckPitch + o.neckN[0], o.neckN[2]);
    turn(kHead, o.headYaw + o.headN[1], o.headPitch + o.headN[0] + o.headBob, o.headRoll + o.headN[2]);
    // arms: noise on the upper arm, forearm and hand (left, right)
    turn(kLUp, 0, o.upperN[0][0], o.upperN[0][1]); turn(kRUp, 0, o.upperN[1][0], -o.upperN[1][1]);
    turn(kLFore, 0, o.foreN[0], 0); turn(kRFore, 0, o.foreN[1], 0);
    turn(kLHand, 0, o.handN[0], 0); turn(kRHand, 0, o.handN[1], 0);
    // ---- a gesture's hand: the reach solve toward a place on her own head or arm
    if ((o.gesture == life::kHairTuck || o.gesture == life::kTouchArm || o.gesture == life::kLipTouch) && o.gestureWeight > 1e-3f) {
        const int sd = o.gestureSide;   // 0 her right hand, 1 her left
        const int up = sd ? kLUp : kRUp, fore = sd ? kLFore : kRFore, hand = sd ? kLHand : kRHand, fing = sd ? kLMid : kRMid;
        if (has(up) && has(fore) && has(hand) && has(fing) && has(kHead)) {
            const M4& hm = mats[size_t(a.lifeBone[kHead])];
            const V3 hp = pivotOf(kHead);
            const float lat = sd ? 1.0f : -1.0f;   // her left is +y
            auto inHead = [&](const V3& v) { return hp + rot(hm, v * unit); };
            layer::ReachGoal goal;
            goal.weight = o.gestureWeight;
            if (o.gesture == life::kHairTuck) {
                goal.target = inHead(V3(-0.01f, lat * 0.105f, 0.04f));
                goal.along = rot(hm, V3(0.1f, lat * -0.2f, 1.0f)).normalized();
                goal.pole = V3(0, lat * 0.25f, -1.0f);
            } else if (o.gesture == life::kLipTouch) {
                goal.target = inHead(V3(0.14f, lat * 0.03f, -0.075f));
                goal.along = rot(hm, V3(-0.15f, lat * -0.1f, 1.0f)).normalized();
                goal.pole = V3(0, lat * 0.3f, -1.0f);
            } else {
                // the other elbow, held from in front: the forearm across her belly, below the chest
                const int ofo = sd ? kRFore : kLFore;
                goal.target = pivotOf(ofo) + V3(0.05f * unit, lat * 0.0f, -0.02f * unit);
                goal.along = V3(0.2f, lat * -1.0f, -0.3f).normalized();
                goal.pole = V3(0, lat * 1.0f, -0.4f);
            }
            layer::ArmPose arm{pivotOf(up), pivotOf(fore), pivotOf(hand), pivotOf(fing)};
            const layer::ReachTurns tr = layer::solveReach(arm, goal);
            auto lay = [&](int k, const M4& d) { for (int i : a.lifeSub[size_t(k)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)]; };
            lay(up, layer::rotateAbout(arm.shoulder, tr.upper));
            lay(fore, layer::rotateAbout(tr.elbow, tr.fore));
            lay(hand, layer::rotateAbout(tr.wrist, tr.hand));
        }
    }
    // ---- the fingers' curl: each finger's root turned toward the palm a few degrees, a little different each
    for (int s = 0; s < 2; ++s) {
        const int hand = s ? kRHand : kLHand;
        const int f0 = s ? kRIdx : kLIdx;
        if (!has(hand) || !has(f0) || !has(f0 + 3)) continue;
        const V3 hp = pivotOf(hand);
        const V3 across = (pivotOf(f0) - pivotOf(f0 + 3)).normalized();
        for (int f = 0; f < 4; ++f) {
            if (!has(f0 + f)) continue;
            const V3 fp = pivotOf(f0 + f);
            const V3 dir = (fp - hp).normalized();
            V3 axis = across.cross(dir);
            if (axis.length() < 1e-4f) continue;
            axis = axis.normalized();
            const float ang = life::rad(2.0f + 6.0f * o.curl[s] + 1.5f * float(f)) * (a.lifeSt.amount() > 0 ? 1.0f : 0.0f);
            // the sign that brings the tip toward her middle (the hand's side over its length)
            const V3 tip = fp + dir * 0.06f;
            const V3 t1 = layer::rotateAbout(fp, qAxis(axis, ang)).point(tip), t2 = layer::rotateAbout(fp, qAxis(axis, -ang)).point(tip);
            turnAbout(f0 + f, fp, axis, std::fabs(t1.y) < std::fabs(t2.y) ? ang : -ang);
        }
    }
    // ---- the eyes: each eyeball aimed at the camera from its own centre (so they converge at its distance), within 30 degrees sideways and 20 up or down
    float lidTrack = o.lidTrack;
    if (o.gazeOn && has(kLEye) && has(kREye) && has(kHead)) {
        const M4& hm = mats[size_t(a.lifeBone[kHead])];
        for (int e = 0; e < 2; ++e) {
            const int k = e ? kREye : kLEye;
            const V3 pe = pivotOf(k);
            V3 dir = (cam - pe).normalized();
            // in the head's frame
            const V3 dh(hm.at(0, 0) * dir.x + hm.at(1, 0) * dir.y + hm.at(2, 0) * dir.z, hm.at(0, 1) * dir.x + hm.at(1, 1) * dir.y + hm.at(2, 1) * dir.z,
                        hm.at(0, 2) * dir.x + hm.at(1, 2) * dir.y + hm.at(2, 2) * dir.z);
            float yaw = std::atan2(dh.y, std::max(dh.x, 1e-3f)), pitch = std::atan2(dh.z, std::hypot(dh.x, dh.y));
            yaw = yaw * o.eyeWeight + o.eyeOffYaw;
            pitch = pitch * o.eyeWeight + o.eyeOffPitch;
            yaw = std::clamp(yaw, -life::rad(30.0f), life::rad(30.0f));
            pitch = std::clamp(pitch, -life::rad(20.0f), life::rad(20.0f));
            const V3 dd(std::cos(pitch) * std::cos(yaw), std::cos(pitch) * std::sin(yaw), std::sin(pitch));
            const V3 want = rot(hm, dd).normalized();
            const V3 fwd = rot(mats[size_t(a.lifeBone[k])], V3(1, 0, 0)).normalized();
            const Quat q = quatArc(fwd, want);
            if (std::fabs(q.w) < 0.9999999f) {
                const M4 d = layer::rotateAbout(pe, q);
                for (int i : a.lifeSub[size_t(k)]) if (size_t(i) < mats.size()) mats[size_t(i)] = d * mats[size_t(i)];
            }
            if (e == 0) lidTrack = std::clamp(-pitch / life::rad(20.0f), 0.0f, 1.0f);
        }
    }
    a.lid[0] = o.lid[0]; a.lid[1] = o.lid[1]; a.lid[2] = lidTrack; a.lid[3] = 1.0f;
}

} // namespace wc
