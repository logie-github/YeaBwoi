#!/usr/bin/env python3
"""WoW: Forever's new quests that the 3.3.5 world can carry as they are: their givers, enders,
the creatures they send you after and the items those creatures drop are all in AzerothCore's
world already, and nothing in them needs a script.

    python3 tools/forever.py import QUESTIEDB_DIR WORLD_SQL_DIR PAGES_DIR
        Reads QuestieDB's Forever database (github.com/Questie/QuestieDB: data/Forever and its
        Forever corrections, read as data, no Lua run), picks the quests, fetches their pages from
        foreverchanges.pro into PAGES_DIR (a cache; Wowhead's Forever database as that site shows
        it: the quests' own words, objectives' counts, rewards, money, experience, reputation),
        and writes data/forever/quests.json (the quests, the items they use) and
        data/forever/report.json (every other new quest and why it isn't here).

    python3 tools/forever.py build DBC_DIR WORLD_SQL_DIR
        Writes data/sql/chromiecraft/world/forever_quests.sql from data/forever/quests.json and
        reward_items.json (the new reward items' stats, from Wowhead's tooltips) and experience.json
        (wow4ever.quest's experience, where the page has none).

DBC_DIR: the server's dbc folder (the data.zip in warcraft/server-data.env); WORLD_SQL_DIR: the
core's data/sql/base/db_world at the realm's commit (warcraft/server-version.env). Both outputs
are committed; run the tool again after changing it or its data.
"""
import json
import os
import re
import statistics
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import foreverchanges  # noqa: E402
import lualit  # noqa: E402
import sqlrows  # noqa: E402

MODULE = os.path.dirname(HERE)
DATA = os.path.join(MODULE, "data", "forever")
SQL_OUT = os.path.join(MODULE, "data", "sql", "chromiecraft", "world", "forever_quests.sql")

FIRST_FOREVER_ID = 86000        # Forever's own quests; below are Classic Era's and Season of Discovery's
# Quests that pass the checks below but can't be made from the data as it is.
LEFT_OUT = {
    92743: "needs a Conqueror's Crown that QuestieDB doesn't list, and Magram standing",
    93928: "the mouse's cookie isn't in the data (a scripted hand-in)",
    97292: "injects a serum in the Ruins of Lordaeron (a spell on an object in a new dungeon)",
    98071: "its objective (the hops from Dun Garok) isn't in the data",
    98221: "its objective (the attack plans to destroy) isn't in the data",
    95737: "leads to Excavation Site: Wetlands, a new dungeon",
    99108: "duels with other players",
}

# Forever's race bits (QuestieDB: requiredRaces) and the 3.3.5 races each opens here. Races the
# 3.3.5 realm adds go with the race whose start they take: the Skyborne of the High Order with the
# Humans (mod-skyborne), the Windshapers and the Zandalari with the Tauren; the Draenei, void elves
# and Lightforged with the Night Elves (the Alliance's other Kalimdor race), the blood elves and the
# nightborne with the Forsaken.
SKYBORNE_A, SKYBORNE_H = 1 << 12, 1 << 13                 # races 13, 14 (mod-skyborne)
VOIDELF, NIGHTBORNE, LIGHTFORGED, ZANDALARI = 1 << 14, 1 << 15, 1 << 16, 1 << 17   # mod-allied-races
RACES = {
    1: 1 | SKYBORNE_A,                                     # Human
    2: 2,                                                  # Orc
    4: 4,                                                  # Dwarf
    8: 8 | 1024 | VOIDELF | LIGHTFORGED,                   # Night Elf (Draenei, void elves, Lightforged)
    16: 16 | 512 | NIGHTBORNE,                             # Undead (blood elves, nightborne)
    32: 32 | SKYBORNE_H | ZANDALARI,                       # Tauren
    64: 64,                                                # Gnome
    128: 128,                                              # Troll
    1 << 32: SKYBORNE_A,                                   # Forever's Skyborne of the High Order
    1 << 33: SKYBORNE_H,                                   # and Windshapers
}
ALL_RACES = 0
for bits in RACES.values():
    ALL_RACES |= bits

# Wowhead's slots and types, as 3.3.5's item_template has them.
SLOTS = {"neck": 2, "chest": 5, "legs": 7, "feet": 8, "hands": 10, "finger": 11, "bag": 18}
ARMOR = {"cloth": 1, "leather": 2, "mail": 3}
MATERIAL = {"cloth": 7, "leather": 8, "mail": 5}
STATS = {"Agility": 3, "Strength": 4, "Intellect": 5, "Spirit": 6, "Stamina": 7}

QUEST_FLAGS_SHARABLE = 8
ITEM_BIND_PICKUP, ITEM_BIND_QUEST = 1, 4


def table(world, name):
    return sqlrows.table(os.path.join(world, name + ".sql"))


def dbc(path):
    """A DBC file's records as tuples of uint32 and its string block."""
    with open(path, "rb") as f:
        data = f.read()
    magic, count, fields, size, strsize = struct.unpack("<4s4I", data[:20])
    assert magic == b"WDBC" and size == fields * 4, path
    rows = [struct.unpack_from("<%dI" % fields, data, 20 + i * size) for i in range(count)]
    return rows, data[20 + count * size:]


def dbc_string(block, offset):
    return block[offset:block.index(b"\0", offset)].decode("utf-8", "replace")


# --- import -----------------------------------------------------------------------------------

def read_questiedb(qdb):
    """Forever's quests, NPCs, items and objects: QuestieDB's raw data with its generated delta-base,
    trace and authored Forever corrections on top, in that order (as its own loader applies them)."""
    def enum(path, name):
        text = open(path, encoding="utf-8").read()
        m = re.search(r"constants\.%s\s*=\s*\{" % name, text)
        return lualit.to_py(lualit.Parser(lualit.tokens(text[m.end() - 1:]), lambda n: ("sym", n)).table())

    zones = enum(os.path.join(qdb, "src/corrections/enum/zones.lua"), "zoneIDs")
    factions = enum(os.path.join(qdb, "src/corrections/enum/factions.lua"), "factionIDs")
    race = {"NONE": 0, "HUMAN": 1, "ORC": 2, "DWARF": 4, "NIGHT_ELF": 8, "UNDEAD": 16, "TAUREN": 32, "GNOME": 64, "TROLL": 128,
            "SKYBORNE_ALLIANCE": 1 << 32, "SKYBORNE_HORDE": 1 << 33}
    race["ALL_ALLIANCE"] = 1 + 4 + 8 + 64 + (1 << 32)
    race["ALL_HORDE"] = 2 + 16 + 32 + 128 + (1 << 33)
    klass = dict(NONE=0, WARRIOR=1, PALADIN=2, HUNTER=4, ROGUE=8, PRIEST=16, DEATH_KNIGHT=32, SHAMAN=64, MAGE=128,
                 WARLOCK=256, MONK=512, DRUID=1024, ALL_CLASSES=1503)

    def resolve(name):
        parts = name.split(".")
        group, last = (parts[-2] if len(parts) > 1 else parts[0]), parts[-1]
        if group in ("questKeys", "npcKeys", "itemKeys", "objectKeys"):
            return "@" + last
        if group in ("raceIDs", "raceKeys") and last in race:
            return race[last]
        if group in ("classIDs", "classKeys") and last in klass:
            return klass[last]
        if group == "zoneIDs" and isinstance(zones.get(last), int):
            return zones[last]
        if group == "factionIDs" and isinstance(factions.get(last), int):
            return factions[last]
        return ("sym", name)

    def keymap(text, kind):
        m = re.search(r"QuestieDB\.%sKeys\s*=\s*\{(.*?)\n\}" % kind, text, re.S)
        return {int(i): k for k, i in re.findall(r"^\s*\['(\w+)'\]\s*=\s*(\d+)", m.group(1), re.M)}

    def corrections(path):
        text = open(path, encoding="utf-8").read()
        return lualit.to_py(lualit.parse_return_table(text, resolve, after=":Load()"))

    def merge(cur, value, op):
        if isinstance(value, dict):
            value = [value.get(i) for i in range(1, max(value) + 1)]
        cur = list(cur or [])
        grouped = all(x is None or isinstance(x, list) for x in value) and any(isinstance(x, list) for x in value)
        if grouped and all(x is None or isinstance(x, list) for x in cur):
            cur += [None] * (len(value) - len(cur))
            for i, group in enumerate(value):
                if group is None:
                    continue
                have = list(cur[i] or [])
                have = have + [x for x in group if x not in have] if op == "add" else [x for x in have if x not in group]
                cur[i] = have
            return cur
        return cur + [x for x in value if x not in cur] if op == "add" else [x for x in cur if x not in value]

    db = {}
    for kind, name, fixes in (("quest", "Quest", "Quest"), ("npc", "Npc", "NPC"), ("item", "Item", "Item"), ("object", "Object", "Object")):
        path = os.path.join(qdb, "data/Forever/forever%sDB.lua" % name)
        text = open(path, encoding="utf-8").read()
        keys = keymap(text, kind)
        m = re.search(r"QuestieDB\.%sData\s*=\s*\[(=*)\[" % kind, text)
        body = text[m.end(): text.index("]%s]" % m.group(1), m.end())]
        raw = lualit.to_py(lualit.parse_return_table(body, resolve))
        rows = {}
        for eid, row in (raw.items() if isinstance(raw, dict) else enumerate(raw, 1)):
            if row is None:
                continue
            if isinstance(row, dict):
                row = [row.get(i) for i in range(1, max(row) + 1)] if row else []
            rows[eid] = {keys[i + 1]: v for i, v in enumerate(row) if v is not None and i + 1 in keys}
        for path in ("src/corrections/Forever/generated/foreverBase%s.lua" % name,
                     "src/corrections/Forever/traces/forever%sTraces.lua" % name,
                     "src/corrections/Forever/forever%sFixes.lua" % fixes):
            for eid, fix in corrections(os.path.join(qdb, path)).items():
                if not isinstance(fix, dict):
                    continue
                row = rows.setdefault(eid, {})
                for key, value in fix.items():
                    if not (isinstance(key, str) and key.startswith("@")):
                        continue
                    key = key[1:]
                    if key.endswith("_add") or key.endswith("_remove"):
                        field, op = key.rsplit("_", 1)
                        row[field] = merge(row.get(field), value, op)
                    else:
                        row[key] = value
        db[kind] = rows
    return db


def group(lst, i):
    return list(lst[i] or []) if lst and len(lst) > i and lst[i] else []


def first_ids(entries):
    return [e[0] if isinstance(e, list) else e for e in entries]


def import_(qdb, world, pages):
    db = read_questiedb(qdb)
    Q, I = db["quest"], db["item"]
    _, quests = table(world, "quest_template")
    core_quests = {int(q["ID"]) for q in quests}
    _, creatures = table(world, "creature")
    spawned = {int(c["id1"]) for c in creatures}
    _, items = table(world, "item_template")
    core_items = {int(i["entry"]) for i in items}
    _, objects = table(world, "gameobject_template")
    core_objects = {int(o["entry"]) for o in objects}

    def problems(qid):
        q = Q[qid]
        out = []
        name = q.get("name") or ""
        if re.search(r"UNUSED|zzOLD|^Legacy Reward", name):
            out.append("not a quest to play (%s)" % name)
        if q.get("requiredClasses"):
            out.append("a class quest (3.3.5's class training has its own)")
        start, end = q.get("startedBy") or [], q.get("finishedBy") or []
        starters, start_objects, start_items = group(start, 0), group(start, 1), group(start, 2)
        enders, end_objects = group(end, 0), group(end, 1)
        if not (starters or start_objects or start_items):
            out.append("nobody starts it in the data")
        if not (enders or end_objects):
            out.append("nobody ends it in the data")
        new_npcs = [n for n in starters + enders if n not in spawned]
        if new_npcs:
            out.append("new NPCs: %s" % new_npcs)
        if [o for o in start_objects + end_objects if o not in core_objects]:
            out.append("new objects")
        if start_items and not starters and not start_objects:
            sources = [d for i in start_items for d in (I.get(i, {}).get("npcDrops") or [])]
            if not sources or any(d not in spawned for d in sources):
                out.append("starts from an item no creature of the 3.3.5 world drops")
        objectives = q.get("objectives") or []
        kills = first_ids(group(objectives, 0))
        if [c for c in kills if c not in spawned]:
            out.append("creatures to kill that the 3.3.5 world hasn't")
        if group(objectives, 1):
            out.append("objects to use")
        for item in first_ids(group(objectives, 2)):
            if item in core_items or item == q.get("sourceItemId"):
                continue
            drops = I.get(item, {}).get("npcDrops") or []
            if not drops:
                out.append("an item to collect with no creature to drop it (%d)" % item)
            elif any(d not in spawned for d in drops):
                out.append("an item dropped by creatures the 3.3.5 world hasn't (%d)" % item)
        if len(objectives) > 3 and objectives[3]:
            out.append("a reputation objective")
        if len(objectives) > 4 and objectives[4]:
            out.append("kill credit (a script)")
        if len(objectives) > 5 and objectives[5]:
            out.append("a spell to cast (a script)")
        if q.get("triggerEnd"):
            out.append("a place to explore (a script)")
        if q.get("extraObjectives"):
            out.append("special objectives (a script)")
        if q.get("requiredSkill") or q.get("requiredRanks"):
            out.append("a profession requirement (Forever's Camping)")
        if qid in LEFT_OUT:
            out.append(LEFT_OUT[qid])
        return out

    new = sorted(q for q in Q if q >= FIRST_FOREVER_ID and q not in core_quests)
    why = {q: problems(q) for q in new}
    picked = {q for q in new if not why[q]}
    # A quest that needs another first goes only with it (or after one the core has).
    grew = True
    while grew:
        grew = False
        for q in sorted(picked):
            pre = (Q[q].get("preQuestSingle") or []) + (Q[q].get("preQuestGroup") or [])
            missing = [p for p in pre if p not in core_quests and p not in picked]
            if missing:
                picked.discard(q)
                why[q] = ["needs %s first, which isn't here" % missing]
                grew = True

    foreverchanges.fetch(sorted(picked), pages)
    for q in sorted(picked):
        page = foreverchanges.parse(os.path.join(pages, "%d.html" % q))
        if not page:
            picked.discard(q)
            why[q] = ["foreverchanges.pro has no page for it (its text)"]
        else:
            Q[q]["page"] = page
    keep = ("page", "name", "startedBy", "finishedBy", "requiredLevel", "questLevel", "requiredRaces", "objectivesText", "objectives",
            "sourceItemId", "preQuestSingle", "preQuestGroup", "exclusiveTo", "zoneOrSort", "breadcrumbForQuestId", "reputationReward")
    out_quests = {}
    used_items = set()
    for q in sorted(picked):
        row = {k: Q[q][k] for k in keep if k in Q[q]}
        out_quests[str(q)] = row
        used_items.update(first_ids(group(row.get("objectives"), 2)))
        used_items.update(group(row.get("startedBy"), 2))
        if row.get("sourceItemId"):
            used_items.add(row["sourceItemId"])
    out_items = {}
    for i in sorted(used_items):
        if i in core_items:
            continue
        row = {k: I.get(i, {}).get(k) for k in ("name", "npcDrops", "startQuest", "class", "subClass") if I.get(i, {}).get(k) is not None}
        out_items[str(i)] = row
    npcs = {}
    for q in out_quests.values():
        for n in group(q.get("startedBy"), 0) + group(q.get("finishedBy"), 0):
            npcs[str(n)] = {"zoneID": db["npc"].get(n, {}).get("zoneID")}
    for q in out_quests.values():
        for i, icon in q["page"].pop("icons").items():
            if i in out_items:
                out_items[i]["icon"] = icon
    head = ("QuestieDB's Forever data (github.com/Questie/QuestieDB, read by tools/forever.py import): the new quests "
            "the 3.3.5 world can carry as they are, the items they use and their NPCs' zones; each quest's page: its words, "
            "objectives, rewards, money, experience and reputation from foreverchanges.pro (Wowhead's Forever database). "
            "Quest text (c) Blizzard Entertainment.")
    with open(os.path.join(DATA, "quests.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"_about": head, "quests": out_quests, "items": out_items, "npcs": npcs}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    report = {"_about": "Forever's new quests (ids %d and up, not in the core) left out, and why." % FIRST_FOREVER_ID,
              "picked": len(picked), "new": len(new),
              "left_out": {str(q): {"name": Q[q].get("name"), "why": why[q]} for q in new if q not in picked}}
    with open(os.path.join(DATA, "report.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("%d of Forever's %d new quests picked" % (len(picked), len(new)))


# --- build ------------------------------------------------------------------------------------

def races(forever_mask):
    if not forever_mask:
        return 0
    out = 0
    for bit, mask in RACES.items():
        if forever_mask & bit:
            out |= mask
    return 0 if out == ALL_RACES else out


def build(dbc_dir, world):
    quests = json.load(open(os.path.join(DATA, "quests.json"), encoding="utf-8"))
    rewards = json.load(open(os.path.join(DATA, "reward_items.json"), encoding="utf-8"))
    experience = {int(k): v for k, v in json.load(open(os.path.join(DATA, "experience.json"), encoding="utf-8"))["xp"].items()}
    Q, ITEMS, NPCS = ({int(k): v for k, v in quests[x].items()} for x in ("quests", "items", "npcs"))
    WI = {int(k): v for k, v in rewards["items"].items()}

    qcols, core_quests = table(world, "quest_template")
    acols, _ = table(world, "quest_template_addon")
    icols, core_items = table(world, "item_template")
    _, creatures = table(world, "creature_template")
    _, loot = table(world, "creature_loot_template")
    lootid = {int(c["entry"]): int(c["lootid"]) for c in creatures}
    names = {int(c["entry"]): c["name"] for c in creatures}
    core_item_ids = {int(i["entry"]) for i in core_items}

    xp_rows, _ = dbc(os.path.join(dbc_dir, "QuestXP.dbc"))
    quest_xp = {r[0]: r[1:] for r in xp_rows}
    area_rows, _ = dbc(os.path.join(dbc_dir, "AreaTable.dbc"))
    areas = {r[0] for r in area_rows}
    faction_rows, block = dbc(os.path.join(dbc_dir, "Faction.dbc"))
    factions = {dbc_string(block, r[23]): r[0] for r in faction_rows}
    display_rows, block = dbc(os.path.join(dbc_dir, "ItemDisplayInfo.dbc"))
    by_icon = {}
    for r in display_rows:
        by_icon.setdefault(dbc_string(block, r[5]).lower(), []).append(r[0])

    # Money and experience where Forever's data has none for a quest: the middle of what the core's
    # quests of the same level and kind pay.
    money = {}
    for q in core_quests:
        if int(q["RewardMoney"]) > 0:
            money.setdefault((int(q["QuestLevel"]), int(q["RewardXPDifficulty"])), []).append(int(q["RewardMoney"]))

    def pay(level, difficulty):
        for d in range(0, 6):
            for key in ((level, difficulty), (level - d, difficulty), (level + d, difficulty)):
                if key in money:
                    return int(statistics.median(money[key]))
        return 0

    def difficulty(level, xp, has_objectives):
        row = quest_xp.get(max(1, level))
        if not xp or not row:
            return 5 if has_objectives else 2
        return min(range(1, 9), key=lambda d: (abs(row[d] - xp), d))

    def display(item_id, name, icon=None, inventory_type=None):
        if icon and icon.lower() in by_icon:
            ids = by_icon[icon.lower()]
            used = [int(i["displayid"]) for i in core_items if int(i["displayid"]) in ids and
                    (inventory_type is None or int(i["InventoryType"]) == inventory_type)]
            return min(used) if used else min(ids)
        # A quest item: the look of the core's quest item whose name is nearest (its last word first).
        words = re.findall(r"[a-z]+", name.lower())
        best, score = None, 0
        for i in core_items:
            if int(i["class"]) not in (12, 15) or not int(i["displayid"]):
                continue
            theirs = set(re.findall(r"[a-z]+", i["name"].lower()))
            s = sum(2 if w == words[-1] else 1 for w in words if w in theirs and len(w) > 2)
            if s > score or (s == score and best is not None and s and int(i["entry"]) < int(best["entry"])):
                best, score = i, s
        return int(best["displayid"]) if best else 7629

    out = ["-- WoW: Forever's new quests that the 3.3.5 world carries as they are (mod-forever-quests, tools/forever.py):\n"
           "-- generated, safe to run again. ChromieCraft's realm only (ctl.sh, realm_sql)."]
    ids = sorted(Q)
    id_list = ", ".join(map(str, ids))
    for t, key in (("quest_template", "ID"), ("quest_template_addon", "ID"), ("quest_request_items", "ID"), ("quest_offer_reward", "ID")):
        out.append("DELETE FROM `%s` WHERE `%s` IN (%s);" % (t, key, id_list))
    for t in ("creature_queststarter", "creature_questender", "gameobject_queststarter", "gameobject_questender"):
        out.append("DELETE FROM `%s` WHERE `quest` IN (%s);" % (t, id_list))

    def insert(t, cols, rows):
        if rows:
            out.append("INSERT INTO `%s` (%s) VALUES\n%s;" % (t, ", ".join("`%s`" % c for c in cols),
                       ",\n".join("(" + ", ".join(sqlrows.quote(r.get(c, 0)) for c in cols) + ")" for r in rows)))

    template, addons, requests, offers = [], [], [], []
    starters, enders, object_starters = [], [], []
    chain_next = {}
    for qid in ids:
        q = Q[qid]
        for p in q.get("preQuestSingle") or []:
            prev = Q.get(p)
            if prev and set(group(prev.get("finishedBy"), 0)) & set(group(q.get("startedBy"), 0)):
                chain_next[p] = qid
    for qid in ids:
        q = Q[qid]
        page = q["page"]
        level = page["level"] or q.get("questLevel") or 1
        min_level = page["min_level"] or q.get("requiredLevel") or 1
        objectives = q.get("objectives") or []
        kills = first_ids(group(objectives, 0))
        collect = first_ids(group(objectives, 2))
        counts = {i: c for i, c in page["need_items"]}
        goals = {name: c for name, c in page["need_goals"]}
        counts.update({c: goals[names[c]] for c in kills if names.get(c) in goals})
        # (a page with no experience has no rewards from Wowhead's data at all: its money is unknown too)
        d = difficulty(level, page["xp"] or experience.get(qid), bool(kills or collect))
        coin = page["money"] if page["xp"] else pay(level, d)
        zone = q.get("zoneOrSort") if isinstance(q.get("zoneOrSort"), int) else 0
        if zone == 0 or zone > 0 and zone not in areas:
            # one of Forever's new areas: the zone of the one who gives it
            zone = next((NPCS[n]["zoneID"] for n in group(q.get("startedBy"), 0) if NPCS.get(n, {}).get("zoneID") in areas), 0)
        enders_here = group(q.get("finishedBy"), 0)
        row = {c: 0 for c in qcols}
        row.update({
            "ID": qid, "QuestType": 2, "QuestLevel": level, "MinLevel": min_level, "QuestSortID": zone,
            "RewardXPDifficulty": d, "RewardMoney": coin, "AllowableRaces": races(q.get("requiredRaces")),
            "Flags": QUEST_FLAGS_SHARABLE if page["shareable"] else 0,
            "StartItem": q.get("sourceItemId") or 0, "RewardNextQuest": chain_next.get(qid, 0),
            "LogTitle": q["name"], "LogDescription": page["objectives_text"],
            "QuestDescription": page["description"], "AreaDescription": "", "QuestCompletionLog": "",
            "ObjectiveText1": "", "ObjectiveText2": "", "ObjectiveText3": "", "ObjectiveText4": "", "VerifiedBuild": 0,
        })
        for n, c in enumerate(kills[:4], 1):
            row["RequiredNpcOrGo%d" % n] = c
            row["RequiredNpcOrGoCount%d" % n] = counts.get(c) or 1
        for n, i in enumerate(collect[:6], 1):
            row["RequiredItemId%d" % n] = i
            row["RequiredItemCount%d" % n] = counts.get(i) or 1
        for n, (i, c) in enumerate(page["reward_items"], 1):
            row["RewardItem%d" % n], row["RewardAmount%d" % n] = i, c
        for n, (i, c) in enumerate(page["reward_choice"], 1):
            row["RewardChoiceItemID%d" % n], row["RewardChoiceItemQuantity%d" % n] = i, c
        for n, (faction, value) in enumerate([r for r in page["reputation"] if r[0] in factions], 1):
            # the amount itself (RewardFactionOverride is a hundredth of a point), not a QuestFactionReward step
            row["RewardFactionID%d" % n], row["RewardFactionOverride%d" % n] = factions[faction], value * 100
        template.append(row)
        addon = {c: 0 for c in acols}
        pre = (q.get("preQuestSingle") or []) + (q.get("preQuestGroup") or [])
        addon.update({"ID": qid, "PrevQuestID": pre[0] if pre else 0, "ProvidedItemCount": 1 if q.get("sourceItemId") else 0,
                      "ExclusiveGroup": 0, "BreadcrumbForQuestId": q.get("breadcrumbForQuestId") or 0})
        addons.append(addon)
        if page["progress"]:
            requests.append({"ID": qid, "EmoteOnComplete": 1, "EmoteOnIncomplete": 1, "CompletionText": page["progress"], "VerifiedBuild": 0})
        offers.append({"ID": qid, "Emote1": 1, "Emote2": 0, "Emote3": 0, "Emote4": 0, "EmoteDelay1": 0, "EmoteDelay2": 0,
                       "EmoteDelay3": 0, "EmoteDelay4": 0, "RewardText": page["completion"], "VerifiedBuild": 0})
        starters += [{"id": n, "quest": qid} for n in group(q.get("startedBy"), 0)]
        object_starters += [{"id": o, "quest": qid} for o in group(q.get("startedBy"), 1)]
        enders += [{"id": n, "quest": qid} for n in enders_here]

    out.append("\n-- The quests")
    insert("quest_template", qcols, template)
    insert("quest_template_addon", acols, addons)
    insert("quest_request_items", ["ID", "EmoteOnComplete", "EmoteOnIncomplete", "CompletionText", "VerifiedBuild"], requests)
    insert("quest_offer_reward", ["ID", "Emote1", "Emote2", "Emote3", "Emote4", "EmoteDelay1", "EmoteDelay2", "EmoteDelay3",
                                  "EmoteDelay4", "RewardText", "VerifiedBuild"], offers)
    out.append("\n-- Who gives and takes them")
    insert("creature_queststarter", ["id", "quest"], starters)
    insert("gameobject_queststarter", ["id", "quest"], object_starters)
    insert("creature_questender", ["id", "quest"], enders)

    # The items: the ones collected, carried and started from (Forever's quest items), and the rewards.
    items = []
    collected = {}
    for qid in ids:
        counts = dict(Q[qid]["page"]["need_items"])
        for i in first_ids(group(Q[qid].get("objectives"), 2)):
            collected[i] = max(collected.get(i, 1), counts.get(i) or 1)
    reward_ids = {i for q in Q.values() for i, _ in q["page"]["reward_items"] + q["page"]["reward_choice"]}
    missing = sorted(reward_ids - core_item_ids - set(WI))
    if missing:
        sys.exit("reward items without their stats in data/forever/reward_items.json: %s" % missing)
    for i in sorted(set(ITEMS) | (reward_ids - core_item_ids)):
        row = {c: 0 for c in icols}
        row.update({"entry": i, "SoundOverrideSubclass": -1, "Quality": 1, "BuyCount": 1, "stackable": 1, "description": "",
                    "ScriptName": "", "VerifiedBuild": 0, "RequiredDisenchantSkill": -1, "ArmorDamageModifier": 0,
                    "dmg_type1": 0, "spellid_1": 0, "spellcooldown_1": -1, "spellcategorycooldown_1": -1,
                    "spellcooldown_2": -1, "spellcategorycooldown_2": -1, "spellcooldown_3": -1, "spellcategorycooldown_3": -1,
                    "spellcooldown_4": -1, "spellcooldown_5": -1, "spellcategorycooldown_4": -1, "spellcategorycooldown_5": -1,
                    "Material": -1, "sheath": 0, "maxcount": 0})
        if i in WI:
            g = WI[i]
            slot = SLOTS[g["slot"]]
            row.update({"name": g["name"], "Quality": g["quality"], "ItemLevel": g["item_level"], "SellPrice": g["sell"],
                        "BuyPrice": g["sell"] * 4, "bonding": ITEM_BIND_PICKUP, "InventoryType": slot,
                        "displayid": display(i, g["name"], g.get("icon"), slot), "Material": MATERIAL.get(g.get("type"), 4),
                        "MaxDurability": g.get("durability") or 0, "armor": g.get("armor") or 0})
            if g["slot"] == "bag":
                row.update({"class": 1, "subclass": 0, "ContainerSlots": g["bag_slots"], "Material": 8})
            elif g.get("type"):
                row.update({"class": 4, "subclass": ARMOR[g["type"]]})
            else:
                row.update({"class": 4, "subclass": 0})
            for n, (stat, value) in enumerate(sorted((g.get("stats") or {}).items()), 1):
                row["stat_type%d" % n], row["stat_value%d" % n] = STATS[stat], value
        else:
            g = ITEMS[i]
            start = g.get("startQuest") or 0
            row.update({"name": g["name"], "class": 12, "subclass": 0, "displayid": display(i, g["name"], g.get("icon")),
                        "bonding": ITEM_BIND_QUEST if not start else ITEM_BIND_PICKUP, "startquest": start,
                        "maxcount": collected.get(i, 1), "stackable": collected.get(i, 1), "Material": -1})
        items.append(row)
    items_cols = [c for c in icols]
    out.append("\n-- Their items: Forever's ids, the looks of the 3.3.5 items nearest them")
    out.append("DELETE FROM `item_template` WHERE `entry` IN (%s);" % ", ".join(str(r["entry"]) for r in items))
    insert("item_template", items_cols, items)

    # Who drops them: Forever's own sources (QuestieDB's npcDrops); the chances aren't known, so a
    # quest item drops as often as the core's quest items commonly do.
    loot_rows, creature_updates = [], set()
    for i in sorted(ITEMS):
        g = ITEMS[i]
        drops = g.get("npcDrops") or []
        if not drops or i not in collected and not g.get("startQuest"):
            continue
        # an item that starts a quest drops for anyone who hasn't taken it (the core leaves it out after),
        # and seldom where many creatures carry it
        starts = bool(g.get("startQuest"))
        drops = [c for c in drops if c in lootid]
        for c in drops:
            entry = lootid.get(c) or c
            if not lootid.get(c):
                creature_updates.add(c)
            chance = (100 if len(drops) == 1 else 8) if starts else (60 if len(drops) <= 2 else 35)
            loot_rows.append({"Entry": entry, "Item": i, "Reference": 0, "Chance": chance, "QuestRequired": 0 if starts else 1,
                              "LootMode": 1, "GroupId": 0, "MinCount": 1, "MaxCount": 1,
                              "Comment": "%s - %s (mod-forever-quests)" % (names.get(c, c), g["name"])})
    out.append("\n-- Who drops them")
    out.append("DELETE FROM `creature_loot_template` WHERE `Item` IN (%s);" % ", ".join(str(i) for i in sorted(ITEMS)))
    for c in sorted(creature_updates):
        out.append("UPDATE `creature_template` SET `lootid` = `entry` WHERE `entry` = %d AND `lootid` = 0;" % c)
    insert("creature_loot_template", ["Entry", "Item", "Reference", "Chance", "QuestRequired", "LootMode", "GroupId", "MinCount",
                                      "MaxCount", "Comment"], loot_rows)
    os.makedirs(os.path.dirname(SQL_OUT), exist_ok=True)
    with open(SQL_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("%d quests, %d items, %d drops: %s" % (len(ids), len(items), len(loot_rows), os.path.relpath(SQL_OUT, MODULE)))


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "import":
        import_(sys.argv[2], sys.argv[3], sys.argv[4])
    elif len(sys.argv) == 4 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
