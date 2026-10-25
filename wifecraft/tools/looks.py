#!/usr/bin/env python3
"""Works out how the demo's people look, from the game's own tables (wago.tools' CSV exports).

A creature with a model of its own (Alleria) is its model and the textures its model names. A
character-race look (Nyrelle, on a Kaldorei Sentinel's) is the race's model with:
  - the geosets its customization choices and its equipment show,
  - its baked skin (the NPC's HD bake), and the textures choices bind directly (hair, eyes...),
  - extra models some choices add (skinned models), and items hung on attachments (shoulders).

    python3 tools/looks.py TABLES_DIR > looks.json

TABLES_DIR holds the CSVs (`https://wago.tools/db2/<Table>/csv`): Creature, CreatureDisplayInfo,
CreatureDisplayInfoExtra, CreatureModelData, CreatureDisplayInfoOption, ChrModel,
ChrCustomizationChoice, ChrCustomizationElement, ChrCustomizationGeoset,
ChrCustomizationMaterial, ChrCustomizationSkinnedModel, ChrModelTextureLayer, ChrModelMaterial,
TextureFileData, NPCModelItemSlotDisplayInfo, ItemDisplayInfo, ItemDisplayInfoMaterialRes,
ModelFileData. The output lists file ids only; the files themselves are fetched on the phone.
"""
import csv
import json
import os
import sys

# The demo's people: who they are in the game's tables.
PEOPLE = {
    # Alleria Windrunner as Legion shows her (creature/alleria/alleria.m2).
    "alleria": {"display": 76272},
    # Nyrelle Moonquill: a Kaldorei Sentinel's look (night elf, female, HD), with the Sentinel's
    # gear but no helm (her face and braids show).
    "nyrelle": {"display": 89902, "skipSlots": [1]},
    # Her nightsaber (creature/nightsaber/nightsaber.m2).
    "saber": {"display": 62588},
    # The player's two looks: Kirin Tor Invokers of Legion's Dalaran, a man and a woman.
    "player_man": {"display": 70422},
    "player_woman": {"display": 70987},
}

# NPC item slots (NPCModelItemSlotDisplayInfo.ItemSlot) to equipment slots, and each slot's
# geoset groups: (index into ItemDisplayInfo.GeosetGroup, character geoset group).
NPC_SLOT = {0: 1, 1: 3, 2: 4, 3: 5, 4: 6, 5: 7, 6: 8, 7: 9, 8: 10, 9: 19, 10: 15}
SLOT_GEOSETS = {
    1: [(0, 27), (1, 21)],
    3: [(0, 26)],
    4: [(0, 8), (1, 10)],
    5: [(0, 8), (1, 10), (2, 13), (3, 22), (4, 28)],
    6: [(0, 18)],
    7: [(0, 11), (1, 9), (2, 13)],
    8: [(0, 5), (1, 20)],
    10: [(0, 4), (1, 23)],
    15: [(0, 15)],
    19: [(0, 12)],
}
# When several slots set one group, the first slot listed wins.
PRIORITY = {8: [10, 5, 4], 10: [5, 4], 13: [5, 7]}
# Attachment points for slots whose items are models of their own: the head's, and the left (6)
# and right (5) shoulders'. +y is the model's left.
ATTACH = {1: [11], 3: [6, 5]}
SKIN_TYPES = (1, 8)              # textures composited into the body (covered by the bake)


def table(d, name, key="ID"):
    with open(os.path.join(d, name + ".csv"), newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if key is None:
        return rows
    return {int(r[key]): r for r in rows}


def num(r, k):
    v = r.get(k, "0") or "0"
    try:
        return int(v)
    except ValueError:
        return int(float(v))


def main():
    d = sys.argv[1]
    cdi = table(d, "CreatureDisplayInfo")
    extra = table(d, "CreatureDisplayInfoExtra")
    cmd = table(d, "CreatureModelData")
    tfd_by_mat = {}
    for r in table(d, "TextureFileData", None):
        tfd_by_mat.setdefault(num(r, "MaterialResourcesID"), []).append((num(r, "UsageType"), num(r, "FileDataID")))
    mfd_by_res = {}   # resource -> [(centre across, file)]: a pair of shoulders is one resource
    for r in table(d, "ModelFileData", None):
        across = (float(r["GeoBox_1"] or 0) + float(r["GeoBox_4"] or 0)) / 2
        mfd_by_res.setdefault(num(r, "ModelResourcesID"), []).append((across, num(r, "FileDataID")))
    options_by_extra = {}
    for r in table(d, "CreatureDisplayInfoOption", None):
        options_by_extra.setdefault(num(r, "CreatureDisplayInfoExtraID"), []).append(num(r, "ChrCustomizationChoiceID"))
    elements = {}
    for r in table(d, "ChrCustomizationElement", None):
        elements.setdefault(num(r, "ChrCustomizationChoiceID"), []).append(r)
    choices = table(d, "ChrCustomizationChoice")
    geo = table(d, "ChrCustomizationGeoset")
    mats = table(d, "ChrCustomizationMaterial")
    skinned = table(d, "ChrCustomizationSkinnedModel")
    layers = table(d, "ChrModelTextureLayer", None)
    chr_models = table(d, "ChrModel", None)
    slots_by_npc = {}
    for r in table(d, "NPCModelItemSlotDisplayInfo", None):
        slots_by_npc.setdefault(num(r, "NpcModelID"), {})[num(r, "ItemSlot")] = num(r, "ItemDisplayInfoID")
    idi = table(d, "ItemDisplayInfo")
    idi_mats = {}
    for r in table(d, "ItemDisplayInfoMaterialRes", None):
        idi_mats.setdefault(num(r, "ItemDisplayInfoID"), []).append(r)

    def first_texture(mat_res):
        found = sorted(tfd_by_mat.get(mat_res, []))
        return found[0][1] if found else 0

    out = {}
    for name, who in PEOPLE.items():
        disp = cdi[who["display"]]
        model_id = num(cmd[num(disp, "ModelID")], "FileDataID")
        look = {
            "display": who["display"],
            "model": model_id,
            "scale": float(disp["CreatureModelScale"] or 1),
            "textures": {},
            "geosets": [],
            "attachments": [],
            "extraModels": [],
        }
        for i, slot in enumerate((11, 12, 13)):
            t = num(disp, "TextureVariationFileDataID_%d" % i)
            if t:
                look["textures"][str(slot)] = t
        ext_id = num(disp, "ExtendedDisplayInfoID")
        if ext_id:
            ex = extra[ext_id]
            # The race model's texture layout, for which texture each customization fills.
            layout = 0
            for m in chr_models:
                if num(m, "DisplayID") and num(cdi.get(num(m, "DisplayID"), {}), "ModelID") == num(disp, "ModelID"):
                    layout = num(m, "CharComponentTextureLayoutID")
            bake = num(ex, "HDBakeMaterialResourcesID") or num(ex, "BakeMaterialResourcesID")
            if bake:
                look["textures"]["1"] = first_texture(bake)
            chosen = options_by_extra.get(ext_id, [])
            look["choices"] = {choices[c]["Name_lang"] or str(c): c for c in chosen if c in choices}
            groups = {}   # geoset group -> value (None: the default 01)
            for c in chosen:
                for el in elements.get(c, []):
                    rel = num(el, "RelatedChrCustomizationChoiceID")
                    if rel and rel not in chosen:
                        continue
                    g = num(el, "ChrCustomizationGeosetID")
                    if g and g in geo:
                        groups[num(geo[g], "GeosetType")] = num(geo[g], "GeosetID")
                    s = num(el, "ChrCustomizationSkinnedModelID")
                    if s and s in skinned and num(skinned[s], "GeosetID"):
                        sk = skinned[s]
                        look["extraModels"].append({"model": num(sk, "CollectionsFileDataID"),
                                                    "geoset": num(sk, "GeosetType") * 100 + num(sk, "GeosetID")})
                    m = num(el, "ChrCustomizationMaterialID")
                    if m and m in mats:
                        target = num(mats[m], "ChrModelTextureTargetID")
                        for ly in layers:
                            if num(ly, "CharComponentTextureLayoutsID") != layout:
                                continue
                            if target not in (num(ly, "ChrModelTextureTargetID_0"), num(ly, "ChrModelTextureTargetID_1")):
                                continue
                            tt = num(ly, "TextureType")
                            if tt in SKIN_TYPES:
                                continue
                            fid = first_texture(num(mats[m], "MaterialResourcesID"))
                            if fid:
                                look["textures"][str(tt)] = fid
            # Equipment.
            per_group = {}
            for npc_slot, display in sorted(slots_by_npc.get(ext_id, {}).items()):
                slot = NPC_SLOT.get(npc_slot)
                if slot in who.get("skipSlots", []):
                    continue
                if slot is None or not display or display not in idi:
                    continue
                item = idi[display]
                for gi, cg in SLOT_GEOSETS.get(slot, []):
                    v = num(item, "GeosetGroup_%d" % gi)
                    value = (2 if v == 0 else v) if cg == 20 else 1 + v
                    per_group.setdefault(cg, []).append((slot, value))
                for k, att in enumerate(ATTACH.get(slot, [])):
                    res = num(item, "ModelResourcesID_%d" % k)
                    mres = num(item, "ModelMaterialResourcesID_%d" % k)
                    models = sorted(mfd_by_res.get(res, []))
                    if models:
                        model = models[0][1] if att == 5 else models[-1][1]
                        look["attachments"].append({"slot": slot, "attachment": att, "model": model,
                                                    "texture": first_texture(mres)})
                if slot == 15:
                    mres = num(item, "ModelMaterialResourcesID_0")
                    if first_texture(mres):
                        look["textures"]["2"] = first_texture(mres)
            for cg, vals in per_group.items():
                order = PRIORITY.get(cg)
                pick = vals[0]
                if order:
                    for s in order:
                        hit = [v for v in vals if v[0] == s]
                        if hit:
                            pick = hit[0]
                            break
                groups[cg] = pick[1]
            look["geosetGroups"] = {str(k): v for k, v in sorted(groups.items())}
        out[name] = look
    json.dump(out, sys.stdout, indent=1, sort_keys=True)
    print()


if __name__ == "__main__":
    main()
