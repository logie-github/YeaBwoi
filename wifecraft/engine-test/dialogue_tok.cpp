// dialogue_tok MODEL.gguf FILE... : how many tokens the model's own tokenizer makes of each file's text (the vocabulary only is loaded: no weights).
// Used for the README's prompt-size tables: the prompts come from unit-tests' PromptDump2Test (WC_DUMP_PROMPTS2=<dir> WC_COMPACT=0|1).
#include "llama.h"
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
int main(int argc, char **argv) {
    if (argc < 3) { printf("usage: dialogue_tok MODEL.gguf FILE...\n"); return 2; }
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.vocab_only = true;
    llama_model *m = llama_model_load_from_file(argv[1], mp);
    if (!m) { fprintf(stderr, "load failed\n"); return 1; }
    const llama_vocab *v = llama_model_get_vocab(m);
    for (int i = 2; i < argc; i++) {
        std::ifstream in(argv[i]);
        std::stringstream ss;
        ss << in.rdbuf();
        std::string t = ss.str();
        std::vector<llama_token> out(t.size() + 16);
        int n = llama_tokenize(v, t.data(), (int)t.size(), out.data(), (int)out.size(), false, true);
        printf("%d\t%s\n", n, argv[i]);
    }
    return 0;
}
