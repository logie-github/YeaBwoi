#!/usr/bin/env python3
"""The start quests the Draenei and the Forsaken alone have, cloned for the void elves and the
nightborne: the same quests from the same people, for the new race alone (the originals stay
their race's, untouched), with the quest-givers' words taking in who the player is before
sending them down the same road.

    python3 tools/quests.py WORLD_SQL_DIR    the core's data/sql/base/db_world

Writes data/sql/db-world/allied_races_quests.sql (the clones: quest_template, its addon, texts,
map points, starters and enders) and data/retail/cloned_quests.json (which quests were cloned:
tools/build_data.py opens every other quest of the start race to the new race, these it leaves).

A clone's id is the original's plus 950000. Where a chain's next quest is cloned too, the clone
leads to the clone; where it isn't, to the original (open to the race by then).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sqlrows  # noqa: E402

MODULE = os.path.dirname(HERE)
OFFSET = 950000
BIT = {"voidelf": 1 << 14, "nightborne": 1 << 15}

# The quests cloned: the start race's own in its first zones (quests any Alliance or Horde
# character takes aren't here: those open to the race as they are).
CLONED = {
    "voidelf": [9279, 9280, 9283, 9463, 9473, 9505, 9612,            # Ammen Vale and Azuremyst Isle
                9603, 9604, 9605, 9606,                              # Bloodmyst: the hippogryph
                9753, 9756, 9759, 9760, 9761, 9762],                 # Bloodmyst: the prophecy
    "nightborne": [363,                                              # Deathknell (Welcome!, the Collector's Edition voucher, isn't cloned)
                   6321, 6323, 6322, 6324],                          # Silverpine: the ride to the Undercity
}

# What the quest-givers say to the new race before the quest's own words. $n: the player's name,
# $r: the race (Void Elf, Nightborne), $c: the class, $B: a line break (the game fills them in).
GREETING = {
    "voidelf": [
        "You are no draenei, yet the sea gave you to us with our own wounded. Ren'dorei, they call your kind: elves the Void has marked. The Light does not ask where a hand comes from, $n, only whether it will help.$B$B",
        "A $r among us. Word came from the Exodar that Umbric's exiles had taken the Alliance's side; I did not think to meet one here. Our need is the same as yours, then.$B$B",
        "You stand out among us, $n, and I'll not pretend otherwise. But you have bled for this shore like any draenei, and that is what I judge by.$B$B",
        "The Prophet himself said the ren'dorei would find their way to us. Whatever the Void left in you, it has not taken your will to help.$B$B",
    ],
    "nightborne": [
        "You're no corpse of ours, shal'dorei, though you slept like one. The Dark Lady's word came ahead of you: Suramar's children would learn the Forsaken's ways from the ground up, and the ground starts here, in Deathknell.$B$B",
        "A nightborne, in Tirisfal. Ten thousand years behind your shield, and now you walk among the dead. Good. We have use for an arcanist who doesn't flinch.$B$B",
        "Don't mind the stares, $n. The living are rare here, and your kind rarer. You wear the Horde's colors; that is all Deathknell asks.$B$B",
        "Suramar's magisters sent us their finest, they said. We'll see. Here, $r, the work speaks louder than the lineage.$B$B",
    ],
}
# A quest whose own first words don't fit the new race gets these in their place.
REWRITTEN = {
    9279: "You... you are awake. And you are not one of ours. A ren'dorei, by the look of you: one of the void elves the Exodar's letters spoke of, washed up with the rest of us when the ship broke apart.$B$BWhatever brought you to this shore, $n, you are alive, and we have need of every pair of hands. Will you help us?",
    363: "About time you woke up. We were about to toss you into the fire with the others before someone noticed you were still breathing. A shal'dorei, they tell me: one of Suramar's nightborne, sent to learn how the Forsaken do things.$B$BWell. This is how. I am Mordo, the caretaker of the crypt of Deathknell, and you are nobody's slave here.$B$BSpeak with Shadow Priest Sarvis in the chapel at the base of the hill; he will tell you more of what you must know.$B$BTo get out of this crypt, take the steps up to the surface. Follow the road down to the chapel to find Sarvis.",
}
TEXT_FIXES = {
    "voidelf": [("fellow draenei", "friend of the draenei"), ("my fellow draenei", "my friend"), ("You are a draenei", "You stand with the draenei"),
                ("as a draenei", "as one who stands with the draenei")],
    "nightborne": [("fellow Forsaken", "friend of the Forsaken"), ("my fellow Forsaken", "my friend"), ("You are Forsaken", "You stand with the Forsaken"),
                   ("as a Forsaken", "as one who stands with the Forsaken"), ("undead such as yourself", "one who stands with us")],
}


def main(world):
    T = lambda name: sqlrows.table(os.path.join(world, name + ".sql"))
    out = ["-- The start quests of the Draenei and the Forsaken, cloned for the void elves and the nightborne\n"
           "-- (mod-allied-races, tools/quests.py): the originals are untouched. Safe to run again."]
    quests_cols, quests = T("quest_template")
    by_id = {int(q["ID"]): q for q in quests}
    addon_cols, addons = T("quest_template_addon")
    addon_by_id = {int(a["ID"]): a for a in addons}
    offer_cols, offers = T("quest_offer_reward")
    request_cols, requests = T("quest_request_items")
    details_cols, details = T("quest_details")
    poi_cols, pois = T("quest_poi")
    poi_point_cols, poi_points = T("quest_poi_points")
    cqs_cols, cqs = T("creature_queststarter")
    cqe_cols, cqe = T("creature_questender")
    gqs_cols, gqs = T("gameobject_queststarter")
    gqe_cols, gqe = T("gameobject_questender")
    all_cloned = set(q for qs in CLONED.values() for q in qs)
    clone_id = lambda q: q + OFFSET if q in all_cloned else q

    def insert(table, cols, rows):
        if not rows:
            return
        out.append("INSERT INTO `%s` (%s) VALUES\n%s;" % (table, ", ".join("`%s`" % c for c in cols),
                   ",\n".join("(" + ", ".join(sqlrows.quote(r[c]) for c in cols) + ")" for r in rows)))

    for race, ids in CLONED.items():
        out.append("\n-- %s\nDELETE FROM `quest_template` WHERE `ID` IN (%s);" % (race, ", ".join(str(q + OFFSET) for q in ids)))
        for table in ("quest_template_addon", "quest_offer_reward", "quest_request_items", "quest_details", "quest_poi", "quest_poi_points"):
            out.append("DELETE FROM `%s` WHERE `%s` IN (%s);" % (table, "ID" if table.startswith("quest_template") or table in ("quest_offer_reward", "quest_request_items", "quest_details") else "QuestID",
                                                                 ", ".join(str(q + OFFSET) for q in ids)))
        for table in ("creature_queststarter", "creature_questender", "gameobject_queststarter", "gameobject_questender"):
            out.append("DELETE FROM `%s` WHERE `quest` IN (%s);" % (table, ", ".join(str(q + OFFSET) for q in ids)))
        rows = []
        for n, qid in enumerate(ids):
            q = dict(by_id[qid])
            q["ID"] = qid + OFFSET
            q["AllowableRaces"] = BIT[race]
            q["RewardNextQuest"] = clone_id(int(q["RewardNextQuest"] or 0))
            text = q["QuestDescription"] or ""
            for a, b in TEXT_FIXES[race]:
                text = text.replace(a, b)
            if qid in REWRITTEN:
                text = REWRITTEN[qid]
            else:
                text = GREETING[race][n % len(GREETING[race])] + text
            q["QuestDescription"] = text
            for key in ("LogDescription", "QuestCompletionLog", "AreaDescription"):
                if q.get(key):
                    for a, b in TEXT_FIXES[race]:
                        q[key] = q[key].replace(a, b)
            q["VerifiedBuild"] = 12340
            rows.append(q)
        insert("quest_template", quests_cols, rows)
        rows = []
        for qid in ids:
            a = dict(addon_by_id.get(qid) or {c: 0 for c in addon_cols})
            a["ID"] = qid + OFFSET
            for key in ("PrevQuestID", "NextQuestID", "BreadcrumbForQuestId"):
                v = int(a.get(key) or 0)
                a[key] = (-1 if v < 0 else 1) * clone_id(abs(v)) if v else 0
            v = int(a.get("ExclusiveGroup") or 0)
            a["ExclusiveGroup"] = (-1 if v < 0 else 1) * clone_id(abs(v)) if v else 0
            rows.append(a)
        insert("quest_template_addon", addon_cols, rows)
        for table, cols, source, key in (("quest_offer_reward", offer_cols, offers, "ID"), ("quest_request_items", request_cols, requests, "ID"),
                                         ("quest_details", details_cols, details, "ID"), ("quest_poi", poi_cols, pois, "QuestID"),
                                         ("quest_poi_points", poi_point_cols, poi_points, "QuestID")):
            rows = []
            for r in source:
                if int(r[key]) in ids:
                    c = dict(r)
                    c[key] = int(r[key]) + OFFSET
                    if "RewardText" in c and c["RewardText"]:
                        for a, b in TEXT_FIXES[race]:
                            c["RewardText"] = c["RewardText"].replace(a, b)
                    if "CompletionText" in c and c["CompletionText"]:
                        for a, b in TEXT_FIXES[race]:
                            c["CompletionText"] = c["CompletionText"].replace(a, b)
                    rows.append(c)
            insert(table, cols, rows)
        for table, cols, source in (("creature_queststarter", cqs_cols, cqs), ("creature_questender", cqe_cols, cqe),
                                    ("gameobject_queststarter", gqs_cols, gqs), ("gameobject_questender", gqe_cols, gqe)):
            rows = []
            for r in source:
                if int(r["quest"]) in ids:
                    c = dict(r)
                    c["quest"] = int(r["quest"]) + OFFSET
                    rows.append(c)
            insert(table, cols, rows)
    os.makedirs(os.path.join(MODULE, "data", "sql", "db-world"), exist_ok=True)
    with open(os.path.join(MODULE, "data", "sql", "db-world", "allied_races_quests.sql"), "w", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    with open(os.path.join(MODULE, "data", "retail", "cloned_quests.json"), "w") as f:
        json.dump(CLONED, f, indent=1)
    for race, ids in CLONED.items():
        print("%s: %d quests cloned: %s" % (race, len(ids), ", ".join(by_id[q]["LogTitle"] for q in ids)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
