# SPDX-License-Identifier: GPL-3.0-or-later
"""STUB. NOT THE STORY. Chapter 1's writer replaces this file with The Low Tide (the bible's section 8).

A small chapter that exercises the naga's story engine on the realm (tools/naga/test.steps): the start at
Rethress Sanctum, a hub, and three quests: one plain (a kill), one lived inside a memory (relive on accept, its
objectives inside, a death inside, awake at the pool when it's done), one after it (a question asked: a quiz).
Its words are placeholders; its people are the bible's names in the bible's places, nothing more.

Azshara shows the naga's phase alone (no world) until chapter 7's live world quest (alone_until: a quest of a
chapter not written yet, so for now no one has done it)."""

CHAPTER = dict(key="ch1", title="The Low Tide (stub)", levels=(10, 15), map=1, zones=[16], hub="creche",
               start="start", start_facing=5.35, alone_until="ch7:ch7_live_world")

PLACES = {
    # Rethress Sanctum: the beach the crèche stands on (no live creature near: the naga's phase alone anyway)
    "start": (2205.0, -6445.0), "matron": (2218.0, -6462.0), "shrine": (2226.0, -6471.0), "creche": (2210.0, -6455.0),
    "claw_1": (2210.0, -6390.0), "claw_2": (2220.0, -6380.0), "claw_3": (2215.0, -6370.0), "claw_4": (2222.0, -6395.0),
    "isshara": (2166.0, -6457.0), "pool": (2152.0, -6458.0), "pool_shore": (2176.0, -6451.0),
    "vyssa": (2200.0, -6430.0), "graveyard": (2215.0, -6440.0),
    # Memory A's stage: the Ravencrest Monument (the bible's M0: the broken colossus as Azshara's statue)
    "m0_centre": (2425.0, -6831.0), "m0_arrive": (2441.0, -6806.0), "m0_isshara": (2432.0, -6816.0),
    "m0_fel_1": (2412.0, -6846.0), "m0_fel_2": (2446.0, -6849.0), "m0_fel_3": (2422.0, -6862.0),
}

NPCS = [
    dict(key="matron", name="Matron Szessh", subname="Matron of the Crèche", look="spitelash_screamer", level=20, role="quest",
         at="matron", facing=2.2, gossip="(Stub.) The crèche has work for every tail.",
         idle=[("every", 50000, [("emote", "self", "spell")])]),
    dict(key="clawling", name="Clawling", look="makrinni", size=0.8, level=(10, 11), side="neutral", role="enemy", type="beast",
         at=["claw_1", "claw_2", "claw_3", "claw_4"], wander=4.0, respawn=30),
    # the memory's people (memory A, the Ravencrest Monument's stage): seen only while reliving it
    dict(key="young_isshara", name="Isshara", subname="Handmaiden of the Queen", look="coilfang_siren", level=30, untouchable=True,
         at="m0_isshara", facing=4.0, phase=("memory", "m0"), idle=[("state", "self", "channel")]),
    dict(key="felguard", name="Remembered Felguard", look="felguard", size=1.3, level=(10, 11), side="hostile", role="enemy",
         type="demon", at=["m0_fel_1", "m0_fel_2", "m0_fel_3"], phase=("memory", "m0"), respawn=20),
]

OBJECTS = [
    dict(key="banner", model="naga_banner", name="The Empress's Banner", at="shrine", kind="doodad", facing=2.2),
]

ITEMS = [
    dict(key="knife", name="Coral-Tooth Knife", kind="weapon:dagger", quality=2, level=12, stats=[("agi", 2), ("sta", 1)]),
]

AURAS = []

MEMORIES = [
    dict(key="m0", name="Zin-Azshari (stub)", whose="Isshara", bit="A", text="Isshara's memory (stub).",
         stage="m0_arrive", facing=3.9, centre="m0_centre", radius=120, pool="pool_shore", pool_facing=3.1,
         singer="ch0:isshara", again="(Stub.) Sing it again."),
]

QUESTS = [
    dict(key="ch1_chores", title="Clawlings in the Egg Beds (stub)", giver="matron", ender="matron", level=10,
         goal="Kill 3 Clawlings on the beach of Rethress Sanctum.", kill=[("clawling", 3)],
         text="(Stub: chapter 1's writer replaces this quest.) The clawlings are in the egg beds again. Go and see to it.",
         progress="(Stub.) The beds are still crawling.", reward="(Stub.) Good. The eggs are safe for another night.",
         accept=[("emote", "self", "talk"), ("emote", "self", "spell")], complete=[("emote", "self", "talk"), ("emote", "self", "roar")],
         rewards=["knife"]),
    dict(key="ch1_song", title="The Deep Pool (stub)", giver="ch0:isshara", ender="ch0:isshara", level=11, prev="ch1_chores",
         memory="m0", wake=True, goal="In Isshara's memory, slay 2 Remembered Felguards.", kill=[("felguard", 2)],
         text="(Stub: chapter 1's writer replaces this quest.) Touch the water while I sing, little one. You will be there.",
         progress="(Stub.) The song isn't over.", reward="(Stub.) Now you have seen it. Now you know.",
         accept=[("say", "self", "(Stub.) Listen.", "talk"), ("state", "self", "channel"), ("sound", "self", "siren_hiss"),
                 ("wait", 2500), ("relive", "m0"), ("state", "self", "none")],
         complete=[("emote", "self", "talk"), ("emote", "self", "omnicast")]),
    dict(key="ch1_question", title="What the Water Showed (stub)", giver="ch0:isshara", ender="ch0:vyssa", level=11,
         prev="ch1_song", goal="Answer Isshara's question, then speak with Vyssa the Coral-Keeper.",
         quiz=[dict(npc="ch0:isshara", ask="(Stub.) Ask me what I saw.", question="(Stub.) What did the water show you?",
                    goal="Isshara's question answered",
                    answers=[("(Stub.) The Empress, saving us.", False, [("say", "self", "(Stub.) No. Listen again.", "talk")]),
                             ("(Stub.) Fire, and the Well, and the sea coming in.", True, [("emote", "self", "omnicast")]),
                             ("(Stub.) Nothing. Only the sea.", False, [("emote", "self", "roar")])])],
         text="(Stub: chapter 1's writer replaces this quest.) Tell me what you saw. Then go and eat something: Vyssa will feed you.",
         progress="(Stub.) Well?", reward="(Stub.) Isshara sent you? Then sit. Eat.",
         accept=[("emote", "self", "talk"), ("face", "self", "player")], complete=[("emote", "self", "talk"), ("emote", "self", "work")]),
]

SPAWNS = [
    dict(npc="ch0:isshara", at="isshara", facing=3.0),
    dict(npc="ch0:vyssa", at="vyssa", facing=4.5),      # (stub: the bible has her at the Hollow from chapter 2)
]

GRAVEYARDS = [
    dict(at="graveyard", name="the crèche"),
]
