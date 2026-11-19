// Imported animations as the engine plays them (engine/boneanim.h) and the game's own layers (M2Model::isLayer, pose over a
// base), without GL. Build: engine-test/build.sh; run: build/boneanim_test [FILES_DIR] (exit 0: all pass). With FILES_DIR (the
// downloaded game files, each named by its id) the night elf woman (921844, her skeleton and .anim files) is checked too:
// FacePose and HandsClosed are layers, Stand is not, and FacePose played over Stand keeps Stand's arms (not the bind pose).
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../android/app/src/main/cpp/engine/boneanim.h"

using namespace wc;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static V3 rotate(const Quat& q, V3 v) {
    Quat r = quatMul(quatMul(q, Quat(v.x, v.y, v.z, 0)), quatInv(q));
    return V3(r.x, r.y, r.z);
}
static float angleDeg(V3 a, V3 b) {
    float d = a.normalized().dot(b.normalized());
    return std::acos(std::fmax(-1.0f, std::fmin(1.0f, d))) * 57.29578f;
}

// A small skeleton with the key bone ids findWowBone knows: Root 26, Chest 53, L_Clavicle 2, L_UpperArm 0, L_Forearm 62,
// L_Hand 65, and L_Middle1 (the hand's aim) by its name's CRC.
struct Rig { M2Model m; std::vector<std::string> names; };
static Rig rig(V3 clavicle, V3 upperArm, V3 forearm, V3 hand, V3 middle) {
    Rig r;
    auto add = [&](const char* name, int key, int parent, V3 pivot) {
        M2Bone b; b.keyBoneId = key; b.parent = int16_t(parent); b.pivot = pivot;
        r.m.bones.push_back(b); r.names.push_back(name);
    };
    add("Root", 26, -1, V3(0, 0, 0));
    add("Chest", 53, 0, V3(0, 0, 1.3f));
    add("L_Clavicle", 2, 1, clavicle);
    add("L_UpperArm", 0, 2, upperArm);
    add("L_Forearm", 62, 3, forearm);
    add("L_Hand", 65, 4, hand);
    M2Bone mid; mid.parent = 5; mid.pivot = middle; mid.nameCrc = crc32("MiddleFingerL", std::strlen("MiddleFingerL"));
    r.m.bones.push_back(mid); r.names.push_back("L_Middle1");
    return r;
}

static V3 refDir(const char* bone) {
    for (const AnimRest& a : kAnimRest) if (std::string(a.bone) == bone) return V3(a.x, a.y, a.z).normalized();
    return V3(0, 0, 1);
}

int main(int argc, char** argv) {
    // 1. Keys: before the first and after the last hold; between, linear; a loop wraps from the last key to the first.
    {
        std::vector<float> t{0.0f, 1.0f, 2.0f};
        size_t a, b; float f;
        boneKeys(t, -1, 0, a, b, f); CHECK(a == 0 && b == 0 && f == 0, "before the first key: %zu %zu %f", a, b, f);
        boneKeys(t, 1.5f, 0, a, b, f); CHECK(a == 1 && b == 2 && std::fabs(f - 0.5f) < 1e-6f, "between: %zu %zu %f", a, b, f);
        boneKeys(t, 2.5f, 0, a, b, f); CHECK(a == 2 && b == 2 && f == 0, "past the last, no loop: %zu %zu %f", a, b, f);
        boneKeys(t, 2.5f, 3.0f, a, b, f); CHECK(a == 2 && b == 0 && std::fabs(f - 0.5f) < 1e-6f, "loop wraps: %zu %zu %f", a, b, f);
        boneKeys(t, 2.5f, 2.0f, a, b, f); CHECK(a == 2 && b == 2, "a loop as long as its keys holds the last: %zu %zu", a, b);
        std::vector<float> late{0.5f, 1.0f};
        boneKeys(late, 1.5f, 2.0f, a, b, f); CHECK(a == 1 && b == 1, "keys not from 0 don't wrap: %zu %zu", a, b);
        std::vector<float> one{0.3f};
        boneKeys(one, 5, 1, a, b, f); CHECK(a == 0 && b == 0 && f == 0, "one key holds");
        std::vector<float> none;
        boneKeys(none, 1, 1, a, b, f); CHECK(a == 0 && b == 0, "no keys: no index past the end");
    }

    // 2. The reference rest pose (the bones exactly along kAnimRest): nothing is turned.
    {
        V3 c(0.0f, 0.06f, 1.35f);
        V3 u = c + refDir("L_Clavicle") * 0.15f;
        V3 fo = u + refDir("L_UpperArm") * 0.28f;
        V3 h = fo + refDir("L_Forearm") * 0.25f;
        V3 mid = h + refDir("L_Hand") * 0.08f;
        Rig r = rig(c, u, fo, h, mid);
        BoneAnim an;
        for (const char* n : {"Chest", "L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand", "L_Middle1"})
            an.tracks.push_back({n, true, {0.0f}, {0.1f, 0.2f, 0.3f, 0.9273618f}});
        CHECK(fitBoneAnim(an, r.m) == 0, "the reference rest pose was turned");
    }

    // 3. Another model's rest pose (shoulders 25 degrees lower, upper arm 15 off): the clip's bind pose, as the importer
    // made it for the reference (each bone's world turn Q = arc(reference rest, Sims bind direction), local = inv(parent) Q),
    // once fitted, points each of this model's limbs along the Sims bind direction, as on the reference.
    {
        V3 c(0.0f, 0.06f, 1.35f);
        auto tilt = [](V3 d, float deg) { float a = deg / 57.29578f; return V3(d.x, d.y * std::cos(a) - d.z * std::sin(a), d.y * std::sin(a) + d.z * std::cos(a)).normalized(); };
        V3 u = c + tilt(refDir("L_Clavicle"), -25) * 0.15f;
        V3 fo = u + tilt(refDir("L_UpperArm"), 15) * 0.28f;
        V3 h = fo + tilt(refDir("L_Forearm"), 10) * 0.25f;
        V3 mid = h + refDir("L_Hand") * 0.08f;
        Rig r = rig(c, u, fo, h, mid);
        // the Sims bind directions the clip was made for (any; here a T pose's)
        std::vector<std::string> chain{"L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand"};
        std::vector<V3> sims{V3(0, 1, 0.05f).normalized(), V3(0, 1, 0), V3(0.1f, 1, 0).normalized(), V3(0, 1, -0.1f).normalized()};
        BoneAnim an;
        an.tracks.push_back({"Chest", true, {0.0f, 1.0f}, {0, 0, 0, 1, 0, 0, 0, 1}});
        Quat parentWorld;
        std::vector<Quat> worlds;
        for (size_t i = 0; i < chain.size(); ++i) {
            Quat q = quatArc(refDir(chain[i].c_str()), sims[i]);
            Quat local = quatMul(quatInv(parentWorld), q);
            // two keys, the second the negation of the first (the same turn): the fit keeps them on one side
            an.tracks.push_back({chain[i], true, {0.0f, 1.0f}, {local.x, local.y, local.z, local.w, -local.x, -local.y, -local.z, -local.w}});
            parentWorld = q;
        }
        an.tracks.push_back({"L_Middle1", true, {0.0f}, {0, 0, 0, 1}});
        int turned = fitBoneAnim(an, r.m);
        CHECK(turned >= 4, "the arm's tracks weren't turned (%d)", turned);
        // the model's world turns from the fitted locals, down the chain (Chest at identity)
        Quat world;
        for (size_t i = 0; i < chain.size(); ++i) {
            const BoneAnimTrack& t = an.tracks[i + 1];
            world = quatMul(world, Quat(t.values[0], t.values[1], t.values[2], t.values[3]));
            int b = r.m.findWowBone(chain[i]);
            int aim = r.m.findWowBone(i + 1 < chain.size() ? chain[i + 1] : "L_Middle1");
            V3 rest = r.m.bones[size_t(aim)].pivot - r.m.bones[size_t(b)].pivot;
            float off = angleDeg(rotate(world, rest), sims[i]);
            CHECK(off < 0.5f, "%s points %.2f degrees from the clip's direction after the fit", chain[i].c_str(), off);
            const float* v = &t.values[0];
            CHECK(v[0] * v[4] + v[1] * v[5] + v[2] * v[6] + v[3] * v[7] > 0, "%s's keys flip sign", chain[i].c_str());
        }
        // unfitted, the clavicle would have been off by about the 25 degrees
        Quat q0 = quatArc(refDir("L_Clavicle"), sims[0]);
        V3 rest0 = u - c;
        CHECK(angleDeg(rotate(q0, rest0), sims[0]) > 20, "the test's rest pose isn't different enough");
    }

    // 4. The game's files, when given: FacePose (808) and HandsClosed (15) are layers, Stand (0) isn't; FacePose posed
    // over Stand keeps Stand's arms and legs, and alone (no base) leaves them at the bind (the arms-out pose it fixes).
    if (argc > 1) {
        std::string dir = argv[1];
        FileLookup files = [&](uint32_t f) {
            std::string p = dir + "/" + std::to_string(f);
            FILE* x = std::fopen(p.c_str(), "rb");
            if (!x) return std::string();
            std::fclose(x);
            return p;
        };
        M2Model m;
        if (!m.load(921844, files)) {
            std::printf("skipped the game's files: %s\n", m.error.c_str());
        } else {
            int stand = m.findSequence(0), face = m.findSequence(808), hands = m.findSequence(15);
            for (int s : {stand, face, hands}) if (s >= 0) m.ensureSequence(s, files);
            CHECK(stand >= 0 && m.sequenceReady(stand) && !m.isLayer(stand), "Stand is a layer or not here");
            CHECK(face >= 0 && m.isLayer(face), "FacePose isn't a layer");
            CHECK(hands >= 0 && m.isLayer(hands), "HandsClosed isn't a layer");
            if (stand >= 0 && face >= 0) {
                std::vector<BonePose> base, over, alone;
                m.pose(stand, 500, 500, base, true);
                m.pose(face, 100, 500, over, false, &base);
                m.pose(face, 100, 500, alone, false);
                for (const char* n : {"L_UpperArm", "R_UpperArm", "L_Thigh", "Pelvis"}) {
                    int b = m.findWowBone(n);
                    if (b < 0) continue;
                    const Quat& s = base[size_t(b)].r; const Quat& o = over[size_t(b)].r; const Quat& a = alone[size_t(b)].r;
                    CHECK(std::fabs(s.x - o.x) + std::fabs(s.y - o.y) + std::fabs(s.z - o.z) + std::fabs(s.w - o.w) < 1e-5f,
                          "%s over Stand isn't Stand's", n);
                    CHECK(std::fabs(a.x) + std::fabs(a.y) + std::fabs(a.z) < 1e-5f, "%s of FacePose alone isn't the bind (the test's premise)", n);
                }
                int keyed = 0;
                for (size_t b = 0; b < m.bones.size(); ++b) keyed += m.boneKeyed(int(b), face);
                CHECK(keyed * 2 < int(m.bones.size()), "FacePose keys %d of %zu bones", keyed, m.bones.size());
            }
        }
    }

    if (failures) { std::printf("%d failed\n", failures); return 1; }
    std::printf("boneanim_test: all passed\n");
    return 0;
}
