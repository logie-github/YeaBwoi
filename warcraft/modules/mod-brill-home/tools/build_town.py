#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Makes Brill: A Place Among the Forsaken's world SQL (data/sql/chromiecraft/world/brill_town_world.sql)
from its questlines (tools/town/*.py, each a LINE: see tools/town/README.md for what a line holds).

Each questline is optional and stands alone; this turns their data into the game's rows (creatures,
objects, quests, words) and into the module's own tables, which src/BrillTown.cpp reads as the world
server starts:
- brill_town_phase: which of the town's phases a player is in while in Brill (a bit, and the conditions
  for it: what a questline changed in the town for them);
- brill_town_gossip: what a creature says to a player (its greetings by condition) and the options the
  module adds to its menu (a quest's credit, a choice, a reply, its wares, the house's furniture);
- brill_town_use: what an object does when a player uses it (a quest's credit, words).

Run it after changing a questline; the output is committed.
"""
import importlib.util
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "sql", "chromiecraft", "world", "brill_town_world.sql")
TOWN = os.path.join(HERE, "town")

# The questlines, in order, and the ids each has (a hundred of each kind from its base: quests, creature
# entries, objects, words, and the spawns' guids).
LINES = ["house", "grave", "apprentice", "scarlet", "customer", "deathguard", "well", "gallows"]
BASE = {key: 970200 + 100 * i for i, key in enumerate(LINES)}

# The mod-brill-home ids a line may name (tools/build_sql.py).
KNOWN = {
    "home.house": 970120, "home.welcome": 970121, "home.bed": 970122, "home.dog": 970123,
    "carolai.tonic": 970124, "carolai.locket": 970125, "carolai.gift": 970126, "carolai.tea": 970127,
    "carolai.lily": 970128,
}
# The house's own flags (brill_home.flags: src/BrillHome.cpp) a condition may ask about.
FLAGS = {"DOG": 0x2, "LOVERS": 0x10, "FRIENDS": 0x20, "CAROLAI_HOME": 0x50}
# The phases the town's lines share out (the house's are 0x100 to 0x20000).
FREE_PHASES = [1 << b for b in range(18, 32)]

# The house (tools/build_sql.py): a line may place things in it by the house's own coordinates.
HOUSE_X, HOUSE_Y, HOUSE_O, FLOOR = 2289.0, 352.0, 3.3360, 31.12 + 4.1
# Brill, where the town's phases apply (src/BrillTown.cpp): a spawn in a phase must be in it.
BRILL_X, BRILL_Y, BRILL_RADIUS = 2275.0, 330.0, 140.0

MAP_ZONE = {0: 85}


def q(text):
    return "'" + str(text).replace("\\", "\\\\").replace("'", "\\'") + "'"


def die(line, message):
    sys.exit(f"tools/town/{line}.py: {message}")


def house_point(lx, ly, deg=0.0):
    c, s = math.cos(HOUSE_O), math.sin(HOUSE_O)
    return (HOUSE_X + lx * c - ly * s, HOUSE_Y + lx * s + ly * c, (HOUSE_O + math.radians(deg)) % (2 * math.pi))


def load(key):
    spec = importlib.util.spec_from_file_location(f"town_{key}", os.path.join(TOWN, f"{key}.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.LINE


class Ids:
    def __init__(self, key, base):
        self.key, self.base = key, base
        self.next = {}
        self.ids = {}

    def take(self, kind, name):
        if (kind, name) in self.ids:
            die(self.key, f"two {kind}s called {name}")
        n = self.next.get(kind, 0)
        if n >= 100:
            die(self.key, f"more than 100 {kind}s")
        self.next[kind] = n + 1
        self.ids[(kind, name)] = self.base + n
        return self.base + n


def main():
    lines = [(key, load(key)) for key in LINES if os.path.exists(os.path.join(TOWN, f"{key}.py"))]
    quests, npcs, objects, texts, phases = {}, {}, {}, {}, {}
    ids = {key: Ids(key, BASE[key]) for key, _ in lines}
    free = list(FREE_PHASES)

    # First every line's names, so a line may name another's.
    for key, line in lines:
        i = ids[key]
        for quest in line.get("quests", []):
            quests[f"{key}.{quest['id']}"] = i.take("quest", quest["id"])
        for marker in line.get("markers", []):
            quests[f"{key}.{marker}"] = i.take("quest", marker)
        for npc in line.get("npcs", []):
            npcs[f"{key}.{npc['id']}"] = i.take("npc", npc["id"])
        for credit in line.get("credits", []):
            npcs[f"{key}.{credit['id']}"] = i.take("npc", credit["id"])
        for obj in line.get("objects", []):
            objects[f"{key}.{obj['id']}"] = i.take("object", obj["id"])
        for name in line.get("phases", {}):
            if not free:
                die(key, "the town has no more phases to give (fourteen in all)")
            phases[f"{key}.{name}"] = free.pop(0)

    def ref(key, table, name, what):
        if isinstance(name, int):
            return name
        full = name if "." in name else f"{key}.{name}"
        if table is quests and full in KNOWN:
            return KNOWN[full]
        if full not in table:
            die(key, f"no {what} called {name}")
        return table[full]

    def cond(key, items):
        out = []
        for item in items or []:
            neg = item.startswith("!")
            body = item[1:] if neg else item
            if body in FLAGS:
                out.append(("!F" if neg else "F") + str(FLAGS[body]))
            elif body == "OWN":
                out.append("!O" if neg else "O")
            elif body.startswith("UPG:"):
                out.append(("!U" if neg else "U") + str(int(body[4:])))
            elif body.startswith("DAY:"):
                out.append("D" + str(int(body[4:])))
            elif len(body) > 2 and body[1] == ":" and body[0] in "RTAICM":
                kind, name = body[0], body[2:]
                kind = "R" if kind == "M" else kind
                out.append(("!" if neg else "") + kind + str(ref(key, quests, name, "quest")))
            else:
                die(key, f"a condition I don't know: {item}")
        return " ".join(out)

    def place(key, spawn):
        """(map, x, y, z, o) from a spawn: [map, x, y, z, o] in the world, or ["house", lx, ly, dz, deg]."""
        if spawn[0] == "house":
            x, y, o = house_point(spawn[1], spawn[2], spawn[4] if len(spawn) > 4 else 0)
            return 0, x, y, FLOOR + (spawn[3] if len(spawn) > 3 else 0), o
        if len(spawn) < 4:
            die(key, f"a spawn needs a map and x, y, z: {spawn}")
        return spawn[0], spawn[1], spawn[2], spawn[3], spawn[4] if len(spawn) > 4 else 0.0

    def phase_of(key, thing):
        name = thing.get("phase")
        if not name:
            return 1
        full = name if "." in name else f"{key}.{name}"
        if full not in phases:
            die(key, f"no phase called {name}")
        return phases[full]

    def in_brill(m, x, y):
        return m == 0 and math.hypot(x - BRILL_X, y - BRILL_Y) < BRILL_RADIUS

    out = []
    w = out.append
    w("-- Brill: A Place Among the Forsaken (mod-brill-home; ChromieCraft's world database alone: ctl.sh")
    w("-- realm_sql). Made by tools/build_town.py from tools/town/*.py; don't edit by hand. Written to be")
    w("-- applied again: its own rows go first.")
    w("")
    w("CREATE TABLE IF NOT EXISTS `brill_town_phase` (`bit` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, "
      "`cond` VARCHAR(255) NOT NULL DEFAULT '', PRIMARY KEY (`bit`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;")
    w("CREATE TABLE IF NOT EXISTS `brill_town_gossip` (`npc` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, "
      "`kind` VARCHAR(8) NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0, "
      "`text` TEXT, `text_id` INT UNSIGNED NOT NULL DEFAULT 0, `action` VARCHAR(16) NOT NULL DEFAULT '', "
      "`quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, "
      "`emote` INT UNSIGNED NOT NULL DEFAULT 0, `reply` TEXT, `arg` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`npc`, `ord`)) "
      "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;")
    w("CREATE TABLE IF NOT EXISTS `brill_town_use` (`go` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, "
      "`cond` VARCHAR(255) NOT NULL DEFAULT '', `quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, "
      "`text_id` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`go`, `ord`)) "
      "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;")
    w("DELETE FROM `brill_town_phase`;")
    w("DELETE FROM `brill_town_gossip`;")
    w("DELETE FROM `brill_town_use`;")
    lo, hi = 970200, 970200 + 100 * len(LINES) - 1
    for table, column in (("creature_template", "entry"), ("creature_template_model", "CreatureID"), ("creature", "guid"),
                          ("gameobject_template", "entry"), ("gameobject", "guid"), ("npc_text", "ID"),
                          ("quest_template", "ID"), ("quest_template_addon", "ID"), ("quest_offer_reward", "ID"),
                          ("quest_request_items", "ID"), ("creature_queststarter", "quest"), ("creature_questender", "quest"),
                          ("smart_scripts", "entryorguid")):
        extra = " AND `source_type` = 0" if table == "smart_scripts" else ""
        w(f"DELETE FROM `{table}` WHERE `{column}` BETWEEN {lo} AND {hi}{extra};")
    w("")

    for key, line in lines:
        i = ids[key]
        w(f"-- {line.get('title', key)}")
        # Its words: every text given by a name gets an npc_text row.
        def text(name, body, many=None):
            tid = i.take("text", name)
            if many:
                cols = ", ".join(f"`text{n}_0`, `text{n}_1`, `Probability{n}`" for n in range(len(many)))
                vals = ", ".join(f"{q(t)}, {q(t)}, {1 / len(many):.3f}" for t in many)
                texts[tid] = f"INSERT INTO `npc_text` (`ID`, {cols}) VALUES ({tid}, {vals});"
            else:
                texts[tid] = f"INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES ({tid}, {q(body)}, {q(body)}, 1);"
            return tid

        # Creatures (its own, and the credits for what its quests ask the player to do).
        rows, models, spawns, smart = [], [], [], []
        for npc in line.get("npcs", []):
            entry = npcs[f"{key}.{npc['id']}"]
            flags = 0
            for f in npc.get("flags", ["gossip", "quest"]):
                flags |= {"gossip": 1, "quest": 2, "vendor": 128, "repair": 4096}[f]
            hostile = npc.get("hostile", False)
            faction = npc.get("faction", 14 if hostile else 68)
            level = npc.get("level", 20)
            lmin, lmax = (level, level) if isinstance(level, int) else level
            ai = "SmartAI" if npc.get("spells") else ""
            script = ""   # (the town's menus are src/BrillTown.cpp's, for any creature with rows)
            rows.append(f"({entry}, {q(npc['name'])}, {q(npc['subname']) if npc.get('subname') else 'NULL'}, 0, {lmin}, {lmax}, "
                        f"{faction}, {0 if hostile else flags}, 1, 1.14286, {npc.get('unit_class', 1)}, "
                        f"{npc.get('unit_flags', 0)}, {npc.get('type', 6)}, {npc.get('rank', 0)}, {npc.get('health', 1)}, "
                        f"{npc.get('damage', 1)}, {npc.get('gold', 0)}, {npc.get('gold', 0)}, {2 if not hostile else 0}, "
                        f"{q(ai)}, {q(script)})")
            displays = npc["display"] if isinstance(npc["display"], list) else [npc["display"]]
            for n, d in enumerate(displays):
                models.append(f"({entry}, {n}, {d}, {npc.get('scale', 1)}, 1)")
            for spawn in npc.get("spawns", []):
                m, x, y, z, o = place(key, spawn)
                ph = phase_of(key, npc)
                if ph != 1 and not in_brill(m, x, y):
                    die(key, f"{npc['id']} is in a phase but outside Brill, where no one would see it")
                guid = i.take("cguid", f"{npc['id']}{len(spawns)}")
                spawns.append(f"({guid}, {entry}, {m}, {MAP_ZONE.get(m, 0)}, 0, 1, {ph}, {x:.3f}, {y:.3f}, {z:.3f}, {o:.4f}, "
                              f"{npc.get('respawn', 120 if hostile else 60)}, {npc.get('wander', 0)}, {1 if npc.get('wander') else 0}, "
                              f"{q('mod-brill-home town')})")
            for n, (spell, first, every) in enumerate(npc.get("spells", [])):
                smart.append(f"({entry}, 0, {n}, 0, 0, 0, 100, 0, {first[0]}, {first[1]}, {every[0]}, {every[1]}, 0, 0, 11, {spell}, "
                             f"0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, {q(npc['name'] + ' casts ' + str(spell))})")
            if npc.get("vendor"):
                w(f"DELETE FROM `npc_vendor` WHERE `entry` = {entry};")
                w("INSERT INTO `npc_vendor` (`entry`, `slot`, `item`, `maxcount`, `incrtime`, `ExtendedCost`) VALUES " +
                  ", ".join(f"({entry}, {n}, {item}, 0, 0, 0)" for n, item in enumerate(npc["vendor"])) + ";")
        for credit in line.get("credits", []):
            entry = npcs[f"{key}.{credit['id']}"]
            rows.append(f"({entry}, {q(credit['name'])}, NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '')")
            models.append(f"({entry}, 0, 11686, 1, 1)")
        if rows:
            w("INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, "
              "`npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, "
              "`mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES")
            w(",\n".join(rows) + ";")
            w("INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES")
            w(",\n".join(models) + ";")
        if spawns:
            w("INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, "
              "`position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES")
            w(",\n".join(spawns) + ";")
        if smart:
            w("UPDATE `creature_template` SET `AIName` = 'SmartAI' WHERE `entry` IN (" +
              ", ".join(str(npcs[f"{key}.{n['id']}"]) for n in line.get("npcs", []) if n.get("spells")) + ");")
            w("INSERT INTO `smart_scripts` (`entryorguid`, `source_type`, `id`, `link`, `event_type`, `event_phase_mask`, "
              "`event_chance`, `event_flags`, `event_param1`, `event_param2`, `event_param3`, `event_param4`, `event_param5`, "
              "`event_param6`, `action_type`, `action_param1`, `action_param2`, `action_param3`, `action_param4`, `action_param5`, "
              "`action_param6`, `target_type`, `target_param1`, `target_param2`, `target_param3`, `target_param4`, `target_x`, "
              "`target_y`, `target_z`, `target_o`, `comment`) VALUES")
            w(",\n".join(smart) + ";")

        # Objects.
        rows, spawns, uses = [], [], []
        for obj in line.get("objects", []):
            entry = objects[f"{key}.{obj['id']}"]
            kind = obj.get("type", 2 if obj.get("use") else 5)
            script = "go_brill_town" if kind == 2 else ""
            data0, data1 = (1, 1) if kind == 7 else (0, 0)
            rows.append(f"({entry}, {kind}, {obj['display']}, {q(obj['name'])}, {obj.get('scale', 1)}, {data0}, {data1}, {q(script)})")
            for spawn in obj.get("spawns", []):
                m, x, y, z, o = place(key, spawn)
                ph = phase_of(key, obj)
                if ph != 1 and not in_brill(m, x, y):
                    die(key, f"{obj['id']} is in a phase but outside Brill, where no one would see it")
                guid = i.take("gguid", f"{obj['id']}{len(spawns)}")
                spawns.append(f"({guid}, {entry}, {m}, {MAP_ZONE.get(m, 0)}, 0, 1, {ph}, {x:.3f}, {y:.3f}, {z:.3f}, {o:.4f}, "
                              f"0, 0, {math.sin(o / 2):.6f}, {math.cos(o / 2):.6f}, 0, 255, 1, {q('mod-brill-home town')})")
            for n, use in enumerate(obj.get("use", [])):
                tid = text(f"use:{obj['id']}:{n}", use["text"]) if use.get("text") else 0
                quest = ref(key, quests, use["quest"], "quest") if use.get("quest") else 0
                credit = ref(key, npcs, use["credit"], "credit") if use.get("credit") else 0
                marker = ref(key, quests, use["marker"], "marker") if use.get("marker") else 0
                uses.append(f"({entry}, {n}, {q(cond(key, use.get('cond')))}, {quest}, {credit}, {tid}, {marker})")
        if rows:
            w("INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES")
            w(",\n".join(rows) + ";")
        if spawns:
            w("INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, "
              "`position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, "
              "`state`, `Comment`) VALUES")
            w(",\n".join(spawns) + ";")
        if uses:
            w("INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES")
            w(",\n".join(uses) + ";")

        # What creatures say, and the options the module adds to their menus.
        rows = []
        for n, g in enumerate(line.get("gossip", [])):
            npc = ref(key, npcs, g["npc"], "creature")
            if g["kind"] == "greet":
                tid = text(f"greet:{n}", g.get("text"), g.get("texts"))
                rows.append((npc, f"'greet', {q(cond(key, g.get('cond')))}, 0, NULL, {tid}, '', 0, 0, 0, 0, NULL, 0"))
            else:
                action = g["action"]
                if action not in ("credit", "choice", "say", "vendor", "upgrades", "furnish"):
                    die(key, f"an option's action I don't know: {action}")
                quest = ref(key, quests, g["quest"], "quest") if g.get("quest") else 0
                credit = ref(key, npcs, g["credit"], "credit") if g.get("credit") else 0
                marker = ref(key, quests, g["marker"], "marker") if g.get("marker") else 0
                icon = {"vendor": 1, "upgrades": 1}.get(action, 0)
                rows.append((npc, f"'option', {q(cond(key, g.get('cond')))}, {icon}, {q(g['text'])}, 0, {q(action)}, {quest}, {credit}, "
                                  f"{marker}, {g.get('emote', 0)}, {q(g['reply']) if g.get('reply') else 'NULL'}, {g.get('arg', 0)}"))
        if rows:
            w("INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, "
              "`marker`, `emote`, `reply`, `arg`) VALUES")
            # (an order across lines: the line's place, then its row)
            w(",\n".join(f"({npc}, {LINES.index(key) * 1000 + n}, {rest})" for n, (npc, rest) in enumerate(rows)) + ";")
            # (a creature of the game's own given options: it has a menu to show them in)
            own = sorted({npc for npc, _ in rows if npc < 970000})
            if own:
                w("UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (" + ", ".join(map(str, own)) + ");")

        # The phases (a bit each, its conditions: any row's all).
        rows = []
        for name, alternatives in line.get("phases", {}).items():
            bit = phases[f"{key}.{name}"]
            for n, alt in enumerate(alternatives):
                rows.append(f"({bit}, {n}, {q(cond(key, alt))})")
        if rows:
            w("INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES")
            w(",\n".join(rows) + ";")

        # Quests.
        rows, addons, offers, requests, starters, enders = [], [], [], [], [], []
        for quest in line.get("quests", []):
            qid = quests[f"{key}.{quest['id']}"]
            objs = quest.get("objectives", [])
            if len(objs) > 4:
                die(key, f"{quest['id']} has more than four objectives")
            cols = []
            for n in range(4):
                if n < len(objs):
                    kind, what, count, label = objs[n]
                    target = ref(key, npcs, what, "creature") if kind != "kill" or isinstance(what, str) else what
                    cols.append(f"{target}, {count}, {q(label)}")
                else:
                    cols.append("0, 0, ''")
            items = quest.get("items", []) + [(0, 0)] * (4 - len(quest.get("items", [])))
            sort = quest.get("sort", 159)
            flags = quest.get("flags", 0) | (0x10000 if quest.get("auto_complete") else 0)
            rows.append(f"({qid}, 2, -1, {quest.get('min_level', 5)}, {sort}, {flags}, {quest.get('xp', 3)}, {quest.get('money', 2500)}, 0, "
                        f"{q(quest['title'])}, {q(quest['log'])}, {q(quest['offer'])}, '', {q(quest.get('return', 'Return to ' + quest.get('ender_name', 'the quest giver') + '.'))}, "
                        + ", ".join(cols) + ", " + ", ".join(f"{a}, {b}" for a, b in items) + ")")
            prev = ref(key, quests, quest["prev"], "quest") if quest.get("prev") else 0
            addons.append(f"({qid}, {prev})")
            offers.append(f"({qid}, {quest.get('done_emote', 1)}, {q(quest['done'])})")
            if quest.get("progress"):
                requests.append(f"({qid}, 1, 1, {q(quest['progress'])})")
            giver = ref(key, npcs, quest["giver"], "creature")
            ender = ref(key, npcs, quest.get("ender", quest["giver"]), "creature")
            starters.append(f"({giver}, {qid})")
            enders.append(f"({ender}, {qid})")
        for marker in line.get("markers", []):
            # (a choice's mark: never given or seen, only set as rewarded: src/BrillTown.cpp)
            qid = quests[f"{key}.{marker}"]
            rows.append(f"({qid}, 2, 1, 1, -1, 0, 0, 0, 0, {q('[' + marker + ']')}, '', '', '', '', "
                        + ", ".join(["0, 0, ''"] * 4) + ", " + ", ".join(["0, 0"] * 4) + ")")
        if rows:
            w("INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, "
              "`RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, "
              "`RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, "
              "`RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, "
              "`RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES")
            w(",\n".join(rows) + ";")
        if addons:
            w("INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES " + ", ".join(addons) + ";")
            w("INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES\n" + ",\n".join(offers) + ";")
            if requests:
                w("INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES\n"
                  + ",\n".join(requests) + ";")
            w("INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES " + ", ".join(starters) + ";")
            w("INSERT INTO `creature_questender` (`id`, `quest`) VALUES " + ", ".join(enders) + ";")
            own = sorted({int(s.split(",")[0][1:]) for s in starters + enders if int(s.split(",")[0][1:]) < 970000})
            if own:
                w("UPDATE `creature_template` SET `npcflag` = `npcflag` | 3 WHERE `entry` IN (" + ", ".join(map(str, own)) + ");")
        w("")

    if texts:
        w("-- The words.")
        for tid in sorted(texts):
            w(texts[tid])
    used = sum(1 for _ in phases)
    w(f"-- ({used} of the town's {len(FREE_PHASES)} phases used)")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"{OUT}: {len(lines)} questlines, {sum(len(l.get('quests', [])) for _, l in lines)} quests, {used} phases")


if __name__ == "__main__":
    main()
