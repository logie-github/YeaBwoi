#!/usr/bin/env python3
"""Works out the game's music and sounds the demo plays, by file id, from the game's tables
(wago.tools' CSVs), and writes assets/sounds.json. Only ids: the files are the game's and the app
fetches them, on the phone, from wago.tools.

    python3 tools/sounds.py TABLES_DIR

TABLES_DIR holds SoundKitEntry, EmotesText, EmotesTextSound, CreatureDisplayInfo,
CreatureModelData and CreatureSoundData as CSV (https://wago.tools/db2/<Table>/csv).
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "sounds.json")

# Music, by the story's names for it (files by id; the paths are the community listfile's).
MUSIC = {
    "dawn": 1417233,      # sound/music/legion/mus_70_dalarandawn_a.mp3: the title, the prologue
    "day": 1417262,       # mus_70_dalarankhadgar_day_a.mp3: the city by day
    "dusk": 1417236,      # mus_70_dalarandusk_a.mp3
    "night": 1417266,     # mus_70_dalarankhadgar_night_a.mp3
    "alleria": 6042833,   # sound/music/warwithin/mus_110_alleria_a.mp3: her theme
    "alleria2": 6042831,  # mus_110_alleria_b.mp3: her night
    "nyrelle": 53184,     # sound/music/citymusic/darnassus/darnassus walking 1.mp3
    "moon": 53754,        # sound/music/zonemusic/tavernnightelf/ra_templeofthemoona.mp3: Elune's
}

# The city's own sound, by the scene's light.
AMBIENCE = {
    "day": 594573,        # sound/ambience/zoneambience/dalarancityday.ogg
    "night": 594369,      # sound/ambience/zoneambience/dalarancitynight.ogg
}

# The interface's sound kits (the game's SOUNDKIT constants).
UI = {
    "click": 857,         # IG_MAINMENU_OPTION
    "open": 850,          # IG_MAINMENU_OPEN (the game menu)
    "close": 851,         # IG_MAINMENU_CLOSE
    "gossip": 875,        # IG_QUEST_LIST_OPEN (a choice)
    "ending": 12891,      # UI_ALERT_ACHIEVEMENT_GAINED
}

# Each speaker's voice for the emotes that have one: (race, sex) in EmotesTextSound.
VOICES = {
    "nyrelle": (4, 1),    # night elf woman
    "alleria": (29, 1),   # void elf woman: Alleria, touched by the Void
    "player_man": (1, 0),
    "player_woman": (1, 1),
}
# The story's emotes and the game's emote (EmotesText) that voices each.
EMOTE_VOICE = {
    "laugh": "laugh", "cheer": "cheer", "kiss": "kiss", "cry": "cry", "roar": "roar", "no": "no",
    "wave": "hello", "shy": "giggle", "exclaim": "oops", "applaud": "applaud",
}

# Creatures' own sounds (CreatureSoundData), for the story's sound effects.
CREATURES = {
    "saber": (62588, {"roar": "SoundAggroID", "pounce": "SoundExertionID", "alert": "SoundAlertID"}),
}


def table(d, name):
    with open(os.path.join(d, name + ".csv"), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def main():
    d = sys.argv[1]
    kits = {}
    for r in table(d, "SoundKitEntry"):
        kits.setdefault(int(r["SoundKitID"]), []).append(int(r["FileDataID"]))
    files = lambda kit: sorted(kits.get(kit, []))

    out = {"music": MUSIC, "ambience": AMBIENCE, "ui": {}, "voices": {}, "effects": {}}
    for name, kit in UI.items():
        out["ui"][name] = files(kit)[:1]

    emote_names = {int(r["ID"]): r["Name"].lower() for r in table(d, "EmotesText")}
    sounds = table(d, "EmotesTextSound")
    for who, (race, sex) in VOICES.items():
        voice = {}
        for emote, game in EMOTE_VOICE.items():
            # Today's models have their own recordings: the newest kit (the highest id) is theirs.
            found = [int(r["SoundID"]) for r in sounds
                     if int(r["RaceID"]) == race and int(r["SexID"]) == sex
                     and emote_names.get(int(r["EmotesTextID"])) == game]
            if found and files(max(found)):
                voice[emote] = files(max(found))
        out["voices"][who] = voice

    cdi = {int(r["ID"]): r for r in table(d, "CreatureDisplayInfo")}
    cmd = {int(r["ID"]): r for r in table(d, "CreatureModelData")}
    csd = {int(r["ID"]): r for r in table(d, "CreatureSoundData")}
    for who, (display, wanted) in CREATURES.items():
        disp = cdi[display]
        sound = int(disp.get("SoundID") or 0) or int(cmd[int(disp["ModelID"])].get("SoundID") or 0)
        data = csd[sound]
        for name, column in wanted.items():
            got = files(int(data[column] or 0))
            if got:
                out["effects"]["%s_%s" % (who, name)] = got

    with open(OUT, "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    n = len(MUSIC) + len(AMBIENCE) + sum(len(v) for v in out["ui"].values()) \
        + sum(len(f) for v in out["voices"].values() for f in v.values()) + sum(len(v) for v in out["effects"].values())
    print("%d files" % n)


if __name__ == "__main__":
    main()
