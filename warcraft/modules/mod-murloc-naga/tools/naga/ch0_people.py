# SPDX-License-Identifier: GPL-3.0-or-later
"""The naga's people who come back chapter after chapter (tools/naga/SCHEMA.md, tools/murloc/SCHEMA.md): the
Tide-Broken's own, met in one chapter and met again in others (the bible's 6.1). Nobody here stands anywhere:
each chapter puts them at its places (SPAWNS), with its own phase rules.

Lady Szaria, Myrmidon Ssaveth and Vyssa the Coral-Keeper were the retired Darkshore camp's people: they keep
their creature entries (entry=, tools/murloc/profiles.py's KEPT_ENTRIES), so a realm that had them has them
still, now the story's. Their pasts are the bible's (6.1)."""

CHAPTER = dict(key="ch0", title="The Tide-Broken", levels=(10, 55), map=1, zones=[])

PLACES = {}

NPCS = [
    dict(key="szaria", entry=975017, name="Lady Szaria", subname="The Tide-Broken", look="siren", level=40, role="quest",
         gossip="We were the Empress's once. Then Vashj's. Now we are no one's but our own, and I mean to keep it that way.",
         idle=[("every", 40000, [("emote", "self", "talk")])]),
    dict(key="ssaveth", entry=975018, name="Myrmidon Ssaveth", subname="The Tide-Broken", look="naga_warrior", level=40, role="quest",
         gossip="I watch the water. Whatever comes, it comes from the water.",
         idle=[("state", "self", "ready_thrown"), ("every", 55000, [("emote", "self", "roar")])]),
    dict(key="vyssa", entry=975019, name="Vyssa the Coral-Keeper", subname="Innkeeper", look="sorceress", level=35,
         role="quest+innkeeper", gossip="Coral, kelp, and a dry corner if you can find one. Sit. Eat something.",
         idle=[("state", "self", "work")]),
    dict(key="isshara", name="Isshara the Keeper", subname="Keeper of Songs", look="sea_witch", level=45, role="quest",
         gossip="Come closer, little one. I can't see you, but I can hear the sea in you.",
         idle=[("every", 45000, [("emote", "self", "omnicast"), ("sound", "self", "siren_hiss")])]),
]
