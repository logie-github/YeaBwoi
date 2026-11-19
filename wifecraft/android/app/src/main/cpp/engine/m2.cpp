#include "m2.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

namespace wc {

namespace {

constexpr size_t kSequenceSize = 64, kBoneSize = 88, kTrackSize = 20, kVertexSize = 48;

M2Track readTrack(const Reader& r, size_t off) {
    M2Track t;
    t.interpolation = r.u16(off);
    t.globalSeq = r.u16(off + 2);
    t.timestamps = readArray(r, off + 4);
    t.values = readArray(r, off + 12);
    return t;
}

float unpackShort(int16_t s) { return float(s < 0 ? s + 32768 : s - 32767) / 32767.0f; }

// The sequences of an M2Array at `a` (the model's, or a skeleton's SKS1), marked as `src`'s.
void readSequences(const Reader& r, M2Array a, uint8_t src, std::vector<M2Sequence>& out) {
    for (uint32_t i = 0; i < a.count; ++i) {
        size_t o = a.offset + i * kSequenceSize;
        if (!r.has(o, kSequenceSize)) break;
        M2Sequence s;
        s.id = r.u16(o); s.variation = r.u16(o + 2); s.duration = r.u32(o + 4);
        s.moveSpeed = r.f32(o + 8); s.flags = r.u32(o + 12);
        s.frequency = r.u16(o + 16); s.replayMin = r.u32(o + 20); s.replayMax = r.u32(o + 24);
        s.blendIn = r.u16(o + 28);
        s.variationNext = r.i16(o + 60); s.aliasNext = r.u16(o + 62);
        s.src = src; s.srcIndex = uint16_t(i);
        out.push_back(s);
    }
}

// The bones of an M2Array at `a` (the model's, or a skeleton's SKB1), their tracks marked as `src`'s.
void readBones(const Reader& r, M2Array a, uint8_t src, std::vector<M2Bone>& out) {
    for (uint32_t i = 0; i < a.count; ++i) {
        size_t o = a.offset + i * kBoneSize;
        if (!r.has(o, kBoneSize)) break;
        M2Bone b;
        b.keyBoneId = r.i32(o); b.flags = r.u32(o + 4); b.parent = r.i16(o + 8); b.nameCrc = r.u32(o + 12);
        b.translation = readTrack(r, o + 16);
        b.rotation = readTrack(r, o + 16 + kTrackSize);
        b.scale = readTrack(r, o + 16 + 2 * kTrackSize);
        b.translation.src = b.rotation.src = b.scale.src = src;
        b.pivot = V3(r.f32(o + 76), r.f32(o + 80), r.f32(o + 84));
        out.push_back(b);
    }
}

void readAttachments(const Reader& r, M2Array a, M2Array lookup, std::vector<M2Attachment>& out, std::vector<int16_t>& outLookup) {
    for (uint32_t i = 0; i < a.count; ++i) {
        size_t o = a.offset + i * 40;
        if (!r.has(o, 40)) break;
        out.push_back({r.u32(o), r.u16(o + 4), V3(r.f32(o + 8), r.f32(o + 12), r.f32(o + 16))});
    }
    for (uint32_t i = 0; i < lookup.count; ++i) outLookup.push_back(r.i16(lookup.offset + 2 * i));
}

} // namespace

// A .skel: SKS1 (global loops, sequences), SKB1 (bones; their tracks' offsets count from SKB1's data), SKA1 (attachments),
// SKPD (its parent skeleton) and AFID (the animation files of its sequences). Offsets in each chunk count from its data.
bool M2Model::loadSkel(uint32_t id, const FileLookup& files, Skel& out, uint8_t src) {
    std::string path = files(id);
    if (path.empty() || !readFile(path, out.data)) { error = "skeleton file " + std::to_string(id) + " isn't here"; return false; }
    Reader whole(out.data);
    bool haveBones = false;
    for (const Chunk& c : chunks(whole, 0, out.data.size())) {
        Reader cr(out.data.data() + c.offset, c.size);
        if (c.tag == tag("SKS1")) {
            M2Array loops = readArray(cr, 0);
            for (uint32_t i = 0; i < loops.count; ++i) out.loops.push_back(cr.u32(loops.offset + 4 * i));
            readSequences(cr, readArray(cr, 8), src, out.sequences);
        } else if (c.tag == tag("SKB1")) {
            out.skb = c.offset;
            Reader br(out.data.data() + c.offset, out.data.size() - c.offset);
            readBones(br, readArray(br, 0), src, out.bones);
            haveBones = true;
        } else if (c.tag == tag("SKA1")) {
            readAttachments(cr, readArray(cr, 0), readArray(cr, 8), out.attachments, out.attachmentLookup);
        } else if (c.tag == tag("SKPD")) {
            if (c.size >= 12) out.parent = cr.u32(8);
        } else if (c.tag == tag("AFID")) {
            for (uint32_t i = 0; i + 8 <= c.size; i += 8)
                out.afid.push_back({uint32_t(cr.u16(i)) | uint32_t(cr.u16(i + 2)) << 16, cr.u32(i + 4)});
        }
    }
    if (!haveBones) { error = "skeleton file " + std::to_string(id) + " has no bones (SKB1)"; return false; }
    return true;
}

// A child skeleton (SKPD: Lightforged on Draenei, Mechagnome on Gnome...) keeps the bones (the same, in the same order)
// and the sequences it changes; the rest it plays from its parent. Each of its sequences whose keys are in neither it nor
// an animation file of its own takes the parent's of the same animation and variation; the parent's that it lacks are
// added; the parent's aliases and variations are pointed at the merged list.
void M2Model::mergeParentSequences() {
    auto key = [](const M2Sequence& s) { return uint32_t(s.id) | uint32_t(s.variation) << 16; };
    std::map<uint32_t, int> parentByKey;
    for (size_t j = 0; j < parent_.sequences.size(); ++j) parentByKey.emplace(key(parent_.sequences[j]), int(j));
    std::map<uint32_t, bool> ownFile;
    for (auto& e : skel_.afid) if (e.second) ownFile[e.first] = true;
    std::map<uint32_t, int> childKeys;
    for (size_t i = 0; i < sequences.size(); ++i) {
        M2Sequence& s = sequences[i];
        childKeys.emplace(key(s), int(i));
        bool own = (s.flags & 0x20) || (s.flags & 0x40) || ownFile.count(key(s));
        auto it = parentByKey.find(key(s));
        if (own || it == parentByKey.end()) continue;
        M2Sequence p = parent_.sequences[size_t(it->second)];
        sequences[i] = p;
    }
    for (size_t j = 0; j < parent_.sequences.size(); ++j)
        if (!childKeys.count(key(parent_.sequences[j]))) sequences.push_back(parent_.sequences[j]);
    std::map<uint32_t, int> merged;
    for (size_t i = 0; i < sequences.size(); ++i) merged.emplace(key(sequences[i]), int(i));
    auto remap = [&](int parentIndex) -> int {
        if (parentIndex < 0 || size_t(parentIndex) >= parent_.sequences.size()) return -1;
        auto it = merged.find(key(parent_.sequences[size_t(parentIndex)]));
        return it == merged.end() ? -1 : it->second;
    };
    for (M2Sequence& s : sequences) {
        if (s.src != 2) continue;
        if (s.flags & 0x40) { int a = remap(s.aliasNext); if (a >= 0) s.aliasNext = uint16_t(a); else s.flags &= ~0x40u; }
        s.variationNext = int16_t(remap(s.variationNext));
    }
}

// M2 camera tracks store arrays of spline keys for each sequence. The character
// creation screen uses the first camera at the start of its sequence.
static size_t firstCameraKey(const Reader& r, size_t track, size_t keySize) {
    const M2Array sequences = readArray(r, track + 12);
    if (sequences.count == 0 || !r.has(sequences.offset, 8)) return 0;
    const M2Array keys = readArray(r, sequences.offset);
    return keys.count && r.has(keys.offset, keySize) ? keys.offset : 0;
}

static std::optional<M2CameraView> readPreviewCamera(const Reader& r) {
    const M2Array cameras = readArray(r, 272);
    if (!cameras.count) return {};
    const size_t base = cameras.offset;
    const bool usesFovTrack = r.u32(4) >= 272;
    if (!r.has(base, usesFovTrack ? 116 : 100)) return {};
    const size_t clip = base + (usesFovTrack ? 4 : 8);
    const auto vec = [&](size_t at) { return V3(r.f32(at), r.f32(at + 4), r.f32(at + 8)); };
    const auto point = [&](size_t track) {
        const size_t key = firstCameraKey(r, track, 36);
        return vec(track + 20) + (key ? vec(key) : V3());
    };
    M2CameraView view;
    view.eye = point(clip + 8);
    view.target = point(clip + 40);
    view.farClip = r.f32(clip);
    view.nearClip = r.f32(clip + 4);
    const size_t fovKey = usesFovTrack ? firstCameraKey(r, clip + 92, 12) : base + 4;
    view.diagonalFov = fovKey ? r.f32(fovKey) : 0.0f;
    const size_t rollKey = firstCameraKey(r, clip + 72, 12);
    view.roll = rollKey ? r.f32(rollKey) : 0.0f;
    const float distance = (view.eye - view.target).length();
    if (!std::isfinite(distance) || distance < 0.01f || !(view.diagonalFov > 0.02f && view.diagonalFov < 3.1f))
        return {};
    return view;
}

bool M2Model::load(uint32_t id, const FileLookup& files) {
    fileId = id;
    std::string path = files(id);
    if (path.empty() || !readFile(path, file_)) { error = "model file " + std::to_string(id) + " isn't here"; return false; }
    Reader whole(file_);

    std::vector<uint32_t> skinIds, texIds;
    if (whole.u32(0) == tag("MD20")) {
        base_ = 0;
    } else {
        bool found = false;
        for (const Chunk& c : chunks(whole, 0, file_.size())) {
            Reader cr(file_.data() + c.offset, c.size);
            if (c.tag == tag("MD21")) { base_ = c.offset; found = true; }
            else if (c.tag == tag("SFID")) for (uint32_t i = 0; i + 4 <= c.size; i += 4) skinIds.push_back(cr.u32(i));
            else if (c.tag == tag("TXID")) for (uint32_t i = 0; i + 4 <= c.size; i += 4) texIds.push_back(cr.u32(i));
            else if (c.tag == tag("AFID"))
                for (uint32_t i = 0; i + 8 <= c.size; i += 8)
                    afid_.push_back({uint32_t(cr.u16(i)) | uint32_t(cr.u16(i + 2)) << 16, cr.u32(i + 4)});
            else if (c.tag == tag("SKID") && c.size >= 4) skeletonId = cr.u32(0);
        }
        if (!found) { error = "model file " + std::to_string(id) + " has no MD21 chunk"; return false; }
    }
    Reader r(file_.data() + base_, file_.size() - base_);
    if (r.u32(0) != tag("MD20")) { error = "model file " + std::to_string(id) + " isn't an M2"; return false; }
    flags = r.u32(16);
    previewCamera = readPreviewCamera(r);

    M2Array a = readArray(r, 20);
    for (uint32_t i = 0; i < a.count; ++i) globalLoops.push_back(r.u32(a.offset + 4 * i));

    readSequences(r, readArray(r, 28), 0, sequences);
    ownSequences_ = sequences.size();
    readBones(r, readArray(r, 44), 0, bones);

    a = readArray(r, 60);
    vertices.resize(a.count);
    for (uint32_t i = 0; i < a.count; ++i) {
        size_t o = a.offset + i * kVertexSize;
        if (!r.has(o, kVertexSize)) { vertices.resize(i); break; }
        std::memcpy(&vertices[i], r.data + o, kVertexSize);
    }

    if (!vertices.empty()) {
        extentMin = extentMax = V3(vertices[0].pos[0], vertices[0].pos[1], vertices[0].pos[2]);
        for (const M2Vertex& v : vertices) {
            extentMin = V3(std::min(extentMin.x, v.pos[0]), std::min(extentMin.y, v.pos[1]), std::min(extentMin.z, v.pos[2]));
            extentMax = V3(std::max(extentMax.x, v.pos[0]), std::max(extentMax.y, v.pos[1]), std::max(extentMax.z, v.pos[2]));
        }
    }

    a = readArray(r, 72);
    for (uint32_t i = 0; i < a.count; ++i) {
        colorRgb.push_back(readTrack(r, a.offset + i * 40));
        colorAlpha.push_back(readTrack(r, a.offset + i * 40 + 20));
    }
    a = readArray(r, 80);
    for (uint32_t i = 0; i < a.count; ++i) {
        M2Texture t;
        t.type = r.u32(a.offset + i * 16);
        t.flags = r.u32(a.offset + i * 16 + 4);
        if (i < texIds.size()) t.fileId = texIds[i];
        textures.push_back(t);
    }
    a = readArray(r, 88);
    for (uint32_t i = 0; i < a.count; ++i) weights.push_back(readTrack(r, a.offset + i * kTrackSize));
    a = readArray(r, 112);
    for (uint32_t i = 0; i < a.count; ++i) materials.push_back({r.u16(a.offset + 4 * i), r.u16(a.offset + 4 * i + 2)});
    a = readArray(r, 128);
    for (uint32_t i = 0; i < a.count; ++i) textureCombos.push_back(r.u16(a.offset + 2 * i));
    a = readArray(r, 144);
    for (uint32_t i = 0; i < a.count; ++i) weightCombos.push_back(r.u16(a.offset + 2 * i));
    boundsMin = V3(r.f32(160), r.f32(164), r.f32(168));
    boundsMax = V3(r.f32(172), r.f32(176), r.f32(180));
    readAttachments(r, readArray(r, 240), readArray(r, 248), attachments, attachmentLookup);

    // Today's models keep their skeleton apart (SKID: bones, sequences, attachments, animation files), its parent's too.
    if (skeletonId) {
        if (!loadSkel(skeletonId, files, skel_, 1)) return false;
        if (bones.empty()) bones = skel_.bones;
        if (sequences.empty()) sequences = skel_.sequences;
        if (attachments.empty()) { attachments = skel_.attachments; attachmentLookup = skel_.attachmentLookup; }
        afid_.insert(afid_.end(), skel_.afid.begin(), skel_.afid.end());
        parentSkeletonId = skel_.parent;
        // the parent's keys serve only bones laid out as its own; without it the skeleton's own sequences still play
        if (parentSkeletonId && loadSkel(parentSkeletonId, files, parent_, 2) && parent_.bones.size() == bones.size()) mergeParentSequences();
        else parent_ = Skel();
        error.clear();
    }

    // The first (most detailed) skin.
    if (skinIds.empty() || !skinIds[0]) { error = "model " + std::to_string(id) + " names no skin (SFID)"; return false; }
    Bytes skin;
    std::string skinPath = files(skinIds[0]);
    if (skinPath.empty() || !readFile(skinPath, skin)) { error = "skin file " + std::to_string(skinIds[0]) + " isn't here"; return false; }
    Reader s(skin);
    if (s.u32(0) != tag("SKIN")) { error = "skin file " + std::to_string(skinIds[0]) + " isn't a skin"; return false; }
    M2Array lookup = readArray(s, 4), tris = readArray(s, 12), subs = readArray(s, 28), bats = readArray(s, 36);
    indices.reserve(tris.count);
    for (uint32_t i = 0; i < tris.count; ++i) {
        uint16_t local = s.u16(tris.offset + 2 * i);
        uint32_t global = local < lookup.count ? s.u16(lookup.offset + 2 * local) : 0;
        indices.push_back(global < vertices.size() ? global : 0);
    }
    for (uint32_t i = 0; i < subs.count; ++i) {
        size_t o = subs.offset + i * 48;
        M2Submesh m;
        m.id = s.u16(o);
        m.indexStart = s.u16(o + 8) + (uint32_t(s.u16(o + 2)) << 16);
        m.indexCount = s.u16(o + 10);
        m.sortCenter = V3(s.f32(o + 32), s.f32(o + 36), s.f32(o + 40));
        if (m.indexStart + m.indexCount > indices.size()) m.indexCount = 0;
        submeshes.push_back(m);
    }
    for (uint32_t i = 0; i < bats.count; ++i) {
        size_t o = bats.offset + i * 24;
        M2Batch b;
        b.flags = s.u8(o); b.priority = int8_t(s.u8(o + 1)); b.shaderId = s.u16(o + 2);
        b.submesh = s.u16(o + 4); b.colorIndex = s.u16(o + 8); b.material = s.u16(o + 10);
        b.layer = s.u16(o + 12); b.textureCount = s.u16(o + 14); b.textureCombo = s.u16(o + 16);
        b.weightCombo = s.u16(o + 20);
        batches.push_back(b);
    }
    return true;
}

int M2Model::findSequence(uint16_t animId) const {
    for (size_t i = 0; i < sequences.size(); ++i)
        if (sequences[i].id == animId && sequences[i].variation == 0) return int(i);
    for (size_t i = 0; i < sequences.size(); ++i)
        if (sequences[i].id == animId) return int(i);
    return -1;
}

std::vector<int> M2Model::variations(int seq) const {
    std::vector<int> out;
    if (seq < 0 || size_t(seq) >= sequences.size()) return out;
    for (size_t i = 0; i < sequences.size(); ++i)
        if (sequences[i].id == sequences[size_t(seq)].id) out.push_back(int(i));
    return out;
}

int M2Model::dataSequence(int seq) const {
    int guard = 0;
    while (seq >= 0 && size_t(seq) < sequences.size() && (sequences[seq].flags & 0x40) && guard++ < 16)
        seq = sequences[seq].aliasNext;
    return seq;
}

bool M2Model::sequenceReady(int seq) const {
    int d = dataSequence(seq);
    if (d < 0 || size_t(d) >= sequences.size()) return false;
    return (sequences[d].flags & 0x20) || animFiles_.count(d);
}

void M2Model::ensureSequence(int seq, const FileLookup& files) {
    int d = dataSequence(seq);
    if (d < 0 || size_t(d) >= sequences.size() || sequenceReady(d)) return;
    uint32_t key = uint32_t(sequences[d].id) | uint32_t(sequences[d].variation) << 16;
    for (auto& e : sequences[d].src == 2 ? parent_.afid : afid_) {
        if (e.first != key || !e.second) continue;
        std::string path = files(e.second);
        Bytes b;
        if (path.empty() || !readFile(path, b)) return;
        size_t off = 0;
        Reader ar(b);
        if (ar.u32(0) == tag("AFM2") || ar.u32(0) == tag("AFSA") || ar.u32(0) == tag("AFSB")) {
            for (const Chunk& c : chunks(ar, 0, b.size()))
                if (c.tag == tag("AFM2") || c.tag == tag("AFSB")) { off = c.offset; if (c.tag == tag("AFSB")) break; }
        }
        animBase_[d] = off;
        animFiles_[d] = std::move(b);
        return;
    }
}

bool M2Model::keys(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs,
                   const uint8_t*& data, size_t& dataSize, uint32_t& tsOff, uint32_t& valOff,
                   uint32_t& count, uint32_t& time, uint32_t* loopOut, bool looping) const {
    // the track's own file: the model, its skeleton or the skeleton's parent
    const Skel* sk = t.src == 1 ? &skel_ : t.src == 2 ? &parent_ : nullptr;
    Reader r = sk ? Reader(sk->data.data() + sk->skb, sk->data.size() - sk->skb) : Reader(file_.data() + base_, file_.size() - base_);
    const std::vector<uint32_t>& loops = sk ? sk->loops : globalLoops;
    uint32_t idx;
    int animKey = 0;
    bool inModel;
    if (loopOut) *loopOut = 0;
    if (t.globalSeq != 0xFFFF) {
        idx = 0; inModel = true;
        uint32_t loop = t.globalSeq < loops.size() ? loops[t.globalSeq] : 0;
        time = loop ? globalMs % loop : 0;
        if (loopOut) *loopOut = loop;     // a global sequence always loops
    } else if (!sk && ownSequences_ == 0) {
        // a model whose sequences are its skeleton's: its own tracks (texture weights, colours) keep one set of keys,
        // in the model, for every sequence (the sequence's .anim holds the skeleton's bones, not these)
        idx = 0; inModel = true;
        time = timeMs;
    } else {
        int d = dataSequence(seq);
        if (d < 0 || size_t(d) >= sequences.size()) return false;
        // a skeleton's track holds keys for its own file's sequences only
        if (sk && sequences[d].src != t.src) return false;
        idx = sk ? sequences[d].srcIndex : uint32_t(d);
        animKey = d;
        inModel = (sequences[d].flags & 0x20) != 0;
        time = timeMs;
        if (loopOut && looping) *loopOut = sequences[d].duration;
    }
    if (idx >= t.timestamps.count || idx >= t.values.count) return false;
    M2Array ts = readArray(r, t.timestamps.offset + 8 * idx), vs = readArray(r, t.values.offset + 8 * idx);
    count = std::min(ts.count, vs.count);
    if (!count) return false;
    if (inModel) {
        data = r.data; dataSize = r.size; tsOff = ts.offset; valOff = vs.offset;
    } else {
        auto it = animFiles_.find(animKey);
        if (it == animFiles_.end()) return false;
        size_t b = animBase_.at(animKey);
        data = it->second.data() + b; dataSize = it->second.size() - b;
        tsOff = ts.offset; valOff = vs.offset;
    }
    return true;
}

namespace {

// The key pair around `time` and how far between them it is. With `loop` (the length the keys repeat over), a time past
// the last key moves from it to the first, which comes again at `loop` (the game's keys often stop short of the end:
// holding the last one and then jumping to the first would show as a hitch at every loop).
void bracket(const Reader& r, uint32_t tsOff, uint32_t count, uint32_t time, uint32_t& a, uint32_t& b, float& f, uint32_t loop = 0) {
    uint32_t last = count - 1;
    if (count == 1 || time <= r.u32(tsOff)) { a = b = 0; f = 0; return; }
    uint32_t tl = r.u32(tsOff + 4 * last);
    if (time >= tl) {
        a = b = last; f = 0;
        if (loop > tl && r.u32(tsOff) == 0 && time < loop) { b = 0; f = float(time - tl) / float(loop - tl); }
        return;
    }
    uint32_t lo = 0, hi = last;
    while (hi - lo > 1) {
        uint32_t mid = (lo + hi) / 2;
        if (r.u32(tsOff + 4 * mid) <= time) lo = mid; else hi = mid;
    }
    a = lo; b = hi;
    uint32_t t0 = r.u32(tsOff + 4 * a), t1 = r.u32(tsOff + 4 * b);
    f = t1 > t0 ? float(time - t0) / float(t1 - t0) : 0;
}

// Between two spline keys (value, in tangent, out tangent): 2 Hermite (tangents as slopes), 3 Bezier (as control points).
V3 spline(int interpolation, V3 p0, V3 out0, V3 in1, V3 p1, float f) {
    float f2 = f * f, f3 = f2 * f;
    if (interpolation == 3) {
        float u = 1 - f;
        return p0 * (u * u * u) + out0 * (3 * u * u * f) + in1 * (3 * u * f2) + p1 * f3;
    }
    return p0 * (2 * f3 - 3 * f2 + 1) + out0 * (f3 - 2 * f2 + f) + p1 * (-2 * f3 + 3 * f2) + in1 * (f3 - f2);
}

} // namespace

// Keys by the track's interpolation: 0 none (a key holds until the next), 1 linear, 2 Hermite, 3 Bezier (the last two
// keep the value and two tangents per key).
V3 M2Model::evalV3(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, V3 def, bool looping, bool* keyed) const {
    const uint8_t* d; size_t n; uint32_t tsOff, valOff, count, time, loop;
    bool has = keys(t, seq, timeMs, globalMs, d, n, tsOff, valOff, count, time, &loop, looping);
    if (keyed) *keyed = has;
    if (!has) return def;
    Reader r(d, n);
    uint32_t a, b; float f;
    bracket(r, tsOff, count, time, a, b, f, loop);
    size_t stride = t.interpolation >= 2 ? 36 : 12;
    auto at = [&](uint32_t k, int part) { size_t o = valOff + k * stride + size_t(part) * 12; return V3(r.f32(o), r.f32(o + 4), r.f32(o + 8)); };
    if (t.interpolation == 0 || a == b) return at(a, 0);
    if (t.interpolation >= 2) return spline(t.interpolation, at(a, 0), at(a, 2), at(b, 1), at(b, 0), f);
    return lerp(at(a, 0), at(b, 0), f);
}

Quat M2Model::evalQuat(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, bool looping, bool* keyed) const {
    const uint8_t* d; size_t n; uint32_t tsOff, valOff, count, time, loop;
    bool has = keys(t, seq, timeMs, globalMs, d, n, tsOff, valOff, count, time, &loop, looping);
    if (keyed) *keyed = has;
    if (!has) return Quat();
    Reader r(d, n);
    uint32_t a, b; float f;
    bracket(r, tsOff, count, time, a, b, f, loop);
    size_t stride = t.interpolation >= 2 ? 24 : 8;
    auto at = [&](uint32_t k) {
        size_t o = valOff + k * stride;
        return Quat(unpackShort(r.i16(o)), unpackShort(r.i16(o + 2)), unpackShort(r.i16(o + 4)), unpackShort(r.i16(o + 6))).normalized();
    };
    // a turn between keys at a constant speed, the short way round (spline rotation keys move between their values too)
    return t.interpolation == 0 || a == b ? at(a) : slerp(at(a), at(b), f);
}

float M2Model::evalShort(const M2Track& t, int seq, uint32_t timeMs, uint32_t globalMs, float def) const {
    const uint8_t* d; size_t n; uint32_t tsOff, valOff, count, time;
    if (!keys(t, seq, timeMs, globalMs, d, n, tsOff, valOff, count, time)) return def;
    Reader r(d, n);
    uint32_t a, b; float f;
    bracket(r, tsOff, count, time, a, b, f);
    size_t stride = t.interpolation >= 2 ? 6 : 2;
    float va = r.i16(valOff + a * stride) / 32767.0f, vb = r.i16(valOff + b * stride) / 32767.0f;
    return t.interpolation == 0 ? va : va + (vb - va) * f;
}

void M2Model::pose(int seq, uint32_t timeMs, uint32_t globalMs, std::vector<BonePose>& out, bool looping,
                   const std::vector<BonePose>* base) const {
    out.resize(bones.size());
    // a sequence the parent skeleton plays moves the bones by the parent's tracks (the same bones, in the same order)
    int d = dataSequence(seq);
    bool fromParent = d >= 0 && size_t(d) < sequences.size() && sequences[d].src == 2 && parent_.bones.size() == bones.size();
    // a layer over `base`: a bone the layer has no rotation keys for keeps base's whole pose (never the bind pose)
    const std::vector<BonePose>* under = base && base != &out && base->size() == bones.size() && isLayer(seq) ? base : nullptr;
    for (size_t i = 0; i < bones.size(); ++i) {
        const M2Bone& b = fromParent ? parent_.bones[i] : bones[i];
        bool keyed = true;
        Quat r = evalQuat(b.rotation, seq, timeMs, globalMs, looping, under ? &keyed : nullptr);
        if (under && !keyed) { out[i] = (*under)[i]; continue; }
        out[i].r = r;
        out[i].t = evalV3(b.translation, seq, timeMs, globalMs, V3(0, 0, 0), looping);
        out[i].s = evalV3(b.scale, seq, timeMs, globalMs, V3(1, 1, 1), looping);
    }
}

bool M2Model::boneKeyed(int bone, int seq) const {
    if (bone < 0 || size_t(bone) >= bones.size()) return false;
    int d = dataSequence(seq);
    if (d < 0 || size_t(d) >= sequences.size()) return false;
    bool fromParent = sequences[d].src == 2 && parent_.bones.size() == bones.size();
    const M2Track& t = (fromParent ? parent_.bones[size_t(bone)] : bones[size_t(bone)]).rotation;
    if (t.globalSeq != 0xFFFF) return false;   // a global loop's keys aren't the sequence's
    const uint8_t* data; size_t n; uint32_t tsOff, valOff, count, time;
    return keys(t, seq, 0, 0, data, n, tsOff, valOff, count, time);
}

namespace {
// The body's main bones (WoW-side names), what isLayer measures a sequence by.
const char* const kLayerBones[] = {"Pelvis", "Spine1", "Chest", "Neck1", "Head", "L_Clavicle", "R_Clavicle", "L_UpperArm",
    "R_UpperArm", "L_Forearm", "R_Forearm", "L_Hand", "R_Hand", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Foot", "R_Foot"};
}

bool M2Model::isLayer(int seq) const {
    int d = dataSequence(seq);
    if (d < 0 || size_t(d) >= sequences.size() || !sequenceReady(d)) return false;
    auto it = layers_.find(d);
    if (it != layers_.end()) return it->second;
    int found = 0, bare = 0;
    for (const char* name : kLayerBones) {
        int b = findWowBone(name);
        if (b < 0) continue;
        ++found;
        if (!boneKeyed(b, d)) ++bare;
    }
    // a creature's skeleton names too few of these to tell: never a layer
    bool layer = found >= 8 && bare * 4 >= found;
    layers_[d] = layer;
    return layer;
}

void M2Model::bonesToMatrices(const std::vector<BonePose>& poses, std::vector<M4>& out) const {
    size_t n = bones.size();
    out.assign(n, M4());
    std::vector<uint8_t> done(n, 0);
    // Parents normally come first; the loop repeats for any that don't.
    for (int pass = 0; pass < 8; ++pass) {
        bool all = true;
        for (size_t i = 0; i < n; ++i) {
            if (done[i]) continue;
            int p = bones[i].parent;
            if (p >= 0 && size_t(p) < n && !done[p]) { all = false; continue; }
            const M2Bone& b = bones[i];
            const BonePose& q = i < poses.size() ? poses[i] : BonePose();
            M4 local = M4::translation(b.pivot) * M4::translation(q.t) * M4::rotation(q.r) *
                       M4::scaling(q.s) * M4::translation(b.pivot * -1.0f);
            M4 parent = (p >= 0 && size_t(p) < n) ? out[p] : M4();
            // flags 1, 2, 4: the bone ignores its parent's translation, scale, rotation (it follows where the parent
            // carries its pivot, unless it ignores that too)
            if ((b.flags & 7) && p >= 0) {
                V3 moved = parent.point(b.pivot) - b.pivot;
                if ((b.flags & 6) == 6) parent = (b.flags & 1) ? M4() : M4::translation(moved);
                else if (b.flags & 1) { parent.m[12] -= moved.x; parent.m[13] -= moved.y; parent.m[14] -= moved.z; }
            }
            out[i] = parent * local;
            done[i] = 1;
        }
        if (all) break;
    }
}

float M2Model::evalWeight(int index, int seq, uint32_t timeMs, uint32_t globalMs) const {
    if (index < 0 || size_t(index) >= weightCombos.size()) return 1;
    uint16_t w = weightCombos[index];
    if (w >= weights.size()) return 1;
    return std::clamp(evalShort(weights[w], seq, timeMs, globalMs, 1), 0.0f, 1.0f);
}

void M2Model::evalColor(int index, int seq, uint32_t timeMs, uint32_t globalMs, float rgba[4]) const {
    rgba[0] = rgba[1] = rgba[2] = rgba[3] = 1;
    if (index < 0 || size_t(index) >= colorRgb.size()) return;
    V3 c = evalV3(colorRgb[index], seq, timeMs, globalMs, V3(1, 1, 1));
    rgba[0] = c.x; rgba[1] = c.y; rgba[2] = c.z;
    rgba[3] = std::clamp(evalShort(colorAlpha[index], seq, timeMs, globalMs, 1), 0.0f, 1.0f);
}

uint32_t crc32(const char* s, size_t n) {
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; ++i) {
        c ^= uint8_t(s[i]);
        for (int k = 0; k < 8; ++k) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
    return ~c;
}

namespace {

// name -> key bone ids, bone-name CRC names, "first child of" (tools/wow_bones.py: keep the two in step). The
// left side is +y in model space, the face looks along +x; R_ names are the L_ ones mirrored.
struct WowBoneRule {
    const char* name;
    int keys[2];
    const char* crcs[3];
    const char* childOf;
};

const WowBoneRule kWowBones[] = {
    {"Root", {26, -1}, {"Root"}, nullptr},
    {"Pelvis", {51, -1}, {"EXP_C1_Pelvis1"}, nullptr},
    {"Waist", {5, -1}, {"Waist"}, nullptr},
    {"Spine1", {4, -1}, {"SpineLow"}, nullptr},
    {"Spine2", {48, -1}, {"EXP_C1_Spine2"}, nullptr},
    {"Chest", {53, -1}, {"Chest", "SpineUp"}, nullptr},
    {"Neck1", {49, -1}, {"EXP_C1_Neck1", "Neck"}, nullptr},
    {"Neck2", {50, -1}, {"EXP_C1_Neck2"}, nullptr},
    {"Head", {6, -1}, {"Head"}, nullptr},
    {"Jaw", {7, -1}, {"Jaw"}, nullptr},
    {"L_Clavicle", {2, -1}, {"ShoulderL"}, nullptr},     {"R_Clavicle", {3, -1}, {"ShoulderR"}, nullptr},
    {"L_UpperArm", {0, -1}, {"ArmL"}, nullptr},          {"R_UpperArm", {1, -1}, {"ArmR"}, nullptr},
    {"L_Forearm", {62, -1}, {"ElbowL", "ForearmL"}, nullptr}, {"R_Forearm", {61, -1}, {"ElbowR", "ForearmR"}, nullptr},
    {"L_Hand", {65, -1}, {"HandL"}, nullptr},            {"R_Hand", {64, -1}, {"HandR"}, nullptr},
    {"L_Thigh", {56, -1}, {"EXP_L1_Leg1Twist1", "LegL"}, nullptr}, {"R_Thigh", {55, -1}, {"EXP_R1_Leg1Twist1", "LegR"}, nullptr},
    {"L_Calf", {58, -1}, {"EXP_L1_Leg2Twist1", "KneeL", "CalfL"}, nullptr}, {"R_Calf", {57, -1}, {"EXP_R1_Leg2Twist1", "KneeR", "CalfR"}, nullptr},
    {"L_Ankle", {-1, -1}, {"EXP_L1_Leg2Twist3"}, nullptr}, {"R_Ankle", {-1, -1}, {"EXP_R1_Leg2Twist3"}, nullptr},
    {"L_Foot", {59, -1}, {"FootL"}, nullptr},            {"R_Foot", {60, -1}, {"FootR"}, nullptr},
    {"L_Toe", {-1, -1}, {"ToeL"}, "L_Foot"},             {"R_Toe", {-1, -1}, {"ToeR"}, "R_Foot"},
    {"L_Breast", {-1, -1}, {"EXP_L1_Breast1"}, nullptr}, {"R_Breast", {-1, -1}, {"EXP_R1_Breast1"}, nullptr},
    {"L_Thumb1", {17, -1}, {"ThumbL"}, nullptr},         {"R_Thumb1", {12, -1}, {"ThumbR"}, nullptr},
    {"L_Thumb2", {-1, -1}, {"ThumbTipL"}, nullptr},      {"R_Thumb2", {-1, -1}, {"ThumbTipR"}, nullptr},
    {"L_Index1", {13, -1}, {"IndexFingerL"}, nullptr},   {"R_Index1", {8, -1}, {"IndexFingerR"}, nullptr},
    {"L_Index2", {-1, -1}, {"IndexFingerTipL"}, nullptr}, {"R_Index2", {-1, -1}, {"IndexFingerTipR"}, nullptr},
    {"L_Middle1", {14, -1}, {"MiddleFingerL"}, nullptr}, {"R_Middle1", {9, -1}, {"MiddleFingerR"}, nullptr},
    {"L_Middle2", {-1, -1}, {"MiddleFingerTipL"}, nullptr}, {"R_Middle2", {-1, -1}, {"MiddleFingerTipR"}, nullptr},
    {"L_Ring1", {16, -1}, {"RingFingerL"}, nullptr},     {"R_Ring1", {11, -1}, {"RingFingerR"}, nullptr},
    {"L_Ring2", {-1, -1}, {"RingFingerTipL"}, nullptr},  {"R_Ring2", {-1, -1}, {"RingFingerTipR"}, nullptr},
    {"L_Pinky1", {15, -1}, {"PinkyFingerL"}, nullptr},   {"R_Pinky1", {10, -1}, {"PinkyFingerR"}, nullptr},
    {"L_Pinky2", {-1, -1}, {"PinkyFingerTipL"}, nullptr}, {"R_Pinky2", {-1, -1}, {"PinkyFingerTipR"}, nullptr},
    {"L_Eye", {285, -1}, {"EXP_L1_Eye1"}, nullptr},      {"R_Eye", {286, -1}, {"EXP_R1_Eye1"}, nullptr},
};

} // namespace

int M2Model::findWowBone(const std::string& name) const {
    for (const WowBoneRule& r : kWowBones) {
        if (name != r.name) continue;
        for (int k : r.keys) {
            if (k < 0) continue;
            for (size_t i = 0; i < bones.size(); ++i) if (bones[i].keyBoneId == k) return int(i);
        }
        for (const char* c : r.crcs) {
            if (!c) continue;
            uint32_t h = crc32(c, std::strlen(c));
            for (size_t i = 0; i < bones.size(); ++i) if (bones[i].nameCrc == h) return int(i);
        }
        if (r.childOf) {
            int p = findWowBone(r.childOf);
            if (p >= 0) for (size_t i = 0; i < bones.size(); ++i) if (bones[i].parent == p) return int(i);
        }
        return -1;
    }
    return -1;
}

size_t applyBodyMorph(std::vector<M2Vertex>& vertices, const float* morph, size_t count, BodyMorphStats* stats) {
    if (!morph || !count || vertices.empty()) return 0;
    constexpr float kCell = 0.02f, kReach = 0.02f, kExact = 1e-5f;
    auto cellOf = [&](float v) { return int64_t(std::floor(v / kCell)); };
    auto key = [](int64_t x, int64_t y, int64_t z) { return (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF); };
    std::unordered_map<int64_t, std::vector<uint32_t>> grid;
    grid.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        const float* r = morph + i * kBodyMorphStride;
        grid[key(cellOf(r[0]), cellOf(r[1]), cellOf(r[2]))].push_back(uint32_t(i));
    }
    BodyMorphStats st;
    size_t moved = 0;
    for (M2Vertex& v : vertices) {
        V3 p(v.pos[0], v.pos[1], v.pos[2]);
        V3 n(v.normal[0], v.normal[1], v.normal[2]);
        int64_t cx = cellOf(p.x), cy = cellOf(p.y), cz = cellOf(p.z);
        // the looks vertices at this place (several: a seam's, the same place in two sections), the best by its coordinates
        const float* exact = nullptr;
        int exactRank = -1;
        V3 sum, nsum;
        float wsum = 0;
        for (int64_t dx = -1; dx <= 1; ++dx)
            for (int64_t dy = -1; dy <= 1; ++dy)
                for (int64_t dz = -1; dz <= 1; ++dz) {
                    auto it = grid.find(key(cx + dx, cy + dy, cz + dz));
                    if (it == grid.end()) continue;
                    for (uint32_t i : it->second) {
                        const float* r = morph + size_t(i) * kBodyMorphStride;
                        V3 d = V3(r[0], r[1], r[2]) - p;
                        float dist = d.length();
                        if (dist < kExact) {
                            bool body = std::fabs(r[12] - v.uv[0] * 2) < 1e-4f && std::fabs(r[13] - v.uv[1]) < 1e-4f;
                            bool same = std::fabs(r[12] - v.uv[0]) < 1e-4f && std::fabs(r[13] - v.uv[1]) < 1e-4f;
                            V3 rn(r[6], r[7], r[8]);
                            int rank = (body || same ? 2 : 0) + (rn.dot(n) > 0.99f ? 1 : 0);
                            if (rank > exactRank) { exactRank = rank; exact = r; }
                            continue;
                        }
                        if (dist > kReach || exact) continue;
                        V3 rn(r[6], r[7], r[8]);
                        float facing = rn.dot(n);
                        if (facing < 0.3f) continue;
                        float w = facing / (dist * dist + 1e-8f);
                        sum = sum + V3(r[3], r[4], r[5]) * w;
                        nsum = nsum + (V3(r[9], r[10], r[11]) - rn) * w;
                        wsum += w;
                    }
                }
        V3 dp, dn;
        if (exact) {
            dp = V3(exact[3], exact[4], exact[5]);
            dn = V3(exact[9], exact[10], exact[11]) - V3(exact[6], exact[7], exact[8]);
            float du = exact[14] - exact[12], dv = exact[15] - exact[13];
            if (du != 0 || dv != 0) {
                if (std::fabs(exact[12] - v.uv[0] * 2) < 1e-4f && std::fabs(exact[13] - v.uv[1]) < 1e-4f) {
                    v.uv[0] = exact[14] * 0.5f; v.uv[1] = exact[15]; ++st.uvs;
                } else if (std::fabs(exact[12] - v.uv[0]) < 1e-4f && std::fabs(exact[13] - v.uv[1]) < 1e-4f) {
                    v.uv[0] = exact[14]; v.uv[1] = exact[15]; ++st.uvs;
                }
            }
            ++st.exact;
        } else if (wsum > 0) {
            dp = sum * (1.0f / wsum);
            dn = nsum * (1.0f / wsum);
            ++st.blended;
        } else {
            continue;
        }
        if (dp.length() < 1e-7f && dn.length() < 1e-6f) continue;
        v.pos[0] += dp.x; v.pos[1] += dp.y; v.pos[2] += dp.z;
        V3 nn = (n + dn).normalized();
        v.normal[0] = nn.x; v.normal[1] = nn.y; v.normal[2] = nn.z;
        ++moved;
    }
    if (stats) *stats = st;
    return moved;
}

} // namespace wc
