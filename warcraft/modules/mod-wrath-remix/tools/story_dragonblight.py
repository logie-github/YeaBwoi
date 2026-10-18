"""The Dragonblight and the bronze, up to WRM-204, while Moratari and Eternus steer the war (STORY.md,
"Moratari in the war"; tools/story.py writes it).

- Alliance: WRM-223 The Second Sky (Wintergarde's dispositions for Naxxramas's next blows: the
  archers off the square, the mine road closed, the gryphons off the cracked roost); The Noble's
  Crypt (Necrolord Amarion) put on the road before Chromie's summons; a 7th Legion company sent to
  Bolvar at Fordragon Hold, which the High Commander recalls once the Timerunner has met Sylvanas
  (WRM-201 says so).
- Horde: WRM-236 Eyes and Ears (a word for Saurfang's son, through Messenger Torvus); WRM-234
  Before the Second Wave (the cultists empowering the blightbeasts over Icemist, and the road to
  Venomspite the game leaves out); WRM-235 A Careful Inventory (what the Royal Apothecary Society's
  people let slip about the blight: evidence the Timerunner must never show anyone of this year).
- The bronze: Moratari waits outside the time storm (no immortal may enter), her words on
  Nozdormu, and one of the Infinite who was watching the Timerunner, not the hourglass.
"""
from story_common import (ALLIANCE, HORDE, BOTH, TAKEN, DONE, TURNED_IN, ZONE_DRAGONBLIGHT, STRANGERS, WEIGHT_OF_TIME,
                          rewarded, not_rewarded)

# The game's people
WYRMBANE, URIK, DURKON, LYNORE, LEORIC = 27136, 27317, 27318, 27155, 27156
AGMAR, JUKTOK, TORVUS, SINTAR, MIDDLETON, MOLDER, LAWRENCE = 26379, 26415, 26649, 26505, 27172, 27320, 27846
ANUBAR_CULTIST = 26319
CHROMIE = 27856

# The game's quests
FALL_OF_WINTERGARDE, FLIGHT_OF_THE_DEFENDER, RETURN_TO_THE_HIGH_COMMANDER, RESCUE_FROM_TOWN_SQUARE, FIND_DURKON, NOBLES_CRYPT = (
    12235, 12237, 12251, 12253, 12309, 12311)
VICTORY_NEARS, FROM_THE_DEPTHS, IMBECILES_ABOUND, HOW_NOT_TO_DIE, EMERALD_TEARS, SPREAD_THE_GOOD_WORD, THE_FORSAKEN_BLIGHT = (
    12034, 12036, 12189, 12188, 12200, 12218, 12221)
MYSTERY_OF_THE_INFINITE = 12470
ROAD_TO_ANGRATHAR_A, ROAD_TO_ANGRATHAR_H, SUMMONS_A, SUMMONS_H = 77201, 77211, 77203, 77213
HOUR_OF_THE_GATE_H = 77218

# This file's quests
SECOND_SKY, BEFORE_THE_SECOND_WAVE, CAREFUL_INVENTORY, EYES_AND_EARS = 77223, 77234, 77235, 77236

# Credits and new creatures
C_LYNORE, C_LEORIC, C_URIK, C_TORVUS, C_MOLDER, C_LAWRENCE = 770580, 770581, 770582, 770583, 770584, 770585
LEGION_COMPANY, INFINITE_WATCHER = 770590, 770591

SCENE_INFINITE_NOTICES = 201

CREATURES = [
    dict(entry=C_LYNORE, name="Commander Lynore Windstryke warned", credit=True),
    dict(entry=C_LEORIC, name="Highlord Leoric Von Zeldig warned", credit=True),
    dict(entry=C_URIK, name="Gryphon Commander Urik warned", credit=True),
    dict(entry=C_TORVUS, name="A word sent with Messenger Torvus", credit=True),
    dict(entry=C_MOLDER, name="Deathguard Molder asked about the blight spreaders", credit=True),
    dict(entry=C_LAWRENCE, name="Junior Apothecary Lawrence asked about the shipments", credit=True),
    dict(entry=LEGION_COMPANY, **{"from": 27162}, name="7th Legion Sentinel", subname="Wintergarde's company", unit_flags=770,
         why="The company Wyrmbane sends to Bolvar at Fordragon Hold, until he recalls it"),
    dict(entry=INFINITE_WATCHER, **{"from": 27898}, name="Infinite Chrono-Magus", unit_flags=770,
         why="One of the Infinite, watching the Timerunner atop Wyrmrest (a scene's)"),
]

UNTIL_RECALLED = not_rewarded(ROAD_TO_ANGRATHAR_A)
SPAWNS = [
    # Moratari beside Chief Plaguebringer Middleton at Venomspite while A Careful Inventory is hers to give.
    dict(guid=7704384, entry=770001, x=3225.2, y=-730.9, z=168.1, o=2.51, equip=0,
         visible=[[rewarded(SPREAD_THE_GOOD_WORD), not_rewarded(THE_FORSAKEN_BLIGHT)]], why="Moratari beside Middleton, Venomspite"),
    dict(guid=7704380, entry=LEGION_COMPANY, x=4698.0, y=1445.5, z=188.6, o=2.80, visible=[[rewarded(NOBLES_CRYPT), UNTIL_RECALLED]],
         why="Wintergarde's company at Fordragon Hold"),
    dict(guid=7704381, entry=LEGION_COMPANY, x=4698.5, y=1450.0, z=188.7, o=2.80, visible=[[rewarded(NOBLES_CRYPT), UNTIL_RECALLED]],
         why="Wintergarde's company at Fordragon Hold"),
    dict(guid=7704382, entry=LEGION_COMPANY, x=4699.0, y=1454.5, z=188.8, o=2.80, visible=[[rewarded(NOBLES_CRYPT), UNTIL_RECALLED]],
         why="Wintergarde's company at Fordragon Hold"),
    dict(guid=7704383, entry=LEGION_COMPANY, x=4694.5, y=1449.0, z=188.6, o=2.80, visible=[[rewarded(NOBLES_CRYPT), UNTIL_RECALLED]],
         why="Wintergarde's company at Fordragon Hold"),
]

COMMON_A = dict(side=ALLIANCE, sort=ZONE_DRAGONBLIGHT, level=-1, min=20, bronze=800)
COMMON_H = dict(side=HORDE, sort=ZONE_DRAGONBLIGHT, level=-1, min=20, bronze=800)

QUESTS = [
    dict(COMMON_A, id=SECOND_SKY, title="The Second Sky", prev=FLIGHT_OF_THE_DEFENDER,
         log="Warn Commander Lynore Windstryke, Highlord Leoric Von Zeldig and Gryphon Commander Urik of what Naxxramas will do next.",
         desc="Naxxramas has already fallen on Wintergarde, and the lower village is lost. That much I cannot change, $N. It happened "
              "before either of us arrived.$B$BBut I know where it strikes next. The risen gryphon riders come over the square from above. "
              "The dead come up the mine road. And the roost that cracked on the first night comes down with the birds in it.$B$BThe "
              "commander, the highlord and the gryphon master. A veteran's eye, a hunch, a rumor from the crypt: say whatever they will "
              "believe.",
         done="Return to Moratari.",
         objectives=[(C_LYNORE, 1, "Commander Lynore Windstryke warned"), (C_LEORIC, 1, "Highlord Leoric Von Zeldig warned"),
                     (C_URIK, 1, "Gryphon Commander Urik warned")],
         request="Did they listen?",
         reward="Two of them believed you, and the third will do it anyway, to prove you wrong. That is the 7th Legion.$B$B"
                "Fewer names on Wintergarde's stones. Eternus will be pleased. So, I find, am I."),
    dict(COMMON_H, id=EYES_AND_EARS, title="Eyes and Ears", prev=VICTORY_NEARS, ender=TORVUS,
         log="Send a word to High Overlord Saurfang with Messenger Torvus at Agmar's Hammer.",
         desc="Messenger Torvus carries Saurfang's letters, the ones Agmar is not meant to read. Saurfang has sent his son to command the "
              "Horde's forces at the Wrathgate, and the son is a better soldier than he is a careful one.$B$BSend a word back with Torvus. "
              "Not what you know, $N. A soldier's superstition: you have seen sons die proving themselves to their fathers. Let a father "
              "read that, and see what he does with it.",
         done="Speak with Messenger Torvus.",
         objectives=[(C_TORVUS, 1, "A word sent with Messenger Torvus")],
         reward="<Messenger Torvus folds your note into his glove.>$B$BSaurfang's son needs no nursemaid. But a father may want to hear it, "
                "and I will carry it. No one reads my glove."),
    dict(COMMON_H, id=BEFORE_THE_SECOND_WAVE, title="Before the Second Wave", prev=FROM_THE_DEPTHS, ender=SINTAR,
         log="Slay 8 Anub'ar Cultists at Icemist Village, then speak with Doctor Sintar Malefious at Agmar's Hammer.",
         desc="Icemist is already gone. The Anub'ar razed it before the Horde arrived, and nothing either of us can do will raise those "
              "walls again.$B$BBut the Anub'ar are still there, and their cultists are breathing life into the blightbeasts on the "
              "village's ruins. In the record, those beasts fly to the Wrathgate, and the taunka who would have held its flank die under "
              "them.$B$BKill the cultists before the beasts are ready. Then come back to Agmar's Hammer: the Grand Apothecary has a "
              "shipment I want you to see.",
         done="Speak with Doctor Sintar Malefious at Agmar's Hammer.",
         objectives=[(ANUBAR_CULTIST, 8, "")],
         request="What? Can't you see that I'm busy?",
         reward="You again. The one Agmar keeps sending on errands. <Doctor Malefious looks you over.> Good. I have an errand of my own."),
    dict(COMMON_H, id=CAREFUL_INVENTORY, title="A Careful Inventory", prev=SPREAD_THE_GOOD_WORD,
         log="Ask Deathguard Molder and Junior Apothecary Lawrence at Venomspite about the blight's shipments, then return to Moratari.",
         desc="The field test was a success. Middleton says so, and Middleton is very proud. Before you carry the blight to Agmar's Hammer, "
              "I want you to count something, $N.$B$BThe deathguard who wanted the blight spreaders for his siege and was told they were "
              "for 'special missions'. The junior apothecary who signs for the shipments. Ask them. Carefully. Then keep whatever you learn "
              "to yourself: nobody in this year can hear it from you, not Agmar, not the Warchief, not anyone.",
         done="Return to Moratari.",
         objectives=[(C_MOLDER, 1, "Deathguard Molder asked about the blight spreaders"),
                     (C_LAWRENCE, 1, "Junior Apothecary Lawrence asked about the shipments")],
         request="Well? What did you count?",
         reward="More blight than Agmar ordered, consigned to 'the Grand Apothecary's special detachment'. Spreaders kept back for 'special "
                "missions'. And an apothecary who thinks the word 'living' slips out by accident.$B$BSay nothing, $N. Not yet. Eternus has "
                "a plan for the Wrathgate, and I think, now, I understand what it is for."),
]

GATES = {
    RETURN_TO_THE_HIGH_COMMANDER: [[rewarded(SECOND_SKY)]],
    FROM_THE_DEPTHS: [[rewarded(EYES_AND_EARS)]],
    IMBECILES_ABOUND: [[rewarded(BEFORE_THE_SECOND_WAVE)]],
    THE_FORSAKEN_BLIGHT: [[rewarded(CAREFUL_INVENTORY)]],
}

TEXTS = {
    NOBLES_CRYPT: dict(
        reward="Now if we could stop the Scourge attacks coming from outside the keep we might be able to send help to Lord Fordragon at "
               "the Wrathgate.$B$B<Durkon looks at you for a long moment.>$B$BYou went straight for the bottom chamber, like you'd been "
               "down there before. Lucky guess. I'll call it that.$B$BThe High Commander's sending a company to Fordragon Hold tonight. "
               "Your doing too, I expect."),
}

TALK = [
    # WRM-223
    dict(npc=LYNORE, quest=SECOND_SKY, credit=C_LYNORE,
         option="Commander, the riders who fell over the village will come back over the square. Pull your archers off the roofs before "
                "they do.",
         reply="<Commander Lynore Windstryke studies the sky over the square.>$B$BOur own dead, raining stormhammers on us. I've had the "
               "same nightmare every night since the citadel came down.$B$BYou've a soldier's eye, $c. Fine. The archers come down to the "
               "walls."),
    dict(npc=LEORIC, quest=SECOND_SKY, credit=C_LEORIC,
         option="Highlord, close the mine road. The dead will come up it before the week is out.",
         reply="Close the mine road? On whose authority? <Highlord Leoric Von Zeldig snorts.>$B$B...Mine, I suppose. Very well. If you're "
               "wrong, I've lost a road. If you're right, I've kept a keep."),
    dict(npc=URIK, quest=SECOND_SKY, credit=C_URIK,
         option="Gryphon Commander, the roost that cracked on the first night won't hold. Move your birds tonight.",
         reply="My roost? <Urik runs a hand over the cracked beam.>$B$BI'd thought it would last the week. Gryphons know when a perch is "
               "bad, though, and they've been restless.$B$BAll right. We move them tonight. If it's still standing in the morning, you're "
               "carrying the feed sacks."),
    # WRM-236
    dict(npc=TORVUS, quest=EYES_AND_EARS, credit=C_TORVUS,
         option="Tell Saurfang this, from me: I have seen sons die proving themselves to their fathers. His should not lead the charge "
                "himself.",
         reply="<Messenger Torvus is quiet for a moment.>$B$BYou speak as one who has buried a friend's son. I will carry it, word for word."),
    # WRM-235
    dict(npc=MOLDER, quest=CAREFUL_INVENTORY, credit=C_MOLDER,
         option="Deathguard, those blight spreaders by the east gate. Why can't you use them on the siegesmiths?",
         reply="Because the apothecaries say they're 'only for special missions'! <Deathguard Molder spits.>$B$BSpecial missions. I've a "
               "Scourge siege yard full of ballistae and they won't lend me a single wagon. Whatever they're saving them for, it had better "
               "be worth it."),
    dict(npc=LAWRENCE, quest=CAREFUL_INVENTORY, credit=C_LAWRENCE,
         option="Apothecary, I'm counting crates for Agmar's Hammer. How much of the blight is going there?",
         reply="Agmar's Hammer? Oh, only the specimen. <Junior Apothecary Lawrence shuffles his papers.> The rest is consigned to the Grand "
               "Apothecary's special detachment. I'm not to say where. I'm not even sure I'm supposed to have said that.$B$BYou won't tell "
               "anyone I said that?"),
]

SCENES = [
    # Mystery of the Infinite, as it's turned in: one of the Infinite was watching the Timerunner.
    dict(id=SCENE_INFINITE_NOTICES, name="Mystery of the Infinite: the Infinite notices", quest=MYSTERY_OF_THE_INFINITE, trigger=2, anchor=CHROMIE,
         steps=[
             ("bind", 1, CHROMIE),
             ("summon", 2, INFINITE_WATCHER, 14.0, 9.0, 3.14),
             ("face", 2, 255),
             ("wait", 2500),
             ("face", 1, 2),
             ("say", 1, "Don't turn around too quickly. There's one of them on the edge of the platform."),
             ("say", 1, "That one wasn't watching the hourglass, $N. It was watching you."),
             ("cast", 2, 52096, 0),
             ("wait", 1200),
             ("despawn", 2),
             ("face", 1, 255),
             ("say", 1, "Gone. They'll remember your face now. Try not to make it too memorable, all right?"),
         ]),
]

WORDS = [
    # Alliance
    (FALL_OF_WINTERGARDE, TAKEN, ALLIANCE, "Wintergarde. I showed you this place once already, in the lower village, the night the plague "
                                           "came down. Naxxramas fell on it because Bolvar struck at the gate first. The Lich King answers "
                                           "his front door by dropping a citadel on the back one."),
    (FLIGHT_OF_THE_DEFENDER, TAKEN, ALLIANCE, "'I picked a bad day to stop drinking!' One of them says that every time. Every single time. "
                                              "I have started to look forward to it."),
    (FLIGHT_OF_THE_DEFENDER, TURNED_IN, ALLIANCE, "The roost. Look at the beam above the gryphons. Talk to me before the High Commander "
                                                  "does.", dict(delay=4000)),
    (SECOND_SKY, TAKEN, ALLIANCE, "Lynore by the High Commander, Leoric up the hill, Urik at the roost. A veteran's eye. You have one; use "
                                  "it."),
    (RESCUE_FROM_TOWN_SQUARE, TAKEN, ALLIANCE, "The geists in the square were the living, last week. They do not know they are hunting their "
                                               "own neighbors. Free whoever you can."),
    (FIND_DURKON, TURNED_IN, ALLIANCE, "Necrolord Amarion is in the crypt, raising the nobles buried there. In the record, the crypt is "
                                       "cleared the day after Chromie sends for you. A day is a long time in a crypt. Go tonight."),
    (NOBLES_CRYPT, TAKEN, ALLIANCE, "There is a shadow beside the necrolord: Thel'zan's. Thel'zan himself is under the old village, in the "
                                    "mausoleum. That fight comes later, and you will win it."),
    (NOBLES_CRYPT, TURNED_IN, ALLIANCE, "A company to Fordragon Hold. In the record, Wintergarde's help never reaches Bolvar: the dead at the "
                                        "gate are all Fordragon's. Eternus will be very pleased with this one.", dict(delay=5000)),
    # Horde
    (VICTORY_NEARS, TAKEN, HORDE, "Agmar, Kor'kron Commander. 'His word is life and his word is death', he says of Hellscream. He reads "
                                  "everyone's mail, too. Keep yours short."),
    (EYES_AND_EARS, TAKEN, HORDE, "Torvus, by the burning brazier. Agmar demands that every letter be read before it is delivered. Torvus "
                                  "has never let him read one."),
    (FROM_THE_DEPTHS, TAKEN, HORDE, "The Pit of Narjun opens into Azjol-Nerub. You have been down there before, in your own year, and killed "
                                    "its king. Twice. The nerubian at the edge of the pit is Kilix; not everything down there serves the "
                                    "Lich King."),
    (BEFORE_THE_SECOND_WAVE, TAKEN, HORDE, "The cultists are on Icemist's ruins, breathing on the blightbeasts. The beasts are for the "
                                           "Wrathgate. Make them go to it short-handed."),
    (BEFORE_THE_SECOND_WAVE, TURNED_IN, HORDE, "Sintar is a Grand Apothecary. So is another apothecary you have heard of, whose name nobody "
                                               "here says yet. Watch what you carry for him.", dict(delay=4000)),
    (IMBECILES_ABOUND, TAKEN, HORDE, "'The last piece of the victory puzzle.' Venomspite, south of here. Chief Plaguebringer Middleton has "
                                     "the shipment, and he is very proud of it."),
    (HOW_NOT_TO_DIE, TAKEN, HORDE, "The specters on the beach are Prince Arthas's own soldiers, left to freeze when he burned his ships. The "
                                   "Royal Apothecary Society grinds his abandoned dead into its weapon. There is a lesson in that, but I "
                                   "am not sure anyone learns it."),
    (EMERALD_TEARS, TAKEN, HORDE, "Green dragons come to the Emerald Dragonshrine to die, and they weep as they go. 'They cry a lot right "
                                  "before they die. Go figure.' Do not let me hear you say that to a dragon, $N."),
    (SPREAD_THE_GOOD_WORD, TAKEN, HORDE, "A field test on Wintergarde's dead. Listen to Middleton afterwards: 'we will ravage the living... I "
                                         "mean the Scourge.' He says it like a joke."),
    (CAREFUL_INVENTORY, TAKEN, HORDE, "Molder by the east gate, Lawrence by the alchemy tables. Count, $N. Do not accuse."),
    (THE_FORSAKEN_BLIGHT, TURNED_IN, HORDE, "'This work is all classified.' It is. It is also the weapon that falls on everyone at the "
                                            "Wrathgate. I would like very much to be wrong about that.", dict(delay=4000)),
    # The bronze
    (MYSTERY_OF_THE_INFINITE, TAKEN, BOTH, "A time storm keeps immortals out of the Bronze Dragonshrine. It is the only thing Chromie and "
                                           "I have in common: neither of us may go in. You may. I will wait at the edge."),
    (MYSTERY_OF_THE_INFINITE, DONE, BOTH, "She will think the hourglass failed. It showed her exactly what she asked for; she cannot read it "
                                          "yet. Neither could I, for a long time."),
    (MYSTERY_OF_THE_INFINITE, TURNED_IN, BOTH, "Do not take it for fate, $N. The Lord of Time has spent ages refusing an ending he was "
                                               "shown. If he can refuse one, so can we. That is what Eternus would say, anyway.",
     dict(delay=16000)),
    (STRANGERS, TAKEN, BOTH, "The Wyrmrest Accord's ambassadors are on this platform. None of them may learn who those two rangers are. "
                             "Speak softly."),
    # After the turn: the word sent to Saurfang never arrived.
    (HOUR_OF_THE_GATE_H, TAKEN, HORDE, "The letter you sent Varok never reached his son. Agmar reads all the mail, and he burned that one as "
                                       "'delicate matters'. I went and checked. I am glad.", dict(after=EYES_AND_EARS)),
]
