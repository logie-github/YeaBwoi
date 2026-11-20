// The Scene's side of the creator's body pick and sculpt (bodypick.h, sculpt.h): a model's bones named into regions and the
// sculpt's rig, the tap's ray against the actor as posed, and the sculpt kept on the actor (the Eve body takes it as it is
// fitted: evescene.cpp).
#include <algorithm>
#include <cmath>

#include "bodypick.h"
#include "scene.h"
#include "sculpt.h"

namespace wc {

std::vector<uint8_t> boneRegionsOf(const M2Model& m) {
    static const std::pair<const char*, uint8_t> kNamed[] = {
        {"Root", kRegionHips}, {"Pelvis", kRegionHips},
        {"Waist", kRegionWaist}, {"Spine1", kRegionWaist},
        {"Spine2", kRegionChest}, {"Chest", kRegionChest}, {"L_Breast", kRegionChest}, {"R_Breast", kRegionChest},
        {"L_Clavicle", kRegionChest}, {"R_Clavicle", kRegionChest},
        {"Neck1", kRegionNeck}, {"Neck2", kRegionNeck},
        {"Head", kRegionHead}, {"Jaw", kRegionHead}, {"L_Eye", kRegionHead}, {"R_Eye", kRegionHead},
        {"L_UpperArm", kRegionArms}, {"R_UpperArm", kRegionArms}, {"L_Forearm", kRegionArms}, {"R_Forearm", kRegionArms},
        {"L_Hand", kRegionHands}, {"R_Hand", kRegionHands},
        {"L_Thigh", kRegionLegs}, {"R_Thigh", kRegionLegs}, {"L_Calf", kRegionLegs}, {"R_Calf", kRegionLegs},
        {"L_Ankle", kRegionFeet}, {"R_Ankle", kRegionFeet}, {"L_Foot", kRegionFeet}, {"R_Foot", kRegionFeet},
        {"L_Toe", kRegionFeet}, {"R_Toe", kRegionFeet},
    };
    std::vector<std::pair<int, uint8_t>> seeds;
    for (const auto& [name, region] : kNamed) {
        int b = m.findWowBone(name);
        if (b >= 0) seeds.emplace_back(b, region);
    }
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) parents[i] = m.bones[i].parent;
    return boneRegionsFrom(parents, seeds);
}

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

int Scene::pickBodyRegion(int id, float sx, float sy, int width, int height) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || width <= 0 || height <= 0) return kRegionNone;
    const Actor& a = it->second;
    if (!a.mesh || !a.mesh->model || !a.visible) return kRegionNone;
    const M2Model& m = *a.mesh->model;
    // the camera's ray through the point (as Scene::pick), then into the actor's model space
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fov_ * 3.14159265f / 360.0f), aspect = float(width) / float(height);
    float nx = 2.0f * sx / float(width) - 1.0f, ny = 1.0f - 2.0f * sy / float(height);
    V3 d = (f + s * (nx * th * aspect) + u * (ny * th)).normalized();
    V3 rel = eye_ - a.position;
    float c = std::cos(-a.facing), sn = std::sin(-a.facing);
    auto unturn = [&](const V3& v) { return V3(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z); };
    float scale = a.scale > 1e-6f ? a.scale : 1.0f;
    V3 o = unturn(rel) * (1.0f / scale);
    V3 dm = unturn(d);
    // the body as drawn: Eve's with the cut body, else the shaped body, else the model's own
    const bool eve = a.eve.active && a.eve.measure.host >= 0;
    const BodyGl& b = a.body;
    const bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    const std::vector<M2Vertex>& verts = eve ? a.eve.measure.verts : own ? b.shaped : m.vertices;
    const std::vector<uint32_t>& idx = eve ? a.eve.measure.indices : own ? b.indices : m.indices;
    const std::vector<M2Submesh>& subs = eve ? a.eve.measure.subs : own ? b.submeshes : m.submeshes;
    if (boneRegionsModel_ != &m) { boneRegions_ = boneRegionsOf(m); boneRegionsModel_ = &m; }
    BodyPickHit hit = pickBody(verts, idx, subs, a.geosets, a.mats, boneRegions_, o, dm);
    // the hair: a style that is a model of its own (not the body's geosets) is not in these triangles, so a tap round the
    // head that misses the body, or lands on the top of the head, is the hair
    const int head = m.findWowBone("Head"), pelvis = m.findWowBone("Pelvis");
    if (head >= 0 && size_t(head) < a.mats.size()) {
        const V3 hp = a.mats[size_t(head)].point(m.bones[size_t(head)].pivot);
        const float unit = std::max(pelvis >= 0 ? m.bones[size_t(pelvis)].pivot.z : 1.0f, 0.1f);
        if (hit.region == kRegionHead && hit.point.z > hp.z + 0.1f * unit) return kRegionHair;
        if (hit.region == kRegionNone) {
            V3 dn = dm.normalized();
            float along = (hp - o).dot(dn);
            float miss = (o + dn * along - hp).length();
            if (along > 0 && miss < 0.2f * unit) return kRegionHair;
        }
    }
    return hit.region;
}

void Scene::setSculpt(int id, const float* values, size_t count) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    std::vector<float> v(values, values + std::min(count, size_t(kSculptCount)));
    if (v == it->second.sculpt) return;
    it->second.sculpt = std::move(v);
    // the Eve body is fitted again with it (evescene.cpp: eveCheck mixes it into the key)
}

} // namespace wc
