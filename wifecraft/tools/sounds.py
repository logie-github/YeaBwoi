#!/usr/bin/env python3
"""Works out the game's music and sounds Wifecraft plays, by file id, from the game's tables
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
    "dawn": 1417233,      # sound/music/legion/mus_70_dalarandawn_a.mp3: the title and the editor
    "day": 1417262,       # mus_70_dalarankhadgar_day_a.mp3: the city by day
    "dusk": 1417236,      # mus_70_dalarandusk_a.mp3
    "night": 1417266,     # mus_70_dalarankhadgar_night_a.mp3
}

# The city's own sound, by the scene's light.
AMBIENCE = {
    "day": 594573,        # sound/ambience/zoneambience/dalarancityday.ogg
    "night": 594369,      # sound/ambience/zoneambience/dalarancitynight.ogg
}

# The interface's sound kits (the game's SOUNDKIT constants: Blizzard_SharedXML/Mainline/SoundKitConstants.lua;
# audio/Soundscape.kt's UiSounds keeps the same table). The game's buttons have no hover sound.
UI = {
    "open": 850,          # IG_MAINMENU_OPEN (a window)
    "close": 851,         # IG_MAINMENU_CLOSE
    "click": 852,         # IG_MAINMENU_OPTION (857 is the checkbox-off kit)
    "ok": 798,            # GS_TITLE_OPTION_OK
    "check_on": 856,      # IG_MAINMENU_OPTION_CHECKBOX_ON
    "check_off": 857,     # IG_MAINMENU_OPTION_CHECKBOX_OFF
    "tab": 841,           # IG_CHARACTER_INFO_TAB
    "page": 836,          # IG_ABILITY_PAGE_TURN (three files)
    "select": 877,        # IG_QUEST_LIST_SELECT
    "create": 813,        # GS_CHARACTER_SELECTION_CREATE_NEW
    "begin": 809,         # GS_CHARACTER_SELECTION_ENTER_WORLD
    "gossip": 875,        # IG_QUEST_LIST_OPEN (a choice)
    "ending": 12891,      # achievmentsound1.ogg (the achievement toast's)
}

# The title's music: MUS_110_MAIN_TITLE (The War Within's main title, mus_110_thewarwithin_maintitle.mp3).
TITLE_KIT = 262929

# The places' sound (assets/locations.json's ambience block, from the tables): the Embassy's room (WMOAreaTable 93968)
# has ZoneMusic 1929 (Zone_735_AlliedRace_EmbassyAlliance: kit 97594), the area (9171) SoundAmbience 569: day kit
# 17895, night kit 17896.
PLACES = {
    "embassy": {"area": 9171, "zoneMusic": 1929, "ambience": 569, "music_kit": 97594,
                "day_kit": 17895, "night_kit": 17896, "silenceMs": [180000, 300000]},
}

# Each speaker's voice for the emotes that have one: (race, sex) in EmotesTextSound.
# (None yet: the editor's people are any race and sex; their voices by race and sex are still to come.)
VOICES = {}
# The story's emotes and the game's emote (EmotesText) that voices each.
EMOTE_VOICE = {
    "laugh": "laugh", "cheer": "cheer", "kiss": "kiss", "cry": "cry", "roar": "roar", "no": "no",
    "wave": "hello", "shy": "giggle", "exclaim": "oops", "applaud": "applaud",
}

# Creatures' own sounds (CreatureSoundData), for the story's sound effects.
CREATURES = {}


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
        out["ui"][name] = files(kit)
    out["title"] = {"kit": TITLE_KIT, "music": files(TITLE_KIT)}
    out["places"] = {}
    for pid, p in PLACES.items():
        out["places"][pid] = {"area": p["area"], "zoneMusic": p["zoneMusic"], "ambience": p["ambience"],
                              "music": files(p["music_kit"]), "ambienceDay": files(p["day_kit"]),
                              "ambienceNight": files(p["night_kit"]), "silenceMs": p["silenceMs"]}

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
