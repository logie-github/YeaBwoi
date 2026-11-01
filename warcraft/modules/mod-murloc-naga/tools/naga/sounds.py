# SPDX-License-Identifier: GPL-3.0-or-later
"""The naga's fetched sounds by name (bible 18.5: Warcraft III's voices from warcraft.wiki.gg, fetched as the
server is packaged, never committed), each its SoundEntries id (995000-995199) in the patch. A chapter names
them in its beats: ("sound", "self", "LadyVashjReady1"), ("say", "vashj", "...", "talk", "LadyVashjYes1").
The naga's assets write this list; these few stand in until they do. Data only."""

SOUNDS = {
    "LadyVashjReady1": 995000,
    "NagaSirenReady1": 995001,
    "NagaSirenWhat4": 995002,
}
