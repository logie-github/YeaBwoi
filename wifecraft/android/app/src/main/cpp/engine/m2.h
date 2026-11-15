// M2, the game's model format (characters, creatures, props), as today's game stores it: an MD21
// chunk holding the MD20 model, with its skins (.skin), animations (.anim) and textures named by
// file id. This side holds the data and evaluates the skeleton; m2_gl draws it.
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "bytes.h"
#include "wmath.h"

namespace wc {

// Finds a game file by its file id: the path it was downloaded to, or "" when it isn't there.
using FileLookup = std::function<std::string(uint32_t)>;

struct M2Vertex {
    float pos[3];
    uint8_t weights[4];
    uint8_t bones[4];
    float normal[3];
    float uv[2];
    float uv2[2];
};

struct M2Track {
    uint16_t interpolation = 0;
    uint16_t globalSeq = 0xFFFF;
    M2Array timestamps;   // one (count, offset) per sequence
    M2Array values;
    uint8_t src = 0;      // where its keys are: 0 the model (MD20), 1 its skeleton (.skel, SKB1), 2 the skeleton's parent
};

struct M2Bone {
    int32_t keyBoneId = -1;
    uint32_t flags = 0;
    int16_t parent = -1;
    uint32_t nameCrc = 0;   // the bone's name hashed: a skinned extra model's bones are the body's, found by this
    M2Track translation, rotation, scale;
    V3 pivot;
};

struct M2Sequence {
    uint16_t id = 0, variation = 0;
    uint32_t duration = 0;
    float moveSpeed = 0;
    uint32_t flags = 0;
    int16_t variationNext = -1;
    uint16_t aliasNext = 0;
    uint16_t blendIn = 150;
    uint16_t frequency = 0;          // how often this variation plays, out of 0x7FFF for all of the animation's
    uint32_t replayMin = 0, replayMax = 0;   // how many times it plays over once chosen (0: once)
    uint8_t src = 0;       // whose keys it plays: 0 the model's, 1 its skeleton's, 2 the parent skeleton's
    uint16_t srcIndex = 0;  // its index in that file's own sequence list
};

struct M2Texture {
    uint32_t type = 0;      // 0: the file below; otherwise a slot the look fills (1 skin, 2 cape, 6 hair, 11-13 creature skins, ...)
    uint32_t flags = 0;     // 1 wrap U, 2 wrap V
    uint32_t fileId = 0;
};

struct M2Submesh {
    uint16_t id = 0;        // geoset id
    uint32_t indexStart = 0, indexCount = 0;
    V3 sortCenter;
};

struct M2Batch {
    uint8_t flags = 0;
    int8_t priority = 0;
    uint16_t shaderId = 0;
    uint16_t submesh = 0;
    uint16_t colorIndex = 0xFFFF;
    uint16_t material = 0;
    uint16_t layer = 0;
    uint16_t textureCount = 1;
    uint16_t textureCombo = 0;
    uint16_t weightCombo = 0xFFFF;
};

struct M2Material {
    uint16_t flags = 0;     // 1 unlit, 2 unfogged, 4 two-sided, 8 no depth test, 16 no depth write
    uint16_t blend = 0;     // 0 opaque, 1 alpha key, 2 alpha, 3 no-alpha add, 4 add, 5 mod, 6 mod2x, 7 blend add
};

struct M2Attachment {
    uint32_t id = 0;
    uint16_t bone = 0;
    V3 position;
};

// Each bone's position, turn and size at one moment of one animation, before the hierarchy.
struct BonePose {
    V3 t;
    Quat r;
    V3 s{1, 1, 1};
};

class M2Model {
public:
    bool load(uint32_t fileId, const FileLookup& files);

    uint32_t fileId = 0;
    std::string error;          // why load() failed (the file and what of it), empty when it didn't
    uint32_t skeletonId = 0, parentSkeletonId = 0;   // today's models keep bones in a .skel (SKID; SKPD its parent)
    uint32_t flags = 0;
    std::vector<M2Vertex> vertices;
    std::vector<uint32_t> indices;           // the first skin's triangles, as model vertex numbers
    std::vector<M2Submesh> submeshes;
    std::vector<M2Batch> batches;
    std::vector<M2Texture> textures;
    std::vector<uint16_t> textureCombos;
    std::vector<M2Material> materials;
    std::vector<uint16_t> weightCombos;
    std::vector<M2Track> weights;            // texture weights (int16 alpha)
    std::vector<M2Track> colorRgb, colorAlpha;
    std::vector<M2Bone> bones;
    std::vector<M2Sequence> sequences;
    std::vector<uint32_t> globalLoops;
    std::vector<M2Attachment> attachments;
    std::vector<int16_t> attachmentLookup;
    V3 boundsMin, boundsMax;     // as the file gives them (the animations' reach)
    V3 extentMin, extentMax;     // of the vertices themselves

    // The sequence index playing `animId` (its first variation), or -1.
    int findSequence(uint16_t animId) const;
    // Loads the .anim file a sequence's keys are in, when they aren't in the model.
    void ensureSequence(int seq, const FileLookup& files);
    bool sequenceReady(int seq) const;

    // The local pose of every bone at `timeMs` into sequence `seq` (`globalMs` drives looping
    // effects that run on their own clock). `looping`: past a track's last key the pose moves on toward its first (the
    // game's keys often end before the sequence does), so a loop has no seam; otherwise the last key holds.
    void pose(int seq, uint32_t timeMs, uint32_t globalMs, std::vector<BonePose>& out, bool looping = false) const;
    // The sequences of the same animation as `seq` (its variations, `seq` included), in the file's order.
    std::vector<int> variations(int seq) const;
    // Turns local poses into each bone's matrix in model space.
    void bonesToMatrices(const std::vector<BonePose>& poses, std::vector<M4>& out) const;

    // The bone a WoW-side bone name (an imported animation's: "Root", "Head", "L_UpperArm" ...) stands for in
    // this model, or -1: a key bone id, else a bone name CRC, else the first child of a named bone (kWowBones in
    // m2.cpp; tools/wow_bones.py holds the same table).
    int findWowBone(const std::string& name) const;

    float evalWeight(int index, int seq, uint32_t timeMs, uint32_t globalMs) const;
    void evalColor(int index, int seq, uint32_t timeMs, uint32_t globalMs, float rgba[4]) const;

private:
    Bytes file_;
    size_t base_ = 0;                       // where MD20 starts in file_
    std::map<int, Bytes> animFiles_;        // sequence -> its .anim keys
    std::map<int, size_t> animBase_;        // offset of the keys within that file
    std::vector<std::pair<uint32_t, uint32_t>> afid_;   // (animId | variation << 16, file id)
    // A skeleton file (.skel): its bytes, where SKB1's data starts (its tracks' offsets count from there), its global
    // loops, animation files, bones and sequences.
    struct Skel {
        Bytes data;
        size_t skb = 0;
        uint32_t parent = 0;
        std::vector<uint32_t> loops;
        std::vector<std::pair<uint32_t, uint32_t>> afid;
        std::vector<M2Bone> bones;
        std::vector<M2Sequence> sequences;
        std::vector<M2Attachment> attachments;
        std::vector<int16_t> attachmentLookup;
    };
    Skel skel_, parent_;
    bool loadSkel(uint32_t id, const FileLookup& files, Skel& out, uint8_t src);
    void mergeParentSequences();

    int dataSequence(int seq) const;         // follows aliases
    size_t ownSequences_ = 0;               // the MD20's own (0: today's models, whose sequences are the skeleton's)
    // `loop`: the length the track's keys repeat over (0: they don't wrap; a sequence played once, or not asked to).
    bool keys(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs,
              const uint8_t*& data, size_t& dataSize, uint32_t& tsOff, uint32_t& valOff,
              uint32_t& count, uint32_t& time, uint32_t* loop = nullptr, bool looping = false) const;
    V3 evalV3(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, V3 def, bool looping = false) const;
    Quat evalQuat(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, bool looping = false) const;
    float evalShort(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, float def) const;
};

// The Root pivot height of the skeleton imported animations' positions are given for (night elf female 921844;
// anim/Profile.REFERENCE_ROOT_HEIGHT): a model's own Root height over this scales them.
constexpr float kAnimRootHeight = 1.441f;

// zlib's CRC-32 (today's M2 bones carry it of their names).
uint32_t crc32(const char* s, size_t n);

// Wrath++'s body shapes (creator/BodyShapes.kt) on today's model. `morph` holds kBodyMorphStride floats per vertex of
// Wrath++'s looks model: its place as drawn, how far the shape moves it, its normal before and after, its first texture
// coordinates before and after (the looks' body coordinates are today's with u doubled). Each of `vertices` at a looks
// vertex (within 0.01 mm) moves as that one does (its normal and, where the coordinates show the same vertex, its texture
// coordinates too); one between them moves as the looks vertices within 2 cm facing alike do, nearer ones more; one
// farther stays. Returns how many vertices moved.
constexpr size_t kBodyMorphStride = 16;
struct BodyMorphStats {
    size_t exact = 0, blended = 0, uvs = 0;
};
size_t applyBodyMorph(std::vector<M2Vertex>& vertices, const float* morph, size_t count, BodyMorphStats* stats = nullptr);

} // namespace wc
