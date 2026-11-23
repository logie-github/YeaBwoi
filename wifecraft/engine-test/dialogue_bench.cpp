// dialogue_bench MODEL THREADS PREFIX.txt FULL.txt [samples=3] [maxTokens=40] [temp=0.9]
// Cold and warm timings of one prompt, and the lines written. "cold": the NPC's place is empty, the whole prompt is read. "warm": the stable
// prefix (PREFIX.txt: the system prompt and the examples) was read ahead (what the warm-up does), so only the last message is read.
// The prompts come from unit-tests' PromptDump2Test (WC_DUMP_PROMPTS2=<dir> WC_COMPACT=0|1): joi-<level>-<stage>.prefix.txt / .txt, chat-prefix.txt / chat-first.txt.
// The answer ends at a newline, at <end_of_turn> or at a closing quote (the Gemma template opens the answer with a quote). Desktop speed is not phone speed.
#include "dialogue_core.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
using namespace wcd;
static const char *finishName(const GenResult &r) { switch (r.finish) { case Finish::Eos: return "eos"; case Finish::Length: return "length"; case Finish::Stop: return "stop"; case Finish::Cancelled: return "cancelled"; default: return "error"; } }
static std::string slurp(const char *p) { std::ifstream f(p); std::stringstream s; s << f.rdbuf(); return s.str(); }
int main(int argc, char **argv) {
    if (argc < 5) return 2;
    Core c; LoadParams lp; lp.path = argv[1]; lp.threads = atoi(argv[2]); lp.threadsBatch = atoi(argv[2]); lp.ctx = 4096; lp.slots = 2;
    std::string err; if (!c.load(lp, err)) { fprintf(stderr, "load: %s\n", err.c_str()); return 1; }
    std::string prefix = slurp(argv[3]), full = slurp(argv[4]);
    int n = argc > 5 ? atoi(argv[5]) : 3; int maxTok = argc > 6 ? atoi(argv[6]) : 40; float temp = argc > 7 ? atof(argv[7]) : 0.9f;
    GenParams gp; gp.maxTokens = maxTok; gp.temperature = temp; gp.topP = 0.93f; gp.repeatPenalty = 1.04f; gp.stop = {"\n", "<|im_end|>", "<end_of_turn>", "\""};
    uint64_t t = 0;
    auto run = [&](const char *label, bool warm) {
        c.reset("k");
        double warmMs = 0; int warmTok = 0;
        if (warm) { GenParams wp; wp.maxTokens = 0; auto a = std::chrono::steady_clock::now(); GenResult w = c.generate(++t, "k", prefix, wp, [](const std::string &) { return true; });
            warmMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count(); warmTok = w.promptTokens - w.reusedTokens; }
        double first = -1; auto a = std::chrono::steady_clock::now();
        GenResult r = c.generate(++t, "k", full, gp, [&](const std::string &) { if (first < 0) first = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count(); return true; });
        double tot = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count();
        printf("[%s] warmup %d tok %.0f ms | prompt %d read %d reused %d prefill %.0f ms (%.0f tok/s) | first piece %.0f ms | decode %d tok %.0f ms (%.1f tok/s) | total %.0f ms | %s\n   > %s\n",
            label, warmTok, warmMs, r.promptTokens, r.promptTokens - r.reusedTokens - r.sharedTokens - r.restoredTokens, r.reusedTokens, r.prefillMs,
            r.prefillMs > 0 ? (r.promptTokens - r.reusedTokens) * 1000.0 / r.prefillMs : 0.0, first, r.generated, r.decodeMs, r.decodeMs > 0 ? r.generated * 1000.0 / r.decodeMs : 0.0, tot, finishName(r), r.text.c_str());
    };
    for (int i = 0; i < n; i++) run("cold", false);
    for (int i = 0; i < n; i++) run("warm", true);
    return 0;
}
