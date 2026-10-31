#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Corn, the Brill home's dog: the game's pug (creature/pug/pug.m2, file 346291) as a Forsaken pug, in the
user's texture (warcraft/branding/corn-forsaken-pug.png: the pug's own texture made undead, its UVs; the
user: "Use this texture on the pug dog you get from the quest in brill. Name the dog Corn"). The model
is Cataclysm Classic's (wago.tools, build CATACLYSM_BUILD), made a model the 3.3.5 game draws as the
mastiff before it was (Wrath Remix's converter, mod-wrath-remix/tools/models.py, and the starting
areas' fixes), its skin a texture the display names (CreatureDisplayInfo's ForsakenPug_Corn).

    python3 tools/corn.py fetch DIR          fetches the model's files from wago.tools by their ids and
                                             writes the 3.3.5 model into DIR (Creature/ForsakenPug/...)
    python3 tools/corn.py convert SRC DIR    the same from files already fetched (named by their ids)

The model's files are the game's, so they are never in this repository: the server package fetches and
converts them (scripts/package-azerothcore.sh) into the module's data/files, where the world server
finds them (src/BrillHomePatch.cpp puts them in the game's patch-<locale>-V with the dog's
CreatureModelData and CreatureDisplayInfo rows: tools/build_sql.py). The texture is the user's.
"""
import os
import struct
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-wrath-remix", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-allied-races", "tools"))
import models  # noqa: E402
import blp  # noqa: E402
import importlib.util  # noqa: E402

ZONES = os.path.join(HERE, "..", "..", "mod-worgen-goblin", "tools", "zones")
sys.path.insert(0, ZONES)
_spec = importlib.util.spec_from_file_location("zones_package", os.path.join(ZONES, "package.py"))
zones_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(zones_package)

MODEL = 346291
CATACLYSM_BUILD = "4.4.2.60895"   # Cataclysm Classic's last build on wago.tools
TEXTURE = os.path.join(HERE, "..", "..", "..", "branding", "corn-forsaken-pug.png")
SKIN = "ForsakenPug_Corn"
models.MODEL = MODEL
models.NAME = "ForsakenPug"
models.FOLDER = "Creature/ForsakenPug"
models.MANIFEST = "corn.files"


def skin_first_texture(m2):
    """The model with its first texture (the pug's coat, hard-coded in the game's file) made the
    creature's skin, which the display names: the user's texture in its place."""
    out = bytearray()
    at = 0
    while at < len(m2):
        tag, size = m2[at:at + 4], struct.unpack_from("<I", m2, at + 4)[0]
        body = bytearray(m2[at + 8:at + 8 + size])
        if tag == b"MD21":
            count, offset = struct.unpack_from("<II", body, 80)
            if not count or struct.unpack_from("<I", body, offset)[0] != 0:
                raise ValueError("the pug's first texture isn't the hard-coded coat")
            struct.pack_into("<I", body, offset, models.TEXTURE_SKIN)
        if tag == b"TXID":
            struct.pack_into("<I", body, 0, 0)
        out += tag + struct.pack("<I", len(body)) + body
        at += 8 + size
    return bytes(out)


def convert(fetch_file, folder):
    m2 = skin_first_texture(fetch_file(MODEL))
    chunks = models.chunks_of(m2)
    sfid = struct.unpack("<%dI" % (len(chunks["SFID"]) // 4), chunks["SFID"])
    skin = fetch_file(sfid[0])
    models.SHOWN_GEOSETS = {struct.unpack_from("<H", skin, struct.unpack_from("<II", skin, 28)[1] + s * 48)[0]
                            for s in range(struct.unpack_from("<II", skin, 28)[0])}
    model, new_skin, textures, anims = models.convert_model(m2, skin)
    rgba = np.asarray(Image.open(TEXTURE).convert("RGBA"))
    files = {
        "Creature/ForsakenPug/ForsakenPug.m2": model,
        "Creature/ForsakenPug/ForsakenPug00.skin": new_skin,
        "Creature/ForsakenPug/%s.blp" % SKIN: blp.write_dxt(np.ascontiguousarray(rgba)),
    }
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
    # the list of its files, written last: the world server takes the dog into the game only when it's
    # there and every file it names is (src/BrillHomePatch.cpp)
    with open(os.path.join(folder, models.MANIFEST), "w") as out:
        out.write("".join(name + "\n" for name in sorted(files)))
    print("corn: %d files in %s (geosets %s)" % (len(files), folder, sorted(models.SHOWN_GEOSETS)))


def cataclysm(file_id):
    """A file as Cataclysm Classic's client has it (wago.tools, CATACLYSM_BUILD)."""
    for attempt in range(4):
        try:
            url = "https://wago.tools/api/casc/%d?download&version=%s" % (file_id, CATACLYSM_BUILD)
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": models.USER_AGENT}),
                                        timeout=60) as response:
                return response.read()
        except Exception as error:  # a network hiccup: once more, a little later
            print("corn: file %d: %s" % (file_id, error), file=sys.stderr)
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
