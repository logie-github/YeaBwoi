#!/usr/bin/env python3
"""Builds the two races' data from the game's own tables and data/retail/races.json (tools/
races.py): the world database's SQL (data/sql/db-world/allied_races_world.sql) and the changes
the module makes to the game's tables as the realm starts (data/client/dbc/*.txt;
AlliedRacesPatch.cpp applies them, the way mod-skyborne's are). Run it again when something here
or in races.json changes; its output is committed.

  build_data.py DBC_DIR WORLD_SQL_DIR

DBC_DIR: the server's dbc folder (the data.zip in warcraft/server-data.env); WORLD_SQL_DIR: the
core's data/sql/base/db_world at the realm's commit (warcraft/server-version.env).

The races:
  15  Void Elf, Alliance: the Draenei's start in Ammen Vale, their quests and standing (the Exodar)
  16  Nightborne, Horde: the Forsaken's start in Deathknell, their quests and standing (Undercity)
Races 15 and 16 were the game's Skeleton and Vrykul, which only creatures use: they move to 24
and 25 in the game's tables (their looks, their creatures' looks), out of the way (the Skyborne
moved 13 and 14 to 22 and 23 the same way).
"""
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import names                                   # noqa: E402
import sqlrows                                 # noqa: E402

MODULE = os.path.dirname(HERE)
COLUMNS = json.load(open(os.path.join(HERE, "dbc_columns.json")))
TYPES = json.load(open(os.path.join(HERE, "dbc_types.json")))
RACES = json.load(open(os.path.join(MODULE, "data", "retail", "races.json")))

HUMAN, ORC, DWARF, NIGHTELF, UNDEAD, TAUREN, GNOME, TROLL, BLOODELF, DRAENEI = 1, 2, 3, 4, 5, 6, 7, 8, 10, 11
VOIDELF, NIGHTBORNE = 15, 16
MOVED = {15: 24, 16: 25}                       # Skeleton, Vrykul: the creatures' races, moved
KEY = {VOIDELF: "voidelf", NIGHTBORNE: "nightborne"}
BIT = {VOIDELF: 1 << 14, NIGHTBORNE: 1 << 15}
ALLIANCE_MASK = sum(1 << (r - 1) for r in (HUMAN, DWARF, NIGHTELF, GNOME, DRAENEI))
HORDE_MASK = sum(1 << (r - 1) for r in (ORC, UNDEAD, TAUREN, TROLL, BLOODELF))
WARRIOR, PALADIN, HUNTER, ROGUE, PRIEST, DK, SHAMAN, MAGE, WARLOCK, DRUID = 1, 2, 3, 4, 5, 6, 7, 8, 9, 11
CLASSES = (WARRIOR, HUNTER, ROGUE, PRIEST, DK, MAGE, WARLOCK)       # both races' classes of today that 3.3.5 has
START = {VOIDELF: DRAENEI, NIGHTBORNE: UNDEAD}                      # where they begin, whose quests and standing they share
# Whose start each class takes (gear, action bars): the start race's where it has the class, the
# nearest of its side where it doesn't.
TEMPLATE = {
    VOIDELF: {WARRIOR: DRAENEI, HUNTER: DRAENEI, ROGUE: HUMAN, PRIEST: DRAENEI, DK: DRAENEI, MAGE: DRAENEI, WARLOCK: HUMAN},
    NIGHTBORNE: {WARRIOR: UNDEAD, HUNTER: BLOODELF, ROGUE: UNDEAD, PRIEST: UNDEAD, DK: UNDEAD, MAGE: UNDEAD, WARLOCK: UNDEAD},
}
STATS_FROM = {VOIDELF: BLOODELF, NIGHTBORNE: NIGHTELF}             # their kin's starting stats
MODEL_FROM = {VOIDELF: BLOODELF, NIGHTBORNE: NIGHTELF}             # whose model rows theirs are copied from
# Void elves retain their native Blood Elf-sized body; keep the Nightborne reduction.
DISPLAY_SCALE = {VOIDELF: 1.0, NIGHTBORNE: 0.9}
PREFIX = {VOIDELF: "Ve", NIGHTBORNE: "Nb"}
NAME = {VOIDELF: "Void Elf", NIGHTBORNE: "Nightborne"}
NARRATION = {VOIDELF: "ve.mp3", NIGHTBORNE: "nb.mp3"}
FEATURE_LABELS = {VOIDELF: ("FEATURES", "FEATURES", "NORMAL"), NIGHTBORNE: ("FEATURES", "FEATURES", "NORMAL")}
# The quests of the start race alone that are cloned for the new race (tools/quests.py): those
# keep their race, the clone is the new race's; every other quest of the start race opens to it.
CLONED = json.load(open(os.path.join(MODULE, "data", "retail", "cloned_quests.json"))) if os.path.exists(
    os.path.join(MODULE, "data", "retail", "cloned_quests.json")) else {}

# New rows' ids, clear of the game's, the Skyborne's (900000s, 9000s) and Wrath Remix's.
ID_SECTIONS = 920000
ID_SECTIONS_END = 969999
ID_OUTFIT = 9100
ID_HAIRGEO = 9100
ID_NAMEGEN = 91000
ID_VOCAL = 9100
ID_EMOTESOUND = 91000
ID_SEQUENCE = {VOIDELF: 9011, NIGHTBORNE: 9012}
ID_CAMERA = {VOIDELF: 9011, NIGHTBORNE: 9012}
ID_NARRATION = {VOIDELF: 60001, NIGHTBORNE: 60002}  # unused legacy-range sound IDs
ID_VOICE = 910100
ID_DISPLAY = {VOIDELF: (33001, 33002), NIGHTBORNE: (33003, 33004)}  # male, female looks
ID_MODEL = {VOIDELF: (3601, 3602), NIGHTBORNE: (3603, 3604)}
SPELLS = {"rift": 98711, "rift_open": 98712, "entropic": 98713, "entropic_buff": 98714, "chill": 98715, "calm": 98716,
          "cantrips": 98717, "pulse": 98718, "pulse_slow": 98719, "arcane_res": 98720, "affinity": 98721, "history": 98722}
NPC_RIFT = 900101
DISPLAY_RIFT = 9510                            # Creature\Spells\Creature_SpellPortal


class Dbc:
    def __init__(self, path):
        data = open(path, "rb").read()
        magic, self.count, self.fields, self.size, strings = struct.unpack("<4s4I", data[:20])
        assert magic == b"WDBC", path
        self.raw = [data[20 + i * self.size:20 + (i + 1) * self.size] for i in range(self.count)]
        self.text = data[20 + self.count * self.size:]
        self.rows = [list(struct.unpack(f"<{self.fields}I", r)) for r in self.raw] if self.size == self.fields * 4 else None

    def s(self, offset):
        end = self.text.index(b"\0", offset)
        return self.text[offset:end].decode("utf-8")


def f32(v):
    return struct.unpack("<f", struct.pack("<I", v))[0]


def i32(v):
    return struct.unpack("<i", struct.pack("<I", v & 0xFFFFFFFF))[0]


class Client:
    """The changes to the game's tables, a file for each (AlliedRacesPatch.cpp applies them)."""

    def __init__(self):
        self.files = {}
        self.types = {}

    def line(self, name, text):
        self.files.setdefault(name, []).append(text)

    def put(self, name, types, values):
        assert len(types) == len(values), (name, len(types), len(values))
        assert self.types.setdefault(name, types) == types, name
        self.line(name, "put\t" + "\t".join(esc(v) if t == "s" else repr(float(v)) if t == "f" else str(int(v) & 0xFFFFFFFF)
                                             for t, v in zip(types, values)))

    def put_raw(self, name, data):
        self.line(name, "putraw\t" + data.hex())

    def append_raw(self, name, data):
        self.line(name, "appendraw\t" + data.hex())

    def orfield(self, name, row_id, field, value):
        self.line(name, f"or\t{row_id}\t{field}\t{value & 0xFFFFFFFF}")

    def setfield(self, name, row_id, field, value):
        self.line(name, f"set\t{row_id}\t{field}\t{value & 0xFFFFFFFF}")

    def write(self, folder):
        os.makedirs(folder, exist_ok=True)
        for old in os.listdir(folder):
            if old.endswith(".txt"):
                os.remove(os.path.join(folder, old))
        for name, lines in sorted(self.files.items()):
            with open(os.path.join(folder, name + ".txt"), "w", newline="\n") as f:
                f.write(f"# {name}: the void elves' and the nightborne's changes (tools/build_data.py)\n")
                if name in self.types:
                    f.write(f"types\t{self.types[name]}\n")
                f.write("\n".join(lines) + "\n")


def esc(text):
    return str(text).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def sql_value(v, t):
    if t == "s":
        return "'" + str(v).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
    if t == "f":
        return repr(float(v))
    return str(i32(int(v)))


class Sql:
    def __init__(self):
        self.parts = []

    def comment(self, text):
        self.parts.append("\n-- " + text.replace("\n", "\n-- "))

    def raw(self, text):
        self.parts.append(text)

    def replace(self, table, columns, types, rows):
        if not rows:
            return
        cols = ", ".join(f"`{c}`" for c in columns)
        for i in range(0, len(rows), 200):
            chunk = rows[i:i + 200]
            values = ",\n".join("(" + ", ".join(sql_value(v, t) for v, t in zip(r, types)) + ")" for r in chunk)
            self.parts.append(f"REPLACE INTO `{table}` ({cols}) VALUES\n{values};")


def dbc_row_values(dbc, row, types):
    out = []
    for v, t in zip(row, types):
        out.append(dbc.s(v) if t == "s" else f32(v) if t == "f" else v)
    return out


def narration_sound(template, race):
    """One narration, with no weighted empty alternatives inherited from the template."""
    sound = list(template)
    sound[0], sound[2] = ID_NARRATION[race], f"{RACES[KEY[race]]['name']}FlyByNarration"
    sound[3:13] = [NARRATION[race]] + [""] * 9
    sound[13:23] = [1] + [0] * 9
    sound[23] = "Sound\\CinematicVoices"
    return sound


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    dbc_dir, world = sys.argv[1], sys.argv[2]
    D = lambda name: Dbc(os.path.join(dbc_dir, name + ".dbc"))
    client, sql = Client(), Sql()
    # Remap this client's rows, including HD tables whose IDs and textures differ from the server's.
    for old, new in MOVED.items():
        client.line("ChrRaces", f"copyrow\t{old}\t{new}")
        for table, field in (("CharSections", 1), ("CharHairGeosets", 1),
                             ("CreatureDisplayInfoExtra", 1), ("CharacterFacialHairStyles", 0)):
            client.line(table, f"remapfield\t{field}\t{old}\t{new}")
    sql.raw("-- The void elves and the nightborne (mod-allied-races): generated by tools/build_data.py from the\n"
            "-- game's own tables and data/retail/races.json; edit those, not this. Safe to run again: every row is\n"
            "-- replaced, every mask OR'd.")
    models = {race: {int(sex): m for sex, m in RACES[KEY[race]]["models"].items()} for race in (VOIDELF, NIGHTBORNE)}

    # Models and looks: a CreatureModelData row and a CreatureDisplayInfo row a model, copied from
    # the kin's (the blood elves' for the void elves, the night elves' for the nightborne).
    races = D("ChrRaces")
    rt = TYPES["chrraces_dbc"]
    race_rows = {r[0]: r for r in races.rows}
    displays, model_data = D("CreatureDisplayInfo"), D("CreatureModelData")
    display_rows = {r[0]: r for r in displays.rows}
    model_rows = {r[0]: r for r in model_data.rows}
    dt = "iiiifissssiiiiii"
    mt = "iisifiiffffiiifff" + "ffffff" + "fffff"
    sql.comment("Their models (CreatureModelData) and looks (CreatureDisplayInfo), from their kin's rows.")
    display_sql, model_sql = [], []
    for race in (VOIDELF, NIGHTBORNE):
        kin = race_rows[MODEL_FROM[race]]
        for sex in (0, 1):
            kin_display = display_rows[kin[4 + sex]]
            kin_model = model_rows[kin_display[1]]
            m = dbc_row_values(model_data, kin_model, mt)
            m[0] = ID_MODEL[race][sex]
            m[2] = models[race][sex]["folder"] + "\\" + models[race][sex]["name"] + ".mdx"
            client.put("CreatureModelData", mt, m)
            model_sql.append(m)
            d = dbc_row_values(displays, kin_display, dt)
            d[0] = ID_DISPLAY[race][sex]
            d[1] = ID_MODEL[race][sex]
            d[2] = 0                                        # no creature sound set
            d[3] = 0                                        # no extra: a player's look
            d[4] = DISPLAY_SCALE[race]
            d[12] = 0
            client.put("CreatureDisplayInfo", dt, d)
            display_sql.append(d)
    sql.replace("creaturemodeldata_dbc", [c for c in ["ID", "Flags", "ModelName", "SizeClass", "ModelScale", "BloodID", "FootprintTextureID",
                "FootprintTextureLength", "FootprintTextureWidth", "FootprintParticleScale", "FoleyMaterialID", "FootstepShakeSize",
                "DeathThudShakeSize", "SoundID", "CollisionWidth", "CollisionHeight", "MountHeight", "GeoBoxMinX", "GeoBoxMinY", "GeoBoxMinZ",
                "GeoBoxMaxX", "GeoBoxMaxY", "GeoBoxMaxZ", "WorldEffectScale", "AttachedEffectScale", "MissileCollisionRadius",
                "MissileCollisionPush", "MissileCollisionRaise"]], mt, model_sql)
    sql.replace("creaturedisplayinfo_dbc", ["ID", "ModelID", "SoundID", "ExtendedDisplayInfoID", "CreatureModelScale", "CreatureModelAlpha",
                "TextureVariation_1", "TextureVariation_2", "TextureVariation_3", "PortraitTextureName", "BloodLevel", "BloodID",
                "NPCSoundID", "ParticleColorID", "CreatureGeosetData", "ObjectEffectPackageID"], dt, display_sql)

    # Races ----------------------------------------------------------------------------------------
    sql.comment("The two races: the start race's faction, language and side; their own models, names and intros.")
    server_races = []
    for race in (VOIDELF, NIGHTBORNE):
        start = race_rows[START[race]]
        v = dbc_row_values(races, start, rt)
        v[0] = race
        v[1] = race_rows[BLOODELF][1]                   # flags: playable, can mount
        v[4], v[5] = ID_DISPLAY[race]
        v[6] = PREFIX[race]
        v[11] = models[race][0]["folder"].split("\\")[1]    # ClientFileString: Character\<this>\...
        v[12] = ID_SEQUENCE[race]
        for base in (14, 31, 48):
            for i in range(base, base + 16):
                v[i] = NAME[race] if i == base else ""
        v[65], v[66], v[67] = FEATURE_LABELS[race]      # the sliders' names: men's feature, women's, hair
        v[68] = 0                                       # any expansion
        client.put("ChrRaces", rt, v)
        server_races.append(v)
    sql.replace("chrraces_dbc", COLUMNS["chrraces_dbc"], rt, server_races)
    # Looks ----------------------------------------------------------------------------------------
    st = TYPES["charsections_dbc"]
    sections = D("CharSections")
    new_sections = []
    next_id = [ID_SECTIONS]

    def section(race, sex, kind, tex, flags, variation, colour):
        v = [next_id[0], race, sex, kind, tex[0], tex[1], tex[2], flags, variation, colour]
        next_id[0] += 1
        client.put("CharSections", st, v)
        new_sections.append(v)

    SKIN_FLAGS, FACE_FLAGS = 17, 1
    for race in (VOIDELF, NIGHTBORNE):
        for sex in (0, 1):
            m = models[race][sex]
            for s in m["skins"]:
                c = s["index"]
                section(race, sex, 0, [names.skin(m, c), "", ""], SKIN_FLAGS, 0, c)
                if s["pelvis"]:
                    section(race, sex, 4, [names.pelvis(m, c), names.torso(m, c) if s["torso"] else "", ""], SKIN_FLAGS, 0, c)
            for fi, face in enumerate(m["faces"]):
                for s in m["skins"]:
                    fid = names.face_file(m, face, s)
                    for eye in m["eyes"]:
                        v = names.face_variation(m, fi, eye["index"])
                        section(race, sex, 1, [names.face_lower(m, fid, eye), names.face_upper(m, eye), ""], FACE_FLAGS, v, s["index"])
            for h in m["hair"]:
                for hc in m["hair_colors"]:
                    section(race, sex, 3, [names.hair(m, hc["index"]), "", ""], SKIN_FLAGS, h["variation"], hc["index"])
    for r in sections.rows:
        if r[1] in MOVED:
            v = dbc_row_values(sections, r, st)
            v[1] = MOVED[r[1]]
            new_sections.append(v)
    sql.comment("Their looks: skins, faces (a face an eye colour), hair, underwear; the creatures' races 15 and 16 moved to 24 and 25.")
    sql.raw(f"DELETE FROM `charsections_dbc` WHERE `ID` BETWEEN {ID_SECTIONS} AND {ID_SECTIONS_END};\n")
    assert next_id[0] <= ID_SECTIONS_END, next_id[0]
    sql.replace("charsections_dbc", COLUMNS["charsections_dbc"], st, new_sections)

    # Hair shapes, features, names, voices (the game's tables only, but for the emotes' sounds).
    geo = D("CharHairGeosets")
    gid = ID_HAIRGEO
    for race in (VOIDELF, NIGHTBORNE):
        for sex in (0, 1):
            for h in models[race][sex]["hair"]:
                client.put("CharHairGeosets", "iiiiii", [gid, race, sex, h["variation"], h["geoset"], 0])
                gid += 1
    for race in (VOIDELF, NIGHTBORNE):
        for sex in (0, 1):
            for f in models[race][sex]["features"]:
                client.append_raw("CharacterFacialHairStyles", struct.pack("<8I", race, sex, f["variation"], *f["geosets"]))
    nid = ID_NAMEGEN
    for race in (VOIDELF, NIGHTBORNE):
        for n in RACES[KEY[race]]["names"]:
            client.put("NameGen", "isii", [nid, n["name"], race, n["sex"]])
            nid += 1

    # Voices: a SoundEntries row a sound kit (its WAVs, tools/package.py), named by VocalUISounds
    # (the race's words at the UI's moments) and EmotesTextSound (its emotes).
    sounds = D("SoundEntries")
    snt = TYPES["soundentries_dbc"]
    snd_rows = {r[0]: r for r in sounds.rows}
    emote_ids = set(r[0] for r in D("EmotesText").rows)
    sound_sql, emote_rows = [], []
    vid, eid, sid = ID_VOCAL, ID_EMOTESOUND, ID_VOICE
    vocal_template = dbc_row_values(sounds, snd_rows[9549], snt)      # BloodElfMale_InventoryFull
    emote_template = dbc_row_values(sounds, snd_rows[10025], snt)     # TextEmote BeF Bored
    for race in (VOIDELF, NIGHTBORNE):
        name = RACES[KEY[race]]["name"]
        kit_sound = {}

        def sound_for(kit, template, label):
            nonlocal sid
            if not kit["files"]:
                return 0
            if kit["id"] in kit_sound:
                return kit_sound[kit["id"]]
            row = list(template)
            row[0] = sid
            row[2] = f"{name} {label} {kit['id']}"
            files = [f"{name}_{kit['id']}_{n + 1}.mp3" for n in range(min(10, len(kit["files"])))]
            for i in range(10):
                row[3 + i] = files[i] if i < len(files) else ""
                row[13 + i] = 1 if i < len(files) else 0
            row[23] = f"Sound\\Character\\{name}"
            client.put("SoundEntries", snt, row)
            sound_sql.append(row)
            kit_sound[kit["id"]] = sid
            sid += 1
            return kit_sound[kit["id"]]

        for v in RACES[KEY[race]]["voices"]["vocal"]:
            ids = [sound_for(k, vocal_template, "Vocal") for k in v["kits"]]
            client.put("VocalUISounds", "iiiiiii", [vid, v["enum"], race, ids[0], ids[1], 0, 0])
            vid += 1
        for e in RACES[KEY[race]]["voices"]["emotes"]:
            if e["emote"] not in emote_ids:
                continue
            s = sound_for(e["kit"], emote_template, "Emote")
            if not s:
                continue
            row = [eid, e["emote"], race, e["sex"], s]
            client.put("EmotesTextSound", "iiiii", row)
            emote_rows.append(row)
            eid += 1
    sql.comment("Their voices (the game's sound kits of today, as MP3s in the patch) and their emotes.")
    sql.replace("soundentries_dbc", COLUMNS["soundentries_dbc"], snt, sound_sql)
    sql.replace("emotestextsound_dbc", COLUMNS["emotestextsound_dbc"], "iiiii", emote_rows)

    # Classes and starting gear.
    for race in (VOIDELF, NIGHTBORNE):
        for cls in CLASSES:
            client.append_raw("CharBaseInfo", bytes([race, cls]))
    outfits = D("CharStartOutfit")
    oid, outfit_rows = ID_OUTFIT, []
    for raw in outfits.raw:
        orace, cls, sex, outfit = raw[4], raw[5], raw[6], raw[7]
        for new in (VOIDELF, NIGHTBORNE):
            if cls in CLASSES and TEMPLATE[new][cls] == orace:
                data = struct.pack("<I", oid) + bytes([new, cls, sex, outfit]) + raw[8:]
                client.put_raw("CharStartOutfit", data)
                ints = struct.unpack(f"<{len(raw[8:]) // 4}I", raw[8:])
                outfit_rows.append([oid, new, cls, sex, outfit] + list(ints))
                oid += 1
    sql.comment("Their classes' starting gear: the start race's for the class (or the nearest race's).")
    sql.replace("charstartoutfit_dbc", COLUMNS["charstartoutfit_dbc"], TYPES["charstartoutfit_dbc"], outfit_rows)

    # Skills and abilities by race, standing --------------------------------------------------------
    lines = D("SkillLine")
    racial_lines, languages = set(), {}
    for r in lines.rows:
        name = lines.s(r[3])
        if r[1] == 9 and "racial" in name.lower():
            racial_lines.add(r[0])
        if r[1] == 10:
            languages[r[0]] = name
    COMMON, ORCISH = 98, 109

    def faction_bits(mask, line):
        add = 0
        if line in languages:
            if line == COMMON:
                add |= BIT[VOIDELF]
            if line == ORCISH:
                add |= BIT[NIGHTBORNE]
            return add
        if line in racial_lines:
            return 0
        if mask & ALLIANCE_MASK:
            add |= BIT[VOIDELF]
        if mask & HORDE_MASK:
            add |= BIT[NIGHTBORNE]
        return add

    def ors(table, dbc, field_mask, field_line, sql_table):
        rows = []
        for r in dbc.rows:
            mask = r[field_mask]
            if mask == 0 or mask & 0x7FFFFFFF == 0x7FFFFFFF:
                continue
            add = faction_bits(mask, r[field_line])
            if add and (mask | add) != mask:
                client.orfield(table, r[0], field_mask, add)
                v = list(r)
                v[field_mask] = mask | add
                rows.append(v)
        if rows:
            cols = COLUMNS[sql_table]
            body = ",\n".join("(" + ", ".join(str(i32(x)) for x in v) + ")" for v in rows)
            sql.raw(f"INSERT INTO `{sql_table}` ({', '.join(f'`{c}`' for c in cols)}) VALUES\n{body}\n"
                    f"ON DUPLICATE KEY UPDATE `RaceMask` = `RaceMask` | VALUES(`RaceMask`);")

    sql.comment("Skills and abilities open to either side's races are open to theirs (the racials and languages aside).")
    ors("SkillRaceClassInfo", D("SkillRaceClassInfo"), 2, 1, "skillraceclassinfo_dbc")
    ors("SkillLineAbility", D("SkillLineAbility"), 3, 1, "skilllineability_dbc")

    sql.comment("Standing: the start race's (the Exodar's for the void elves, Undercity's for the nightborne).")
    factions = D("Faction")
    ft = TYPES["faction_dbc"]
    faction_rows = []
    for r in factions.rows:
        v = list(r)
        for slot in range(4):
            mask = r[2 + slot]
            if mask & 0x7FFFFFFF == 0x7FFFFFFF:
                continue
            for race in (VOIDELF, NIGHTBORNE):
                if mask & (1 << (START[race] - 1)):
                    v[2 + slot] |= BIT[race]
        if v != list(r):
            for slot in range(4):
                if v[2 + slot] != r[2 + slot]:
                    client.orfield("Faction", r[0], 2 + slot, v[2 + slot] & ~r[2 + slot])
            faction_rows.append(dbc_row_values(factions, v, ft))
    sql.replace("faction_dbc", COLUMNS["faction_dbc"], ft, faction_rows)

    # The intros: the start race's flight, their own narration (the user's MP3s) -----------------
    seqs, cams = D("CinematicSequences"), D("CinematicCamera")
    seq_rows = {r[0]: r for r in seqs.rows}
    cam_rows = {r[0]: r for r in cams.rows}
    narration_rows, cam_sql, seq_sql = [], [], []
    for race in (VOIDELF, NIGHTBORNE):
        sound = narration_sound(dbc_row_values(sounds, snd_rows[3840], snt), race)  # HumanFlyByNarration
        client.put("SoundEntries", snt, sound)
        narration_rows.append(sound)
        seq = seq_rows[race_rows[START[race]][12]]
        cam = dbc_row_values(cams, cam_rows[seq[2]], TYPES["cinematiccamera_dbc"])
        cam[0], cam[2] = ID_CAMERA[race], ID_NARRATION[race]
        client.put("CinematicCamera", TYPES["cinematiccamera_dbc"], cam)
        cam_sql.append(cam)
        s = [ID_SEQUENCE[race], seq[1], ID_CAMERA[race]] + [0] * 7
        client.put("CinematicSequences", "i" * 10, s)
        seq_sql.append(s)
    sql.comment("The intros: the start race's flight over its home, with each race's own narration.")
    sql.replace("soundentries_dbc", COLUMNS["soundentries_dbc"], snt, narration_rows)
    sql.replace("cinematiccamera_dbc", COLUMNS["cinematiccamera_dbc"], TYPES["cinematiccamera_dbc"], cam_sql)
    sql.replace("cinematicsequences_dbc", COLUMNS["cinematicsequences_dbc"], TYPES["cinematicsequences_dbc"], seq_sql)

    # The racials -----------------------------------------------------------------------------------
    spells = D("Spell")
    spt = TYPES["spell_dbc"]
    by_id = {r[0]: r for r in spells.rows}
    C = {n: i for i, n in enumerate(COLUMNS["spell_dbc"])}

    def spell(template, sid, name, rank, description, aura_text, **fields):
        v = dbc_row_values(spells, by_id[template], spt)
        v[0] = sid
        for k in ("Name", "NameSubtext", "Description", "AuraDescription"):
            for i in range(16):
                v[C[f"{k}_Lang_enUS"] + i] = ""
        v[C["Name_Lang_enUS"]] = name
        v[C["NameSubtext_Lang_enUS"]] = rank
        v[C["Description_Lang_enUS"]] = description
        v[C["AuraDescription_Lang_enUS"]] = aura_text
        for k, value in fields.items():
            v[C[k]] = value
        client.put("Spell", spt, v)
        return v

    # Templates: 130 (Slow Fall: an instant self spell), 20550 (Endurance) and 20557 (Beast
    # Slaying), the Tauren's and Trolls' passives; 54710 (MOLL-E, the portable mailbox); 20549
    # (War Stomp, around the caster).
    no_cost = dict(Reagent_1=0, ReagentCount_1=0, ManaCostPct=0, SpellClassSet=0, SpellClassMask_1=0,
                   SpellClassMask_2=0, SpellClassMask_3=0, BaseLevel=1, SpellLevel=1, RangeIndex=1,
                   ImplicitTargetA_1=1, CastingTimeIndex=1, StartRecoveryCategory=133, StartRecoveryTime=1500,
                   SchoolMask=32, Attributes=0x10, AttributesEx=0, AttributesEx6=0, InterruptFlags=0, DispelType=0)
    ALL_SCHOOLS, MAGIC_SCHOOLS, SHADOW, ARCANE = 127, 126, 32, 64
    spell_rows = [
        spell(130, SPELLS["rift"], "Spatial Rift", "Racial",
              "Tear a rift in space up to 15 yards ahead. Use this ability again within 6 sec to teleport through the rift. 3 min cooldown once the rift closes.", "",
              SpellIconID=87, Effect_1=3, EffectAura_1=0, EffectBasePoints_1=0, EffectDieSides_1=0, SpellVisualID_1=0, DurationIndex=0,
              RecoveryTime=0, **no_cost),
        spell(130, SPELLS["rift_open"], "Spatial Rift", "",
              "A rift stands open. Use Spatial Rift again to teleport through it.", "A rift stands open. Use Spatial Rift again to teleport through it.",
              SpellIconID=87, Effect_1=6, EffectAura_1=4, EffectBasePoints_1=0, EffectDieSides_1=0, SpellVisualID_1=0, DurationIndex=32,
              **dict(no_cost, Attributes=0, StartRecoveryCategory=0, StartRecoveryTime=0)),
        spell(20550, SPELLS["entropic"], "Entropic Embrace", "Racial Passive",
              "Your abilities have a chance to empower you with the essence of the Void, increasing your damage and healing by 5% for 12 sec.", "",
              SpellIconID=164, EffectAura_1=42, EffectBasePoints_1=0, EffectDieSides_1=0, EffectTriggerSpell_1=SPELLS["entropic_buff"],
              ProcTypeMask=0x55554, ProcChance=10, SchoolMask=SHADOW),
        spell(20550, SPELLS["entropic_buff"], "Entropic Embrace", "",
              "Damage and healing done increased by 5%.", "Damage and healing done increased by 5%.",
              SpellIconID=164, EffectAura_1=79, EffectBasePoints_1=4, EffectDieSides_1=1, EffectMiscValue_1=ALL_SCHOOLS,
              Effect_2=6, EffectAura_2=136, EffectBasePoints_2=4, EffectDieSides_2=1, ImplicitTargetA_2=1,
              DurationIndex=29, Attributes=0, SchoolMask=SHADOW),
        spell(20557, SPELLS["chill"], "Chill of Night", "Racial Passive",
              "Reduces Shadow damage taken by 1%.", "",
              SpellIconID=207, EffectAura_1=87, EffectBasePoints_1=-2, EffectDieSides_1=1, EffectMiscValue_1=SHADOW),
        spell(20557, SPELLS["calm"], "Preternatural Calm", "Racial Passive",
              "Your spell casts are not delayed by taking damage.", "",
              SpellIconID=2014, EffectAura_1=88, EffectBasePoints_1=99, EffectDieSides_1=1, EffectMiscValue_1=ALL_SCHOOLS),
        spell(54710, SPELLS["cantrips"], "Cantrips", "Racial",
              "Conjure up an Eldritch Grimoire, allowing you mail access for 10 min.", "",
              SpellIconID=2030, RecoveryTime=600000, CastingTimeIndex=5, ManaCost=0, ManaCostPct=0, Reagent_1=0, ReagentCount_1=0,
              Attributes=0x10, SchoolMask=SHADOW, BaseLevel=1, SpellLevel=1),
        spell(20549, SPELLS["pulse"], "Arcane Pulse", "Racial",
              "Deals Arcane damage to nearby enemies within 8 yards and reduces their movement speed by 50% for 12 sec.", "",
              SpellIconID=2294, Effect_1=2, EffectAura_1=0, EffectBasePoints_1=19, EffectDieSides_1=1, EffectRealPointsPerLevel_1=2.0,
              ImplicitTargetA_1=22, ImplicitTargetB_1=15, EffectRadiusIndex_1=14, EffectMechanic_1=0, Mechanic=0,
              Effect_2=64, EffectAura_2=0, EffectBasePoints_2=0, EffectDieSides_2=0, EffectTriggerSpell_2=SPELLS["pulse_slow"],
              ImplicitTargetA_2=1, ImplicitTargetB_2=0, RecoveryTime=180000, DurationIndex=0, SchoolMask=ARCANE, DispelType=0,
              CastingTimeIndex=1, StartRecoveryCategory=133, StartRecoveryTime=1500, Attributes=0x10),
        spell(20549, SPELLS["pulse_slow"], "Arcane Pulse", "",
              "Movement speed reduced by 50%.", "Movement speed reduced by 50%.",
              SpellIconID=2294, Effect_1=6, EffectAura_1=33, EffectBasePoints_1=-51, EffectDieSides_1=1, EffectMiscValue_1=0,
              ImplicitTargetA_1=22, ImplicitTargetB_1=15, EffectRadiusIndex_1=14, EffectMechanic_1=11, Mechanic=11,
              RecoveryTime=0, DurationIndex=29, SchoolMask=ARCANE, DispelType=1, Attributes=0, StartRecoveryCategory=0, StartRecoveryTime=0),
        spell(20557, SPELLS["arcane_res"], "Arcane Resistance", "Racial Passive",
              "Reduces Arcane damage taken by 1%.", "",
              SpellIconID=1918, EffectAura_1=87, EffectBasePoints_1=-2, EffectDieSides_1=1, EffectMiscValue_1=ARCANE),
        spell(20557, SPELLS["affinity"], "Magical Affinity", "Racial Passive",
              "Increases magical damage dealt by 1%.", "",
              SpellIconID=2120, EffectAura_1=79, EffectBasePoints_1=0, EffectDieSides_1=1, EffectMiscValue_1=MAGIC_SCHOOLS),
        spell(20557, SPELLS["history"], "Ancient History", "Racial Passive",
              "Inscription skill increased by 15.", "",
              SpellIconID=3267, EffectAura_1=98, EffectBasePoints_1=14, EffectDieSides_1=1, EffectMiscValue_1=773),
    ]
    sql.comment("Their racials: Spatial Rift (with its open-rift mark), Entropic Embrace (with its buff), Chill of Night,\n"
                "Preternatural Calm, Cantrips; Arcane Pulse (with its slow), Arcane Resistance, Magical Affinity, Ancient History.")
    sql.replace("spell_dbc", COLUMNS["spell_dbc"], spt, spell_rows)
    sql.raw(f"DELETE FROM `spell_proc` WHERE `SpellId` = {SPELLS['entropic']};\n"
            f"INSERT INTO `spell_proc` (`SpellId`, `SchoolMask`, `SpellFamilyName`, `SpellFamilyMask0`, `SpellFamilyMask1`, `SpellFamilyMask2`, "
            f"`ProcFlags`, `SpellTypeMask`, `SpellPhaseMask`, `HitMask`, `AttributesMask`, `DisableEffectsMask`, `ProcsPerMinute`, `Chance`, `Cooldown`, `Charges`) VALUES\n"
            f"({SPELLS['entropic']}, 0, 0, 0, 0, 0, 0x55554, 0, 2, 0, 0, 0, 0, 10, 30000, 0);")
    sql.raw(f"DELETE FROM `spell_script_names` WHERE `spell_id` IN ({SPELLS['rift']}, {SPELLS['rift_open']});\n"
            f"INSERT INTO `spell_script_names` (`spell_id`, `ScriptName`) VALUES ({SPELLS['rift']}, 'spell_allied_spatial_rift'), "
            f"({SPELLS['rift_open']}, 'spell_allied_spatial_rift_open');")
    sql.comment("Spatial Rift's rift: a creature that stands where the rift is torn (AlliedRacesSpells.cpp).")
    sql.raw(f"DELETE FROM `creature_template` WHERE `entry` = {NPC_RIFT};\n"
            f"INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `unit_flags`, `type`, `type_flags`, "
            f"`MovementType`, `flags_extra`, `AIName`, `ScriptName`, `VerifiedBuild`) VALUES\n"
            f"({NPC_RIFT}, 'Spatial Rift', '', 1, 1, 35, 0, 33554434, 10, 0, 0, 130, '', '', 12340);\n"
            f"DELETE FROM `creature_template_model` WHERE `CreatureID` = {NPC_RIFT};\n"
            f"INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES\n"
            f"({NPC_RIFT}, 0, {DISPLAY_RIFT}, 1, 1, 12340);")

    # The world database: starts, skills, stats, quests, standing ----------------------------------
    sql.comment("Where they start (their start race's place), for every class (the death knights' at Ebon Hold).")
    cols, pci = sqlrows.table(os.path.join(world, "playercreateinfo.sql"))
    place = {(int(r["race"]), int(r["class"])): r for r in pci}
    out = []
    for race in (VOIDELF, NIGHTBORNE):
        for cls in CLASSES:
            p = place.get((START[race], cls)) or place.get((TEMPLATE[race][cls], cls)) or place[(START[race], WARRIOR)]
            out.append([race, cls, p["map"], p["zone"], p["position_x"], p["position_y"], p["position_z"], p["orientation"]])
    sql.raw("REPLACE INTO `playercreateinfo` (`race`, `class`, `map`, `zone`, `position_x`, `position_y`, `position_z`, `orientation`) VALUES\n"
            + ",\n".join("(" + ", ".join(str(x) for x in r) + ")" for r in out) + ";")

    racial_spells = set()
    for r in D("SkillLineAbility").rows:
        if r[1] in racial_lines:
            racial_spells.add(r[2])
    sql.comment("Their action bars: the class's start race's, without that race's racials.")
    cols, acts = sqlrows.table(os.path.join(world, "playercreateinfo_action.sql"))
    out = []
    for race in (VOIDELF, NIGHTBORNE):
        for cls in CLASSES:
            for r in acts:
                if int(r["race"]) == TEMPLATE[race][cls] and int(r["class"]) == cls and not (int(r["type"]) == 0 and int(r["action"]) in racial_spells):
                    out.append([race, cls, r["button"], r["action"], r["type"]])
    sql.raw(f"DELETE FROM `playercreateinfo_action` WHERE `race` IN ({VOIDELF}, {NIGHTBORNE});")
    sql.raw("INSERT INTO `playercreateinfo_action` (`race`, `class`, `button`, `action`, `type`) VALUES\n"
            + ",\n".join("(" + ", ".join(str(x) for x in r) + ")" for r in out) + ";")

    sql.comment("Their skills: their side's weapons and armour; Common or Orcish; no other race's racials.")
    cols, skills = sqlrows.table(os.path.join(world, "playercreateinfo_skills.sql"))
    for r in skills:
        mask, skill = int(r["raceMask"]), int(r["skill"])
        if mask == 0:
            continue
        add = faction_bits(mask, skill)
        if add:
            sql.raw(f"UPDATE `playercreateinfo_skills` SET `raceMask` = `raceMask` | {add} WHERE (`raceMask` & {mask}) = {mask} AND `classMask` = {r['classMask']} AND `skill` = {skill};")

    sql.comment("Faction languages are explicitly learned even after another race module changed the masks.")
    sql.raw("INSERT IGNORE INTO `playercreateinfo_spell_custom` (`racemask`, `classmask`, `Spell`, `Note`) VALUES\n"
            "(16384, 0, 668, 'Void Elf - Common'),\n(32768, 0, 669, 'Nightborne - Orcish');")

    sql.comment("Their racials, learned at creation.")
    sql.raw(f"DELETE FROM `playercreateinfo_spell_custom` WHERE `Spell` BETWEEN {min(SPELLS.values())} AND {max(SPELLS.values())};")
    custom = [(BIT[VOIDELF], SPELLS["rift"], "Void Elf - Spatial Rift"), (BIT[VOIDELF], SPELLS["entropic"], "Void Elf - Entropic Embrace"),
              (BIT[VOIDELF], SPELLS["chill"], "Void Elf - Chill of Night"), (BIT[VOIDELF], SPELLS["calm"], "Void Elf - Preternatural Calm"),
              (BIT[VOIDELF], SPELLS["cantrips"], "Void Elf - Cantrips"),
              (BIT[NIGHTBORNE], SPELLS["pulse"], "Nightborne - Arcane Pulse"), (BIT[NIGHTBORNE], SPELLS["arcane_res"], "Nightborne - Arcane Resistance"),
              (BIT[NIGHTBORNE], SPELLS["affinity"], "Nightborne - Magical Affinity"), (BIT[NIGHTBORNE], SPELLS["history"], "Nightborne - Ancient History")]
    sql.raw("INSERT INTO `playercreateinfo_spell_custom` (`racemask`, `classmask`, `Spell`, `Note`) VALUES\n"
            + ",\n".join(f"({m}, 0, {s}, '{n}')" for m, s, n in custom) + ";")

    sql.comment("Their stats: their kin's (the blood elves' for the void elves, the night elves' for the nightborne).")
    cols, stats = sqlrows.table(os.path.join(world, "player_race_stats.sql"))
    by_race = {int(r["Race"]): r for r in stats}
    sql.raw("REPLACE INTO `player_race_stats` (`Race`, `Strength`, `Agility`, `Stamina`, `Intellect`, `Spirit`) VALUES\n"
            + ",\n".join(f"({race}, " + ", ".join(by_race[STATS_FROM[race]][k] for k in ("Strength", "Agility", "Stamina", "Intellect", "Spirit")) + ")"
                         for race in (VOIDELF, NIGHTBORNE)) + ";")

    sql.comment("Quests, items and conditions for their start race are theirs too (the Draenei's for the void elves, the\n"
                "Forsaken's for the nightborne), but for the start quests cloned for them (tools/quests.py), which stay the\n"
                "start race's alone; and those of the race their classes' starts come from where the start race hasn't the\n"
                "class (the Humans' rogues' and warlocks', the Blood Elves' hunters').")
    for race in (VOIDELF, NIGHTBORNE):
        b = BIT[race]
        s = 0
        for r in {START[race]} | set(TEMPLATE[race].values()):
            s |= 1 << (r - 1)
        cloned = CLONED.get(KEY[race], [])
        keep = f" AND `ID` NOT IN ({', '.join(str(q) for q in cloned)})" if cloned else ""
        sql.raw(f"UPDATE `quest_template` SET `AllowableRaces` = `AllowableRaces` | {b} WHERE `AllowableRaces` & {s} AND `AllowableRaces` & 0x7FFFFFFF <> 0x7FFFFFFF{keep};")
        sql.raw(f"UPDATE `item_template` SET `AllowableRace` = `AllowableRace` | {b} WHERE `AllowableRace` <> -1 AND `AllowableRace` & {s} AND `AllowableRace` & 0x7FFFFFFF <> 0x7FFFFFFF;")
        sql.raw(f"UPDATE `conditions` SET `ConditionValue1` = `ConditionValue1` | {b} WHERE `ConditionTypeOrReference` = 16 AND `ConditionValue1` & {s};")

    # Write -----------------------------------------------------------------------------------------
    with open(os.path.join(MODULE, "data", "sql", "db-world", "allied_races_world.sql"), "w", newline="\n") as f:
        f.write("\n".join(sql.parts) + "\n")
    client.write(os.path.join(MODULE, "data", "client", "dbc"))
    print(f"{len(new_sections)} looks, {len(client.files)} tables, {len(spell_rows)} spells, {len(sound_sql)} voice sounds, {len(emote_rows)} emotes")


if __name__ == "__main__":
    main()
