"""The starting areas' C++ scripts (the module's src/WorgenGoblinGilneas.cpp and the rest: CPP's ported
under CPP's own names, and the ones written here): which of CPP's script names the world keeps, what
the scripts name (their NPC_, OBJECT_, SPELL_, QUEST_, PATH_, BROADCAST_ and CINEMATIC_ constants,
Cataclysm's ids, brought into the world with the rest), and the header that turns Cataclysm's ids into the realm's
(src/WorgenGoblinZonesIds.h, written here).
"""
import os
import re

import db

from build_tables import MODULE, game_path
from quests import STAND_INS

SOURCES = os.path.join(MODULE, "src")
# The module's files with the starting areas' scripts.
SCRIPT_FILES = ("WorgenGoblinGilneas.cpp", "WorgenGoblinGilneas.h")
CONSTANT = re.compile(r"\b(NPC|OBJECT|SPELL|QUEST|PATH|BROADCAST|CINEMATIC)_\w+\s*=\s*(\d+)")
REGISTERED = re.compile(r"Register(?:CreatureAI|GameObjectAI|SpellScript|SpellAndAuraScriptPair)\((\w+)|new (\w+)\(\)")
IDS_HEADER = os.path.join(SOURCES, "WorgenGoblinZonesIds.h")


def read_sources(folder, names):
    text = ""
    for name in names:
        path = os.path.join(folder, name)
        if os.path.exists(path):
            text += open(path, encoding="utf-8").read()
    return text


class ScriptsMixin:
    """For WorldBuilder: self.need_creature, self.need_object, self.spell, self.quest_id, self.paths_needed."""

    def ported(self):
        """CPP's script names the module has, and the ids its scripts name, by kind."""
        if "ported" not in self.t:
            text = read_sources(SOURCES, SCRIPT_FILES)
            names = {a or b for a, b in REGISTERED.findall(text)}
            ids = {}
            for kind, value in CONSTANT.findall(text):
                ids.setdefault(kind, set()).add(int(value))
            self.t["ported"] = (names, ids)
        return self.t["ported"]

    def core_scripts(self):
        """The script names AzerothCore's own scripts register (a CPP name it has is kept: the
        training dummy's, the chicken's)."""
        if "core_scripts" not in self.t:
            names = set()
            for root, _, files in os.walk(os.path.join(self.core, "src", "server", "scripts")):
                for f in files:
                    if f.endswith(".cpp"):
                        text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
                        names |= {a or b for a, b in REGISTERED.findall(text)}
            self.t["core_scripts"] = names
        return self.t["core_scripts"]

    def script_name(self, name, kind, cata_id):
        """CPP's script name kept when there's code for it (the module's or AzerothCore's)."""
        if not name:
            return ""
        if name in self.ported()[0] or name in self.core_scripts():
            return name
        self.drop(f"{kind} script {name}", cata_id)
        return ""

    def need_scripted(self):
        """What the ported scripts name, brought in."""
        _, ids = self.ported()
        for e in ids.get("NPC", ()):
            self.need_creature(e)
        for e in ids.get("OBJECT", ()):
            self.need_object(e)
        for s in ids.get("SPELL", ()):
            self.spell(s)
        for q in ids.get("QUEST", ()):
            self.quest_id(q)
        for b in ids.get("BROADCAST", ()):
            self.broadcast(b)
        for c in ids.get("CINEMATIC", ()):
            self.cinematic(c)
        self.paths_needed |= ids.get("PATH", set())

    def cinematic(self, cata_id):
        """A camera flight of Cataclysm's (CinematicSequences, its cameras and their files) as new rows."""
        key = ("CinematicSequences", cata_id)
        if key in self.done:
            return self.done[key]
        seq = self.by_id("CinematicSequences").get(cata_id)
        our = 0
        if seq is None:
            self.drop("cinematic", cata_id)
        else:
            our = self.ids.new("CinematicSequences", cata_id)
            values = {"ID": our, "SoundID": self.sound(seq["SoundID"])}
            for n in range(8):
                values[f"Camera_{n}"] = self.cinematic_camera(int(seq[f"Camera_{n}"]))
            self.out.add("CinematicSequences", values)
        self.done[key] = our
        return our

    def cinematic_camera(self, cata_id):
        if cata_id <= 0:
            return 0
        key = ("CinematicCamera", cata_id)
        if key in self.done:
            return self.done[key]
        cam = self.by_id("CinematicCamera").get(cata_id)
        our = 0
        if cam is None or int(cam["FileDataID"]) not in self.names:
            self.drop("cinematic camera", cata_id)
        else:
            path = self.file(cam["FileDataID"], "model")
            our = self.ids.new("CinematicCamera", cata_id)
            self.out.add("CinematicCamera", {
                "ID": our, "Model": game_path(path)[:-3] + ".mdx", "SoundID": self.sound(cam["SoundID"]),
                "Origin_0": float(cam["Origin_0"]), "Origin_1": float(cam["Origin_1"]), "Origin_2": float(cam["Origin_2"]),
                "OriginFacing": float(cam["OriginFacing"])})
        self.done[key] = our
        return our

    def cast_credits(self):
        """The quests whose objective is casting a spell (Cataclysm's class quests: Charge on a
        training dummy, a heal on a wounded guard; the objective's creature is the credit's, "Spell
        Practice Credit"). 3.3.5a's quests haven't the kind: the module's code counts the cast.
        [(quest, credit creature, spell)], the realm's ids; a spell 3.3.5a's class hasn't at the start,
        its stand-in (quests.STAND_INS)."""
        rows = db.rows(db.CPP_DB, f"SELECT ID, RequiredSpell, RequiredNpcOrGo1 FROM quest_template "
                                  f"WHERE ID IN ({db.ids_in(self.quests_seen)}) AND RequiredSpell > 0 AND RequiredNpcOrGo1 > 0")
        out = []
        for r in rows:
            stand_in = STAND_INS.get(int(r["RequiredSpell"]))
            spell = stand_in[0] if stand_in else self.spell(r["RequiredSpell"])
            if spell:
                self.need_creature(r["RequiredNpcOrGo1"])
                out.append((self.ENTRY + int(r["ID"]), self.creature_entry(r["RequiredNpcOrGo1"]), spell))
        return sorted(out)

    def write_ids_header(self, phases):
        """src/WorgenGoblinZonesIds.h: Cataclysm's ids as the realm's, for the scripts."""
        spells = sorted((c, o) for c, o in self.spell_ids.items() if o)
        credits = self.credits
        lines = [
            "// SPDX-License-Identifier: GPL-3.0-or-later",
            "// Written by tools/zones/build_world.py (scripts.py): Cataclysm's ids of the starting areas as the",
            "// realm's, for their scripts. Made again with the rest of data/zones; not edited by hand.",
            "#pragma once",
            "",
            "#include \"Define.h\"",
            "",
            "namespace WorgenGoblinZones",
            "{",
            f"    constexpr uint32 ENTRY = {self.ENTRY}, GUID = {self.GUID}, PATH = {self.PATH};",
            "",
            "    // A creature's, an object's or a quest's id (Cataclysm's, from ENTRY).",
            "    constexpr uint32 Id(uint32 cata) { return ENTRY + cata; }",
            "    // A waypoint path's (CPP's, from PATH).",
            "    constexpr uint32 Path(uint32 cata) { return PATH + cata; }",
            "",
            "    // A spell's (3.3.5a's own where it has the same; 0 for one that isn't in the realm).",
            "    constexpr uint32 SpellId(uint32 cata)",
            "    {",
            "        switch (cata)",
            "        {",
        ]
        lines += [f"            case {c}: return {o};" for c, o in spells]
        lines += [
            "            default: return 0;",
            "        }",
            "    }",
            "",
            "    // A camera flight's (CinematicSequences; 0 for one that isn't in the realm).",
            "    constexpr uint32 Cinematic(uint32 cata)",
            "    {",
            "        switch (cata)",
            "        {",
        ]
        cinematics = sorted((k[1], o) for k, o in self.done.items() if k[0] == "CinematicSequences" and o)
        lines += [f"            case {c}: return {o};" for c, o in cinematics]
        lines += [
            "            default: return 0;",
            "        }",
            "    }",
            "",
            "    // A quest whose objective is casting a spell (WorgenGoblinZones.cpp counts the casts).",
            "    struct CastCredit { uint32 quest, creature, spell; };",
            "    constexpr CastCredit CAST_CREDITS[] =",
            "    {",
        ]
        lines += [f"        {{ {q}, {c}, {sp} }}," for q, c, sp in credits]
        lines += [
            "    };",
            "",
            "    // A phase of Cataclysm's as its bit in the phase mask (0 for one the starting areas haven't).",
            "    constexpr uint32 PhaseMask(uint32 phase)",
            "    {",
            "        switch (phase)",
            "        {",
        ]
        lines += [f"            case {p}: return 0x{1 << i:X};" for i, p in enumerate(phases)]
        lines += [
            "            default: return 0;",
            "        }",
            "    }",
            "}",
            "",
        ]
        with open(IDS_HEADER, "w", newline="\n") as f:
            f.write("\n".join(lines))
