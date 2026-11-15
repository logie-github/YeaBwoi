#!/usr/bin/env python3
"""What Wifecraft's character creator offers, read from the game's own tables on the desktop: the reference the app's
creator (android/.../creator/Catalog.kt, LookResolver.kt) is checked against. Every playable race and allied race with
a ChrModel, both sexes: the categories, the options in the game's order with their choice counts, and the choices the
game's own tables give nothing to draw (no ChrCustomizationElement row, nothing hanging on them, not the option's first or
a "None": the Worgen's Mane keeps the model's own hair, as in the game). Whether every choice draws with the choices it
hangs on is the JVM test's work (RealTablesTest picks each one in turn and resolves the look).

    python3 tools/creator_probe.py fetch DIR [BUILD]   the tables into DIR (wago.tools' CSVs; the latest build if none)
    python3 tools/creator_probe.py catalog DIR         per race and sex: categories, options, choices

The JVM test that picks every choice of every option in turn (unit-tests, RealTablesTest) reads the same folder:

    WIFECRAFT_TABLES=DIR gradle -p unit-tests test

Nothing fetched goes into the repository.
"""
import collections
import csv
import os
import sys
import urllib.request

TABLES = ("ChrRaces", "ChrRaceXChrModel", "ChrModel", "ChrCustomizationCategory", "ChrCustomizationOption",
          "ChrCustomizationChoice", "ChrCustomizationElement", "ChrCustomizationGeoset", "ChrCustomizationMaterial",
          "ChrCustomizationSkinnedModel", "ChrCustomizationReq", "ChrCustomizationBoneSet", "ChrCustomizationCondModel",
          "ChrModelTextureLayer", "ChrModelMaterial", "CreatureDisplayInfo", "CreatureModelData", "TextureFileData")
USER_AGENT = "Mozilla/5.0 (compatible; table-fetch)"
ALLIED_FLAG, NPC_ONLY_FLAG, TRANSMOG_REQ = 0x80000, 0x1, 4
DRAW_KINDS = ("ChrCustomizationGeosetID", "ChrCustomizationSkinnedModelID", "ChrCustomizationMaterialID",
              "ChrCustomizationBoneSetID", "ChrCustomizationCondModelID")


def fetch(out, build=None):
    os.makedirs(out, exist_ok=True)
    for t in TABLES:
        url = "https://wago.tools/db2/%s/csv%s" % (t, "?build=" + build if build else "")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=300) as r:
            data = r.read()
        with open(os.path.join(out, t + ".csv"), "wb") as f:
            f.write(data)
        print("%s: %d bytes" % (t, len(data)))


def rows(d, name):
    path = os.path.join(d, name + ".csv")
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def num(r, k):
    v = (r.get(k) or "0").strip()
    try:
        return int(v)
    except ValueError:
        return int(float(v))


def races(d):
    """The races as Catalog.kt lists them: (race row, {sex: ChrModel id}), in the creator's order."""
    chr_models = {num(m, "ID"): m for m in rows(d, "ChrModel")}
    by_race = collections.defaultdict(dict)
    for x in rows(d, "ChrRaceXChrModel"):
        m = chr_models.get(num(x, "ChrModelID"))
        if m is not None and num(m, "Sex") in (0, 1):
            by_race[num(x, "ChrRacesID")].setdefault(num(m, "Sex"), num(m, "ID"))
    picks = []
    for r in rows(d, "ChrRaces"):
        if num(r, "PlayableRaceBit") < 0 or num(r, "Flags") & NPC_ONLY_FLAG:
            continue
        models = by_race.get(num(r, "ID")) or by_race.get(num(r, "UnalteredVisualRaceID"))
        if models:
            picks.append((r, models))
    one = {}
    for r, models in picks:
        key = tuple(sorted(models.values()))
        rank = (0 if num(r, "Alliance") == 0 else 1, num(r, "ID"))
        if key not in one or rank < one[key][0]:
            one[key] = (rank, r, models)
    out = [(r, models) for _, r, models in one.values()]
    out.sort(key=lambda p: (1 if num(p[0], "Flags") & ALLIED_FLAG else 0, 0 if num(p[0], "Alliance") == 0 else 1,
                            num(p[0], "UiDisplayOrder"), num(p[0], "ID")))
    return out


def catalog(d):
    cats = {num(c, "ID"): c for c in rows(d, "ChrCustomizationCategory")}
    reqs = {num(r, "ID"): num(r, "ReqType") for r in rows(d, "ChrCustomizationReq")}
    options = collections.defaultdict(list)
    for o in rows(d, "ChrCustomizationOption"):
        options[num(o, "ChrModelID")].append(o)
    choices = collections.defaultdict(list)
    for c in rows(d, "ChrCustomizationChoice"):
        choices[num(c, "ChrCustomizationOptionID")].append(c)
    model_of_choice = {}
    for m, os_ in options.items():
        for o in os_:
            for c in choices[num(o, "ID")]:
                model_of_choice[num(c, "ID")] = m
    elements = collections.defaultdict(list)
    related_to = collections.defaultdict(set)
    for e in rows(d, "ChrCustomizationElement"):
        ch = num(e, "ChrCustomizationChoiceID")
        if ch not in model_of_choice or not any(num(e, k) for k in DRAW_KINDS):
            continue
        rel = num(e, "RelatedChrCustomizationChoiceID")
        if rel and model_of_choice.get(rel) != model_of_choice[ch]:
            rel = 0                                     # hangs on another model's choice: the choice's own
        elements[ch].append(rel)
        if rel:
            related_to[rel].add(ch)

    def draws_somehow(cid):
        """Draws with some choice of what it hangs on (or with something hanging on it)."""
        rels = elements.get(cid, [])
        return (0 in rels) or bool(rels) or bool(related_to.get(cid))

    total = {"models": 0, "options": 0, "choices": 0, "nothing": 0}
    for race, models in races(d):
        for sex, chr_model in sorted(models.items()):
            shown, used = [], {}
            for o in sorted(options.get(chr_model, []), key=lambda o: (num(o, "OrderIndex"), num(o, "ID"))):
                cat = cats.get(num(o, "ChrCustomizationCategoryID"))
                if cat is None or num(cat, "SpellShapeshiftFormID"):
                    continue
                cs = [c for c in choices[num(o, "ID")] if reqs.get(num(c, "ChrCustomizationReqID")) != TRANSMOG_REQ]
                if not (o.get("Name_lang") or "").strip() or len(cs) < 2:
                    continue
                cs.sort(key=lambda c: (num(c, "UiOrderIndex"), num(c, "OrderIndex"), num(c, "ID")))
                first = min(cs, key=lambda c: (num(c, "OrderIndex"), num(c, "ID")))
                nothing = [c for c in cs if c is not first and (c.get("Name_lang") or "") != "None"
                           and not draws_somehow(num(c, "ID"))]
                shown.append((o, cat, cs, nothing))
                used[num(cat, "ID")] = cat
            cat_list = sorted(used.values(), key=lambda c: (num(c, "OrderIndex"), num(c, "ID")))
            print("%s %s (race %d, ChrModel %d): %s" % (race["Name_lang"], "female" if sex else "male", num(race, "ID"), chr_model,
                                                       ", ".join(c["CategoryName_lang"].split(" (")[0] for c in cat_list)))
            for o, cat, cs, nothing in shown:
                print("    %-24s %-12s %3d choices%s" % (o["Name_lang"], cat["CategoryName_lang"].split(" (")[0], len(cs),
                                                         ("  empty in the game's tables: %s" % ", ".join(c["ID"] for c in nothing)) if nothing else ""))
                total["choices"] += len(cs)
                total["nothing"] += len(nothing)
            total["models"] += 1
            total["options"] += len(shown)
    print("%(models)d models, %(options)d options, %(choices)d choices, %(nothing)d empty in the game's own tables" % total)
    return total


def main(argv):
    if len(argv) in (3, 4) and argv[1] == "fetch":
        fetch(argv[2], argv[3] if len(argv) == 4 else None)
        return 0
    if len(argv) == 3 and argv[1] == "catalog":
        catalog(argv[2])
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
