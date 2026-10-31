#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The mastiff (Cataclysm's Gilneas dog: creature/mastiff/mastiff.m2, file 320622) made a model the 3.3.5
game draws, with its four coats (gray, black, red, yellow). Its files are Cataclysm's: the Cataclysm
Classic client's (wago.tools, build CATACLYSM_BUILD; the user: "Completely backport it from cata using
wago tools"), not the current game's, whose model has had sequences added since.

    python3 tools/mastiff.py fetch DIR          fetches its files from wago.tools by their ids and writes
                                                the 3.3.5 model into DIR (Creature/Mastiff/...)
    python3 tools/mastiff.py convert SRC DIR    the same from files already fetched (named by their ids)

The files are the game's, so they are never in this repository: the server package fetches and
converts them (scripts/package-azerothcore.sh) into the module's data/files, where the world server
finds them (src/BrillHomePatch.cpp puts them in the game's patch-<locale>-V with their CreatureModelData and
CreatureDisplayInfo rows). The conversion is Wrath Remix's (mod-wrath-remix/tools/models.py, made for
Eternus's dragon, the same newer format), with the mastiff's name, folder and coats.
"""
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-wrath-remix", "tools"))
import models  # noqa: E402
import importlib.util  # noqa: E402

# The starting areas' fix for converted models (mod-worgen-goblin/tools/zones/package.py, fix_models):
# the model's own name, its whole animation lookup, a set of keys a sequence where the newer format
# keeps one for all, no texture transform the model hasn't.
ZONES = os.path.join(HERE, "..", "..", "mod-worgen-goblin", "tools", "zones")
sys.path.insert(0, ZONES)
_spec = importlib.util.spec_from_file_location("zones_package", os.path.join(ZONES, "package.py"))
zones_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(zones_package)

MODEL = 320622
CATACLYSM_BUILD = "4.4.2.60895"   # Cataclysm Classic's last build on wago.tools
COATS = {"Gray": 320626, "Black": 320625, "Red": 320627, "Yellow": 320628}
models.MODEL = MODEL
models.NAME = "Mastiff"
models.FOLDER = "Creature/Mastiff"
models.MANIFEST = "mastiff.files"
models.SKIN_TEXTURE = COATS["Gray"]


def convert(fetch_file, folder):
    """Writes the mastiff into [folder]: Wrath Remix's conversion, then each coat as a texture of
    its own (the displays' TextureVariation: MastiffDog_<coat>)."""
    m2 = fetch_file(MODEL)
    chunks = models.chunks_of(m2)
    sfid = models.struct.unpack("<%dI" % (len(chunks["SFID"]) // 4), chunks["SFID"])
    skin = fetch_file(sfid[0])
    # (every geoset the skin has: a dog has no gear or hair to choose between)
    models.SHOWN_GEOSETS = {models.struct.unpack_from("<H", skin, models.struct.unpack_from("<II", skin, 28)[1] + s * 48)[0]
                            for s in range(models.struct.unpack_from("<II", skin, 28)[0])}
    model, new_skin, textures, anims = models.convert_model(m2, skin)
    files = {
        "Creature/Mastiff/Mastiff.m2": model,
        "Creature/Mastiff/Mastiff00.skin": new_skin,
    }
    for coat, file_id in COATS.items():
        files["Creature/Mastiff/MastiffDog_%s.blp" % coat] = fetch_file(file_id)
    for file_id, name in textures.items():
        files[name.replace("\\", "/")] = fetch_file(file_id)
    for file_id, name in anims.items():
        files[name] = models.anim_payload(fetch_file(file_id))
    for name, data in files.items():
        if name.endswith(".blp") and data[:4] != b"BLP2":
            raise ValueError("%s isn't a BLP2 texture" % name)
    for name, data in files.items():
        path = os.path.join(folder, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(data)
    zones_package.fix_models(folder)
    # the list of its files, written last: the world server takes the mastiff into the game only when
    # it's there and every file it names is (src/BrillHomePatch.cpp)
    with open(os.path.join(folder, models.MANIFEST), "w") as out:
        out.write("".join(name + "\n" for name in sorted(files)))
    print("mastiff: %d files in %s (geosets %s)" % (len(files), folder, sorted(models.SHOWN_GEOSETS)))


def cataclysm(file_id):
    """A file as Cataclysm Classic's client has it (wago.tools, CATACLYSM_BUILD)."""
    for attempt in range(4):
        try:
            url = "https://wago.tools/api/casc/%d?download&version=%s" % (file_id, CATACLYSM_BUILD)
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": models.USER_AGENT}),
                                        timeout=60) as response:
                return response.read()
        except Exception as error:  # a network hiccup: once more, a little later
            print("mastiff: file %d: %s" % (file_id, error), file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("file %d couldn't be fetched" % file_id)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fetch":
        convert(cataclysm, sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "convert":
        convert(models.local(sys.argv[2]), sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
