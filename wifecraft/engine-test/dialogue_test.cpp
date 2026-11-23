// The dialogue core on the desktop (engine/../android/app/src/main/cpp/dialogue/dialogue_core): loads a real GGUF, generates for
// several NPCs through their own KV sequences and checks what the app relies on. Usage: dialogue_test MODEL.gguf [THREADS].
// Exit 0 when every check passes; prints the measurements (tokens per second, resident memory).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

#include "dialogue_core.h"

using namespace wcd;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond); printf(__VA_ARGS__); printf("\n"); } else { printf("  ok   %s\n", #cond); } } while (0)

static std::string chatml(const std::string &sys, const std::vector<std::pair<std::string, std::string>> &turns) {
    std::string p = "<|im_start|>system\n" + sys + "<|im_end|>\n";
    for (auto &t : turns) p += "<|im_start|>" + t.first + "\n" + t.second + "<|im_end|>\n";
    return p + "<|im_start|>assistant\n";
}

static const std::string WORLD = "You are a character living in the world of Azeroth, in Dalaran, after the Cataclysm. You know Warcraft lore well and never "
    "say you are an AI. Stay in character and answer in one or two short sentences. Dalaran is the violet citadel of the Kirin Tor, "
    "above Crystalsong Forest in Northrend; the Violet Hold holds its prisoners; the Silver Enclave and the Sunreaver's Sanctuary house the "
    "Alliance and the Horde; Krasus's Landing is where the dragons rest; the Legerdemain Lounge serves the best drinks in the city. ";

static GenResult run(Core &c, uint64_t &ticket, const std::string &npc, const std::string &prompt, int maxTok, float temp = 0.0f, const std::vector<std::string> &stop = {}, std::string *streamed = nullptr) {
    GenParams gp;
    gp.maxTokens = maxTok;
    gp.temperature = temp;
    gp.stop = stop;
    gp.seed = 7;
    return c.generate(++ticket, npc, prompt, gp, [&](const std::string &s) { if (streamed) *streamed += s; return true; });
}

static void show(const char *label, const GenResult &r) {
    printf("  [%s] finish=%d prompt=%d reused=%d shared=%d restored=%d gen=%d prefill=%.0fms decode=%.0fms (%.1f tok/s)\n    > %s\n", label, (int)r.finish, r.promptTokens,
           r.reusedTokens, r.sharedTokens, r.restoredTokens, r.generated, r.prefillMs, r.decodeMs,
           r.decodeMs > 0 ? r.generated * 1000.0 / r.decodeMs : 0.0, r.text.c_str());
}

int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: dialogue_test MODEL.gguf [THREADS]\n"); return 2; }
    int threads = argc > 2 ? atoi(argv[2]) : 4;
    std::string stateDir = "/tmp/wcd-state-test";
    mkdir(stateDir.c_str(), 0755);
    for (const char *f : {"x"}) (void)f;
    (void)!system(("rm -f " + stateDir + "/*.seq").c_str());

    Core::setLogger([](int level, const std::string &t) { if (level >= 4) fputs(t.c_str(), stderr); });
    uint64_t ticket = 0;

    // dialogue_test MODEL THREADS --chat "<|im_start|>..." : one raw prompt, streamed (the template is the caller's), for trying a model by hand
    if (argc > 4 && std::string(argv[3]) == "--chat") {
        Core one;
        LoadParams p1; p1.path = argv[1]; p1.threads = threads; p1.ctx = 4096; p1.slots = 1;
        std::string e1;
        if (!one.load(p1, e1)) { printf("load failed: %s\n", e1.c_str()); return 1; }
        GenParams g1; g1.maxTokens = 220; g1.temperature = 0.8f;
        GenResult r1 = one.generate(1, "chat", argv[4], g1, [](const std::string &s) { fputs(s.c_str(), stdout); fflush(stdout); return true; });
        printf("\n[finish=%d gen=%d %.1f tok/s]\n", (int)r1.finish, r1.generated, r1.decodeMs > 0 ? r1.generated * 1000.0 / r1.decodeMs : 0.0);
        return 0;
    }

    printf("load\n");
    Core c;
    LoadParams lp;
    lp.path = argv[1];
    lp.threads = threads;
    lp.ctx = 4096;
    lp.slots = 2;            // two slots: a third NPC forces an eviction
    lp.stateDir = stateDir;
    std::string err;
    uint64_t rss0 = Core::rssBytes();
    auto t0 = std::chrono::steady_clock::now();
    CHECK(c.load(lp, err), "%s", err.c_str());
    double loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    uint64_t rssLoaded = Core::rssBytes(), anonLoaded = Core::rssAnonBytes();
    ModelInfo mi = c.info();
    printf("  %s, %.0f MB file, %.2f B params, train ctx %d, ctx %d, slots %d, load %.0f ms\n", mi.desc.c_str(), mi.sizeBytes / 1e6, mi.params / 1e9, mi.trainCtx, mi.ctx, mi.slots, loadMs);

    printf("one NPC, two turns (the second must reuse the first's tokens)\n");
    const std::string sysA = WORLD + "You are Thalia, a night elf huntress, dry and teasing.";
    GenResult a1 = run(c, ticket, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}}), 48);
    show("A1", a1);
    CHECK(a1.finish == Finish::Eos || a1.finish == Finish::Length, "finish %d", (int)a1.finish);
    CHECK(!a1.text.empty(), "empty text");
    CHECK(a1.reusedTokens == 0 && a1.sharedTokens == 0, "first turn has nothing to reuse");
    GenResult a2 = run(c, ticket, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}, {"assistant", a1.text}, {"user", "And where can I get a drink?"}}), 48);
    show("A2", a2);
    CHECK(a2.reusedTokens >= a1.promptTokens - 2, "reused %d of %d", a2.reusedTokens, a1.promptTokens);
    CHECK(a2.prefillMs < a1.prefillMs, "prefill %.0f vs %.0f", a2.prefillMs, a1.prefillMs);

    printf("a second NPC with the same world preamble borrows it\n");
    const std::string sysB = WORLD + "You are Korg, an orc blademaster, gruff and blunt.";
    GenResult b1 = run(c, ticket, "korg", chatml(sysB, {{"user", "Who guards the Violet Hold?"}}), 48);
    show("B1", b1);
    CHECK(b1.sharedTokens >= 40, "shared %d", b1.sharedTokens);

    uint64_t rssWarm = Core::rssBytes(), anonWarm = Core::rssAnonBytes();
    printf("sequences do not leak into each other: Thalia's greedy answer after Korg's turn equals a fresh run's\n");
    GenResult a3 = run(c, ticket, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}, {"assistant", a1.text}, {"user", "And where can I get a drink?"}}), 48);
    CHECK(a3.text == a2.text, "A3 '%s' vs A2 '%s'", a3.text.c_str(), a2.text.c_str());
    {
        Core fresh;
        LoadParams fp = lp; fp.stateDir = ""; fp.slots = 1;
        std::string e2;
        CHECK(fresh.load(fp, e2), "%s", e2.c_str());
        uint64_t t2 = 0;
        GenResult f = run(fresh, t2, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}, {"assistant", a1.text}, {"user", "And where can I get a drink?"}}), 48);
        CHECK(f.text == a2.text, "fresh '%s' vs cached '%s'", f.text.c_str(), a2.text.c_str());
    }

    printf("a third NPC evicts the oldest slot to a file; coming back restores it\n");
    const std::string sysC = WORLD + "You are Brann, a dwarf explorer, boastful.";
    GenResult c1 = run(c, ticket, "brann", chatml(sysC, {{"user", "What is the Legerdemain Lounge?"}}), 32);
    show("C1", c1);
    CHECK(c.liveSlots() == 2, "live %d", c.liveSlots());
    GenResult b2 = run(c, ticket, "korg", chatml(sysB, {{"user", "Who guards the Violet Hold?"}, {"assistant", b1.text}, {"user", "Fine. And the Sunreavers?"}}), 32);
    show("B2", b2);
    CHECK(b2.restoredTokens > 0 || b2.sharedTokens > 0 || b2.reusedTokens > 0, "B2 started cold");

    printf("stop string and streaming\n");
    std::string streamed;
    GenResult s1 = run(c, ticket, "thalia", chatml(sysB, {{"user", "Who guards the Violet Hold?"}}), 60, 0.0f, {"Violet"}, &streamed);
    show("S1", s1);
    CHECK(s1.finish == Finish::Stop, "finish %d", (int)s1.finish);
    CHECK(s1.text.find("Violet") == std::string::npos, "stop string leaked");
    CHECK(streamed == s1.text, "streamed text differs");

    printf("cancel from another thread\n");
    {
        uint64_t tk = ++ticket;
        std::thread killer([&] { std::this_thread::sleep_for(std::chrono::milliseconds(1200)); c.cancel(tk); });
        GenParams gp; gp.maxTokens = 400; gp.temperature = 0.7f;
        int pieces = 0;
        auto t1 = std::chrono::steady_clock::now();
        GenResult r = c.generate(tk, "thalia", chatml(sysA, {{"user", "Tell me the whole history of the Kirin Tor, in great detail."}}), gp, [&](const std::string &) { pieces++; return true; });
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
        killer.join();
        show("cancel", r);
        CHECK(r.finish == Finish::Cancelled, "finish %d", (int)r.finish);
        CHECK(ms < 4000, "took %.0f ms", ms);
        GenResult after = run(c, ticket, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}, {"assistant", a1.text}, {"user", "And where can I get a drink?"}}), 48);
        CHECK(after.text == a2.text, "after cancel the slot answers '%s' not '%s'", after.text.c_str(), a2.text.c_str());
    }
    printf("a sink that returns false ends the turn\n");
    {
        GenParams gp; gp.maxTokens = 200; gp.temperature = 0.7f;
        int n = 0;
        GenResult r = c.generate(++ticket, "brann", chatml(sysC, {{"user", "Tell me a long tale."}}), gp, [&](const std::string &) { return ++n < 4; });
        CHECK(r.finish == Finish::Cancelled && n == 4, "finish %d n %d", (int)r.finish, n);
    }
    printf("too long a prompt is refused\n");
    {
        std::string big;
        for (int i = 0; i < 1500; i++) big += "the violet citadel hangs over the forest. ";
        GenResult r = run(c, ticket, "thalia", chatml(big, {{"user", "hi"}}), 32);
        CHECK(r.finish == Finish::Error && !r.error.empty(), "finish %d", (int)r.finish);
    }
    printf("reset and flush\n");
    CHECK(c.flush() >= 1, "flush");
    c.reset("thalia");
    GenResult cold = run(c, ticket, "thalia", chatml(sysA, {{"user", "Who guards the Violet Hold?"}}), 8);
    CHECK(cold.restoredTokens == 0, "restored %d after reset", cold.restoredTokens);

    printf("warm-up: a prompt read with maxTokens 0 leaves the NPC's place warm; the first real request pays for the new words only\n");
    {
        const std::string sysW = WORLD + "You are Wren, a gnome tinker, quick and curious.";
        GenResult w = run(c, ticket, "wren", chatml(sysW, {}), 0);
        show("warm", w);
        CHECK(w.finish == Finish::Length && w.generated == 0 && w.text.empty(), "nothing sampled: finish %d gen %d", (int)w.finish, w.generated);
        CHECK(w.kvTokens > 0 && w.kvTokens == w.promptTokens, "the place holds the prompt: kv %d prompt %d", w.kvTokens, w.promptTokens);
        GenResult w2 = run(c, ticket, "wren", chatml(sysW, {{"user", "Mend this clock?"}}), 24);
        show("after warm", w2);
        CHECK(w2.reusedTokens >= w.promptTokens - 4, "reused %d of the warmed %d", w2.reusedTokens, w.promptTokens);
        CHECK(w2.promptTokens - w2.reusedTokens - w2.sharedTokens - w2.restoredTokens < 40, "only the new words were read");
        CHECK(w2.firstTokenMs > 0 && w2.firstTokenMs >= w2.prefillMs, "first token %.0f ms after prefill %.0f ms", w2.firstTokenMs, w2.prefillMs);
        CHECK(w2.kvTokens >= w2.promptTokens, "kv %d", w2.kvTokens);
        CHECK(w2.threads == threads && w2.threadsBatch >= 1, "threads %d/%d", w2.threads, w2.threadsBatch);
    }
    printf("a restored state file is kept (a crash before the next flush loses nothing)\n");
    {
        CHECK(c.flush() >= 1, "flush");
        c.reset("nobody");
        // evict wren by filling the slots, then read it back
        const char *names[] = {"n1", "n2", "n3", "n4"};
        for (const char *n : names) run(c, ticket, n, chatml(WORLD + std::string("You are ") + n + ".", {{"user", "Hello"}}), 4);
        GenResult back = run(c, ticket, "wren", chatml(WORLD + "You are Wren, a gnome tinker, quick and curious.", {{"user", "Mend this clock?"}}), 8);
        show("wren back", back);
        CHECK(back.restoredTokens > 0 || back.reusedTokens > 0, "wren came back cold");
        GenResult again = c.generate(++ticket, "other-key-forces-evict", chatml(WORLD, {{"user", "hi"}}), GenParams(), nullptr);
        (void)again;
    }
    printf("thread counts can change between generations\n");
    {
        c.setThreads(2, 3);
        GenResult t2 = run(c, ticket, "bench", chatml(WORLD, {{"user", "Say hello."}}), 8);
        CHECK(t2.threads == 2 && t2.threadsBatch == 3, "threads %d/%d", t2.threads, t2.threadsBatch);
        c.setThreads(threads, 0);
    }
    printf("system info names the kernels in use\n");
    CHECK(!c.info().system.empty() && c.info().loadMs > 0 && c.info().contextMs > 0, "system '%s' load %.0f ctx %.0f", c.info().system.c_str(), c.info().loadMs, c.info().contextMs);
    printf("  %s\n", c.info().system.c_str());

    printf("throughput (greedy, 96 tokens, warm slot)\n");
    GenResult t = run(c, ticket, "bench", chatml(WORLD, {{"user", "Describe Dalaran at night, at length."}}), 96, 0.7f);
    show("bench", t);
    double tps = t.decodeMs > 0 ? t.generated * 1000.0 / t.decodeMs : 0;
    printf("MEASURED threads=%d decode=%.1f tok/s prefill=%.0f tok/s rss: before load %.0f MB, loaded %.0f MB, after 2 NPCs warm %.0f MB; anonymous: loaded %.0f MB, warm %.0f MB (model file %.0f MB, ctx %d q8 KV) load=%.0f ms\n", threads, tps,
           t.promptTokens * 1000.0 / (t.prefillMs > 0 ? t.prefillMs : 1), rss0 / 1e6, rssLoaded / 1e6, rssWarm / 1e6, anonLoaded / 1e6, anonWarm / 1e6, mi.sizeBytes / 1e6, mi.ctx, loadMs);

    c.unload();
    printf(failures ? "FAILED: %d\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
