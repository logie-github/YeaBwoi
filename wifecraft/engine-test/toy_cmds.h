// The desktop harness's toy commands (Scene::setToy and friends; README "Toy interaction"), kept out of harness.cpp:
//
//   toyload FILE.wcprop GUID                       Scene::loadProp
//   toy ACTOR GUID MODE FX FY W H DEPTH VIB ROLL [double]   Scene::setToy: MODE 1 held free at pixel FX FY of a W x H view, 2 snapped
//                                                  at DEPTH Sims metres past the entry; VIB 0..1; ROLL radians
//   toyoff                                         the toy put away
//   toyinfo                                        prints Scene::toyInfo and the opening's dynamics
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <vector>
#include <sstream>
#include <string>

#include "scene.h"

static bool toyCommand(wc::Scene& scene, const std::string& cmd, std::istringstream& ss) {
    using namespace wc;
    if (cmd == "toyload") {
        std::string file; unsigned long long guid = 0;
        ss >> file >> guid;
        std::ifstream f(file, std::ios::binary);
        std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        std::string err;
        if (!scene.loadProp(guid, bytes.data(), bytes.size(), err)) std::printf("toyload %s: %s\n", file.c_str(), err.c_str());
        return true;
    }
    if (cmd == "toy") {
        int actor = 0, mode = 0; unsigned long long guid = 0; ToyInput in; std::string flag;
        ss >> actor >> guid >> mode >> in.fx >> in.fy >> in.width >> in.height >> in.depth >> in.vibration >> in.roll >> flag;
        in.mode = mode; in.hidden = flag == "hide" || flag == "double-hide";
        scene.setToy(actor, guid, flag == "double" || flag == "double-hide", in);
        return true;
    }
    if (cmd == "toypose") {   // toypose ACTOR V0..V11 (toyrig::Pose's order) | toypose ACTOR off
        int actor = 0; std::string first; ss >> actor >> first;
        if (first == "off") { scene.clearToyPose(actor); return true; }
        float v[12] = {}; v[0] = std::strtof(first.c_str(), nullptr);
        for (int i = 1; i < 12; ++i) ss >> v[i];
        scene.setToyPose(actor, v, 12);
        return true;
    }
    if (cmd == "toyoff") { scene.clearToy(); return true; }
    if (cmd == "toyinfo") {
        ToyInfo i;
        const bool ok = scene.toyInfo(i);
        const orifice::Dyn& d = scene.toyDyn();
        std::printf("toyinfo: %s hd %d entry (%.1f, %.1f) axis (%.3f, %.3f) %.1f px/m tip (%.1f, %.1f) length %.3f m radius %.4f m depth %.4f m wet %.3f | dyn radius %.5f drag %.5f tremor %.3f speed %.4f\n",
                    ok ? "ready" : "not ready", i.hd ? 1 : 0, i.entryX, i.entryY, i.axisX, i.axisY, i.pxPerMetre, i.tipX, i.tipY, i.insertLength, i.radius, i.depth, i.wet,
                    d.radius, d.drag, d.tremor, d.speed);
        return true;
    }
    return false;
}
