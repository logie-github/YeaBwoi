#include "stockfabric.h"

#include <algorithm>
#include <cmath>
#include <map>

namespace wc {

float StockFabric::rnd() {
    rng_ = rng_ * 1664525u + 1013904223u;
    return float(rng_ >> 8) / float(1u << 24);
}

void StockFabric::init(const std::vector<V3>& rest, const std::vector<uint32_t>& tris, uint32_t seed) {
    rest_ = rest; p_ = rest; target_ = rest; v_.assign(rest.size(), V3());
    tris_ = tris;
    tris_.resize(tris_.size() / 3 * 3);
    edges_.clear(); vertEdges_.assign(rest.size(), {}); edgeTris_.clear();
    std::map<std::pair<uint32_t, uint32_t>, uint32_t> seen;
    for (size_t t = 0; t < tris_.size(); t += 3)
        for (int k = 0; k < 3; ++k) {
            uint32_t a = tris_[t + k], b = tris_[t + (k + 1) % 3];
            if (a >= rest.size() || b >= rest.size() || a == b) continue;
            auto key = std::make_pair(std::min(a, b), std::max(a, b));
            auto f = seen.find(key);
            if (f == seen.end()) {
                uint32_t id = uint32_t(edges_.size());
                edges_.push_back({key.first, key.second, (rest[a] - rest[b]).length(), false});
                edgeTris_.push_back({});
                vertEdges_[key.first].push_back(id); vertEdges_[key.second].push_back(id);
                f = seen.emplace(key, id).first;
            }
            edgeTris_[f->second].push_back(uint32_t(t / 3));
        }
    alive_.assign(tris_.size() / 3, 1); rim_.assign(rest.size(), 0);
    weight_.assign(rest.size(), 0); vstrain_.assign(rest.size(), 0);
    history_.clear(); grabbed_ = -1; tension_ = 0; runs_ = 0; seed_ = seed ? seed : 1; rng_ = seed_;
}

void StockFabric::setTargets(const std::vector<V3>& t) { if (t.size() == target_.size()) target_ = t; }

bool StockFabric::grab(const V3& point, float reach, float radius) {
    int best = -1; float bd = reach;
    std::vector<uint8_t> used(p_.size(), 0);
    for (size_t t = 0; t < alive_.size(); ++t) if (alive_[t]) for (int k = 0; k < 3; ++k) used[tris_[t * 3 + k]] = 1;
    for (size_t i = 0; i < p_.size(); ++i) {
        if (!used[i]) continue;
        float d = (p_[i] - point).length();
        if (d < bd) { bd = d; best = int(i); }
    }
    if (best < 0) return false;
    grabbed_ = best; grabRest_ = rest_[size_t(best)]; grabTo_ = p_[size_t(best)];
    for (size_t i = 0; i < p_.size(); ++i) {
        float d = (rest_[i] - grabRest_).length() / std::max(radius, 1e-4f);
        weight_[i] = d >= 1.0f ? 0.0f : (1.0f - d * d) * (1.0f - d * d);   // a smooth falloff
    }
    return true;
}

void StockFabric::drag(const V3& to) { if (grabbed_ >= 0) grabTo_ = to; }

void StockFabric::release() { grabbed_ = -1; std::fill(weight_.begin(), weight_.end(), 0.0f); }

size_t StockFabric::deadTriangles() const { size_t n = 0; for (uint8_t a : alive_) n += a ? 0 : 1; return n; }

float StockFabric::worstRatio() const {
    float w = 0;
    for (const Edge& e : edges_) if (!e.broken && e.rest > 1e-6f) w = std::max(w, (p_[e.a] - p_[e.b]).length() / e.rest);
    return w;
}

void StockFabric::refresh() {
    std::fill(rim_.begin(), rim_.end(), 0);
    for (size_t t = 0; t < alive_.size(); ++t) if (!alive_[t]) for (int k = 0; k < 3; ++k) rim_[tris_[t * 3 + k]] = 1;
}

void StockFabric::tear(uint32_t from) {
    // a run: from the stretched edge along the leg's length (z), each step to the neighbouring edge the most along z, with a stochastic
    // choice among the best, up to runLength (more each time it is pulled again: the run that is already there grows)
    Damage d;
    const float length = params.runLength + params.runGrow * float(runs_ > 0 ? std::min(runs_, 4) : 0);
    uint32_t cur = from;
    float done = 0;
    int dir = rnd() < 0.5f ? 1 : -1;
    for (int guard = 0; guard < 400 && done < length; ++guard) {
        Edge& e = edges_[cur];
        if (!e.broken) { e.broken = true; d.edges.push_back(cur); }
        for (uint32_t t : edgeTris_[cur]) alive_[t] = 0;
        done += std::fabs(rest_[e.a].z - rest_[e.b].z) + 0.15f * e.rest;
        // the next edge: from either end, one that goes on along z in the direction, not yet broken
        std::vector<std::pair<float, uint32_t>> cand;
        for (uint32_t v : {e.a, e.b})
            for (uint32_t id : vertEdges_[v]) {
                if (id == cur || edges_[id].broken) continue;
                const Edge& n = edges_[id];
                float dz = (rest_[n.a].z + rest_[n.b].z) * 0.5f - (rest_[e.a].z + rest_[e.b].z) * 0.5f;
                float along = dir * dz / std::max(n.rest, 1e-6f);
                cand.push_back({along + 0.35f * (rnd() - 0.5f), id});
            }
        if (cand.empty()) break;
        std::sort(cand.begin(), cand.end(), [](auto& x, auto& y) { return x.first > y.first; });
        cur = cand[std::min<size_t>(size_t(rnd() * 2.0f), cand.size() - 1)].second;
    }
    if (!d.edges.empty()) { history_.push_back(d); ++runs_; }
    refresh();
}

void StockFabric::step(float dt) {
    if (!(dt > 0) || !std::isfinite(dt)) return;
    dt = std::min(dt, 1.0f / 30.0f);
    const size_t n = p_.size();
    // the pull: the grabbed fabric is carried by the finger, its falloff share of the finger's offset from where it was grabbed
    V3 offset;
    if (grabbed_ >= 0) offset = grabTo_ - target_[size_t(grabbed_)];
    for (size_t i = 0; i < n; ++i) {
        V3 want = target_[i];
        float w = weight_[i];
        if (w > 0) want = want + offset * w;
        V3 force = (want - p_[i]) * params.bindStiffness * (w > 0 ? 3.0f : 1.0f);
        v_[i] = (v_[i] + force * dt) * std::max(0.0f, 1.0f - params.damping * dt);
        p_[i] = p_[i] + v_[i] * dt;
    }
    // the edges: pulled back toward their rest length; the elasticity says how far the yarn lets them go (the stiffer, the more the fabric
    // drags the leg's part along instead of stretching)
    const float give = 0.5f * (1.0f - 0.9f * params.elasticity) * 0.5f;
    for (int it = 0; it < std::max(1, params.iterations); ++it)
        for (const Edge& e : edges_) {
            if (e.broken || e.rest < 1e-7f) continue;
            V3 d = p_[e.b] - p_[e.a];
            float len = d.length();
            if (len < 1e-9f) continue;
            float ratio = len / e.rest;
            // a stretched edge is shortened by `give` of its excess per pass: the stiffer the yarn, the more it carries the leg's part along
            // instead of stretching, the stretchier, the more the strain stays (and the tension with it)
            if (ratio > 1.0f) {
                V3 c = d * ((len - e.rest) / len * give);
                p_[e.a] = p_[e.a] + c; p_[e.b] = p_[e.b] - c;
            }
        }
    // the tension: the worst edge's strain over the tearing strain; per vertex its worst edge's strain, 0..1
    float worst = 0;
    std::fill(vstrain_.begin(), vstrain_.end(), 0.0f);
    uint32_t worstEdge = 0;
    for (size_t i = 0; i < edges_.size(); ++i) {
        const Edge& e = edges_[i];
        if (e.broken || e.rest < 1e-7f) continue;
        float s = std::max(0.0f, (p_[e.a] - p_[e.b]).length() / e.rest - 1.0f) / params.tearStrain;
        vstrain_[e.a] = std::max(vstrain_[e.a], std::min(1.0f, s)); vstrain_[e.b] = std::max(vstrain_[e.b], std::min(1.0f, s));
        if (s > worst) { worst = s; worstEdge = uint32_t(i); }
    }
    tension_ = std::min(1.0f, worst);
    // at full tension while held: the fabric gives (a run starts, or the one there grows)
    if (grabbed_ >= 0 && worst >= 1.0f) tear(worstEdge);
}

bool StockFabric::undo() {
    if (history_.empty()) return false;
    for (uint32_t id : history_.back().edges) {
        edges_[id].broken = false;
    }
    history_.pop_back();
    --runs_;
    // the triangles come back unless another run's edge is on them
    for (size_t t = 0; t < alive_.size(); ++t) alive_[t] = 1;
    for (const Edge* e = edges_.data(); e != edges_.data() + edges_.size(); ++e)
        if (e->broken) for (uint32_t t : edgeTris_[size_t(e - edges_.data())]) alive_[t] = 0;
    refresh();
    return true;
}

void StockFabric::repairAll() {
    for (Edge& e : edges_) e.broken = false;
    std::fill(alive_.begin(), alive_.end(), 1);
    history_.clear(); runs_ = 0;
    refresh();
}

} // namespace wc
