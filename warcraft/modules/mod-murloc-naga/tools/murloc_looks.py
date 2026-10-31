# SPDX-License-Identifier: GPL-3.0-or-later
"""The murlocs' looks: what the character creation screen offers and what each choice is made of,
read by tools/murloc_model.py (the model and textures, from the game's files as the server is
packaged) and tools/build_data.py (the tables). Data only.

The model is Blizzard's newer murloc (creature/murloc2/murloc2.m2, file 1113034: the murlocs of
Cataclysm and after), made a character's. Its kin built on the same skeleton (every bone of theirs
at one of its bones' places, a few added) lend their parts: the shark-hooded murloc's hood and
outfit, Maria's hair and dress (the murloc in a dress of the Darkmoon Faire), the demon hunter
murloc's horns, blindfold and loincloth, the deep sea murloc's lure. Each is a geoset of the model
the screen shows by its choice (CharHairGeosets: group 0; CharacterFacialHairStyles: group 1).

The choices:
  - Skin color: every skin the game gives that model (13; the deep sea's and the demon hunter's are
    made for their own models): drawn on the body as its "extra" texture (CharSections' skin row's
    second texture, the model's texture type 8), whole and as the game has it.
  - Face: the eyes of each of those skins (13 colours: their eyes are a mesh of their own, read
    from the character's composed texture's face region, FaceLower).
  - Hair style: none, the shark hood, the demon horns, the lure (and, for women, Maria's hair).
  - Hair color: for Maria's hair (its texture's colours), the others as they are.
  - Features: none, the blindfold, the shark outfit, the loincloth (and, for women, the dress).
"""

BASE_MODEL = 1113034                 # creature/murloc2/murloc2.m2

# Skin colours (file ids of the textures the game's displays of murloc2 and its re-skinned kin give it).
SKINS = [
    (1113060, "Grey"), (1113057, "Blue"), (1113058, "Green"), (1113061, "Pale"), (1113059, "Orange"),
    (1450941, "Gold"), (1589499, "Fel"), (2764576, "Red"), (3492077, "Purple"), (1115036, "Slate"),
    (3987167, "Abyssal"), (4693215, "Bronze"), (4693216, "Ice"),
]
# The eyes' place in those textures (u0, v0, u1, v1): where murloc2's eye mesh reads them.
EYES = (0.015, 0.355, 0.135, 0.475)

# The parts, from the models that lend them: [model] (file id), [batch] (its skin's batch, in order),
# [texture] (file id), and which of the batch's pieces (connected parts of its mesh) are the part:
#   "top"        pieces reaching above z (yards above the feet),
#   "below"      pieces not reaching above z,
#   "head"       triangles on the head's bones alone,
#   "body"       the rest,
#   "centre"     pieces whose centre is in the box (x0, x1, y0, y1, z0, z1),
#   "added"      triangles on the bones murloc2 hasn't (the lure's own) with their centre in the box.
PARTS = {
    "Hood":      {"model": 1502337, "batch": 1, "texture": 1502344, "take": ("top", 1.2)},
    "Outfit":    {"model": 1502337, "batch": 1, "texture": 1502344, "take": ("below", 1.2)},
    "Hair":      {"model": 1502336, "batch": 1, "texture": 1502339, "take": ("head",), "hair": True},
    "Dress":     {"model": 1502336, "batch": 1, "texture": 1502339, "take": ("body",)},
    "Horns":     {"model": 1129417, "batch": 0, "texture": 1131231, "take": ("centre", -1, 1, 0.15, 1, 1.0, 2)},
    "HornsL":    {"model": 1129417, "batch": 0, "texture": 1131231, "take": ("centre", -1, 1, -1, -0.15, 1.0, 2)},
    "Blindfold": {"model": 1129417, "batch": 0, "texture": 1131231, "take": ("centre", 0.2, 0.5, -0.05, 0.05, 0.75, 1.0)},
    "Loincloth": {"model": 1129417, "batch": 0, "texture": 1131231, "take": ("centre", -0.2, 0.2, -0.05, 0.05, 0.3, 0.62)},
    "Lure":      {"model": 360359, "batch": 0, "texture": 360360, "take": ("added", 0.15, 1.0, -0.3, 0.3, 1.05, 2)},
    "LureBulb":  {"model": 360359, "batch": 1, "texture": 360360, "take": ("top", 1.4)},
}

# The screen's choices, a sex's: each a list of parts (none: nothing). Hair styles are geosets 2, 3...
# of group 0 (style 0 shows none); features geosets 102, 103... of group 1.
HAIR_STYLES = {
    "Male":   [[], ["Hood"], ["Horns", "HornsL"], ["Lure", "LureBulb"]],
    "Female": [[], ["Hair"], ["Hood"], ["Horns", "HornsL"], ["Lure", "LureBulb"]],
}
FEATURES = {
    "Male":   [[], ["Blindfold"], ["Outfit"], ["Loincloth"]],
    "Female": [[], ["Blindfold"], ["Dress"], ["Outfit"], ["Loincloth"]],
}
# Maria's hair's colours (hue 0 to 1, or -1 for the texture's own; saturation and lightness multiplied).
HAIR_COLOURS = [
    ("Brown", -1, 1.0, 1.0), ("Black", -1, 0.2, 0.35), ("Auburn", 0.02, 1.4, 1.0), ("Blonde", 0.12, 1.1, 1.9),
    ("Silver", -1, 0.0, 1.9), ("Sea green", 0.45, 0.9, 1.3), ("Violet", 0.78, 0.9, 1.2),
]


# The parts' textures' names (Character\\Murloc\\Murloc<name>.blp), by file id.
TEXTURE_NAMES = {1502344: "Shark", 1502339: "Maria", 1131231: "Demon", 360360: "DeepSea"}


def hair_geoset(style):
    return 0 if style == 0 else style + 1


def feature_geoset(style):
    return 0 if style == 0 else style + 1          # (group 1: mesh 100 + this)
