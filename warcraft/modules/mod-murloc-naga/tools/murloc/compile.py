# SPDX-License-Identifier: GPL-3.0-or-later
"""The murlocs' questline, compiled: tools/murloc/ch*.py (written as tools/murloc/SCHEMA.md says)
made the world database's rows and the game's table rows, checked as it goes.

    import compile as S
    story = S.Story(S.load(), world, dbc, ground)    # world(table) -> rows of the core's dumps,
    tables, client = story.build()                   # dbc(name) -> a DBC, ground(map, x, y) -> z
    sql = S.sql(tables)                              # the SQL (every row the module's own, replaced)

Words: a murloc's are run through Blizzard's Nerglish translator (tools/nerglish.py); another race's
are garbled as the game garbles a language one doesn't know (garble.py), as quest and gossip
windows show text as it's written. The goal line of each quest stays English.

What the scenes do (SCHEMA.md's beats) is SmartAI: a beat list is a timed action list of the creature
it's said by; a beat by someone else is that one's own list, started by data the first sets on it."""
import glob
import importlib.util
import math
import os
import re
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import common as K  # noqa: E402
import nerglish  # noqa: E402
from garble import Garbler  # noqa: E402


class StoryError(Exception):
    pass


def load(folder=HERE):
    """The chapters, in order (ch1_*.py, ch2_*.py ...): each a dict of its file's names."""
    out = []
    for path in sorted(glob.glob(os.path.join(folder, "ch*.py")), key=lambda p: int(re.match(r"ch(\d+)", os.path.basename(p)).group(1))):
        spec = importlib.util.spec_from_file_location("murloc_" + os.path.basename(path)[:-3], path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        out.append({k: getattr(mod, k) for k in ("CHAPTER", "PLACES", "NPCS", "OBJECTS", "ITEMS", "QUESTS", "TOWNS") if hasattr(mod, k)})
    return out


# SmartAI's ids (the core's SmartScriptMgr.h)
EV_UPDATE_OOC, EV_OOC_LOS, EV_ACCEPTED_QUEST, EV_REWARD_QUEST, EV_DATA_SET, EV_GOSSIP_SELECT = 1, 10, 19, 20, 38, 62
EV_UPDATE_IC = 0
A_TALK, A_SOUND, A_EMOTE, A_CAST, A_SUMMON, A_AREA_EXPLORED, A_EMOTE_STATE, A_FOLLOW = 1, 4, 5, 11, 12, 15, 17, 29
A_KILLED_MONSTER, A_DESPAWN, A_SET_DATA, A_ADD_ITEM, A_REMOVE_ITEM, A_SET_RUN, A_TELEPORT = 33, 41, 45, 56, 57, 59, 62
A_ORIENTATION, A_MOVE_TO, A_CLOSE_GOSSIP, A_ACTIONLIST, A_JUMP, A_MUSIC = 66, 69, 72, 80, 97, 216
T_SELF, T_INVOKER, T_POSITION, T_CLOSEST_CREATURE = 1, 7, 8, 19
CHAT = {"say": 12, "yell": 14, "whisper": 15, "emote_text": 16}
# Quest statuses as bits (spell_area's quest_start_status / quest_end_status)
REWARDED, IN_LOG = 1 << 6, (1 << 1) | (1 << 3)

NPCFLAG = {"gossip": 1, "quest": 2, "trainer": 16, "class_trainer": 32, "profession_trainer": 64, "vendor": 128,
           "repair": 4096, "innkeeper": 65536}
STAT = {"agi": 3, "str": 4, "int": 5, "spi": 6, "sta": 7, "def": 12, "dodge": 13, "parry": 14, "hit": 31, "crit": 32,
        "ap": 38, "sp": 45, "mp5": 43, "haste": 36, "expertise": 37, "armor_pen": 44}
WEAPONS = {  # kind: (subclass, inventory type)
    "axe": (0, 13), "axe2h": (1, 17), "bow": (2, 15), "gun": (3, 26), "mace": (4, 13), "mace2h": (5, 17),
    "polearm": (6, 17), "spear": (6, 17), "sword": (7, 13), "sword2h": (8, 17), "staff": (10, 17), "fist": (13, 13),
    "dagger": (15, 13), "thrown": (16, 25), "crossbow": (18, 26), "wand": (19, 26), "fishing_pole": (20, 17),
}
SLOTS = {"head": 1, "shoulder": 3, "chest": 5, "waist": 6, "legs": 7, "feet": 8, "wrist": 9, "hands": 10, "back": 16}
MATERIALS = {"cloth": 1, "leather": 2, "mail": 3, "plate": 4}


def _poly(x, y, r, n=8):
    return [(round(x + r * math.cos(2 * math.pi * i / n)), round(y + r * math.sin(2 * math.pi * i / n))) for i in range(n)]


class Story:
    def __init__(self, chapters, world, dbc, ground, race_phase_spell=0):
        self.chapters = chapters
        self.W = world
        self.D = dbc
        self.ground = ground
        self.race_phase_spell = race_phase_spell
        self.errors = []
        lw = self.D("LanguageWords")
        self.garbler = Garbler([(r[1], lw.s(r[2])) for r in lw.rows])
        self.tables = {}
        self.client = {}
        self.next_option = {}

    # -- small things --------------------------------------------------------------------------
    def err(self, where, what):
        self.errors.append(f"{where}: {what}")

    def row(self, table, **values):
        self.tables.setdefault(table, []).append(values)

    def words(self, text, language):
        """[text] as a murloc reads it, said in [language]."""
        if not text:
            return ""
        if language in ("nerglish", None):
            return nerglish.translate(text)
        lang = K.LANGUAGES.get(language)
        if lang is None:
            raise StoryError(f"no language {language!r}")
        return self.garbler.text(text, lang)

    # -- names ---------------------------------------------------------------------------------
    def qualify(self, chapter, key):
        if isinstance(key, int) or key is None:
            return key
        return key if ":" in key else f"{chapter}:{key}"

    def index(self):
        self.places, self.npcs, self.objects, self.items, self.quests = {}, {}, {}, {}, {}
        self.chapter_of = {}
        for ch in self.chapters:
            c = ch["CHAPTER"]["key"]
            m = ch["CHAPTER"].get("map", K.MAP_KALIMDOR)
            for k, v in ch.get("PLACES", {}).items():
                self.places[f"{c}:{k}"] = (m, v)
            for kind, store in (("NPCS", self.npcs), ("OBJECTS", self.objects), ("ITEMS", self.items), ("QUESTS", self.quests)):
                for d in ch.get(kind, []):
                    key = f"{c}:{d['key']}"
                    if key in store:
                        self.err(key, f"two {kind.lower()} with the key {d['key']!r}")
                    store[key] = dict(d, key=key, chapter=c)
                    self.chapter_of[key] = ch["CHAPTER"]

    def assign(self):
        def ids(store, base, end, what):
            out = {}
            for n, key in enumerate(store):
                if base + n > end:
                    raise StoryError(f"too many {what} ({len(store)})")
                out[key] = base + n
            return out
        self.npc_id = ids(self.npcs, K.NPC_BASE, K.NPC_END - 400, "creatures")    # (the last 400: triggers)
        self.go_id = ids(self.objects, K.GO_BASE, K.GO_END, "objects")
        self.quest_id = ids(self.quests, K.QUEST_BASE, K.QUEST_END, "quests")
        self.item_id = ids(self.items, K.ITEM_BASE, K.ITEM_END, "items")
        self.next_trigger = K.NPC_END - 400 + 1
        self.next_spawn = K.SPAWN_BASE
        self.next_go_spawn = K.GO_SPAWN_BASE
        self.next_display = K.GO_DISPLAY_BASE
        self.next_trainer = K.TRAINER_BASE
        self.lists = {}                 # entry -> number of timed action lists made
        self.data_fields = {}           # entry -> next data value
        self.script_rows = {}           # (entry, source type) -> next id
        self.texts = {}                 # entry -> next creature_text group

    # -- places --------------------------------------------------------------------------------
    def point(self, ref, chapter, where):
        """(map, x, y, z) of a place key, an (x, y) or an (x, y, z)."""
        if isinstance(ref, str):
            key = self.qualify(chapter, ref)
            if key not in self.places:
                self.err(where, f"no place {ref!r}")
                return (K.MAP_KALIMDOR, 0.0, 0.0, 0.0)
            m, v = self.places[key]
        else:
            m, v = self.chapter_of_key(chapter).get("map", K.MAP_KALIMDOR), ref
        if len(v) == 3:
            return (m, float(v[0]), float(v[1]), float(v[2]))
        z = self.ground(m, v[0], v[1])
        if z is None:
            self.err(where, f"no ground at {v}")
            z = 0.0
        return (m, float(v[0]), float(v[1]), float(z))

    def chapter_of_key(self, chapter):
        return next(ch["CHAPTER"] for ch in self.chapters if ch["CHAPTER"]["key"] == chapter)

    def points(self, ref, chapter, where):
        refs = ref if isinstance(ref, list) else [ref]
        return [self.point(r, chapter, where) for r in refs]

    # -- references ----------------------------------------------------------------------------
    def creature(self, ref, chapter, where):
        """A creature entry: an NPC key of the story's, or the game's entry."""
        if isinstance(ref, int):
            if ref not in self.world_creatures:
                self.err(where, f"no creature {ref} in the game")
            return ref
        key = self.qualify(chapter, ref)
        if key not in self.npc_id:
            self.err(where, f"no NPC {ref!r}")
            return 0
        return self.npc_id[key]

    def item(self, ref, chapter, where):
        if isinstance(ref, int):
            if ref not in self.world_items:
                self.err(where, f"no item {ref} in the game")
            return ref
        key = self.qualify(chapter, ref)
        if key not in self.item_id:
            self.err(where, f"no item {ref!r}")
            return 0
        return self.item_id[key]

    def quest(self, ref, chapter, where):
        key = self.qualify(chapter, ref)
        if key not in self.quest_id:
            self.err(where, f"no quest {ref!r}")
            return 0
        return self.quest_id[key]

    # -- the build -----------------------------------------------------------------------------
    def build(self):
        W = self.W
        self.world_creatures = {int(r["entry"]): r for r in W("creature_template")}
        self.world_items = {int(r["entry"]): r for r in W("item_template")}
        self.index()
        self.assign()
        self.phase_bits = {}            # (zone, rule) -> bit
        self.phase_spells = []          # (spell, bit, rule, zones)
        self.zones = set()
        for ch in self.chapters:
            self.zones.update(ch["CHAPTER"].get("zones", []))
        for key, d in self.items.items():
            self.build_item(key, d)
        # (those one talks to or gives something to for a quest: a gossip menu each)
        self.talkers = set()
        for q in self.quests.values():
            for target, _ in q.get("interact", []):
                self.talkers.add(self.qualify(q["chapter"], target))
            for _, target, _ in q.get("use", []):
                if isinstance(target, str) and self.qualify(q["chapter"], target) in self.npcs:
                    self.talkers.add(self.qualify(q["chapter"], target))
        for key, d in self.npcs.items():
            self.build_npc(key, d)
        for key, d in self.objects.items():
            self.build_object(key, d)
        for key, d in self.quests.items():
            self.build_quest(key, d)
        self.build_phases()
        finals = [k for k, q in self.quests.items() if q.get("final")]
        if len(finals) > 1:
            self.err("the story", f"more than one final quest: {finals}")
        final = finals[0] if finals else (list(self.quests)[-1] if self.quests else None)
        self.tables["_story"] = {"final_quest": self.quest_id[final] if final else 0, "first_quest": K.QUEST_BASE,
                                 "last_quest": K.QUEST_END}
        if self.errors:
            raise StoryError("the murlocs' story has problems:\n  " + "\n  ".join(self.errors))
        return self.tables, self.client

    # -- items ---------------------------------------------------------------------------------
    def icon(self, ref, where):
        if isinstance(ref, int):
            return ref
        if isinstance(ref, str) and ref.startswith("item:"):
            r = self.world_items.get(int(ref[5:]))
            if not r:
                self.err(where, f"no item {ref}")
                return 0
            return int(r["displayid"])
        if ref in K.ICONS:
            return K.ICONS[ref]
        self.err(where, f"no icon {ref!r}")
        return 0

    def template_item(self, cls, sub, inv, level, quality, name):
        """An item of the game's like the one wanted (its look, damage, armour, speed)."""
        key = (cls, sub, inv)
        if not hasattr(self, "_by_kind"):
            self._by_kind = {}
            for r in self.world_items.values():
                if int(r["Quality"]) in (1, 2, 3) and int(r["ItemLevel"]) > 0 and int(r["displayid"]) > 0:
                    self._by_kind.setdefault((int(r["class"]), int(r["subclass"]), int(r["InventoryType"])), []).append(r)
        pool = self._by_kind.get(key, [])
        if not pool and inv == 13:
            pool = self._by_kind.get((cls, sub, 21), [])
        if not pool:
            return None
        best = sorted(pool, key=lambda r: (abs(int(r["ItemLevel"]) - level), abs(int(r["Quality"]) - quality), int(r["entry"])))
        near = [r for r in best if abs(int(r["ItemLevel"]) - level) <= abs(int(best[0]["ItemLevel"]) - level) + 3][:12]
        return near[zlib.crc32(name.encode()) % len(near)]

    def build_item(self, key, d):
        iid = self.item_id[key]
        kind = d.get("kind")
        name = d["name"]
        row = dict(entry=iid, name=name, Quality=d.get("quality", 1), bonding=4, stackable=d.get("stack", 1),
                   maxcount=d.get("stack", 1) if not kind else 0, InventoryType=0, ItemLevel=d.get("level", 1),
                   RequiredLevel=0, Material=-1, description=d.get("description", ""), displayid=0, armor=0, dmg_min1=0,
                   dmg_max1=0, delay=0, sheath=0)
        stats = []
        if not kind:
            row.update({"class": 12, "subclass": 0, "displayid": self.icon(d.get("icon", "letter"), key)})
        else:
            level = d.get("level", 10)
            quality = d.get("quality", 2)
            part = kind.split(":")
            if part[0] == "weapon":
                sub, inv = WEAPONS[part[1]]
                cls = 2
            elif part[0] == "armor":
                mat, slot = part[1], part[2]
                cls, sub = 4, MATERIALS[mat] if slot != "back" else 1
                inv = SLOTS[slot]
            elif part[0] in ("ring", "neck", "trinket"):
                cls, sub, inv = 4, 0, {"ring": 11, "neck": 2, "trinket": 12}[part[0]]
            elif part[0] == "shield":
                cls, sub, inv = 4, 6, 14
            elif part[0] == "offhand":
                cls, sub, inv = 4, 0, 23
            else:
                self.err(key, f"no item kind {kind!r}")
                return
            t = self.template_item(cls, sub, inv, level, quality, name)
            if t is None:
                self.err(key, f"no item of the game's like {kind}")
                return
            row.update({"class": cls, "subclass": sub, "InventoryType": inv, "displayid": int(t["displayid"]),
                        "Quality": quality, "bonding": 1, "stackable": 1, "maxcount": 0, "ItemLevel": level,
                        "RequiredLevel": max(1, level - 5), "Material": int(t["Material"]), "armor": int(t["armor"]),
                        "dmg_min1": float(t["dmg_min1"]), "dmg_max1": float(t["dmg_max1"]), "dmg_type1": int(t["dmg_type1"]),
                        "delay": int(t["delay"]), "sheath": int(t["sheath"]), "MaxDurability": int(t["MaxDurability"]),
                        "SellPrice": int(t["SellPrice"]), "BuyPrice": int(t["BuyPrice"]), "block": int(t["block"]),
                        "ammo_type": int(t["ammo_type"]), "RangedModRange": float(t["RangedModRange"])})
            for s, v in d.get("stats", []):
                if s == "armor":
                    row["armor"] += v
                elif s in STAT:
                    stats.append((STAT[s], v))
                else:
                    self.err(key, f"no stat {s!r}")
        for i, (t_, v) in enumerate(stats[:10], start=1):
            row[f"stat_type{i}"], row[f"stat_value{i}"] = t_, v
        self.row("item_template", **row)
        self.client.setdefault("Item", []).append([iid, row["class"], row["subclass"], -1, row["Material"], row["displayid"],
                                                   row["InventoryType"], row["sheath"]])

    # -- creatures -----------------------------------------------------------------------------
    def look(self, ref, where):
        if isinstance(ref, int):
            return ref
        if ref in K.LOOKS:
            return K.LOOKS[ref]
        self.err(where, f"no look {ref!r}")
        return K.LOOKS["warrior"]

    def side(self, d):
        s = d.get("side", "tribe")
        if isinstance(s, int):
            return s
        if s.startswith("faction:"):
            return self.faction_template(int(s.split(":")[1]), d["key"])
        if s not in K.SIDES:
            self.err(d["key"], f"no side {s!r}")
            return K.SIDES["tribe"]
        return K.SIDES[s]

    def faction_template(self, faction, where):
        """The FactionTemplate of a reputation faction's own people (the first of the game's)."""
        ft = self.D("FactionTemplate")
        for r in ft.rows:
            if r[1] == faction:
                return r[0]
        self.err(where, f"no FactionTemplate for Faction {faction}")
        return 35

    def language(self, d):
        if d.get("language"):
            return d["language"]
        return "nerglish" if d.get("side", "tribe") in ("tribe",) or d.get("murloc") else "common"

    def spawn(self, entry, pts, facing, phase, wander=0.0, respawn=300, comment=""):
        for i, (m, x, y, z) in enumerate(pts):
            guid = self.next_spawn
            self.next_spawn += 1
            if guid > K.SPAWN_END:
                raise StoryError("too many spawns")
            o = facing if facing is not None else (i * 1.37) % 6.28
            self.row("creature", guid=guid, id=entry, map=m, zoneId=0, areaId=0, spawnMask=1, phaseMask=phase,
                     position_x=round(x, 2), position_y=round(y, 2), position_z=round(z, 2), orientation=round(o, 3),
                     spawntimesecs=respawn, wander_distance=wander, MovementType=1 if wander else 0, Comment=comment)
            yield guid

    def phase_of(self, d, chapter):
        p = d.get("phase", "always")
        if p == "always":
            return K.RACE_PHASE
        kind, quest = p
        return self.phase_bit(chapter, kind, self.quest(quest, chapter, d["key"]))

    def phase_bit(self, chapter, kind, quest):
        zones = tuple(self.chapter_of_key(chapter).get("zones", []))
        rule = (kind, quest)
        for (zs, r), bit in self.phase_bits.items():
            if r == rule and zs == zones:
                return bit
        used = {bit for (zs, _), bit in self.phase_bits.items() if set(zs) & set(zones)}
        free = [b for b in K.STORY_PHASE_BITS if b not in used]
        if not free:
            raise StoryError(f"no phase bit left in zones {zones}")
        self.phase_bits[(zones, rule)] = free[0]
        return free[0]

    def build_npc(self, key, d):
        entry = self.npc_id[key]
        chapter = d["chapter"]
        role = d.get("role", "ambient")
        lo, hi = d["level"] if isinstance(d.get("level"), (tuple, list)) else (d.get("level", 10), d.get("level", 10))
        flags = 0
        menu = 0
        lang = self.language(d)
        if role in ("quest", "vendor", "innkeeper", "repair", "travel") or role.startswith(("trainer:", "profession:")) or d.get("gossip") \
                or key in self.talkers:
            flags |= NPCFLAG["gossip"]
            menu = entry
        if role == "quest" or self.gives_quests(key):
            flags |= NPCFLAG["quest"]
        options = []
        if role in ("vendor", "innkeeper", "repair"):
            flags |= NPCFLAG["vendor"]
            options.append((3, NPCFLAG["vendor"], "Let me see what you have."))
            for slot, item in enumerate(d.get("sells", K.VENDOR_FOOD)):
                self.row("npc_vendor", entry=entry, slot=slot, item=self.item(item, chapter, key))
        if role == "repair":
            flags |= NPCFLAG["repair"]
        if role == "innkeeper":
            flags |= NPCFLAG["innkeeper"]
            options.insert(0, (8, NPCFLAG["innkeeper"], "Make this place your home."))
        if role.startswith(("trainer:", "profession:")):
            what = role.split(":")[1]
            flags |= NPCFLAG["trainer"]
            if role.startswith("trainer:"):
                flags |= NPCFLAG["class_trainer"]
                if what not in K.CLASSES:
                    self.err(key, f"no class {what!r}")
                    return
                self.trainer(entry, 0, K.CLASSES[what], key, lang)
                options.append((5, NPCFLAG["trainer"], "Teach me."))
                options.append((16, NPCFLAG["trainer"], "Make me forget what I learned."))
            else:
                flags |= NPCFLAG["profession_trainer"]
                if what not in K.SKILLS:
                    self.err(key, f"no profession {what!r}")
                    return
                self.trainer(entry, 2, K.SKILLS[what], key, lang, requirement=0)
                options.append((5, NPCFLAG["trainer"], "Teach me your trade."))
        if role == "travel":
            for n, (dest, needs) in enumerate(d.get("travel", [])):
                m, x, y, z = self.point(dest, chapter, key)
                options.append(("travel", 1, "Take me to the other place.", (m, x, y, z), needs))
        side = self.side(d)
        enemy = role == "enemy" or d.get("side") in ("naga", "hostile")
        unit_class = d.get("class", 2 if d.get("caster") else 1)
        self.row("creature_template", entry=entry, name=d["name"], subname=d.get("subname") or "", gossip_menu_id=menu,
                 minlevel=lo, maxlevel=hi, faction=side, npcflag=flags, speed_walk=1.0, speed_run=1.14286, unit_class=unit_class,
                 unit_flags=d.get("unit_flags", 0), type=7, flags_extra=d.get("flags_extra", 0), rank=d.get("rank", 0),
                 HealthModifier=d.get("health", 1.0), ManaModifier=1.0, ArmorModifier=1.0, DamageModifier=d.get("damage", 1.0),
                 BaseAttackTime=2000, RangeAttackTime=2000, lootid=entry if enemy else 0,
                 mingold=lo * 8 if enemy else 0, maxgold=hi * 20 if enemy else 0, AIName="SmartAI", ScriptName="",
                 detection_range=d.get("detection", 20.0))
        self.row("creature_template_model", CreatureID=entry, Idx=0, CreatureDisplayID=self.look(d.get("look", "warrior"), key),
                 DisplayScale=d.get("scale", 1.0), Probability=1)
        for i, extra in enumerate(d.get("looks", []), start=1):
            self.row("creature_template_model", CreatureID=entry, Idx=i, CreatureDisplayID=self.look(extra, key),
                     DisplayScale=d.get("scale", 1.0), Probability=1)
        # gossip: the greeting, and what it offers
        if menu:
            greeting = d.get("gossip") or d.get("greeting") or "Hello. What do you want?"
            self.row("npc_text", ID=entry, text0_0=self.words(greeting, lang), text0_1=self.words(greeting, lang), lang0=0,
                     Probability0=1, em0_0=0, em0_1=1)
            self.row("gossip_menu", MenuID=menu, TextID=entry)
            for n, opt in enumerate(options):
                if opt[0] == "travel":
                    _, icon, text, (m, x, y, z), needs = opt
                    self.row("gossip_menu_option", MenuID=menu, OptionID=n, OptionIcon=2, OptionText=self.words(text, lang),
                             OptionType=1, OptionNpcFlag=1)
                    self.smart(entry, 0, EV_GOSSIP_SELECT, (menu, n), [(A_CLOSE_GOSSIP, (), (T_INVOKER,)),
                                                                     (A_TELEPORT, (m,), (T_POSITION, 0, 0, 0, x, y, z, 0))])
                    if needs:
                        self.condition(15, menu, n, 8, self.quest(needs, chapter, key))
                    continue
                kind, nf, text = opt
                self.row("gossip_menu_option", MenuID=menu, OptionID=n, OptionIcon={3: 1, 5: 3, 8: 5, 16: 3}.get(kind, 0),
                         OptionText=self.words(text, lang), OptionType=kind, OptionNpcFlag=nf)
            self.next_option[entry] = len(options)
        # where
        if d.get("at") is not None and not d.get("summoned"):
            phase = self.phase_of(d, chapter)
            guids = list(self.spawn(entry, self.points(d["at"], chapter, key), d.get("facing"), phase, d.get("wander", 0.0),
                                    d.get("respawn", 60 if enemy else 120), f"mod-murloc-naga: {d['name']}"))
            state = next((b[2] for b in d.get("idle", []) if b[0] == "state" and b[1] == "self"), None)
            if state is not None:
                for g in guids:
                    self.row("creature_addon", guid=g, emote=K.STATES.get(state, state) if isinstance(state, str) else state)
        # idle
        for beat in d.get("idle", []):
            if beat[0] == "every":
                _, period, beats = beat
                lid = self.actionlist(entry, beats, chapter, key)
                self.smart(entry, 0, EV_UPDATE_OOC, (period // 2, period, period, period + period // 2),
                           [(A_ACTIONLIST, (lid, 0, 0), (T_SELF,))])
        for spell, every in d.get("abilities", []):
            self.smart(entry, 0, EV_UPDATE_IC, (2000, 5000, every, every + 3000), [(A_CAST, (spell, 0), (2,))])

    def gives_quests(self, key):
        return any(self.qualify(q["chapter"], q["giver"]) == key for q in self.quests.values() if isinstance(q.get("giver"), str))

    def trainer(self, entry, kind, teaches, key, lang, requirement=None):
        """A trainer of the story's own (its greeting murloc), teaching what the game's best trainer of
        the class or the profession teaches."""
        tid = self.next_trainer
        self.next_trainer += 1
        best = self.best_trainer(kind, teaches)
        if not best:
            self.err(key, f"no trainer of the game's for {kind}:{teaches}")
            return
        self.row("trainer", Id=tid, Type=kind, Requirement=teaches if requirement is None else requirement,
                 Greeting=self.words("Learn, little one.", lang))
        self.tables.setdefault("_trainer_copy", []).append((tid, best))
        self.row("creature_default_trainer", CreatureId=entry, TrainerId=tid)

    def best_trainer(self, kind, requirement):
        """The game's trainer teaching the most of a class (kind 0, [requirement] the class) or a
        profession (kind 2, [requirement] its skill line: the one most of its spells need), up to
        Artisan (no Grand Master: Northrend's)."""
        if not hasattr(self, "_trainers"):
            spells = {}
            for r in self.W("trainer_spell"):
                spells.setdefault(int(r["TrainerId"]), []).append(r)
            self._trainers = {}
            for r in self.W("trainer"):
                tid, ttype = int(r["Id"]), int(r["Type"])
                rows = spells.get(tid, [])
                if ttype == 0:
                    key = (0, int(r["Requirement"]))
                elif ttype == 2:
                    lines = [int(s["ReqSkillLine"]) for s in rows if int(s["ReqSkillLine"])]
                    if not lines:
                        continue
                    key = (2, max(set(lines), key=lines.count))
                else:
                    continue
                self._trainers.setdefault(key, []).append((len(rows), tid, rows))
        cands = self._trainers.get((kind, requirement), [])
        if kind == 2:
            cands = [c for c in cands if all(int(s["ReqLevel"]) <= 65 for s in c[2])] or cands
        return max(cands)[1] if cands else None

    # -- objects -------------------------------------------------------------------------------
    def display(self, ref, where):
        if isinstance(ref, int):
            return ref
        m = K.MODELS.get(ref, ref)
        if isinstance(m, int):
            return m
        if not hasattr(self, "_displays"):
            gd = self.D("GameObjectDisplayInfo")
            self._displays = {gd.s(r[1]).lower(): r[0] for r in gd.rows}
            self._new_displays = {}
        path = m
        if path.lower() in self._displays:
            return self._displays[path.lower()]
        if path.lower().endswith(".mdx") and path.lower()[:-4] + ".m2" in self._displays:
            return self._displays[path.lower()[:-4] + ".m2"]
        if path not in self._new_displays:
            did = self.next_display
            self.next_display += 1
            if did > K.GO_DISPLAY_END:
                raise StoryError("too many object looks")
            self._new_displays[path] = did
            self.client.setdefault("GameObjectDisplayInfo", []).append([did, path] + [0] * 10 + [-1.0, -1.0, 0.0, 1.0, 1.0, 3.0, 0])
        return self._new_displays[path]

    def build_object(self, key, d):
        entry = self.go_id[key]
        chapter = d["chapter"]
        kind = d.get("kind", "doodad")
        data = [0] * 24
        quest = self.quest(d["quest"], chapter, key) if d.get("quest") else 0
        if kind == "doodad":
            gtype = 5
        elif kind == "loot":
            gtype = 3
            data[0], data[1], data[3], data[8] = 43 if d.get("open") else 0, entry, 1, quest  # lock, loot, respawn, quest
            data[2] = 0
            for item, lo, hi in d.get("loot", []):
                self.row("gameobject_loot_template", Entry=entry, Item=self.item(item, chapter, key), Reference=0,
                         Chance=100, QuestRequired=1 if quest else 0, LootMode=1, GroupId=0, MinCount=lo, MaxCount=hi,
                         Comment="mod-murloc-naga")
        elif kind == "use":
            gtype = 10
            data[1], data[3], data[4] = quest, 3000, 0
            if d.get("consumable", True):
                data[5] = 1
        elif kind == "chair":
            gtype, data[0], data[1] = 7, 1, 1
        else:
            self.err(key, f"no object kind {kind!r}")
            return
        self.row("gameobject_template", entry=entry, type=gtype, displayId=self.display(d.get("model", "crate"), key),
                 name=self.words(d["name"], "nerglish") if d.get("murloc_name") else d["name"], size=d.get("scale", 1.0),
                 **{f"Data{i}": v for i, v in enumerate(data)})
        if d.get("at") is not None:
            phase = self.phase_of(d, chapter)
            for i, (m, x, y, z) in enumerate(self.points(d["at"], chapter, key)):
                guid = self.next_go_spawn
                self.next_go_spawn += 1
                o = d.get("facing", (i * 1.1) % 6.28)
                self.row("gameobject", guid=guid, id=entry, map=m, zoneId=0, areaId=0, spawnMask=1, phaseMask=phase,
                         position_x=round(x, 2), position_y=round(y, 2), position_z=round(z, 2), orientation=round(o, 3),
                         rotation0=0, rotation1=0, rotation2=round(math.sin(o / 2), 5), rotation3=round(math.cos(o / 2), 5),
                         spawntimesecs=d.get("respawn", 30 if kind in ("loot", "use") else 0), animprogress=100, state=1,
                         Comment=f"mod-murloc-naga: {d['name']}")

    # -- SmartAI -------------------------------------------------------------------------------
    def smart(self, entry, source, event, eparams, actions, chance=100, flags=0):
        """Rows of one event and its actions (linked): actions are (type, params, target)."""
        k = (entry, source)
        first = self.script_rows.get(k, 0)
        for n, (atype, aparams, target) in enumerate(actions):
            sid = first + n
            ev = (event, eparams) if n == 0 else (61, ())     # (the rest: linked)
            ep = list(ev[1]) + [0] * (6 - len(ev[1]))
            ap = list(aparams) + [0] * (6 - len(aparams))
            tp = list(target) + [0] * (9 - len(target))
            self.row("smart_scripts", entryorguid=entry, source_type=source, id=sid,
                     link=sid + 1 if n + 1 < len(actions) else 0, event_type=ev[0], event_phase_mask=0,
                     event_chance=chance if n == 0 else 100, event_flags=flags,
                     **{f"event_param{i + 1}": ep[i] for i in range(6)}, action_type=atype,
                     **{f"action_param{i + 1}": ap[i] for i in range(6)}, target_type=tp[0],
                     target_param1=tp[1], target_param2=tp[2], target_param3=tp[3], target_param4=0,
                     target_x=tp[4], target_y=tp[5], target_z=tp[6], target_o=tp[7], comment="mod-murloc-naga")
        self.script_rows[k] = first + len(actions)

    def text_group(self, entry, text, chat, lang, emote, sound):
        g = self.texts.get(entry, 0)
        self.texts[entry] = g + 1
        self.row("creature_text", CreatureID=entry, GroupID=g, ID=0, Text=text, Type=chat, Language=lang, Probability=100,
                 Emote=emote, Duration=0, Sound=sound, BroadcastTextId=0, TextRange=0, comment="mod-murloc-naga")
        return g

    def emote_id(self, name, where):
        if isinstance(name, int):
            return name
        if name in K.EMOTES:
            return K.EMOTES[name]
        self.err(where, f"no emote {name!r}")
        return 0

    def sound_id(self, name, where):
        if isinstance(name, int):
            return name
        if name in K.SOUNDS:
            return K.SOUNDS[name]
        self.err(where, f"no sound {name!r}")
        return 0

    def visual_id(self, name, where):
        if isinstance(name, int):
            return name
        if name in K.VISUALS:
            return K.VISUALS[name]
        self.err(where, f"no visual {name!r}")
        return 0

    def actor(self, who, owner, chapter, where):
        """(entry, target) of a beat's doer as the list's owner sees it: itself, or the nearest of them."""
        if who in ("self", None):
            return owner, (T_SELF,)
        if who == "player":
            return None, (T_INVOKER,)
        e = self.creature(who, chapter, where)
        return e, (T_CLOSEST_CREATURE, e, 80, 0)

    def actionlist(self, owner, beats, chapter, where):
        """A timed action list of [owner]'s doing [beats] (others' beats: their own lists, started by data)."""
        n = self.lists.get(owner, 0)
        self.lists[owner] = n + 1
        if n >= 100:
            raise StoryError(f"{where}: more than 100 scenes for one creature")
        lid = owner * 100 + n
        rows = []                       # (delay, action, params, target)
        delay = 0
        for beat in beats:
            kind = beat[0]
            if kind == "wait":
                delay += beat[1]
                continue
            got = self.beat(owner, beat, chapter, where)
            for a in got:
                rows.append((delay,) + a)
                delay = 0
        if delay and rows:
            pass
        sid = 0
        for d, atype, aparams, target in rows:
            ap = list(aparams) + [0] * (6 - len(aparams))
            tp = list(target) + [0] * (9 - len(target))
            self.row("smart_scripts", entryorguid=lid, source_type=9, id=sid, link=0, event_type=0, event_phase_mask=0,
                     event_chance=100, event_flags=0, event_param1=d, event_param2=d, event_param3=0, event_param4=0,
                     event_param5=0, event_param6=0, action_type=atype, **{f"action_param{i + 1}": ap[i] for i in range(6)},
                     target_type=tp[0], target_param1=tp[1], target_param2=tp[2], target_param3=tp[3], target_param4=0,
                     target_x=tp[4], target_y=tp[5], target_z=tp[6], target_o=tp[7], comment="mod-murloc-naga")
            sid += 1
        return lid

    def by_other(self, owner, other_entry, beat, chapter, where):
        """A beat another creature does itself: a list of its own, started by data set on it."""
        lid = self.actionlist(other_entry, [beat[:1] + ("self",) + beat[2:]], chapter, where)
        v = self.data_fields.get(other_entry, 1)
        self.data_fields[other_entry] = v + 1
        self.smart(other_entry, 0, EV_DATA_SET, (1, v), [(A_ACTIONLIST, (lid, 0, 0), (T_SELF,))])
        return [(A_SET_DATA, (1, v), (T_CLOSEST_CREATURE, other_entry, 80, 0))]

    def beat(self, owner, beat, chapter, where):
        kind = beat[0]
        who = beat[1] if len(beat) > 1 else None
        if kind in ("say", "yell", "whisper"):
            _, who, text = beat[:3]
            emote = self.emote_id(beat[3], where) if len(beat) > 3 and beat[3] else 0
            sound = self.sound_id(beat[4], where) if len(beat) > 4 and beat[4] else 0
            e, target = self.actor(who, owner, chapter, where)
            if e is None:
                self.err(where, "the player can't speak")
                return []
            d = self.npcs.get(next((k for k, v in self.npc_id.items() if v == e), None), {})
            lang = self.language(d) if d else "nerglish"
            words = self.words(text, lang)
            g = self.text_group(e, words, CHAT[kind], 0, emote, sound)
            if e == owner:
                return [(A_TALK, (g, 0, 0), (T_INVOKER,))]
            return [(A_TALK, (g, 0, 0), target)]
        if kind == "emote":
            e, target = self.actor(who, owner, chapter, where)
            return [(A_EMOTE, (self.emote_id(beat[2], where),), target if e else (T_SELF,))]
        if kind == "state":
            e, target = self.actor(who, owner, chapter, where)
            s = beat[2]
            sid = K.STATES.get(s, s) if isinstance(s, str) else s
            if isinstance(sid, str):
                self.err(where, f"no state {s!r}")
                sid = 0
            return [(A_EMOTE_STATE, (sid,), target)]
        if kind == "sound":
            e, target = self.actor(who, owner, chapter, where)
            return [(A_SOUND, (self.sound_id(beat[2], where), 0), target if e else (T_SELF,))]
        if kind == "music":
            return [(216, (self.sound_id(beat[1], where), 0, 0), (T_INVOKER,))]
        if kind == "visual":
            e, target = self.actor(who, owner, chapter, where)
            return [(A_CAST, (self.visual_id(beat[2], where), 2), target)]
        if kind == "cast":
            e, _ = self.actor(who, owner, chapter, where)
            spell = self.visual_id(beat[2], where) if not isinstance(beat[2], int) else beat[2]
            tgt = beat[3] if len(beat) > 3 else "player"
            _, ttarget = self.actor(tgt, owner, chapter, where)
            if e != owner:
                return self.by_other(owner, e, ("cast", "self", spell, tgt), chapter, where)
            return [(A_CAST, (spell, 2), ttarget)]
        if kind in ("move", "jump", "face", "despawn"):
            e, _ = self.actor(who, owner, chapter, where)
            if e is None:
                self.err(where, f"the player can't be made to {kind}")
                return []
            if e != owner:
                return self.by_other(owner, e, beat, chapter, where)
            if kind == "move":
                m, x, y, z = self.point(beat[2], chapter, where)
                run = len(beat) > 3 and beat[3] == "run"
                return [(A_SET_RUN, (1 if run else 0,), (T_SELF,)), (A_MOVE_TO, (0, 0, 0, 0), (T_POSITION, 0, 0, 0, x, y, z, 0))]
            if kind == "jump":
                m, x, y, z = self.point(beat[2], chapter, where)
                return [(A_JUMP, (10, 10), (T_POSITION, 0, 0, 0, x, y, z, 0))]
            if kind == "face":
                if beat[2] == "player":
                    return [(A_ORIENTATION, (), (T_INVOKER,))]
                m, x, y, z = self.point(beat[2], chapter, where)
                return [(A_ORIENTATION, (), (T_POSITION, 0, 0, 0, x, y, z, 0))]
            return [(A_DESPAWN, (500,), (T_SELF,))]
        if kind == "give":
            return [(A_ADD_ITEM, (self.item(beat[1], chapter, where), beat[2] if len(beat) > 2 else 1), (T_INVOKER,))]
        if kind == "take":
            return [(A_REMOVE_ITEM, (self.item(beat[1], chapter, where), beat[2] if len(beat) > 2 else 1), (T_INVOKER,))]
        if kind == "summon":
            e = self.creature(beat[1], chapter, where)
            m, x, y, z = self.point(beat[2], chapter, where)
            secs = beat[3] if len(beat) > 3 else 60
            return [(A_SUMMON, (e, 3, secs * 1000), (T_POSITION, 0, 0, 0, x, y, z, 0))]
        if kind == "credit":
            return [(A_AREA_EXPLORED, (self.quest(beat[1], chapter, where),), (T_INVOKER,))]
        if kind == "follow":
            e, _ = self.actor(who, owner, chapter, where)
            if e is not None and e != owner:
                return self.by_other(owner, e, beat, chapter, where)
            return [(A_FOLLOW, (2, 0), (T_INVOKER,))]
        self.err(where, f"no beat {kind!r}")
        return []

    def condition(self, source, group, entry, ctype, value, negative=0, else_group=0, value2=0):
        self.row("conditions", SourceTypeOrReferenceId=source, SourceGroup=group, SourceEntry=entry, SourceId=0,
                 ElseGroup=else_group, ConditionTypeOrReference=ctype, ConditionTarget=0, ConditionValue1=value,
                 ConditionValue2=value2, ConditionValue3=0, NegativeCondition=negative, ErrorType=0, ErrorTextId=0,
                 ScriptName="", Comment="mod-murloc-naga")

    # -- quests --------------------------------------------------------------------------------
    def trigger(self, place_pt, radius, quest, name):
        """An unseen creature at a place: whoever comes within [radius] on [quest] has explored it."""
        entry = self.next_trigger
        self.next_trigger += 1
        if entry > K.NPC_END:
            raise StoryError("too many triggers")
        self.row("creature_template", entry=entry, name=name, subname="", gossip_menu_id=0, minlevel=1, maxlevel=1,
                 faction=35, npcflag=0, speed_walk=1.0, speed_run=1.14286, unit_class=1, unit_flags=0x02000000 | 0x200,
                 type=10, flags_extra=128, rank=0, HealthModifier=1.0, ManaModifier=1.0, ArmorModifier=1.0, DamageModifier=1.0,
                 BaseAttackTime=2000, RangeAttackTime=2000, lootid=0, mingold=0, maxgold=0, AIName="SmartAI", ScriptName="",
                 detection_range=20.0)
        self.row("creature_template_model", CreatureID=entry, Idx=0, CreatureDisplayID=K.LOOKS["trigger"], DisplayScale=1.0,
                 Probability=1)
        list(self.spawn(entry, [place_pt], 0.0, K.RACE_PHASE, comment=f"mod-murloc-naga: {name}"))
        self.smart(entry, 0, EV_OOC_LOS, (2, int(radius), 1000, 1000, 1), [(A_AREA_EXPLORED, (quest,), (T_INVOKER,))])
        return entry

    def build_quest(self, key, d):
        chapter = d["chapter"]
        ch = self.chapter_of_key(chapter)
        qid = self.quest_id[key]
        where = key
        giver = d["giver"]
        ender = d.get("ender", giver)
        giver_npc = self.npcs.get(self.qualify(chapter, giver)) if isinstance(giver, str) else None
        ender_npc = self.npcs.get(self.qualify(chapter, ender)) if isinstance(ender, str) else None
        lang_give = self.language(giver_npc) if giver_npc else "nerglish"
        lang_end = self.language(ender_npc) if ender_npc else lang_give
        level = d["level"]
        req_npc, req_items, objective_text = [], [], []
        special = 0
        for target, count in d.get("kill", []):
            req_npc.append((self.creature(target, chapter, where), count))
            objective_text.append("")
        for target, text in d.get("interact", []):
            e = self.creature(target, chapter, where)
            npc_key = self.qualify(chapter, target)
            req_npc.append((e, 1))
            objective_text.append(d.get("interact_goal", ""))
            self.interaction(e, npc_key, qid, text, d.get("interact_beats", []), chapter, where)
        for item, target, count in d.get("use", []):
            self.use(qid, item, target, count, req_npc, objective_text, chapter, where)
        if d.get("explore"):
            special |= 2
            pt = self.point(d["explore"], chapter, where)
            self.trigger(pt, d.get("radius", 15), qid, f"{d['title']} (where)")
        if d.get("event"):
            special |= 2
        if d.get("escort"):
            special |= 2
            who, path = d["escort"]
            e = self.creature(who, chapter, where)
            pt = self.point(path[-1], chapter, where)
            self.trigger(pt, d.get("radius", 12), qid, f"{d['title']} (the end of the way)")
            d.setdefault("accept", []).insert(0, ("follow", who))
        for item, count in d.get("items", []):
            req_items.append((self.item(item, chapter, where), count))
        for item, sources in d.get("drops", {}).items():
            iid = self.item(item, chapter, where)
            for src, chance in sources:
                e = self.creature(src, chapter, where)
                lootid = e
                if isinstance(src, int):
                    r = self.world_creatures.get(src)
                    lootid = int(r["lootid"]) if r and int(r["lootid"]) else src
                    if r and not int(r["lootid"]):
                        self.tables.setdefault("_lootid", set()).add(src)
                self.row("creature_loot_template", Entry=lootid, Item=iid, Reference=0, Chance=chance, QuestRequired=1,
                         LootMode=1, GroupId=0, MinCount=1, MaxCount=1, Comment="mod-murloc-naga")
        if len(req_npc) > 4 or len(req_items) > 6:
            self.err(where, "too many objectives")
        rewards = [self.item(i, chapter, where) for i in d.get("rewards", [])]
        choice = [self.item(i, chapter, where) for i in d.get("choice", [])]
        rep = d.get("rep", [])
        row = dict(ID=qid, QuestType=2, QuestLevel=level, MinLevel=d.get("min", max(1, level - 2)), QuestSortID=ch.get("zones", [0])[0],
                   Flags=8, RewardXPDifficulty=d.get("xp", 7), RewardMoney=d.get("money", 75 * level),
                   AllowableRaces=K.MURLOC_BIT, LogTitle=self.words(d["title"], lang_give),
                   LogDescription=d["goal"], QuestDescription=self.words(d["text"], lang_give), AreaDescription="",
                   QuestCompletionLog=self.completion_log(ender, chapter, where),
                   StartItem=self.item(d["source"], chapter, where) if d.get("source") else 0)
        for i, (e, n) in enumerate(req_npc[:4], start=1):
            row[f"RequiredNpcOrGo{i}"], row[f"RequiredNpcOrGoCount{i}"] = e, n
            if objective_text[i - 1]:
                row[f"ObjectiveText{i}"] = objective_text[i - 1]
        for i, (e, n) in enumerate(req_items[:6], start=1):
            row[f"RequiredItemId{i}"], row[f"RequiredItemCount{i}"] = e, n
        for i, e in enumerate(rewards[:4], start=1):
            row[f"RewardItem{i}"], row[f"RewardAmount{i}"] = e, 1
        for i, e in enumerate(choice[:6], start=1):
            row[f"RewardChoiceItemID{i}"], row[f"RewardChoiceItemQuantity{i}"] = e, 1
        for i, (f, v) in enumerate(rep[:5], start=1):
            row[f"RewardFactionID{i}"], row[f"RewardFactionOverride{i}"] = f, v * 100
        if d.get("source"):
            row["ItemDrop1"], row["ItemDropQuantity1"] = self.item(d["source"], chapter, where), 1
        self.row("quest_template", **row)
        prev = d.get("prev")
        prevs = prev if isinstance(prev, list) else ([prev] if prev else [])
        excl = d.get("exclusive", 0)
        self.row("quest_template_addon", ID=qid, PrevQuestID=self.quest(prevs[0], chapter, where) if len(prevs) == 1 else 0,
                 SpecialFlags=special, ProvidedItemCount=1 if d.get("source") else 0, ExclusiveGroup=excl and (K.QUEST_BASE + excl))
        if len(prevs) > 1:
            for p in prevs:
                self.condition(19, 0, qid, 8, self.quest(p, chapter, where))
        self.row("quest_offer_reward", ID=qid, Emote1=1, RewardText=self.words(d.get("reward", ""), lang_end))
        if d.get("progress"):
            self.row("quest_request_items", ID=qid, EmoteOnComplete=1, EmoteOnIncomplete=6,
                     CompletionText=self.words(d["progress"], lang_end))
        details = [self.emote_id(e, where) for e in d.get("details", ["talk"])][:4]
        self.row("quest_details", ID=qid, **{f"Emote{i + 1}": e for i, e in enumerate(details)})
        # who gives it, who ends it
        for kind, who in (("starter", giver), ("ender", ender)):
            if isinstance(who, str) and who.startswith("object:"):
                ok = self.qualify(chapter, who[7:])
                if ok not in self.go_id:
                    self.err(where, f"no object {who}")
                    continue
                self.row(f"gameobject_quest{kind}", id=self.go_id[ok], quest=qid)
            else:
                self.row(f"creature_quest{kind}", id=self.creature(who, chapter, where), quest=qid)
        # scenes
        g_entry = self.creature(giver, chapter, where) if not (isinstance(giver, str) and giver.startswith("object:")) else 0
        e_entry = self.creature(ender, chapter, where) if not (isinstance(ender, str) and ender.startswith("object:")) else 0
        if d.get("accept") and g_entry:
            lid = self.actionlist(g_entry, d["accept"], chapter, where + " (accept)")
            self.smart(g_entry, 0, EV_ACCEPTED_QUEST, (qid,), [(A_ACTIONLIST, (lid, 0, 0), (T_SELF,))])
        if d.get("complete") and e_entry:
            lid = self.actionlist(e_entry, d["complete"], chapter, where + " (reward)")
            self.smart(e_entry, 0, EV_REWARD_QUEST, (qid,), [(A_ACTIONLIST, (lid, 0, 0), (T_SELF,))])
        # where the map marks it
        self.pois(qid, d, req_npc, chapter, where)

    def completion_log(self, ender, chapter, where):
        if isinstance(ender, int):
            r = self.world_creatures.get(ender)
            return f"Return to {r['name']}." if r else ""
        if isinstance(ender, str) and ender.startswith("object:"):
            return ""
        d = self.npcs.get(self.qualify(chapter, ender))
        return f"Return to {d['name']}." if d else ""

    def interaction(self, entry, npc_key, qid, text, beats, chapter, where):
        """A gossip option on the NPC while [qid] is in the log: choosing it is the objective's credit."""
        d = self.npcs.get(npc_key)
        if d is None:
            self.err(where, f"no NPC {npc_key} to talk to")
            return
        menu = entry
        opt = self.next_option.get(entry, 0)
        self.next_option[entry] = opt + 1
        self.row("gossip_menu_option", MenuID=menu, OptionID=opt, OptionIcon=0, OptionText=self.words(text, self.language(d)),
                 OptionType=1, OptionNpcFlag=1)
        self.condition(15, menu, opt, 9, qid)
        self.condition(15, menu, opt, 28, qid, negative=1)
        actions = [(A_CLOSE_GOSSIP, (), (T_INVOKER,)), (A_KILLED_MONSTER, (entry,), (T_INVOKER,))]
        if beats:
            actions.append((A_ACTIONLIST, (self.actionlist(entry, beats, chapter, where), 0, 0), (T_SELF,)))
        self.smart(entry, 0, EV_GOSSIP_SELECT, (menu, opt), actions)

    def use(self, qid, item, target, count, req_npc, objective_text, chapter, where):
        """Using a quest item: on someone (a gossip option while you carry it), or at a place (a thing there
        to use)."""
        iid = self.item(item, chapter, where)
        if isinstance(target, str) and self.qualify(chapter, target) in self.npc_id:
            e = self.npc_id[self.qualify(chapter, target)]
            d = self.npcs[self.qualify(chapter, target)]
            menu = e
            opt = self.next_option.get(e, 0)
            self.next_option[e] = opt + 1
            self.row("gossip_menu_option", MenuID=menu, OptionID=opt, OptionIcon=0,
                     OptionText=self.words("Give it to them.", self.language(d)), OptionType=1, OptionNpcFlag=1)
            self.condition(15, menu, opt, 9, qid)
            self.condition(15, menu, opt, 2, iid, value2=1)
            self.smart(e, 0, EV_GOSSIP_SELECT, (menu, opt), [(A_CLOSE_GOSSIP, (), (T_INVOKER,)), (A_REMOVE_ITEM, (iid, 1), (T_INVOKER,)),
                                                              (A_KILLED_MONSTER, (e,), (T_INVOKER,))])
            req_npc.append((e, count))
            objective_text.append("")
            return
        # at a place: a thing to use there, which needs the item
        pt = self.point(target, chapter, where)
        entry = K.GO_BASE + len(self.go_id) + len(self.tables.get("_use_gos", []))
        if entry > K.GO_END:
            raise StoryError("too many objects")
        self.tables.setdefault("_use_gos", []).append(entry)
        data = [0] * 24
        data[1], data[3] = qid, 3000
        self.row("gameobject_template", entry=entry, type=10, displayId=self.display("sack", where), name="", size=0.6,
                 **{f"Data{i}": v for i, v in enumerate(data)})
        guid = self.next_go_spawn
        self.next_go_spawn += 1
        m, x, y, z = pt
        self.row("gameobject", guid=guid, id=entry, map=m, zoneId=0, areaId=0, spawnMask=1, phaseMask=K.RACE_PHASE,
                 position_x=round(x, 2), position_y=round(y, 2), position_z=round(z, 2), orientation=0, rotation0=0, rotation1=0,
                 rotation2=0, rotation3=1, spawntimesecs=10, animprogress=100, state=1, Comment="mod-murloc-naga: a quest's place")
        req_npc.append((-entry, count))
        objective_text.append("")

    def pois(self, qid, d, req_npc, chapter, where):
        ch = self.chapter_of_key(chapter)
        zone = ch.get("zones", [0])[0]
        wma = self.world_map_area(zone)
        if not wma:
            return
        marks = []
        for n, ref in enumerate(d.get("poi", [])):
            m, x, y, z = self.point(ref, chapter, where)
            marks.append((n if n < len(req_npc) else 0, m, x, y, d.get("poi_radius", 25)))
        if not marks:
            for n, (e, _) in enumerate(req_npc):
                if e > 0 and e not in self.npc_id.values():
                    pts = self.world_spawns(e, ch.get("map", K.MAP_KALIMDOR), ch.get("zones", []))
                    if pts:
                        x = sum(p[0] for p in pts) / len(pts)
                        y = sum(p[1] for p in pts) / len(pts)
                        r = max(20, min(120, max(math.hypot(p[0] - x, p[1] - y) for p in pts)))
                        marks.append((n, ch.get("map", K.MAP_KALIMDOR), x, y, r))
        for i, (obj, m, x, y, r) in enumerate(marks):
            self.row("quest_poi", QuestID=qid, id=i, ObjectiveIndex=obj, MapID=m, WorldMapAreaId=wma, Floor=0, Priority=0, Flags=3)
            for j, (px, py) in enumerate(_poly(x, y, r)):
                self.row("quest_poi_points", QuestID=qid, Idx1=i, Idx2=j, X=px, Y=py)

    def world_map_area(self, zone):
        if not hasattr(self, "_wma"):
            self._wma = {r[2]: r[0] for r in self.D("WorldMapArea").rows}
        return self._wma.get(zone, 0)

    def world_spawns(self, entry, mapid, zones):
        if not hasattr(self, "_spawns"):
            self._spawns = {}
            for r in self.W("creature"):
                self._spawns.setdefault(int(r["id1"]), []).append((float(r["position_x"]), float(r["position_y"]), int(r["map"]), int(r["zoneId"])))
        pts = [p for p in self._spawns.get(entry, []) if p[2] == mapid and (not zones or p[3] in zones or p[3] == 0)]
        return pts

    # -- phases --------------------------------------------------------------------------------
    def build_phases(self):
        """A story phase is a spell whose aura is its bit, on a murloc in the chapter's zones by the quest
        rule it was made for (spell_area)."""
        spell = K.PHASE_SPELL_BASE
        for (zones, (kind, quest)), bit in sorted(self.phase_bits.items(), key=lambda kv: kv[1]):
            self.phase_spells.append((spell, bit))
            for zone in zones:
                if kind == "from":
                    self.row("spell_area", spell=spell, area=zone, quest_start=quest, quest_end=0, aura_spell=0,
                             racemask=K.MURLOC_BIT, gender=2, autocast=1, quest_start_status=REWARDED, quest_end_status=0)
                elif kind == "until":
                    self.row("spell_area", spell=spell, area=zone, quest_start=0, quest_end=quest, aura_spell=0,
                             racemask=K.MURLOC_BIT, gender=2, autocast=1, quest_start_status=0, quest_end_status=REWARDED)
                elif kind == "during":
                    self.row("spell_area", spell=spell, area=zone, quest_start=quest, quest_end=0, aura_spell=0,
                             racemask=K.MURLOC_BIT, gender=2, autocast=1, quest_start_status=IN_LOG, quest_end_status=0)
                else:
                    raise StoryError(f"no phase rule {kind!r}")
            spell += 1
        if self.race_phase_spell:
            for zone in sorted(self.zones - {148, 331}):        # (those two: tools/build_data.py's)
                self.row("spell_area", spell=self.race_phase_spell, area=zone, quest_start=0, quest_end=0, aura_spell=0,
                         racemask=K.MURLOC_BIT, gender=2, autocast=1, quest_start_status=64, quest_end_status=11)


# -- lint ---------------------------------------------------------------------------------------------

ACTING = {"say", "yell", "whisper", "emote", "state", "move", "jump", "give", "take", "summon", "visual", "cast", "sound", "music"}


def lint(story):
    """What a chapter does that the story's rules frown on (show, don't tell; the right looks for
    acting), as warnings: [(where, what)]."""
    out = []
    emoting = {f"murloc_{n}" for n in K.TRIBE_SKINS}
    no_emote_murlocs = {"oracle", "tidehunter", "seer", "coastrunner", "netter", "warrior", "hunter", "raider",
                        "flagglemurk", "murkdeep", "fry_green", "fry_blue", "fry_pink", "fry_purple"}
    actors = set()
    for key, q in story.quests.items():
        for kind in ("accept", "complete"):
            beats = q.get(kind, [])
            acts = [b for b in beats if b[0] in ACTING]
            if len(acts) < 2:
                out.append((key, f"its {kind} scene does little ({len(acts)} beats that show something)"))
            for b in beats:
                if b[0] in ("say", "yell", "emote", "state") and len(b) > 1 and b[1] not in ("self", "player"):
                    actors.add(story.qualify(q["chapter"], b[1]))
            if any(b[0] in ("emote", "state") or (b[0] in ("say", "yell") and len(b) > 3 and b[3]) for b in beats):
                for who in (q.get("giver"), q.get("ender", q.get("giver"))):
                    if isinstance(who, str) and not who.startswith("object:"):
                        actors.add(story.qualify(q["chapter"], who))
        if not q.get("poi") and not q.get("kill") and (q.get("items") or q.get("explore") or q.get("use")):
            out.append((key, "no map marker (poi) for what it asks"))
        if len(q.get("text", "")) < 60:
            out.append((key, "a short quest text"))
    for key in actors:
        d = story.npcs.get(key)
        if d and isinstance(d.get("look"), str) and d["look"] in no_emote_murlocs and d.get("look") not in emoting:
            out.append((key, f"acts, but its look ({d['look']}) plays no emotes: a murloc_<skin> look does"))
    for ch in story.chapters:
        lo, hi = ch["CHAPTER"].get("levels", (1, 80))
        for q in ch.get("QUESTS", []):
            if not lo <= q["level"] <= hi + 1:
                out.append((f"{ch['CHAPTER']['key']}:{q['key']}", f"level {q['level']} outside the chapter's {lo}-{hi}"))
    return out


# -- SQL ---------------------------------------------------------------------------------------------

def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, str):
        return "'" + v.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
    if isinstance(v, float):
        return repr(round(v, 6))
    return str(v)


DELETES = [
    ("creature_template", f"`entry` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("creature_template_model", f"`CreatureID` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("creature_text", f"`CreatureID` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("creature_addon", f"`guid` BETWEEN {K.SPAWN_BASE} AND {K.SPAWN_END}"),
    ("creature", f"`guid` BETWEEN {K.SPAWN_BASE} AND {K.SPAWN_END}"),
    ("smart_scripts", f"(`source_type` = 0 AND `entryorguid` BETWEEN {K.NPC_BASE} AND {K.NPC_END}) OR (`source_type` = 9 AND `entryorguid` BETWEEN {K.NPC_BASE * 100} AND {K.NPC_END * 100 + 99})"),
    ("npc_text", f"`ID` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("gossip_menu", f"`MenuID` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("gossip_menu_option", f"`MenuID` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("npc_vendor", f"`entry` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("trainer_spell", f"`TrainerId` BETWEEN {K.TRAINER_BASE} AND {K.TRAINER_BASE + 999}"),
    ("trainer", f"`Id` BETWEEN {K.TRAINER_BASE} AND {K.TRAINER_BASE + 999}"),
    ("creature_default_trainer", f"`CreatureId` BETWEEN {K.NPC_BASE} AND {K.NPC_END}"),
    ("gameobject_template", f"`entry` BETWEEN {K.GO_BASE} AND {K.GO_END}"),
    ("gameobject", f"`guid` BETWEEN {K.GO_SPAWN_BASE} AND {K.GO_SPAWN_END}"),
    ("gameobject_loot_template", f"`Entry` BETWEEN {K.GO_BASE} AND {K.GO_END}"),
    ("creature_loot_template", "`Comment` = 'mod-murloc-naga'"),
    ("item_template", f"`entry` BETWEEN {K.ITEM_BASE} AND {K.ITEM_END}"),
    ("quest_template", f"`ID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_template_addon", f"`ID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_offer_reward", f"`ID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_request_items", f"`ID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_details", f"`ID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("creature_queststarter", f"`quest` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("creature_questender", f"`quest` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("gameobject_queststarter", f"`quest` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("gameobject_questender", f"`quest` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_poi", f"`QuestID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("quest_poi_points", f"`QuestID` BETWEEN {K.QUEST_BASE} AND {K.QUEST_END}"),
    ("conditions", "`Comment` = 'mod-murloc-naga'"),
    ("spell_area", f"`spell` BETWEEN {K.PHASE_SPELL_BASE} AND {K.PHASE_SPELL_BASE + 99} OR (`racemask` = {K.MURLOC_BIT} AND `area` NOT IN (148, 331))"),
]


def sql(tables, phase_spells_sql=""):
    """The SQL of [tables]: every row of the module's ranges taken out first, then put in."""
    out = ["-- The murlocs' questline (mod-murloc-naga): made by tools/murloc/compile.py from tools/murloc/ch*.py; edit those.",
           "-- Safe to run again: every row of its ranges is taken out first."]
    for table, where in DELETES:
        out.append(f"DELETE FROM `{table}` WHERE {where};")
    for src in sorted(tables.get("_lootid", [])):
        out.append(f"UPDATE `creature_template` SET `lootid` = `entry` WHERE `entry` = {src} AND `lootid` = 0;")
    for tid, best in tables.get("_trainer_copy", []):
        out.append(f"INSERT INTO `trainer_spell` (`TrainerId`, `SpellId`, `MoneyCost`, `ReqSkillLine`, `ReqSkillRank`, `ReqAbility1`, "
                   f"`ReqAbility2`, `ReqAbility3`, `ReqLevel`) SELECT {tid}, `SpellId`, `MoneyCost`, `ReqSkillLine`, `ReqSkillRank`, "
                   f"`ReqAbility1`, `ReqAbility2`, `ReqAbility3`, `ReqLevel` FROM `trainer_spell` WHERE `TrainerId` = {best};")
    for table, rows in tables.items():
        if table.startswith("_") or not rows:
            continue
        cols = []
        for r in rows:
            for c in r:
                if c not in cols:
                    cols.append(c)
        for i in range(0, len(rows), 300):
            body = ",\n".join("(" + ", ".join(q(r.get(c, DEFAULTS.get((table, c), 0))) for c in cols) + ")" for r in rows[i:i + 300])
            out.append(f"INSERT INTO `{table}` ({', '.join(f'`{c}`' for c in cols)}) VALUES\n{body};")
    if phase_spells_sql:
        out.append(phase_spells_sql)
    story = tables.get("_story")
    if story:
        out.append("-- What the module (src/MurlocNaga.cpp) reads of the story: its quests' range, and the last quest, which opens\n"
                   "-- the Alliance's and everyone's quests to a murloc.")
        out.append("CREATE TABLE IF NOT EXISTS `murloc_naga_story` (`name` VARCHAR(32) NOT NULL PRIMARY KEY, `value` INT UNSIGNED NOT NULL);")
        out.append("REPLACE INTO `murloc_naga_story` (`name`, `value`) VALUES "
                   + ", ".join(f"({q(k)}, {v})" for k, v in sorted(story.items())) + ";")
    return "\n".join(out) + "\n"


DEFAULTS = {
    ("creature_template", "subname"): "", ("creature_template", "ScriptName"): "", ("creature_template", "AIName"): "",
    ("item_template", "description"): "", ("quest_template", "ObjectiveText1"): "", ("quest_template", "ObjectiveText2"): "",
    ("quest_template", "ObjectiveText3"): "", ("quest_template", "ObjectiveText4"): "", ("creature", "Comment"): "",
    ("gameobject", "Comment"): "", ("gameobject_template", "name"): "", ("npc_text", "text0_0"): "", ("npc_text", "text0_1"): "",
}
