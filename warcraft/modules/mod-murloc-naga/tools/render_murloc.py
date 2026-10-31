#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The murlocs' character models (tools/murloc_model.py's output) drawn as the game would draw them at
character creation: each hair style, feature, skin colour and eye colour, in a contact sheet, with
mod-allied-races' renderer (tools/render_m2.py: its reading of the files, not the game).

    python3 tools/render_murloc.py FILES OUT.png

FILES: tools/murloc_model.py's output folder. Needs numpy and Pillow.
"""
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-allied-races", "tools"))
import murloc_looks as L  # noqa: E402
import render_m2 as R  # noqa: E402
import blp  # noqa: E402

COMPOSED = 512


def texture(files, name):
    return np.asarray(blp.read(open(os.path.join(files, *name.split("/")), "rb").read()))


def composed(files, eyes):
    """The composed texture: the base, the eyes' FaceLower in its region (0, 384)-(256, 512)."""
    out = np.array(Image.fromarray(texture(files, "Character/Murloc/MurlocBase.blp")).convert("RGBA").resize((COMPOSED, COMPOSED)))
    face = np.asarray(Image.fromarray(texture(files, "Character/Murloc/MurlocEyes%02d.blp" % eyes)).convert("RGBA").resize((256, 128)))
    out[384:512, 0:256] = face
    return out


def draw(files, sex, skin, eyes, hair_style, feature, hair_colour=0, size=(220, 300), yaw=0.0):
    path = os.path.join(files, "Character", "Murloc", sex, "Murloc%s.m2" % sex)
    model = R.Model(path)
    skin_file = R.Skin(os.path.join(os.path.dirname(path), "Murloc%s00.skin" % sex))
    # the skin "extra" (type 8) as the game draws it: the skin colour's texture, as it is
    model.textures = [(0, "Character\\Murloc\\MurlocSkin%02d.blp" % skin) if kind == 8 else (kind, name) for kind, name in model.textures]
    seq = next((i for i, s in enumerate(model.seqs) if s[0] == 0), 0)
    world = model.bone_matrices(seq, 400)
    shown = {0, L.hair_geoset(hair_style), 100 + L.feature_geoset(feature)}
    cam = model.camera(1, seq, 400) or model.camera(0, seq, 400)
    if yaw:
        import math
        fov, near, far, eye, target = cam
        e = np.array(eye) - np.array(target)
        c, s = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        e = np.array([e[0] * c - e[1] * s, e[0] * s + e[1] * c, e[2]])
        cam = (fov, near, far, tuple(np.array(target) + e), target)
    light = np.array([1.0, 0.6, 1.2])
    light /= np.linalg.norm(light)
    hair = texture(files, "Character/Murloc/MurlocHair%02d.blp" % hair_colour)
    image, drawn = R.render(model, skin_file, composed(files, eyes), hair, files, shown, world, cam, size, light)
    return image


def main():
    files, out = sys.argv[1], sys.argv[2]
    rows = []
    for sex in ("Male", "Female"):
        tiles = [draw(files, sex, 0, 0, h, 0) for h in range(len(L.HAIR_STYLES[sex]))]
        tiles += [draw(files, sex, 1, 0, 0, f) for f in range(1, len(L.FEATURES[sex]))]
        rows.append(np.concatenate(tiles, axis=1))
    rows.append(np.concatenate([draw(files, "Male", s, s, 0, 0, size=(150, 200)) for s in range(len(L.SKINS))], axis=1))
    rows.append(np.concatenate([draw(files, "Female", 2, 2, 1, 2, c, size=(150, 200)) for c in range(len(L.HAIR_COLOURS))]
                               + [draw(files, "Female", 2, 2, 1, 2, 0, size=(150, 200), yaw=y) for y in (90, 180)], axis=1))
    width = max(r.shape[1] for r in rows)
    sheet = np.concatenate([np.pad(r, ((0, 0), (0, width - r.shape[1]), (0, 0))) for r in rows], axis=0)
    Image.fromarray(sheet).save(out)
    print(out, sheet.shape)


if __name__ == "__main__":
    main()
