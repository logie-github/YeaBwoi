// Cuts model parts on the desktop as the app does on the phone (engine/modelpart.h, Native.buildModelParts): the
// manifest's sets (assets/model_parts.json), each from its source model in FILES_DIR (the downloaded files, each named by
// its id), written to OUT_DIR/<set>/ (<region>_<n>.wcbp, set.json). Only the named SETs when given.
//   build/modelparts MANIFEST FILES_DIR OUT_DIR [SET...]   exit 0 when every set was written
#include <algorithm>
#include <cstdio>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "bytes.h"
#include "modelpart.h"

using namespace wc;

int main(int argc, char** argv) {
    if (argc < 4) { std::fprintf(stderr, "usage: modelparts MANIFEST FILES_DIR OUT_DIR [SET...]\n"); return 2; }
    Bytes raw;
    if (!readFile(argv[1], raw)) { std::fprintf(stderr, "no manifest %s\n", argv[1]); return 2; }
    ModelPartManifest man;
    std::string err;
    if (!parseModelPartManifest(std::string(raw.begin(), raw.end()), man, err)) { std::fprintf(stderr, "manifest: %s\n", err.c_str()); return 2; }
    std::string dir = argv[2];
    FileLookup files = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 && st.st_size > 0 ? p : std::string();
    };
    std::vector<std::string> only(argv + 4, argv + argc);
    int failed = 0;
    for (const ModelPartSet& s : man.sets) {
        if (!only.empty() && std::find(only.begin(), only.end(), s.set) == only.end()) continue;
        std::string report;
        bool ok = buildModelPartSet(s, man, files, argv[3], report);
        std::printf("== %s (%s, model %u on %u): %s\n%s", s.set.c_str(), s.character.c_str(), s.model, s.standIn, ok ? "ok" : "FAILED", report.c_str());
        if (!ok) ++failed;
    }
    return failed ? 1 : 0;
}
