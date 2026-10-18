"""The Horde's Borean Tundra, while Moratari and Eternus steer the war (STORY.md, "Moratari in the war";
tools/story.py writes it).

Before the turn, Moratari walks with the Timerunner through the whole tundra and acts on what she
knows, quietly. The interventions, in the order the chain meets them:

- WRM-050 Beneath the Floor: warn Garrosh, Saurfang and the Warsong Captain that the nerubians will
  come up through the floor and down out of the sky. Garrosh scoffs; Saurfang listens, and hears a
  name from the east (Kel'Thuzad: "So it's true", Garrosh will say at the Plains of Nasam).
- WRM-051 Hands for Holgoth: Saurfang's promised hands, and the game's own Patience is a Virtue and
  Bury Those Cockroaches! put on the road: the four sinkholes under the quarry collapsed early.
- Mobu's parts: the game's own two quests, back on the road (WRM-016 no longer skips them), with
  Moratari knowing exactly where they are; two more tanks patched from the spare plates.
- WRM-054 The Condemned Coast: the Kvaldir driven off the landing, and a garrison Gorge asks
  Saurfang for, on the coast Hellscream condemned.
- The Plains of Nasam: every tank that will run rolls out together, and the wounded come home.
- WRM-056 Shipping Lanes: the chapter's new end: the argument Garrosh and Saurfang have every few
  minutes at the war map, settled the other way, for once.

After the turn, story_undo.py lets the record take its own back (the garrison and the spare tanks
recalled under the Warchief's martial law; Garrosh's lesson broken at his son's armour).
"""
from story_common import (HORDE, TAKEN, DONE, TURNED_IN, ZONE_BOREAN_TUNDRA, INTO_THE_DRAGONBLIGHT_H,
                          rewarded, not_rewarded)

# The game's people
GARROSH, SAURFANG, CAPTAIN, RAZGOR, HOLGOTH, GORGE, MOBU = 25237, 25256, 25446, 25279, 25327, 25329, 25475
KVALDIR_MIST_LORD = 25496

# The game's quests on the way
HELLSCREAMS_VIGIL, HELLSCREAMS_VIGIL_2 = 11585, 11586
DEFENSE_OF_WARSONG_HOLD = 11596
TAKING_BACK_MIGHTSTONE, CUTTING_OFF_THE_SOURCE = 11598, 11602
PATIENCE, BURY_THOSE_COCKROACHES = 11606, 11608
KINDNESS_H, WIND_MASTER, MAGIC_CARPET_RIDE = 77018, 11634, 11636
TANK_AINT_GONNA_FIX_ITSELF, TRANSJIGAMARIG, METAL_PLATES, TANKS_A_LOT, PLAINS_OF_NASAM = 11642, 11643, 11644, 11651, 11652
DARKNESS_STIRS, HERALD_OF_WAR = 13242, 13257

# This file's quests
BENEATH_THE_FLOOR, HANDS_FOR_HOLGOTH, THE_CONDEMNED_COAST, SHIPPING_LANES = 77050, 77051, 77054, 77056
RECORD_KEEPS_WARSONG = 77230   # the undoing (story_undo.py)

# Credits and new creatures
C_GARROSH, C_SAURFANG, C_CAPTAIN, C_GORGE = 770560, 770561, 770562, 770563
SINKHOLE_GUARD, COAST_GUARD, SPARE_TANK, RECOVERING_WARRIOR, RECOVERING_MAGE, SCENE_TANK = 770570, 770571, 770572, 770573, 770574, 770575

SCENE_TANKS_ROLL, SCENE_SHIPPING_LANES = 150, 151

CREATURES = [
    dict(entry=C_GARROSH, name="Garrosh Hellscream warned", credit=True),
    dict(entry=C_SAURFANG, name="High Overlord Saurfang warned", credit=True),
    dict(entry=C_CAPTAIN, name="The Warsong Captain warned", credit=True),
    dict(entry=C_GORGE, name="Gorge asked to hold the coast", credit=True),
    dict(entry=SINKHOLE_GUARD, **{"from": 25242}, name="Warsong Sapper", subname=None, unit_flags=770,
         why="Grunts on the collapsed sinkholes of Mightstone Quarry (the hands Saurfang sent)"),
    dict(entry=COAST_GUARD, **{"from": 25242}, name="Warsong Coast Guard", subname=None, unit_flags=770,
         why="The garrison on the condemned coast (WRM-054)"),
    dict(entry=SPARE_TANK, **{"from": 27103}, name="Horde Siege Tank", subname=None, unit_flags=770,
         why="Two more tanks, patched from Mobu's spare plates"),
    dict(entry=RECOVERING_WARRIOR, **{"from": 27106}, name="Recovering Warsong Warrior", unit_flags=770, stand=1, emote=0,
         why="The Plains of Nasam's wounded, brought home"),
    dict(entry=RECOVERING_MAGE, **{"from": 27107}, name="Recovering Warsong Mage", unit_flags=770, stand=1, emote=0,
         why="The Plains of Nasam's wounded, brought home"),
    dict(entry=SCENE_TANK, **{"from": 27103}, name="Horde Siege Tank", subname=None, unit_flags=770,
         why="The tanks rolling out together in The Plains of Nasam's scene"),
]

UNTIL_RECALLED = not_rewarded(RECORD_KEEPS_WARSONG)
SPAWNS = [
    # The sinkholes, collapsed early, with a sapper on each.
    dict(guid=7704360, entry=SINKHOLE_GUARD, x=2660.5, y=6175.0, z=39.0, o=3.90, visible=[[rewarded(BURY_THOSE_COCKROACHES)]],
         why="a sapper on the southern sinkhole, Mightstone Quarry"),
    dict(guid=7704361, entry=SINKHOLE_GUARD, x=2719.5, y=6024.5, z=31.3, o=3.90, visible=[[rewarded(BURY_THOSE_COCKROACHES)]],
         why="a sapper on the eastern sinkhole, Mightstone Quarry"),
    dict(guid=7704362, entry=SINKHOLE_GUARD, x=2881.5, y=6279.5, z=60.9, o=3.90, visible=[[rewarded(BURY_THOSE_COCKROACHES)]],
         why="a sapper on the western sinkhole, Mightstone Quarry"),
    dict(guid=7704363, entry=SINKHOLE_GUARD, x=2965.5, y=6191.0, z=62.1, o=3.90, visible=[[rewarded(BURY_THOSE_COCKROACHES)]],
         why="a sapper on the northern sinkhole, Mightstone Quarry"),
    # The coast: two more tanks, a garrison, the wounded.
    dict(guid=7704364, equip=0, entry=SPARE_TANK, x=2795.0, y=6747.0, z=7.6, o=5.80, visible=[[rewarded(TANKS_A_LOT), UNTIL_RECALLED]],
         why="a spare tank, the Coast of Echoes"),
    dict(guid=7704365, equip=0, entry=SPARE_TANK, x=2782.5, y=6757.0, z=7.6, o=5.80, visible=[[rewarded(TANKS_A_LOT), UNTIL_RECALLED]],
         why="a spare tank, the Coast of Echoes"),
    dict(guid=7704366, entry=COAST_GUARD, x=2826.0, y=6705.0, z=10.8, o=2.70, visible=[[rewarded(THE_CONDEMNED_COAST), UNTIL_RECALLED]],
         why="the coast's garrison, the Coast of Echoes"),
    dict(guid=7704367, entry=COAST_GUARD, x=2829.0, y=6716.0, z=10.8, o=2.70, visible=[[rewarded(THE_CONDEMNED_COAST), UNTIL_RECALLED]],
         why="the coast's garrison, the Coast of Echoes"),
    dict(guid=7704368, entry=COAST_GUARD, x=2832.5, y=6727.5, z=10.6, o=2.40, visible=[[rewarded(THE_CONDEMNED_COAST), UNTIL_RECALLED]],
         why="the coast's garrison, the Coast of Echoes"),
    dict(guid=7704369, entry=RECOVERING_WARRIOR, x=2812.0, y=6740.0, z=9.8, o=4.70, visible=[[rewarded(PLAINS_OF_NASAM), UNTIL_RECALLED]],
         why="the plains' wounded, recovering by Elder Mootoo"),
    dict(guid=7704370, entry=RECOVERING_MAGE, x=2815.2, y=6737.2, z=10.0, o=4.20, visible=[[rewarded(PLAINS_OF_NASAM), UNTIL_RECALLED]],
         why="the plains' wounded, recovering by Elder Mootoo"),
    dict(guid=7704371, entry=RECOVERING_WARRIOR, x=2809.2, y=6736.2, z=9.8, o=5.20, visible=[[rewarded(PLAINS_OF_NASAM), UNTIL_RECALLED]],
         why="the plains' wounded, recovering by Elder Mootoo"),
]

COMMON = dict(side=HORDE, sort=ZONE_BOREAN_TUNDRA, level=-1, min=10, bronze=800)

QUESTS = [
    dict(COMMON, id=BENEATH_THE_FLOOR, title="Beneath the Floor",
         gates=[[rewarded(HELLSCREAMS_VIGIL)], [rewarded(HELLSCREAMS_VIGIL_2)]],
         log="Warn Garrosh Hellscream, High Overlord Saurfang and the Warsong Captain on the south wall of what is coming to Warsong "
             "Hold, without telling them how you know.",
         desc="You remember the first week here, don't you? The nerubians came up through the floor of the keep while Garrosh was still "
              "shouting at the boats. And out of the sky, every hour, over the south wall.$B$BThis time the Horde can be ready. Tell "
              "Garrosh: he will scoff. Tell Saurfang: he will listen, and he will tell no one he did. Tell the captain on the wall to put his "
              "marksmen up.$B$BNot how you know, $N. No one in this year may learn that. You smelled it in the quarry. You heard it in the "
              "rock. Orcs believe in instincts.",
         done="Return to Moratari.",
         objectives=[(C_GARROSH, 1, "Garrosh Hellscream warned"), (C_SAURFANG, 1, "High Overlord Saurfang warned"),
                     (C_CAPTAIN, 1, "The Warsong Captain warned")],
         request="Well? Who listened?",
         reward="Garrosh laughed at you. Saurfang did not. The captain will have his marksmen up before the next attack, and he will think it "
                "was his own idea by tomorrow.$B$BThat is how we work, you and I: the Horde will be ready, and no one will know who told it."),
    dict(COMMON, id=HANDS_FOR_HOLGOTH, title="Hands for Holgoth", prev=TAKING_BACK_MIGHTSTONE, ender=HOLGOTH,
         log="Speak with Quartermaster Holgoth in Warsong Hold.",
         desc="Saurfang promised Holgoth hands by nightfall. You are the hands.$B$BThe quartermaster has munitions in the quarry and a plan "
              "for the nerubians' four sinkholes, and no one to carry either. In the record I have, the sinkholes are still open when the "
              "nerubians come up through the floor. Let us close them first.",
         done="Speak with Quartermaster Holgoth.",
         reward="The old one sent you? <Holgoth scratches his chin.> Well, I'll not argue with a gift. I've got munitions scattered all over "
                "that quarry and a dozen holes that need filling."),
    dict(COMMON, id=THE_CONDEMNED_COAST, title="The Condemned Coast", prev=TANKS_A_LOT,
         log="Slay 6 Kvaldir Mist Lords in the mist over Garrosh's Landing, and ask Gorge to keep a garrison on the coast.",
         desc="Hellscream condemned this coast the day the mist came, and left Gorge, a goblin and six tanks to hold it. In the record I "
              "have, the Kvaldir come back out of the mist the week after the tanks roll, and there is no one left on this beach to stop them "
              "carrying off what the plains spared.$B$BThin the Mist Lords on the landing. Then tell Gorge to ask Saurfang for a garrison. "
              "Hellscream condemned the coast. He never said no one could guard it.",
         done="Return to Moratari.",
         objectives=[(KVALDIR_MIST_LORD, 6, ""), (C_GORGE, 1, "Gorge asked to hold the coast")],
         request="Is the landing quiet?",
         reward="A garrison on the condemned coast, a supply lane from the sea, and two more tanks than history ever had. Saurfang will "
                "want to know who suggested it. Gorge will say it was his idea. Let him."),
    dict(COMMON, id=SHIPPING_LANES, title="Shipping Lanes", prev=PLAINS_OF_NASAM, ender=SAURFANG, bronze=1500,
         log="Speak with High Overlord Saurfang in Warsong Hold.",
         desc="Kel'Thuzad. You saw him with your own eyes, over the plains, and Garrosh heard the name from you before you ever saw him. "
              "Now he wants to march on Icecrown tonight, before the dread citadel is finished rising.$B$BIn the record I have, Saurfang "
              "talks him out of it, barely, and they shout at each other over that floor map for the rest of the war. Today, I think the "
              "argument ends differently. Go and stand beside the old one, $N. He will want a witness.",
         done="Speak with High Overlord Saurfang.",
         reward="<High Overlord Saurfang watches Hellscream at the war map.>$B$BStand here, $N. You will want to remember this."),
]

GATES = {
    # The Defense of Warsong Hold (the variant a new character gets): the game's own conditions, and
    # the warning first.
    DEFENSE_OF_WARSONG_HOLD: [[rewarded(7784, 1), rewarded(8743, 1), rewarded(BENEATH_THE_FLOOR)]],
    KINDNESS_H: [[rewarded(BURY_THOSE_COCKROACHES)]],
    PATIENCE: [[rewarded(HANDS_FOR_HOLGOTH)]],
    INTO_THE_DRAGONBLIGHT_H: [[rewarded(SHIPPING_LANES)]],
}

TEXTS = {
    BURY_THOSE_COCKROACHES: dict(
        reward="That'll stop 'em - at least I think it'll stop 'em. Either way, you've done your part. That's all an $r can ask for, no?$B$B"
               "Funny thing. The old one sent me six peons this morning, no questions asked. Somebody's been talking in his ear."),
    TRANSJIGAMARIG: dict(
        reward="Already? Buddy, I been lookin' for that thing since we landed! How'd you know it was in the old hut? ...No. Don't tell me. "
               "Now where'd I put those spare parts..."),
    TANKS_A_LOT: dict(
        reward="With the current state of our transports and shipping lanes, we haven't been able to get a damned siege engine to Warsong. "
               "These tanks are all that we have left from the original expedition. Now that they're operational, you're gonna use 'em to "
               "stomp the Scourge offensive at the Plains of Nasam.$B$BAnd Mobu says he's got plates left over for two more. Two more! I might "
               "hug the little gnome."),
    PLAINS_OF_NASAM: dict(
        desc="Get in a tank and get out to the Plains of Nasam. Use the tank to kill as many Scourge as you can find. If you find any of our "
             "soldiers, grab 'em! Bring 'em back here!$B$BAnd you ain't alone out there this time. Every tank that'll run is rollin' with "
             "you. Hellscream wants a show!$B$BTry to get close enough to that structure in the center and see what's going on. If you do all "
             "of this and live, go to Hellscream!"),
}

TALK = [
    # WRM-050
    dict(npc=GARROSH, quest=BENEATH_THE_FLOOR, credit=C_GARROSH,
         option="The nerubians are under the floor, Hellscream. They'll come up through it, and down out of the sky.",
         reply="Under my floor? <Garrosh sniffs.> Then they will find my boot waiting.$B$BI smelled your fear when you walked in, $r. Keep it "
               "out of my hold. The Warsong does not cower from bugs."),
    dict(npc=GARROSH, quest=BENEATH_THE_FLOOR,
         option="And the cultists in the west serve a lich. Kel'Thuzad.",
         reply="Kel'Thuzad? The Argent Dawn put that lich in the ground in the Plaguelands. <Garrosh spits.> I do not fight ghost stories, "
               "$r. I fight what bleeds."),
    dict(npc=SAURFANG, quest=BENEATH_THE_FLOOR, credit=C_SAURFANG,
         option="High Overlord, the nerubians will come up through the floor of the keep. The quarry is riddled with their tunnels.",
         reply="<Saurfang is quiet for a long moment.>$B$BRazgor says the quarry is soft. Holgoth says he has charges and no hands to carry "
               "them. He will have hands by nightfall.$B$BAnd the necromancers in the west wear the Cult's robes. The Cult has one master. If "
               "he is here, his citadel is not far behind him. I will put scouts on the plains.$B$BSay nothing of this to Hellscream. He has "
               "enough to shout about."),
    dict(npc=CAPTAIN, quest=BENEATH_THE_FLOOR, credit=C_CAPTAIN,
         option="Captain, they'll come over the south wall, and out of the sky. Put your marksmen up.",
         reply="Settle down, soldier!$B$B<The captain looks up at the sky, then back at you.>$B$B...We'll be ready for 'em. You did a good job "
               "in warning us."),
    # WRM-054
    dict(npc=GORGE, quest=THE_CONDEMNED_COAST, credit=C_GORGE,
         option="Gorge, keep a garrison on this coast. Ask Saurfang for the men. He'll send them.",
         reply="A garrison? Here? Hellscream condemned this coast, an' everybody on it! <Gorge chews on that.>$B$B...But Saurfang ain't "
               "Hellscream. An' I ain't watchin' them mist-men drag off my tanks while I'm out stompin' Scourge.$B$BI'll send a runner. And "
               "if anybody asks, it was my idea."),
]

SCENES = [
    # The Plains of Nasam, as it's taken: every tank that will run rolls out with the Timerunner.
    dict(id=SCENE_TANKS_ROLL, name="The Plains of Nasam: the tanks roll out together", quest=PLAINS_OF_NASAM, trigger=1, anchor=GORGE,
         steps=[
             ("bind", 1, GORGE),
             ("summon", 2, SCENE_TANK, -6.0, 8.0, 2.4),
             ("summon", 3, SCENE_TANK, -9.0, 4.0, 2.4),
             ("summon", 4, SCENE_TANK, -12.0, 9.0, 2.4),
             ("yell", 1, "Every tank that runs, rolls! Hellscream wants a show, an' he's gonna get one!"),
             ("move", 2, -60.0, 70.0, 1),
             ("move", 3, -64.0, 64.0, 1),
             ("move", 4, -68.0, 72.0, 1),
             ("say", 1, "Go on, then! An' bring my soldiers home!"),
             ("wait", 6000),
             ("despawn", 2),
             ("despawn", 3),
             ("despawn", 4),
         ]),
    # Shipping Lanes, as it's turned in: the argument at the war map, settled the other way.
    dict(id=SCENE_SHIPPING_LANES, name="Shipping Lanes: Garrosh and Saurfang at the war map", quest=SHIPPING_LANES, trigger=2, anchor=SAURFANG,
         steps=[
             ("bind", 1, GARROSH),
             ("bind", 2, SAURFANG),
             ("face", 1, 2),
             ("say", 1, "Kel'Thuzad. Then we march on Icecrown tonight, before his citadel is done rising."),
             ("face", 2, 1),
             ("say", 2, "With what? The tanks rolled today because their parts were found before Mobu asked for them. The coast held because "
                        "someone garrisoned it. The wounded came home because no one left them on the plains."),
             ("say", 1, "Shipping lanes. Supplies. You still bore me to death, old one."),
             ("say", 2, "And you are still alive to be bored."),
             ("wait", 1500),
             ("say", 1, "...Show me this lane of yours."),
             ("say", 2, "Garrosh's Landing. The coast you condemned."),
             ("say", 1, "Then it is not condemned. There. Now we have a shipping lane."),
         ]),
]

WORDS = [
    (HELLSCREAMS_VIGIL, TAKEN, HORDE, "Garrosh Hellscream, son of Grom. He smells fear on everyone who walks into his hold. Let him smell "
                                      "nothing on you. It confuses him."),
    (HELLSCREAMS_VIGIL, TURNED_IN, HORDE, "He and Saurfang have the same argument at that floor map every few minutes. Shipping lanes, "
                                          "supplies, Garrosh stamping on things. I have counted. I have had the time.", dict(delay=6000)),
    (BENEATH_THE_FLOOR, TAKEN, HORDE, "Garrosh first; he is closest, and he will not believe you. Then Saurfang. Then the captain on the south "
                                      "wall, by the battlements."),
    (BENEATH_THE_FLOOR, DONE, HORDE, "'You did a good job in warning us.' He has had that line ready for a week, and no one ever gave him a "
                                     "reason to say it."),
    (DEFENSE_OF_WARSONG_HOLD, TAKEN, HORDE, "'Do not let Hellscream dissuade you.' Saurfang drank Mannoroth's blood beside Grom, and he has "
                                            "spent every day since making sure Grom's son does not do the same."),
    (TAKING_BACK_MIGHTSTONE, TAKEN, HORDE, "Some of those cocoons hold soldiers, not peons. Cut them down and they will fight beside you. "
                                           "Some of them would otherwise be names on the wall of the hold, with a line through them."),
    (TAKING_BACK_MIGHTSTONE, TURNED_IN, HORDE, "The quarry is the Horde's again. Now let us make sure nothing comes up under it."),
    (HANDS_FOR_HOLGOTH, TAKEN, HORDE, "Holgoth, by the quartermaster's stores. He has explosives and a plan. Goblin explosives, orc plan. "
                                      "That is usually how wars are won."),
    (PATIENCE, TAKEN, HORDE, "Munitions crates, all over the quarry. Pick them up quickly; the nerubians like to sit on them."),
    (BURY_THOSE_COCKROACHES, TAKEN, HORDE, "Four sinkholes. In the record, they are still open when the floor of the keep caves in. Not this "
                                           "time."),
    (BURY_THOSE_COCKROACHES, TURNED_IN, HORDE, "There. The floor of Warsong Hold stays a floor. Garrosh will never know he had nothing to "
                                               "stamp on."),
    (CUTTING_OFF_THE_SOURCE, TAKEN, HORDE, "The eggs are on the scaffolding, high and dry. Each one is twenty fewer nerubians, Razgor says. "
                                           "I checked his arithmetic once. He is being modest."),
    (WIND_MASTER, TAKEN, HORDE, "To'bor, at the top of the hold. 'Across dat tundra be death, mon.' He is not wrong, but he is very "
                                "dramatic about it."),
    (MAGIC_CARPET_RIDE, TAKEN, HORDE, "A thirty percent survival rate, and Yanni calls it an improvement. Hold on tight. I will be there when "
                                      "you land."),
    (TANK_AINT_GONNA_FIX_ITSELF, TAKEN, HORDE, "Gorge calls Mobu a gnome. Mobu is a goblin. Do not correct him. Nobody corrects Gorge."),
    (TANK_AINT_GONNA_FIX_ITSELF, TURNED_IN, HORDE, "He is about to ask for a pneumatic tank transjigamarig and ten metal plates. The "
                                                   "transjigamarig is in his old hut by the stables, in the mist. The plates are scattered "
                                                   "over the landing and in the shallows. I have watched him look for them for three days. "
                                                   "Let us save him the trouble.", dict(delay=4000)),
    (TRANSJIGAMARIG, TAKEN, HORDE, "Into the mist. The Kvaldir cannot see you any better than you can see them. That is the only comfort I "
                                   "have to offer."),
    (TANKS_A_LOT, TURNED_IN, HORDE, "Six tanks, all that is left of the first expedition. Nine more lie burned out on the plains. Today, two "
                                    "of those nine come back to life."),
    (THE_CONDEMNED_COAST, TAKEN, HORDE, "The Mist Lords are on the landing, inside the mist. Then Gorge. He will say no first. He always says "
                                        "no first."),
    (PLAINS_OF_NASAM, TAKEN, HORDE, "Three seats in that tank for the wounded. Fill them. The Scourge stitch the ones they find into "
                                    "something called a Stitched Warsong Horror. The name tells you everything."),
    (PLAINS_OF_NASAM, TURNED_IN, HORDE, "'So it's true.' He said that the first time too, and I always wondered who told him. Now I know."),
    (SHIPPING_LANES, TAKEN, HORDE, "The war map, in the middle of the hold. Stand where Saurfang can see you."),
    (SHIPPING_LANES, TURNED_IN, HORDE, "He has never once said that without stamping on something. Remember this day, $N. It does not last.",
     dict(delay=50000)),
]
