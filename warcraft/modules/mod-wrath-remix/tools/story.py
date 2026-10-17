#!/usr/bin/env python3
"""Writes Moratari's part in the war (STORY.md, "Moratari in the war") as the world database's rows:
data/sql/remix/world/wrath_remix_world_0027_moratari.sql.

    python3 tools/story.py

- WORDS: what Moratari (or Eternus) says on the game's own quests, as one is taken, as its
  objectives are done, as it's turned in (remix_quest_whisper; RemixMoratari.cpp says them beside
  the Timerunner when she walks with them, and whispers them when she doesn't).
- COMPANION: where she walks with the Timerunner (remix_companion).
- PLACES: somewhere a quest sends the Timerunner to see for themselves: its credit as they come
  within its radius, and Moratari's words there (remix_place).
- TALK: what the Timerunner can say, to her or to the war's own people (remix_talk), each with the
  reply they get (npc_text, written here) and what it gives (a quest's credit, a scene).
- QUESTS: the story's own quests that live in this file (the interventions), with their givers,
  their place in the game's chains and the conditions that place them.
- TEXTS: the game's quests whose texts change while Moratari and Eternus steer the war (the
  originals are kept in remix_original_quest_text, and put back for any quest dropped from here).

Text tokens, in chat and in replies alike: $N the name, $R the race, $C the class ($r and $c in lower
case), $Gmale:female; by the Timerunner's sex.
"""
import os

TAKEN, DONE, TURNED_IN = 1, 2, 3
BOTH, ALLIANCE, HORDE = 0, 1, 2
ALLIANCE_RACES, HORDE_RACES = 1101, 690

MORATARI, ETERNUS = 770001, 770002

# remix_talk's actions (RemixMoratari.cpp)
SCENE, CLOSE, STAY, COME = 1, 2, 3, 4
ICON_CHAT, ICON_DOT, ICON_TALK = 0, 7, 0

ZONE_BOREAN_TUNDRA, ZONE_DRAGONBLIGHT = 3537, 65

QUEST_FLAG_ALONE = 77040
TO_THE_FRONT_A, TO_THE_FRONT_H = 77014, 77015
INTO_THE_DRAGONBLIGHT_A, INTO_THE_DRAGONBLIGHT_H = 77121, 77122

# npc_text ids this file owns
TEXT_FIRST, TEXT_LAST = 7702600, 7702999

COMPANION = [
    # (side, zone, from (rewarded), until (rewarded), during (in the log), why)
    (ALLIANCE, ZONE_BOREAN_TUNDRA, TO_THE_FRONT_A, INTO_THE_DRAGONBLIGHT_A, 0, "Chapter I: the Borean Tundra, while the Timerunner finds their feet"),
    (HORDE, ZONE_BOREAN_TUNDRA, TO_THE_FRONT_H, INTO_THE_DRAGONBLIGHT_H, 0, "Chapter I: the Borean Tundra, while the Timerunner finds their feet"),
]

WORDS = [
    # (quest, moment, side, text) or (quest, moment, side, text, dict(delay=, after=, before=, races=, speaker=))

    # ---- Chapter I: the landing ---------------------------------------------------------------------
    (TO_THE_FRONT_A, TURNED_IN, ALLIANCE, "Valiance Keep. The fleet came in three days ago, and the Scourge has tested the walls every night since. You remember the smell of this place, I imagine: tar, salt, and far too many soldiers in one harbor."),
    (TO_THE_FRONT_A, TURNED_IN, ALLIANCE, "I will walk with you while you find your feet. If you would rather I waited somewhere, say so.", dict(delay=7000)),
    (TO_THE_FRONT_H, TURNED_IN, HORDE, "Warsong Hold. Garrosh's fortress, dug into a cliff that is already full of Nerubians, though no one inside knows it yet. Well. Almost no one."),
    (TO_THE_FRONT_H, TURNED_IN, HORDE, "I will walk with you while you find your feet. If you would rather I waited somewhere, say so.", dict(delay=7000)),
]

TALK = [
    # dict(npc=, option=, reply=, parent=None|key, key=, side=, quest=, credit=, after=, before=, action=, param=, races=, gender=)
    dict(key="stay", npc=MORATARI, option="Wait for me here. I'd like to walk alone for a while.",
         reply="If you like. I will be where I always am: wherever you next need me. Call me back when you want me.",
         action=STAY),
    dict(key="come", npc=MORATARI, option="Walk with me again.",
         reply="I was hoping you would ask. Lead on, $N.",
         action=COME),
]

PLACES = [
    # dict(quest=, map=, x=, y=, radius=, credit=, scene=, text=)
]

QUESTS = []
TEXTS = {}

# Somewhere Moratari takes the Timerunner (remix_outing; RemixOutings.cpp)
OUTINGS = [
    # dict(id=, name=, map=, x=, y=, z=, o=, scene=, private=, undress=, back=, flight=(from, to, mount), lines=[(ms, text)])
]

# Looks the game hasn't: one of its own with other clothes (remix_look; ClientPatch.cpp writes them in
# the game's patch, the server has them in its *_dbc tables)
LOOKS = [
    # dict(display=, from_display=, extra=, from_extra=, items=[11 item displays], bake=0|1)
]


def sql_text(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    out = []
    w = out.append
    w("""-- Moratari's part in the war (STORY.md, "Moratari in the war"), written by tools/story.py: edit that
-- and run it again. Her words on the game's quests, where she walks with the Timerunner, and what
-- the Timerunner can say to her and to the war's own people (RemixMoratari.cpp).

DROP TABLE IF EXISTS `remix_quest_whisper`;
CREATE TABLE `remix_quest_whisper` (
  `quest` INT UNSIGNED NOT NULL,
  `moment` TINYINT UNSIGNED NOT NULL,             -- 1 taken, 2 its objectives done, 3 turned in
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,     -- 0 both, 1 Alliance, 2 Horde
  `seq` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `speaker` INT UNSIGNED NOT NULL DEFAULT 770001,
  `delay_ms` INT UNSIGNED NOT NULL DEFAULT 3000,  -- after the line before it
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- said only once this is turned in
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0, -- said only until this is turned in
  `races` INT UNSIGNED NOT NULL DEFAULT 0,        -- a race mask (0: any)
  `text` TEXT NOT NULL,
  PRIMARY KEY (`quest`, `moment`, `side`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_companion`;
CREATE TABLE `remix_companion` (
  `id` INT UNSIGNED NOT NULL,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `zone` INT UNSIGNED NOT NULL DEFAULT 0,          -- 0: anywhere
  `from_quest` INT UNSIGNED NOT NULL DEFAULT 0,    -- turned in (0: from the start)
  `until_quest` INT UNSIGNED NOT NULL DEFAULT 0,   -- turned in ends it (0: never)
  `during_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- in the log (0: any time)
  `comment` VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_place`;
CREATE TABLE `remix_place` (
  `quest` INT UNSIGNED NOT NULL,
  `credit` INT UNSIGNED NOT NULL,                  -- given as they come within the radius
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL,
  `y` FLOAT NOT NULL,
  `radius` FLOAT NOT NULL DEFAULT 20,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,         -- played there instead (its end gives the credit)
  `text` TEXT NOT NULL,                            -- Moratari's words there ('' for none)
  PRIMARY KEY (`quest`, `credit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing`;
CREATE TABLE `remix_outing` (
  `id` INT UNSIGNED NOT NULL,
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL, `y` FLOAT NOT NULL, `z` FLOAT NOT NULL, `o` FLOAT NOT NULL,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,          -- played there (remix_scene)
  `private` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- in a phase of its own
  `undress` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- the Timerunner's gear unseen while it lasts
  `back` TINYINT UNSIGNED NOT NULL DEFAULT 1,       -- back where they were after
  `flight_from` INT UNSIGNED NOT NULL DEFAULT 0,    -- a flight on her: from this flight point (at x y z)
  `flight_to` INT UNSIGNED NOT NULL DEFAULT 0,
  `mount` INT UNSIGNED NOT NULL DEFAULT 0,          -- her shape under them
  `name` VARCHAR(120) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing_line`;
CREATE TABLE `remix_outing_line` (
  `outing` INT UNSIGNED NOT NULL,
  `at_ms` INT UNSIGNED NOT NULL,                    -- after the flight starts
  `text` TEXT NOT NULL,
  PRIMARY KEY (`outing`, `at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_look`;
CREATE TABLE `remix_look` (
  `display` INT UNSIGNED NOT NULL,                  -- the new look (CreatureDisplayInfo)
  `from_display` INT UNSIGNED NOT NULL,             -- copied from
  `extra` INT UNSIGNED NOT NULL,                    -- its clothes (CreatureDisplayInfoExtra)
  `from_extra` INT UNSIGNED NOT NULL,               -- copied from
  `bake` TINYINT UNSIGNED NOT NULL DEFAULT 0,       -- keep the copied look's baked texture (0: dressed from its items)
  `item1` INT UNSIGNED NOT NULL DEFAULT 0, `item2` INT UNSIGNED NOT NULL DEFAULT 0, `item3` INT UNSIGNED NOT NULL DEFAULT 0,
  `item4` INT UNSIGNED NOT NULL DEFAULT 0, `item5` INT UNSIGNED NOT NULL DEFAULT 0, `item6` INT UNSIGNED NOT NULL DEFAULT 0,
  `item7` INT UNSIGNED NOT NULL DEFAULT 0, `item8` INT UNSIGNED NOT NULL DEFAULT 0, `item9` INT UNSIGNED NOT NULL DEFAULT 0,
  `item10` INT UNSIGNED NOT NULL DEFAULT 0, `item11` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`display`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_talk`;
CREATE TABLE `remix_talk` (
  `id` INT UNSIGNED NOT NULL,
  `npc` INT UNSIGNED NOT NULL,                     -- the creature it's said to
  `parent` INT UNSIGNED NOT NULL DEFAULT 0,        -- 0: in its first menu; else after that entry's reply
  `ord` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `races` INT UNSIGNED NOT NULL DEFAULT 0,
  `gender` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 0 any, 1 male, 2 female
  `quest` INT UNSIGNED NOT NULL DEFAULT 0,         -- only while this is in the log, unfinished
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `option_text` VARCHAR(255) NOT NULL,
  `reply` INT UNSIGNED NOT NULL DEFAULT 0,         -- npc_text (0: the menu closes)
  `credit` INT UNSIGNED NOT NULL DEFAULT 0,        -- a quest's credit, given as it's said
  `action` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 1 scene [param], 2 close, 3 she waits, 4 she comes
  `param` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
""")

    # The companion
    w("INSERT INTO `remix_companion` (`id`, `side`, `zone`, `from_quest`, `until_quest`, `during_quest`, `comment`) VALUES")
    w(",\n".join(f"({i + 1}, {s}, {z}, {f}, {u}, {d}, {sql_text(c)})" for i, (s, z, f, u, d, c) in enumerate(COMPANION)) + ";\n")

    # Places
    if PLACES:
        w("INSERT INTO `remix_place` (`quest`, `credit`, `map`, `x`, `y`, `radius`, `scene`, `text`) VALUES")
        w(",\n".join(f"({p['quest']}, {p['credit']}, {p['map']}, {p['x']:.1f}, {p['y']:.1f}, {p.get('radius', 20):.1f}, {p.get('scene', 0)}, "
                      f"{sql_text(p.get('text', ''))})" for p in PLACES) + ";\n")

    # Her words on quests
    if WORDS:
        rows, seqs = [], {}
        for entry in WORDS:
            quest, moment, side, text = entry[:4]
            opts = entry[4] if len(entry) > 4 else {}
            key = (quest, moment, side)
            seqs[key] = seqs.get(key, 0) + 1
            rows.append(f"({quest}, {moment}, {side}, {seqs[key]}, {opts.get('speaker', MORATARI)}, {opts.get('delay', 3000)}, "
                        f"{opts.get('after', 0)}, {opts.get('before', 0)}, {opts.get('races', 0)}, {sql_text(text)})")
        w("INSERT INTO `remix_quest_whisper` (`quest`, `moment`, `side`, `seq`, `speaker`, `delay_ms`, `after_quest`, `before_quest`, `races`, `text`) VALUES")
        w(",\n".join(rows) + ";\n")

    # What can be said, and the replies (npc_text: the same words whoever asks; the client puts in
    # $N, $R, $C and $G)
    texts, rows, ids = [], [], {}
    for n, t in enumerate(TALK):
        ids[t.get("key", n)] = 77000 + n + 1
    order = {}
    for n, t in enumerate(TALK):
        tid = ids[t.get("key", n)]
        reply = 0
        if t.get("reply"):
            reply = TEXT_FIRST + len(texts)
            assert reply <= TEXT_LAST
            texts.append((reply, t["reply"]))
        parent = ids[t["parent"]] if t.get("parent") is not None else 0
        order[(t["npc"], parent)] = order.get((t["npc"], parent), 0) + 1
        rows.append(f"({tid}, {t['npc']}, {parent}, {order[(t['npc'], parent)]}, {t.get('side', BOTH)}, {t.get('races', 0)}, {t.get('gender', 0)}, "
                    f"{t.get('quest', 0)}, {t.get('after', 0)}, {t.get('before', 0)}, {t.get('icon', ICON_CHAT)}, {sql_text(t['option'])}, "
                    f"{reply}, {t.get('credit', 0)}, {t.get('action', 0)}, {t.get('param', 0)})")
    w("INSERT INTO `remix_talk` (`id`, `npc`, `parent`, `ord`, `side`, `races`, `gender`, `quest`, `after_quest`, `before_quest`, `icon`, `option_text`, "
      "`reply`, `credit`, `action`, `param`) VALUES")
    w(",\n".join(rows) + ";\n")
    w(f"DELETE FROM `npc_text` WHERE `ID` BETWEEN {TEXT_FIRST} AND {TEXT_LAST};")
    if texts:
        w("INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES")
        w(",\n".join(f"({i}, {sql_text(text)}, {sql_text(text)}, 1)" for i, text in texts) + ";\n")

    # The companion's flag: never offered, rewarded while the Timerunner has asked her to wait.
    w(f"""-- Never offered: "rewarded" while the Timerunner has asked Moratari to wait (RemixMoratari.cpp).
DELETE FROM `quest_template` WHERE `ID` = {QUEST_FLAG_ALONE};
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `LogTitle`, `LogDescription`, `QuestDescription`, `VerifiedBuild`) VALUES
({QUEST_FLAG_ALONE}, 2, 1, 1, 0, 0, 'Walking Alone', '', 'Moratari waits at her posts while you walk alone.', 0);
""")

    path = os.path.join(os.path.dirname(__file__), "..", "data", "sql", "remix", "world", "wrath_remix_world_0027_moratari.sql")
    with open(path, "w") as f:
        f.write("\n".join(out))


if __name__ == "__main__":
    main()
