// The dialogue model's native core: ONE llama.cpp model and ONE context shared by every NPC. Each NPC owns a sequence of the
// context's KV cache (a "slot"), so a persona's prompt prefix is decoded once and each turn only decodes what is new;
// a slot evicted for room is saved to a file and restored when its NPC talks again; a new NPC starts from the longest
// prefix another slot already holds (the shared world preamble costs nothing). Plain C++17 with no JNI: dialogue_jni.cpp
// is the Kotlin door, engine-test/dialogue_test.cpp runs it on the desktop. Not thread safe: one thread calls generate()
// at a time (the Kotlin scheduler's worker); cancel() may be called from any thread.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace wcd {

struct LoadParams {
    std::string path;          // the .gguf
    int threads = 4;           // token generation
    int threadsBatch = 0;      // prompt processing; 0 = threads
    int ctx = 6144;            // tokens of KV cache, shared by all slots
    int slots = 6;             // NPCs that keep a live sequence at once
    int ubatch = 256;          // tokens per decode call (cancellation and pacing happen between calls)
    int kvBits = 8;            // 16 (f16) or 8 (q8_0, half the memory)
    bool mlock = false;
    int nice = 0;              // niceness added to the calling thread before the model loads (compute threads inherit it)
    std::string stateDir;      // where evicted/flushed slots are kept (empty: not kept); one folder per model
};

struct GenParams {
    int maxTokens = 160;
    float temperature = 0.8f;  // <= 0: greedy
    float topP = 0.92f;
    float minP = 0.05f;
    int topK = 40;
    float repeatPenalty = 1.08f;
    uint32_t seed = 0;         // 0: random
    int minShare = 24;         // a prefix another slot holds is borrowed only if at least this long
    int chunkDelayMs = 0;      // a pause after each prompt chunk (the Kotlin side raises it while the renderer struggles)
    std::vector<std::string> stop;
};

enum class Finish { Eos, Length, Stop, Cancelled, Error };

struct GenResult {
    Finish finish = Finish::Error;
    std::string text;          // everything emitted (valid UTF-8, stop string removed)
    std::string error;
    int promptTokens = 0;
    int reusedTokens = 0;      // already in this NPC's slot
    int sharedTokens = 0;      // borrowed from another NPC's slot
    int restoredTokens = 0;    // read back from the NPC's saved state
    int generated = 0;
    double prefillMs = 0;
    double decodeMs = 0;
};

struct ModelInfo {
    std::string desc;
    uint64_t sizeBytes = 0;
    uint64_t params = 0;
    int trainCtx = 0;
    int ctx = 0;
    int slots = 0;
};

using TokenSink = std::function<bool(const std::string &)>;   // false: stop now
using Logger = std::function<void(int level, const std::string &)>;

class Core {
public:
    Core() = default;
    ~Core();
    Core(const Core &) = delete;
    Core &operator=(const Core &) = delete;

    static void setLogger(Logger l);
    static uint64_t rssBytes();       // this process's resident set (anonymous + mapped file pages), 0 where unknown
    static uint64_t rssAnonBytes();   // the anonymous part (what Android's low-memory killer weighs; mapped model pages are not in it)

    bool load(const LoadParams &p, std::string &error);
    void unload();
    bool loaded() const { return model_ != nullptr && ctx_ != nullptr; }
    ModelInfo info() const;

    // Runs one generation for [key] on the calling thread. [ticket] names it for cancel().
    GenResult generate(uint64_t ticket, const std::string &key, const std::string &prompt, const GenParams &gp, const TokenSink &sink);
    // Asks the generation with this ticket to stop at the next token or prompt chunk (any thread).
    void cancel(uint64_t ticket) { cancelled_.store(ticket); }
    bool shouldAbort() const { return current_ != 0 && cancelled_.load() == current_; }

    // Forgets one NPC's slot and its saved state (a new persona, a new story).
    void reset(const std::string &key);
    void resetAll();
    // Saves every slot to the state folder (the app is leaving the foreground).
    int flush();
    // How many NPCs hold a live sequence, and the tokens in them.
    // (counted at the end of each call so another thread can ask while a generation runs)
    int liveSlots() const { return liveSlots_.load(); }
    int liveTokens() const { return liveTokens_.load(); }
    std::vector<int> tokenize(const std::string &text) const;

private:
    struct Slot {
        std::string key;
        std::vector<int32_t> toks;
        uint64_t last = 0;
        bool used = false;
    };

    int acquire(const std::string &key, const std::vector<int32_t> &prompt, GenResult &r, const GenParams &gp);
    void evict(int idx);
    bool decodeChunk(int slot, const int32_t *toks, int n, bool wantLogits, uint64_t ticket, int &err);
    bool evictOneFor(int keep);
    std::string statePath(const std::string &key) const;
    void clearSlot(int idx);
    void publish();

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    const llama_vocab *vocab_ = nullptr;
    LoadParams lp_;
    std::vector<Slot> slots_;
    uint64_t clock_ = 0;
    std::atomic<uint64_t> cancelled_{0};
    std::atomic<uint64_t> current_{0};
    std::atomic<int> liveSlots_{0};
    std::atomic<int> liveTokens_{0};
};

}  // namespace wcd
