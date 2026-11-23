#include "dialogue_core.h"


#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <new>
#include <sys/stat.h>
#include <thread>

#if defined(__linux__)
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "llama.h"
#include "ggml-backend.h"

namespace wcd {

namespace {

std::mutex gLogMutex;
Logger gLogger;

void llamaLog(ggml_log_level level, const char *text, void *) {
    Logger l;
    {
        std::lock_guard<std::mutex> g(gLogMutex);
        l = gLogger;
    }
    if (l && text) l((int)level, text);
}

double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// The length of [s]'s longest prefix that ends on a UTF-8 character boundary.
size_t utf8Whole(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    // walk back over continuation bytes to the lead byte of the last character
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4 && ((unsigned char)s[i - 1] & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return n;   // only continuation bytes: nothing to wait for
    unsigned char lead = (unsigned char)s[i - 1];
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    size_t have = (n - (i - 1));
    return have >= need ? n : i - 1;
}

size_t holdBack(const std::string &s, const std::vector<std::string> &stops) {
    size_t hold = 0;
    for (const auto &st : stops) {
        size_t maxk = std::min(st.size() - 1, s.size());
        for (size_t k = maxk; k > hold; k--) {
            if (s.compare(s.size() - k, k, st, 0, k) == 0) { hold = k; break; }
        }
    }
    return hold;
}

uint64_t fnv(const std::string &s) {
    uint64_t h = 1469598103934665603ull;
    for (unsigned char c : s) { h ^= c; h *= 1099511628211ull; }
    return h;
}

size_t lcp(const std::vector<int32_t> &a, const std::vector<int32_t> &b) {
    size_t n = std::min(a.size(), b.size()), i = 0;
    while (i < n && a[i] == b[i]) i++;
    return i;
}

}  // namespace

static std::atomic<bool> gBackendUp{false};
static bool abortCbImpl(void *p);

uint64_t Core::rssAnonBytes() {
#if defined(__linux__)
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    uint64_t kb = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long long v;
        if (sscanf(line, "RssAnon: %llu kB", &v) == 1) { kb = v; break; }
    }
    fclose(f);
    return kb * 1024;
#else
    return 0;
#endif
}

Core::~Core() { unload(); }

void Core::setLogger(Logger l) {
    std::lock_guard<std::mutex> g(gLogMutex);
    gLogger = std::move(l);
}

uint64_t Core::rssBytes() {
#if defined(__linux__)
    FILE *f = fopen("/proc/self/statm", "r");
    if (!f) return 0;
    unsigned long total = 0, res = 0;
    int ok = fscanf(f, "%lu %lu", &total, &res);
    fclose(f);
    return ok == 2 ? (uint64_t)res * (uint64_t)sysconf(_SC_PAGESIZE) : 0;
#else
    return 0;
#endif
}

namespace {
std::atomic<float> gLoadProgress{0.0f};
bool loadProgressCb(float progress, void *) { gLoadProgress.store(progress); return true; }
}  // namespace

float Core::loadProgress() { return gLoadProgress.load(); }

// Keeps the calling thread (and so the threads ggml makes from it, which inherit the mask) on the given cores.
static void pinToCores(uint64_t mask) {
#if defined(__linux__)
    if (mask == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64 && i < CPU_SETSIZE; i++) if (mask & (1ull << i)) CPU_SET(i, &set);
    sched_setaffinity(0, sizeof set, &set);   // pid 0: this thread; a refusal (some vendors) just leaves every core allowed
#else
    (void)mask;
#endif
}

void Core::setThreads(int threads, int threadsBatch) {
    if (!ctx_) return;
    if (threads > 0) lp_.threads = threads;
    if (threadsBatch > 0) lp_.threadsBatch = threadsBatch;
    llama_set_n_threads(ctx_, std::max(1, lp_.threads), std::max(1, lp_.threadsBatch > 0 ? lp_.threadsBatch : lp_.threads));
}

bool Core::load(const LoadParams &p, std::string &error) {
    gLoadProgress.store(0.0f);
    unload();
    lp_ = p;
    const double tLoad = nowMs();
    if (!gBackendUp.exchange(true)) {
        llama_log_set(llamaLog, nullptr);
#if defined(GGML_BACKEND_DL)
        // the phone's build keeps each CPU variant in its own library (armv8.2 with dotprod, armv8.6 with i8mm, ...): ggml loads
        // every one it finds and keeps the best the CPU can run, the plainest (armv8.0) being the floor every arm64 phone passes
        if (!p.backendDir.empty()) ggml_backend_load_all_from_path(p.backendDir.c_str()); else ggml_backend_load_all();
        if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
            gBackendUp.store(false);
            error = "no CPU backend library could be loaded" + (p.backendDir.empty() ? std::string() : " from " + p.backendDir);
            return false;
        }
#endif
        llama_backend_init();
    }
#if defined(__linux__)
    if (p.nice != 0) setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), p.nice);
#endif
    try {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = loadProgressCb;   // a 7 GB file takes a while to read: the page shows how far it is
        mp.load_mode = p.mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;   // mapped: the OS can drop what is not in use
        model_ = llama_model_load_from_file(p.path.c_str(), mp);
        if (!model_) { error = "the model file could not be read (" + p.path + ")"; return false; }
        vocab_ = llama_model_get_vocab(model_);
        loadMs_ = nowMs() - tLoad;
        const double tCtx = nowMs();

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t)std::max(512, p.ctx);
        cp.n_batch = (uint32_t)std::max(32, p.ubatch);
        cp.n_ubatch = cp.n_batch;
        cp.n_seq_max = (uint32_t)std::max(1, p.slots);
        cp.n_threads = std::max(1, p.threads);
        cp.n_threads_batch = p.threadsBatch > 0 ? p.threadsBatch : cp.n_threads;
        lp_.threadsBatch = (int)cp.n_threads_batch;
        cp.kv_unified = true;
        cp.no_perf = true;
        if (p.kvBits <= 8) {
            cp.type_k = GGML_TYPE_Q8_0;
            cp.type_v = GGML_TYPE_Q8_0;
            cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;   // a quantised V cache needs it
        } else {
            cp.type_k = GGML_TYPE_F16;
            cp.type_v = GGML_TYPE_F16;
        }
        cp.abort_callback = abortCbImpl;
        cp.abort_callback_data = this;
        ctx_ = llama_init_from_model(model_, cp);
        if (!ctx_) { error = "the model's context could not be made (not enough memory?)"; unload(); return false; }
        contextMs_ = nowMs() - tCtx;
    } catch (const std::bad_alloc &) {
        error = "out of memory while loading the model";
        unload();
        return false;
    }
    slots_.assign((size_t)std::max(1, p.slots), Slot{});
    if (!p.stateDir.empty()) mkdir(p.stateDir.c_str(), 0755);
    return true;
}

void Core::unload() {
    if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    vocab_ = nullptr;
    slots_.clear();
}

ModelInfo Core::info() const {
    ModelInfo i;
    if (!model_) return i;
    char buf[256] = {0};
    llama_model_desc(model_, buf, sizeof buf);
    i.desc = buf;
    i.sizeBytes = llama_model_size(model_);
    i.params = llama_model_n_params(model_);
    i.trainCtx = llama_model_n_ctx_train(model_);
    i.ctx = (int)llama_n_ctx(ctx_);
    i.slots = (int)slots_.size();
    i.loadMs = loadMs_;
    i.contextMs = contextMs_;
    const char *sys = llama_print_system_info();
    if (sys) { i.system = sys; for (auto &ch : i.system) if (ch == '\t' || ch == '\n') ch = ' '; }
    return i;
}

std::vector<int> Core::tokenize(const std::string &text) const {
    std::vector<int> out;
    if (!vocab_) return out;
    // the model's own rule for a start-of-text mark (Mistral Nemo wants <s>; Qwen takes none), the same for every prompt so prefixes still match
    const bool addBos = llama_vocab_get_add_bos(vocab_);
    int n = -llama_tokenize(vocab_, text.c_str(), (int32_t)text.size(), nullptr, 0, addBos, true);
    if (n <= 0) return out;
    out.resize((size_t)n);
    int got = llama_tokenize(vocab_, text.c_str(), (int32_t)text.size(), (llama_token *)out.data(), n, addBos, true);
    if (got < 0) out.clear(); else out.resize((size_t)got);
    return out;
}

static bool abortCbImpl(void *p) { return static_cast<Core *>(p)->shouldAbort(); }

std::string Core::statePath(const std::string &key) const {
    if (lp_.stateDir.empty()) return "";
    char h[32];
    snprintf(h, sizeof h, "%016llx", (unsigned long long)fnv(key));
    return lp_.stateDir + "/" + h + ".seq";
}

void Core::clearSlot(int idx) {
    Slot &s = slots_[(size_t)idx];
    llama_memory_seq_rm(llama_get_memory(ctx_), idx, -1, -1);
    s.toks.clear();
    s.used = false;
    s.key.clear();
}

void Core::evict(int idx) {
    Slot &s = slots_[(size_t)idx];
    if (s.used && !lp_.stateDir.empty() && s.toks.size() >= 48) {
        std::string path = statePath(s.key);
        llama_state_seq_save_file(ctx_, path.c_str(), idx, (const llama_token *)s.toks.data(), s.toks.size());
    }
    clearSlot(idx);
}

// Makes room by evicting the least recently used slot other than [keep]; false when there is none to evict.
bool Core::evictOneFor(int keep) {
    int best = -1;
    for (int i = 0; i < (int)slots_.size(); i++) {
        if (i == keep || !slots_[(size_t)i].used || slots_[(size_t)i].toks.empty()) continue;
        if (best < 0 || slots_[(size_t)i].last < slots_[(size_t)best].last) best = i;
    }
    if (best < 0) return false;
    evict(best);
    return true;
}

// Finds or makes the NPC's slot; on a fresh slot, starts from the longest prefix a saved state or another slot gives.
int Core::acquire(const std::string &key, const std::vector<int32_t> &prompt, GenResult &r, const GenParams &gp) {
    for (int i = 0; i < (int)slots_.size(); i++)
        if (slots_[(size_t)i].used && slots_[(size_t)i].key == key) return i;
    int idx = -1;
    for (int i = 0; i < (int)slots_.size(); i++) if (!slots_[(size_t)i].used) { idx = i; break; }
    if (idx < 0) {
        for (int i = 0; i < (int)slots_.size(); i++)
            if (idx < 0 || slots_[(size_t)i].last < slots_[(size_t)idx].last) idx = i;
        evict(idx);
    }
    Slot &s = slots_[(size_t)idx];
    s.used = true;
    s.key = key;
    s.toks.clear();
    llama_memory_seq_rm(llama_get_memory(ctx_), idx, -1, -1);

    // 1. the NPC's own saved state
    size_t own = 0;
    std::string path = statePath(key);
    if (!path.empty()) {
        struct stat st;
        if (stat(path.c_str(), &st) == 0) {
            std::vector<llama_token> toks((size_t)llama_n_ctx(ctx_));
            size_t n = 0;
            size_t ok = llama_state_seq_load_file(ctx_, path.c_str(), idx, toks.data(), toks.size(), &n);
            if (ok > 0 && n > 0) {
                s.toks.assign(toks.begin(), toks.begin() + (long)n);
                own = lcp(s.toks, prompt);
                r.restoredTokens = (int)own;
            } else {
                llama_memory_seq_rm(llama_get_memory(ctx_), idx, -1, -1);
            }
            // the file stays: it is still a true prefix of what the NPC holds, so a crash before the next flush loses nothing;
            // the next eviction or flush overwrites it, and reset() removes it
        }
    }
    // 2. a prefix another NPC's slot holds (the shared preamble): borrowed without copying memory
    int bestSlot = -1;
    size_t bestLen = 0;
    for (int i = 0; i < (int)slots_.size(); i++) {
        if (i == idx || !slots_[(size_t)i].used) continue;
        size_t l = lcp(slots_[(size_t)i].toks, prompt);
        if (l > bestLen) { bestLen = l; bestSlot = i; }
    }
    if (bestSlot >= 0 && bestLen >= (size_t)gp.minShare && bestLen > own + 16) {
        llama_memory_seq_rm(llama_get_memory(ctx_), idx, -1, -1);
        s.toks.clear();
        llama_memory_seq_cp(llama_get_memory(ctx_), bestSlot, idx, 0, (llama_pos)bestLen);
        s.toks.assign(prompt.begin(), prompt.begin() + (long)bestLen);
        r.sharedTokens = (int)bestLen;
        r.restoredTokens = 0;
    }
    return idx;
}

// One decode call of [n] tokens for the slot's sequence, after the tokens it already holds. err: 0 ok, 1 no room, 2 cancelled, 3 failed.
bool Core::decodeChunk(int slot, const int32_t *toks, int n, bool wantLogits, uint64_t ticket, int &err) {
    Slot &s = slots_[(size_t)slot];
    llama_batch b = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        b.token[i] = toks[i];
        b.pos[i] = (llama_pos)(s.toks.size() + (size_t)i);
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = slot;
        b.logits[i] = (wantLogits && i == n - 1) ? 1 : 0;
    }
    b.n_tokens = n;
    int rc;
    for (;;) {
        rc = llama_decode(ctx_, b);
        if (rc == 1 && evictOneFor(slot)) continue;   // out of cache: another NPC's slot goes to its file
        break;
    }
    llama_batch_free(b);
    if (rc == 0) { err = 0; s.toks.insert(s.toks.end(), toks, toks + n); return true; }
    // anything half-processed is dropped so the slot's cells and its token list agree
    llama_memory_seq_rm(llama_get_memory(ctx_), slot, (llama_pos)s.toks.size(), -1);
    err = rc == 1 ? 1 : (rc == 2 || cancelled_.load() == ticket) ? 2 : 3;
    return false;
}

GenResult Core::generate(uint64_t ticket, const std::string &key, const std::string &prompt, const GenParams &gp, const TokenSink &sink) {
    GenResult r;
    if (!loaded()) { r.error = "no model is loaded"; return r; }
    current_ = ticket;
    struct Done { Core *c; ~Done() { c->current_ = 0; c->publish(); } } done{this};
    const double tStart = nowMs();
    pinToCores(lp_.affinityMask);
    r.threads = lp_.threads;
    r.threadsBatch = lp_.threadsBatch;
    try {
        std::vector<int> t = tokenize(prompt);
        std::vector<int32_t> toks(t.begin(), t.end());
        if (toks.empty()) { r.error = "empty prompt"; return r; }
        r.promptTokens = (int)toks.size();
        int ctxN = (int)llama_n_ctx(ctx_);
        if (r.promptTokens + std::max(1, gp.maxTokens) > ctxN - 8) {
            r.error = "the prompt is too long for the context (" + std::to_string(r.promptTokens) + " tokens + " + std::to_string(gp.maxTokens) + " of " + std::to_string(ctxN) + ")";
            return r;
        }
        int slot = acquire(key, toks, r, gp);
        Slot &s = slots_[(size_t)slot];
        s.last = ++clock_;

        // what the slot holds that this prompt shares; one token is always left to decode so there are logits to sample
        size_t common = lcp(s.toks, toks);
        if (common >= toks.size()) common = toks.size() - 1;
        if (common < s.toks.size()) {
            if (!llama_memory_seq_rm(llama_get_memory(ctx_), slot, (llama_pos)common, -1)) { clearSlot(slot); s.used = true; s.key = key; common = 0; }
            s.toks.resize(common);
        }
        r.reusedTokens = (int)common - r.sharedTokens - r.restoredTokens;
        if (r.reusedTokens < 0) r.reusedTokens = 0;

        // prefill in chunks: cancelling and pacing happen between them
        double t0 = nowMs();
        size_t pos = common;
        const size_t chunk = (size_t)std::max(32, lp_.ubatch);
        while (pos < toks.size()) {
            if (cancelled_.load() == ticket) { r.finish = Finish::Cancelled; r.prefillMs = nowMs() - t0; return r; }
            size_t n = std::min(chunk, toks.size() - pos);
            int err = 0;
            bool last = pos + n == toks.size();
            if (!decodeChunk(slot, toks.data() + pos, (int)n, last, ticket, err)) {
                r.prefillMs = nowMs() - t0;
                if (err == 2) { r.finish = Finish::Cancelled; return r; }
                r.error = err == 1 ? "the context is full" : "the prompt could not be processed";
                return r;
            }
            pos += n;
            if (gp.chunkDelayMs > 0 && pos < toks.size()) std::this_thread::sleep_for(std::chrono::milliseconds(gp.chunkDelayMs));
        }
        r.prefillMs = nowMs() - t0;
        r.kvTokens = (int)s.toks.size();
        if (gp.maxTokens <= 0) {   // only read the prompt into the NPC's place (warming it up): nothing is sampled
            r.finish = Finish::Length;
            r.firstTokenMs = nowMs() - tStart;
            s.last = ++clock_;
            return r;
        }

        // the sampler
        llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (gp.repeatPenalty > 1.0f)
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), 64, gp.repeatPenalty, 0.0f, 0.0f));
        if (gp.temperature <= 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            if (gp.topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(gp.topK));
            if (gp.topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(gp.topP, 1));
            if (gp.minP > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(gp.minP, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(gp.temperature));
            uint32_t seed = gp.seed ? gp.seed : (uint32_t)(nowMs() * 1000.0);
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
        }
        for (size_t i = toks.size() > 64 ? toks.size() - 64 : 0; i < toks.size(); i++) llama_sampler_accept(smpl, toks[i]);

        std::string pending;       // decoded, not yet handed out (an unfinished character or a possible stop string)
        std::string out;
        double d0 = nowMs();
        bool stopped = false;
        auto emit = [&](const std::string &piece) -> bool {
            if (piece.empty()) return true;
            out += piece;
            return sink ? sink(piece) : true;
        };
        r.finish = Finish::Length;
        for (int n = 0; n < gp.maxTokens; n++) {
            if (cancelled_.load() == ticket) { r.finish = Finish::Cancelled; break; }
            llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
            if (r.generated == 0) r.firstTokenMs = nowMs() - tStart;
            if (llama_vocab_is_eog(vocab_, tok)) { r.finish = Finish::Eos; break; }
            char buf[256];
            int pl = llama_token_to_piece(vocab_, tok, buf, sizeof buf, 0, false);
            if (pl > 0) pending.append(buf, (size_t)pl);
            r.generated++;

            // stop strings
            size_t cut = std::string::npos;
            for (const auto &st : gp.stop) {
                if (st.empty()) continue;
                size_t f = pending.find(st);
                if (f != std::string::npos && f < cut) cut = f;
            }
            if (cut != std::string::npos) {
                if (!emit(pending.substr(0, cut))) { r.finish = Finish::Cancelled; } else r.finish = Finish::Stop;
                pending.clear();
                stopped = true;
                // the token is in the sequence's text up to here: decode it so the cache stays what the text says
                int err = 0;
                int32_t tk = tok;
                decodeChunk(slot, &tk, 1, false, ticket, err);
                break;
            }
            size_t ready = pending.size() - holdBack(pending, gp.stop);
            ready = std::min(ready, utf8Whole(pending));
            if (ready > 0) {
                std::string piece = pending.substr(0, ready);
                pending.erase(0, ready);
                if (!emit(piece)) { r.finish = Finish::Cancelled; int err = 0; int32_t tk = tok; decodeChunk(slot, &tk, 1, false, ticket, err); break; }
            }
            int err = 0;
            int32_t tk = tok;
            if (!decodeChunk(slot, &tk, 1, true, ticket, err)) {
                if (err == 2) r.finish = Finish::Cancelled;
                else { r.finish = Finish::Error; r.error = err == 1 ? "the context is full" : "decoding failed"; }
                break;
            }
        }
        if (!stopped && (r.finish == Finish::Eos || r.finish == Finish::Length) && !pending.empty()) {
            // what was held back for a stop string that never came, up to the last whole character
            pending.resize(utf8Whole(pending));
            emit(pending);
        }
        r.decodeMs = nowMs() - d0;
        r.kvTokens = (int)s.toks.size();
        r.text = out;
        llama_sampler_free(smpl);
        s.last = ++clock_;
    } catch (const std::bad_alloc &) {
        r.finish = Finish::Error;
        r.error = "out of memory";
    } catch (const std::exception &e) {
        r.finish = Finish::Error;
        r.error = e.what();
    }
    return r;
}

void Core::reset(const std::string &key) {
    if (!loaded()) return;
    for (int i = 0; i < (int)slots_.size(); i++)
        if (slots_[(size_t)i].used && slots_[(size_t)i].key == key) clearSlot(i);
    std::string p = statePath(key);
    if (!p.empty()) remove(p.c_str());
    publish();
}

void Core::resetAll() {
    if (!loaded()) return;
    for (int i = 0; i < (int)slots_.size(); i++) clearSlot(i);
    llama_memory_clear(llama_get_memory(ctx_), true);
    publish();
}

int Core::flush() {
    if (!loaded() || lp_.stateDir.empty()) return 0;
    int n = 0;
    for (int i = 0; i < (int)slots_.size(); i++) {
        Slot &s = slots_[(size_t)i];
        if (!s.used || s.toks.size() < 48) continue;
        std::string path = statePath(s.key);
        if (llama_state_seq_save_file(ctx_, path.c_str(), i, (const llama_token *)s.toks.data(), s.toks.size()) > 0) n++;
    }
    return n;
}

void Core::publish() {
    int n = 0, t = 0;
    for (const auto &s : slots_) if (s.used && !s.toks.empty()) { n++; t += (int)s.toks.size(); }
    liveSlots_.store(n);
    liveTokens_.store(t);
}

}  // namespace wcd
