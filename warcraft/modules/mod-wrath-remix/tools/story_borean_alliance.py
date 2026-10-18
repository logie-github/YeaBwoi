"""The Alliance's Borean Tundra, while Moratari and Eternus steer the war (STORY.md, "Moratari in the
war"; tools/story.py writes it).

Before the turn, Moratari walks with the Timerunner and acts on what she knows of the war, quietly:
no one of this year may learn how. The interventions, in the order the chain meets them:

- WRM-041 A Word in the General's Ear: warn General Arlos and Sergeant Hammerhill of the siege, the
  flyers and the ground. The general, at Counselor Talbot's elbow, waves it away; the sergeant
  listens (the catapults Eternus's note speaks of, moved because he did).
- WRM-042 The Fourth Sinkhole: the cult's necrolytes at the northern sinkhole, and a fourth tunnel
  that history never records, collapsed by Moratari before it opens.
- WRM-043 Not Yet Late: William Allerton, saved from the cult's knife in the Farshire Mine (the
  game has him dead, his throat cut, "no bite or claw marks").
- WRM-044 Named for No Town: Allerton's testimony, Old Man Colburn and Private Brau: Unit S ("'S'
  stood for suicide"), and whose hand signed it.
- WRM-045 No One Left Behind: four of Unit S taken alive to En'kilah, freed; the volunteers
  Hammerhill sent east because the Timerunner warned him.
- WRM-046 Before She Gets Hurt: Leryssa warned and Vindicator Yaala asked to watch her. It fails:
  Last Rites plays as the game has it, because Valanar's unmasking needs his "offerings".
- WRM-047 The Keep Holds: the chapter's new end, at Valiance Keep: Arlos himself again, Vurenn's
  regiments accepted, and the road to Wintergarde.

After the turn, story_undo.py puts back what the record cannot keep (Allerton, Unit S).
"""
from story_common import (ALLIANCE, MORATARI, TAKEN, DONE, TURNED_IN, OUTING, ZONE_BOREAN_TUNDRA,
                          INTO_THE_DRAGONBLIGHT_A, rewarded, not_rewarded, state, IN_PROGRESS, NOT_TAKEN,
                          TAKEN_OR_DONE)

# The game's people
BLYTHE, ARLOS, TALBOT, VURENN, HAMMERHILL, MARK_HANES = 25307, 25250, 25301, 25285, 25816, 26155
LERYSSA, ALLERTON, COLBURN, BRAU, YAALA, DEACON = 25251, 25385, 25302, 25395, 25826, 25245
VENN, CASEY, THASSARIAN, CULTIST_NECROLYTE = 26187, 26186, 26170, 25651

# The game's quests on the way
ENLISTMENT_DAY, A_TIME_FOR_HEROES, THE_SIEGE, DEATH_FROM_ABOVE, PLUG_THE_SINKHOLES = 11672, 11727, 11797, 11889, 11897
KINDNESS, WORD_AT_THE_GATE, WORD_ON_THE_STREET = 77017, 77019, 11927
THASSARIAN_MY_BROTHER, A_USEFUL_OMISSION = 11599, 77006
THASSARIAN_THE_DEATH_KNIGHT, FINDING_THE_PHYLACTERY, BUYING_SOME_TIME, WORDS_OF_POWER, LAST_RITES = 12088, 11956, 11938, 11942, 12019

# This file's quests
WORD_IN_THE_EAR, FOURTH_SINKHOLE, NOT_YET_LATE, NAMED_FOR_NO_TOWN, NO_ONE_LEFT_BEHIND = 77041, 77042, 77043, 77044, 77045
BEFORE_SHE_GETS_HURT, THE_KEEP_HOLDS = 77046, 77047
LOST_AT_SEA, FORGED_ORDERS = 77048, 77049    # the undoing (story_undo.py)

# Credits (never spawned) and new creatures
C_ARLOS, C_HAMMERHILL, C_SINKHOLE, C_COLBURN, C_BRAU = 770530, 770531, 770532, 770533, 770534
C_HARKER, C_DUNN, C_MABRY, C_SORREL, C_LERYSSA, C_YAALA = 770535, 770536, 770537, 770538, 770539, 770540
LIVING_ALLERTON, CULT_ASSASSIN = 770550, 770551
HARKER, DUNN, MABRY, SORREL = 770552, 770553, 770554, 770555
UNIT_S_SURVIVOR, VOLUNTEER, DRAENEI_VANGUARD = 770556, 770557, 770558

SCENE_FOURTH_SINKHOLE, SCENE_KEEP_HOLDS = 101, 102

CREATURES = [
    dict(entry=C_ARLOS, name="General Arlos warned", credit=True),
    dict(entry=C_HAMMERHILL, name="Sergeant Hammerhill warned", credit=True),
    dict(entry=C_SINKHOLE, name="The fourth sinkhole collapsed", credit=True),
    dict(entry=C_COLBURN, name="Old Man Colburn asked about Unit S", credit=True),
    dict(entry=C_BRAU, name="Private Brau asked who signed the order", credit=True),
    dict(entry=C_HARKER, name="Private Harker freed", credit=True),
    dict(entry=C_DUNN, name="Private Dunn freed", credit=True),
    dict(entry=C_MABRY, name="Corporal Mabry freed", credit=True),
    dict(entry=C_SORREL, name="Private Sorrel freed", credit=True),
    dict(entry=C_LERYSSA, name="Leryssa warned", credit=True),
    dict(entry=C_YAALA, name="Vindicator Yaala asked to watch over Leryssa", credit=True),
    # William Allerton as he is when someone gets to the mine in time: the game's, alive (no corpse's
    # look, no feigned death), who can be spoken to and takes back Thassarian, My Brother.
    dict(entry=LIVING_ALLERTON, **{"from": ALLERTON}, name="William Allerton", npcflag=3, dynamicflags=0, unit_flags=768, flags_extra=2,
         auras="", why="William Allerton, alive (WRM-043)"),
    dict(entry=CULT_ASSASSIN, **{"from": 25605}, name="Cult Assassin", subname="Cult of the Damned",
         why="The cult's assassin sent for Allerton, in a Valiance tabard (WRM-043)"),
    dict(entry=HARKER, **{"from": 25313}, name="Private Harker", subname="Unit S", npcflag=1, faction=35, unit_flags=770, stand=8,
         why="Unit S's captives at En'kilah (WRM-045)"),
    dict(entry=DUNN, **{"from": 25313}, name="Private Dunn", subname="Unit S", npcflag=1, faction=35, unit_flags=770, stand=8),
    dict(entry=MABRY, **{"from": 25313}, name="Corporal Mabry", subname="Unit S", npcflag=1, faction=35, unit_flags=770, stand=8),
    dict(entry=SORREL, **{"from": 25313}, name="Private Sorrel", subname="Unit S", npcflag=1, faction=35, unit_flags=770, stand=8),
    dict(entry=UNIT_S_SURVIVOR, **{"from": 25313}, name="Unit S Survivor", subname=None, unit_flags=770,
         why="The freed men of Unit S, at Death's Stand"),
    dict(entry=VOLUNTEER, **{"from": 25313}, name="Valiance Volunteer", subname="Sergeant Hammerhill's", unit_flags=770,
         why="The men Hammerhill sent east, because the Timerunner warned him"),
    dict(entry=DRAENEI_VANGUARD, **{"from": 25300}, name="Draenei Vanguard", subname="Harbinger Vurenn's", unit_flags=770,
         why="Vurenn's regiments, accepted at last (WRM-047)"),
]

# Who sees them: the living Allerton from the moment the Timerunner sets out to save him, until he
# sails (WRM-048); the dead one only for whoever never set out; the assassin while it's being done;
# the captives while they wait; the survivors and volunteers from their rescue until the record takes
# them back (WRM-049); the draenei from the keep's new day.
ALIVE = [[state(NOT_YET_LATE, TAKEN_OR_DONE), not_rewarded(LOST_AT_SEA)]]
SAVED = [[rewarded(NO_ONE_LEFT_BEHIND), not_rewarded(FORGED_ORDERS)]]
SPAWNS = [
    dict(guid=7704330, equip=0, entry=LIVING_ALLERTON, x=2768.0, y=5339.9, z=30.03, o=3.05, visible=ALIVE,
         why="William Allerton, alive, Farshire Mine"),
    dict(guid=7704331, entry=CULT_ASSASSIN, x=2765.9, y=5340.2, z=30.03, o=6.20, respawn=30,
         visible=[[state(NOT_YET_LATE, IN_PROGRESS)]], why="the cult's assassin over Allerton"),
    dict(guid=7704332, entry=HARKER, x=3845.6, y=3726.4, z=47.4, o=3.60, visible=[[state(NO_ONE_LEFT_BEHIND, IN_PROGRESS)]],
         why="Private Harker, captive, En'kilah"),
    dict(guid=7704333, entry=DUNN, x=3847.8, y=3729.6, z=47.5, o=3.60, visible=[[state(NO_ONE_LEFT_BEHIND, IN_PROGRESS)]],
         why="Private Dunn, captive, En'kilah"),
    dict(guid=7704334, entry=MABRY, x=3836.4, y=3728.2, z=47.3, o=0.30, visible=[[state(NO_ONE_LEFT_BEHIND, IN_PROGRESS)]],
         why="Corporal Mabry, captive, En'kilah"),
    dict(guid=7704335, entry=SORREL, x=3843.0, y=3721.0, z=47.2, o=1.60, visible=[[state(NO_ONE_LEFT_BEHIND, IN_PROGRESS)]],
         why="Private Sorrel, captive, En'kilah"),
    dict(guid=7704336, entry=UNIT_S_SURVIVOR, x=3110.2, y=3838.4, z=22.85, o=5.75, visible=SAVED, why="Unit S, Death's Stand"),
    dict(guid=7704337, entry=UNIT_S_SURVIVOR, x=3117.6, y=3839.2, z=22.80, o=3.40, visible=SAVED, why="Unit S, Death's Stand"),
    dict(guid=7704338, entry=UNIT_S_SURVIVOR, x=3109.4, y=3843.6, z=22.80, o=5.20, visible=SAVED, why="Unit S, Death's Stand"),
    dict(guid=7704339, entry=UNIT_S_SURVIVOR, x=3117.9, y=3846.2, z=22.75, o=3.90, visible=SAVED, why="Unit S, Death's Stand"),
    dict(guid=7704340, entry=VOLUNTEER, x=3104.5, y=3830.5, z=23.20, o=5.60, visible=SAVED, why="volunteers, Death's Stand"),
    dict(guid=7704341, entry=VOLUNTEER, x=3120.6, y=3830.4, z=22.90, o=4.20, visible=SAVED, why="volunteers, Death's Stand"),
    dict(guid=7704342, entry=VOLUNTEER, x=3290.5, y=3679.5, z=32.55, o=0.40, visible=SAVED, why="volunteers holding the Wailing Ziggurat"),
    dict(guid=7704343, entry=VOLUNTEER, x=3289.6, y=3686.4, z=32.55, o=5.90, visible=SAVED, why="volunteers holding the Wailing Ziggurat"),
    dict(guid=7704344, entry=VOLUNTEER, x=3304.0, y=3678.6, z=32.55, o=2.60, visible=SAVED, why="volunteers holding the Wailing Ziggurat"),
    dict(guid=7704345, equip=0, entry=DRAENEI_VANGUARD, x=2119.8, y=5303.2, z=32.86, o=3.20, visible=[[rewarded(THE_KEEP_HOLDS)]],
         why="Vurenn's vanguard, Valiance Keep's barracks"),
    dict(guid=7704346, equip=0, entry=DRAENEI_VANGUARD, x=2120.6, y=5306.0, z=32.86, o=3.20, visible=[[rewarded(THE_KEEP_HOLDS)]],
         why="Vurenn's vanguard, Valiance Keep's barracks"),
    dict(guid=7704347, equip=0, entry=DRAENEI_VANGUARD, x=2119.9, y=5308.6, z=32.86, o=3.20, visible=[[rewarded(THE_KEEP_HOLDS)]],
         why="Vurenn's vanguard, Valiance Keep's barracks"),
]

HIDE = [
    # Allerton's corpse, for whoever set out to save him in time.
    dict(guid=99763, entry=ALLERTON, visible=[[state(NOT_YET_LATE, NOT_TAKEN)]], why="Allerton's corpse, unless he was saved"),
    # Counselor Talbot in the barracks, once Prince Valanar has died at Naxxanar (the game leaves him
    # standing beside the general afterwards).
    dict(guid=115989, entry=TALBOT, visible=[[not_rewarded(LAST_RITES)]], why="Counselor Talbot, gone after Last Rites"),
]

# Allerton alive takes back Thassarian, My Brother (Leryssa sends the Timerunner to him).
RELATIONS = [("ender", LIVING_ALLERTON, THASSARIAN_MY_BROTHER)]

COMMON = dict(side=ALLIANCE, sort=ZONE_BOREAN_TUNDRA, level=-1, min=10, bronze=800)

QUESTS = [
    dict(COMMON, id=WORD_IN_THE_EAR, title="A Word in the General's Ear", prev=ENLISTMENT_DAY,
         log="Warn General Arlos and Sergeant Hammerhill of what is coming to Valiance Keep, without telling them how you know.",
         desc="You remember this week, don't you? The crawlers on the beach. The flyers over the inner wall. And on the third night, the ground "
              "opening under the western ridge.$B$BThis time, they can be ready. Tell the general in the barracks, and the sergeant at the "
              "barricade. Not how you know, $N: no one in this year may learn that. Say your scouts saw worm-trails on the ridge. Soldiers believe "
              "scouts.$B$BAnd mind the counselor at the general's elbow. Tell him nothing you would not tell the Scourge.",
         done="Return to Moratari.",
         objectives=[(C_ARLOS, 1, "General Arlos warned"), (C_HAMMERHILL, 1, "Sergeant Hammerhill warned")],
         request="Well? Did they listen?",
         reward="The general did not believe you. The sergeant did. Remember that, $N: in every war, it is the sergeants who listen.$B$B"
                "Now the war knows something it did not know yesterday, and nobody can say who told it. That is how we work, you and I."),
    dict(COMMON, id=FOURTH_SINKHOLE, title="The Fourth Sinkhole", prev=PLUG_THE_SINKHOLES,
         log="Slay 6 Cultist Necrolytes at the northern sinkhole, then go with Moratari to the dunes behind the beach line.",
         desc="Hammerhill thinks there were three sinkholes. There were four. The fourth opens under the dunes behind the beach line on the "
              "third night, and what comes out of it reaches the docks before anyone sounds a horn.$B$BIt has not opened yet. The worms are "
              "still digging it, and the cult's necrolytes are feeding them from the northern sinkhole, chanting the dead awake under the "
              "sand.$B$BKill the necrolytes. Then come with me to the dunes. I will show you what a bronze dragon can do with wooden supports "
              "and a little time.",
         done="Return to Moratari.",
         objectives=[(CULTIST_NECROLYTE, 6, ""), (C_SINKHOLE, 1, "The fourth sinkhole collapsed")],
         request="Is it done?",
         reward="Three sinkholes in the reports. Four in the ground. Three in history, now. I like those numbers better.$B$B"
                "Hammerhill will sleep well on the third night and never know why. That is the best kind of rescue."),
    dict(COMMON, id=NOT_YET_LATE, title="Not Yet Late", prev=WORD_ON_THE_STREET, ender=LIVING_ALLERTON,
         log="Reach William Allerton in the Farshire Mine before the cult's assassin does.",
         desc="Leryssa is about to ask you to find a family friend of hers, William Allerton, at the Farshire mine. Say yes. Then run.$B$B"
              "In the record I have, you find him on the floor of that mine with his throat cut and no claw marks on him. A man in a Valiance "
              "tabard climbed the shaft an hour before you, with orders from the inner hold and a knife.$B$BWilliam knows where they sent "
              "Thassarian. That is why he dies. Today, I would rather he didn't.",
         done="Speak with William Allerton.",
         objectives=[(CULT_ASSASSIN, 1, "")],
         request="<William Allerton is pressed against the wall of the mine, a hand at his throat.>",
         reward="You... you're not one of them. Thank the Light.$B$BThat man came up the shaft in a Valiance tabard. Said he had orders for me "
                "from the inner hold. Then he drew a knife.$B$BI know why, too. I've seen the ledger. I know where they sent Thassarian."),
    dict(COMMON, id=NAMED_FOR_NO_TOWN, title="Named for No Town", prev=NOT_YET_LATE, giver=LIVING_ALLERTON, ender=LERYSSA,
         log="Ask Old Man Colburn at the inn and Private Brau in the barracks jail about Unit S, then tell Leryssa what you have learned.",
         desc="Thassarian enlisted the same day I did. The day after, they had him in charge of something called Unit S. All the other units "
              "are named after towns, $c. Goldshire. Darkshire. Not 'S'.$B$BI started asking questions. Then someone came up my mine shaft "
              "with a knife.$B$BOld Colburn at the inn knows every unit that ever sailed from Stormwind; ask him. And there's a deserter in "
              "the barracks jail, Brau, who was in Unit S before he ran. Ask him who signed the order.$B$BThen tell Leryssa. She deserves to "
              "know where her brother went.",
         done="Return to Leryssa at the Valiance Keep inn.",
         objectives=[(C_COLBURN, 1, "Old Man Colburn asked about Unit S"), (C_BRAU, 1, "Private Brau asked who signed the order")],
         request="Did you find William? Is he...",
         reward="William's alive? Light be praised. And the counselor... <Leryssa sits down, hard.> He's at the general's side every hour of "
                "the day.$B$BA dozen men and a death knight, sent to die on purpose. And Thassarian is one of them.$B$BPlease, $N. Find my "
                "brother before they finish what they started."),
    dict(COMMON, id=NO_ONE_LEFT_BEHIND, title="No One Left Behind", prev=A_USEFUL_OMISSION, ender=VENN,
         log="Free Private Harker, Private Dunn, Corporal Mabry and Private Sorrel of Unit S, held in the Temple City of En'kilah, then "
             "return to Corporal Venn at Death's Stand.",
         desc="Venn thinks the rest of Unit S died on the beach where they landed. Most of them did. Four did not.$B$BThe cult took them "
              "alive, to the necromancers' yard in the Temple City of En'kilah, south of here. In the record, nobody ever goes to look, and in "
              "a week the cult has four new servants who used to be Stormwind boys.$B$BGo and get them, $N. Quietly: tell them you were "
              "sent. Soldiers always believe they were sent.",
         done="Return to Corporal Venn at Death's Stand.",
         objectives=[(C_HARKER, 1, "Private Harker freed"), (C_DUNN, 1, "Private Dunn freed"), (C_MABRY, 1, "Corporal Mabry freed"),
                     (C_SORREL, 1, "Private Sorrel freed")],
         request="You came from Valiance Keep? Then you're the first. Light, they really did send us here to die.",
         reward="Harker? Dunn? <Corporal Venn grabs the nearest man by the shoulders and shakes him, laughing.> We buried you! In our heads "
                "we buried you!$B$BAnd these lads with them say Sergeant Hammerhill sent them. Hammerhill! The one man in that keep who reads "
                "a map.$B$BThank you, $N. Now go find Thassarian. He went north this morning, alone, and I don't like it."),
    dict(COMMON, id=BEFORE_SHE_GETS_HURT, title="Before She Gets Hurt", prev=THASSARIAN_THE_DEATH_KNIGHT,
         log="Warn Leryssa, and ask Vindicator Yaala to watch over her, at Valiance Keep's inn. Moratari will take you there and back.",
         desc="'We must finish this before she gets hurt.' He is right to be afraid, $N. In the record, the counselor's men come for Leryssa "
              "within the week, and she is dragged to the top of Naxxanar as an offering.$B$BI can fold the road to Valiance Keep and back. "
              "Warn her. Ask the draenei vindicator at the inn to watch over her. And then... we will see what the hour allows.",
         done="Return to Moratari.",
         departure=(571, 2272.6, 5186.2, 20.38, 3.40),
         bridge=dict(at=(571, 3292.8, 3679.6, 32.55, 0.60)),
         objectives=[(C_LERYSSA, 1, "Leryssa warned"), (C_YAALA, 1, "Vindicator Yaala asked to watch over Leryssa")],
         request="Did she listen?",
         reward="She will not run, and Yaala will not sleep. Whether that is enough... we will know at the top of Naxxanar.$B$BBack to "
                "Thassarian. Hold on."),
    dict(COMMON, id=THE_KEEP_HOLDS, title="The Keep Holds", prev=LAST_RITES, ender=ARLOS, bronze=1500,
         log="Speak with General Arlos in Valiance Keep's barracks.",
         desc="Talbot is dead, and the hold he kept on the general's mind died with him. For the first time since he landed, Arlos is "
              "himself.$B$BHe is about to make three decisions, $N. The first two are his own. The third is about you. Come; I will fold "
              "the road.",
         done="Speak with General Arlos.",
         departure=(571, 2110.2, 5300.6, 34.09, 2.30),
         reward="<General Arlos looks older than he did a week ago. He also looks awake.>$B$BCome in, $N. There is a great deal I need to say, "
                "and most of it is an apology."),
]

# The game's own quests, placed in the story.
GATES = {
    A_TIME_FOR_HEROES: [[rewarded(WORD_IN_THE_EAR)]],
    KINDNESS: [[rewarded(FOURTH_SINKHOLE)]],
    THASSARIAN_MY_BROTHER: [[state(NOT_YET_LATE, TAKEN_OR_DONE)]],
    A_USEFUL_OMISSION: [[rewarded(NAMED_FOR_NO_TOWN)]],
    FINDING_THE_PHYLACTERY: [[rewarded(BEFORE_SHE_GETS_HURT)]],
    INTO_THE_DRAGONBLIGHT_A: [[rewarded(THE_KEEP_HOLDS)]],
}

TEXTS = {
    THASSARIAN_MY_BROTHER: dict(
        reward="<William Allerton presses a rag to the shallow cut along his neck.>$B$BLeryssa sent you? Light bless that stubborn girl. "
               "Another minute and you'd have found me on this floor with my throat open, and not a claw mark on me to explain it.$B$B"
               "That was no ghoul, $c. That was one of ours. Or one wearing our colors."),
    BUYING_SOME_TIME: dict(
        reward="That should buy us some time. Isidorus has been cooperative, but I really needed that alone time with the lich.$B$B"
               "<Thassarian nods toward the Valiance volunteers at the ziggurat's edge.>$B$BAnd thank Hammerhill's lads for me. I did not ask "
               "for help. It came anyway. I am not used to that."),
}

PLACES = [
    dict(quest=FOURTH_SINKHOLE, credit=C_SINKHOLE, x=2332.0, y=5470.0, radius=14, scene=SCENE_FOURTH_SINKHOLE,
         text="Here. Under the dune. Put your ear to the sand."),
]

TALK = [
    # WRM-041
    dict(npc=ARLOS, quest=WORD_IN_THE_EAR, credit=C_ARLOS,
         option="General, the crawlers on the beach are only a vanguard. My scouts found worm-trails under the western ridge, and their flyers "
                "will come over the inner wall.",
         reply="<General Arlos glances at Counselor Talbot before he answers.>$B$BYour scouts are very... diligent, $N. The counselor has the "
               "ridge reports, and they say nothing of worms. The walls will hold.$B$BStill, a hero's word is worth something. Take it to "
               "Sergeant Hammerhill. If anyone has a use for nervous talk about the ground, it is a dwarf."),
    dict(npc=TALBOT, quest=WORD_IN_THE_EAR,
         option="Counselor, the general says you have the ridge reports. I'd like to see them.",
         reply="<Counselor Talbot smiles. It does not reach his eyes.>$B$BReports are for officers, champion. Kill the crawlers. Leave the "
               "thinking to those of us who were trained for it.$B$BAnd do tell your scouts to be careful on that ridge. It would be a shame to "
               "lose them."),
    dict(npc=HAMMERHILL, quest=WORD_IN_THE_EAR, credit=C_HAMMERHILL,
         option="Sergeant, they'll dig under the western ridge and come up behind your line. And their flyers will hit the inner defenses.",
         reply="Worm-trails, ye say? Hah! The general can keep his reports. Thirty years in the mines taught me to trust the ground over the "
               "brass.$B$BI'll shift two catapults to the south wall tonight and put spotters on the ridge. If you're wrong, I've lost a "
               "night's sleep. If you're right... well. Then I owe ye a drink, and I'll pay it."),
    # WRM-044
    dict(npc=COLBURN, quest=NAMED_FOR_NO_TOWN, credit=C_COLBURN,
         option="Colburn, what do you know about a unit called Unit S?",
         reply="<Old Man Colburn squints at you.>$B$BUnit S? There's no such town. Darkshire was sent to the Dragonblight. The Goldshire boys "
               "went to the Fjord. I know every unit, every brigade, every detachment that ever drew Valiance pay, and there's no S.$B$B"
               "Which means somebody made it up. And people don't make up units to send them somewhere nice."),
    dict(npc=BRAU, quest=NAMED_FOR_NO_TOWN, credit=C_BRAU,
         option="Private, who signed the order that sent Unit S east?",
         reply="<Private Brau glances toward the jailer before he answers, very quietly.>$B$B'S' stood for suicide. That's what the lads "
               "called it. A dozen of us, one death knight, and a Scourge ziggurat.$B$BThe orders came down from the inner hold, with the "
               "general's seal. But the general never signed nothing, friend. I saw the hand. It was the counselor's.$B$BDon't say I told you. "
               "That one by the door hears everything."),
    # WRM-045
    dict(npc=HARKER, quest=NO_ONE_LEFT_BEHIND, credit=C_HARKER, option="Private Harker? Get up. Valiance Keep sent me. Head for Death's Stand.",
         reply="Sent? Somebody sent... <Private Harker scrambles to his feet.> Yes, sir. Death's Stand. I can walk. I can run, if it comes to it."),
    dict(npc=DUNN, quest=NO_ONE_LEFT_BEHIND, credit=C_DUNN, option="Private Dunn. On your feet, soldier. Death's Stand, now.",
         reply="I knew it. I told Harker someone would come. He said I was mad. <Private Dunn laughs, too loudly, then stops.> Death's Stand. Yes."),
    dict(npc=MABRY, quest=NO_ONE_LEFT_BEHIND, credit=C_MABRY, option="Corporal Mabry. Your men are getting out. Lead them to Death's Stand.",
         reply="Lead them. Right. <Corporal Mabry wipes his face with the back of a bound hand.> The cult said we'd be serving them by the full "
               "moon. Tell whoever sent you... no. I'll tell them myself."),
    dict(npc=SORREL, quest=NO_ONE_LEFT_BEHIND, credit=C_SORREL, option="Private Sorrel. Can you stand? Death's Stand is west. Go.",
         reply="<Private Sorrel stares at you as if you were a ghost.>$B$BThey said no one knew we were here. They said the ledger was burned. "
               "...West. Yes. West."),
    # WRM-046
    dict(npc=LERYSSA, quest=BEFORE_SHE_GETS_HURT, credit=C_LERYSSA,
         option="Leryssa, the counselor knows you're asking about Thassarian. Leave Valiance Keep tonight. Take the Kraken home.",
         reply="Go home? <Leryssa laughs, not kindly.>$B$BI sold everything I owned to come here, $N. My brother is alive, somewhere out there "
               "in the snow, and you want me to sail away before I've seen his face?$B$BNo. Let them come. I'm not leaving without him."),
    dict(npc=YAALA, quest=BEFORE_SHE_GETS_HURT, credit=C_YAALA,
         option="Vindicator, the woman upstairs is in danger. Someone in the inner hold wants her silenced. Will you watch over her?",
         reply="<Vindicator Yaala's eyes narrow.>$B$BSomeone in the inner hold. You choose your words carefully, $c. So do I.$B$BThe naaru teach "
               "that darkness hides best in plain sight. I will keep the girl in mine. If anyone comes for her, they will come through me."),
]

SCENES = [
    # WRM-042: the fourth sinkhole, at the dunes (where the Timerunner stands as it starts).
    dict(id=SCENE_FOURTH_SINKHOLE, name="The fourth sinkhole: Moratari and the supports", quest=0, trigger=0, anchor=0,
         credit=C_SINKHOLE, steps=[
             ("summon", 1, 770013, 1.5, 2.0, -0.6),
             ("summon", 2, 26250, 8.0, 0.0, 3.14),
             ("face", 1, 2),
             ("say", 1, "Hear it? Something is chewing. The worms have the shaft dug and the timbers set, and on the third night they pull "
                        "the timbers out and the dune falls in."),
             ("cast", 1, 31387, 2),
             ("say", 1, "Supports are only wood, $N, and wood is only time. A hundred years in a heartbeat."),
             ("wait", 1500),
             ("cast", 2, 46419, 0),
             ("despawn", 2),
             ("wait", 1500),
             ("face", 1, 255),
             ("say", 1, "There. On the third night, nothing will come out of these dunes. Somewhere in the record a page just went quiet."),
         ]),
    # WRM-047: Valiance Keep's new day, as it's turned in, at the general.
    dict(id=SCENE_KEEP_HOLDS, name="The Keep Holds: Arlos, Vurenn, and the road to Wintergarde", quest=THE_KEEP_HOLDS, trigger=2, anchor=ARLOS,
         steps=[
             ("bind", 1, ARLOS),
             ("bind", 2, VURENN),
             ("face", 1, 255),
             ("say", 1, "I remember... pieces. His voice, every morning, telling me what I thought. Light help me, I signed what he put in "
                        "front of me."),
             ("say", 1, "Unit S. A dozen men, sent to die under my seal. I will carry that for the rest of my life."),
             ("face", 1, 2),
             ("say", 1, "Harbinger. Your offer of two regiments. Is it still open?"),
             ("face", 2, 1),
             ("say", 2, "It was never closed, general. It was only waiting for you to be yourself again."),
             ("say", 1, "Then the Valiance Expedition accepts, with the gratitude it should have shown you a month ago."),
             ("face", 1, 255),
             ("say", 1, "And you. Wyrmbane is holding Wintergarde under the shadow of that floating citadel, and he needs every blade I can "
                        "send. Go to him, $N. Tell him Valiance Keep holds."),
         ]),
]

WORDS = [
    # Enlistment Day
    (ENLISTMENT_DAY, TAKEN, ALLIANCE, "Blythe has heard about Outland, not about Icecrown. To this year, you are a hero of the last war, "
                                      "not this one. Enjoy it. It is the last time anyone will underestimate you."),
    (ENLISTMENT_DAY, TURNED_IN, ALLIANCE, "The man at the general's elbow is Counselor Talbot. Smile at him. Never turn your back on him. "
                                          "I will tell you why when I can.", dict(delay=5000)),
    # A Word in the General's Ear
    (WORD_IN_THE_EAR, TAKEN, ALLIANCE, "The general first, in the barracks up the hill. Remember: scouts. Not visions, not dreams, and "
                                       "certainly not 'a bronze dragon told me'."),
    (WORD_IN_THE_EAR, DONE, ALLIANCE, "Did you see the counselor's face when you mentioned the ridge? I did. Remember it."),
    # A Time for Heroes
    (A_TIME_FOR_HEROES, TAKEN, ALLIANCE, "'Valiance Keep cannot fall.' It doesn't, you know. It comes close, three times. Let us make it two."),
    (A_TIME_FOR_HEROES, TURNED_IN, ALLIANCE, "'By Magni's beard.' Hammerhill says it about eleven times a day. I counted once. I had the time."),
    # The Siege
    (THE_SIEGE, TAKEN, ALLIANCE, "Crypt crawlers. They were nerubians once, citizens of Azjol-Nerub, before the Lich King took their kingdom "
                                 "and kept the bodies. Kill them kindly. It is the only kindness they will get."),
    (THE_SIEGE, TURNED_IN, ALLIANCE, "Not a soul lost on the barricade today. You will hear the medic say so, and she will not know who to "
                                     "thank. Let her thank the sergeant. He moved the catapults."),
    # Death From Above
    (DEATH_FROM_ABOVE, TAKEN, ALLIANCE, "Fishing nets against flying nerubians. Do not laugh: it works. Dwarven engineering is mostly "
                                        "refusing to admit something will not work."),
    (DEATH_FROM_ABOVE, TURNED_IN, ALLIANCE, "Hammerhill wants them to come by sea next, so he can give them 'the beating of their lives'. "
                                            "They are already dead. I have stopped arguing with dwarves about details."),
    # Plug the Sinkholes
    (PLUG_THE_SINKHOLES, TAKEN, ALLIANCE, "Two sinkholes in Hammerhill's orders. Three in Eternus's notes. Four in the ground. Blow his two; "
                                          "then come and find me."),
    (PLUG_THE_SINKHOLES, DONE, ALLIANCE, "See the cultists chanting at the northern sinkhole? Remember where they stand. We are coming back for "
                                         "them."),
    # The Fourth Sinkhole
    (FOURTH_SINKHOLE, TAKEN, ALLIANCE, "The necrolytes first, at the northern sinkhole. Cultists chanting over a hole in the ground. Some "
                                       "people have no hobbies."),
    # A Kindness Out of Time / Word at the Gate / Word on the Street
    (WORD_AT_THE_GATE, TURNED_IN, ALLIANCE, "Leryssa. Thassarian's sister. She sold everything she owned to cross an ocean for a brother "
                                            "the whole world says is dead. I like her already."),
    (WORD_ON_THE_STREET, TURNED_IN, ALLIANCE, "Before she asks you anything: I need you. Now. Take what I am about to give you, then say "
                                              "yes to her."),
    # Not Yet Late
    (NOT_YET_LATE, TAKEN, ALLIANCE, "Farshire Mine, east of the keep. Run, $N. I mean it: run."),
    (NOT_YET_LATE, DONE, ALLIANCE, "Breathe. He's alive. Look at the cut: a knife, not a claw. Leryssa was right to be frightened."),
    (THASSARIAN_MY_BROTHER, TAKEN, ALLIANCE, "Now you can tell her you found him. Alive. Watch her face when you do; it is worth the run."),
    # Named for No Town
    (NAMED_FOR_NO_TOWN, TAKEN, ALLIANCE, "Hear that? 'The inner hold.' He was getting close. That is why there was a knife."),
    (NAMED_FOR_NO_TOWN, DONE, ALLIANCE, "The counselor's hand. Of course it was. Do not confront him, $N. Not yet. If he falls here, he does "
                                        "not fall where history needs him to."),
    # A Useful Omission
    (A_USEFUL_OMISSION, TAKEN, ALLIANCE, "The wine, the deserter, the tuskarr with a grudge: you have done the part that mattered. Corporal "
                                         "Venn is holding out where the trail ends."),
    # No One Left Behind
    (NO_ONE_LEFT_BEHIND, TAKEN, ALLIANCE, "The necromancers' yard, in the Temple City. Four of them, on their knees, waiting for the cult to "
                                          "decide what they are for."),
    (NO_ONE_LEFT_BEHIND, DONE, ALLIANCE, "Four. History said none. Let it argue."),
    # Thassarian, the Death Knight
    (THASSARIAN_THE_DEATH_KNIGHT, TAKEN, ALLIANCE, "Thassarian was one of the first death knights to turn from the Lich King. He once went "
                                                   "back into Acherus for an orc, against every covenant he had. He will do worse for his "
                                                   "sister."),
    (THASSARIAN_THE_DEATH_KNIGHT, TURNED_IN, ALLIANCE, "'Before she gets hurt.' Talk to me before you do anything else."),
    # Before She Gets Hurt
    (BEFORE_SHE_GETS_HURT, TAKEN, ALLIANCE, "The inn. She is upstairs, and the vindicator is by the window. Hold on."),
    # Finding the Phylactery
    (FINDING_THE_PHYLACTERY, TAKEN, ALLIANCE, "Dusk knows the way; she has carried him through worse. A lich who keeps his soul in a jar "
                                              "under the ice. Liches are so predictable."),
    # Buying Some Time
    (BUYING_SOME_TIME, TAKEN, ALLIANCE, "Twenty of them in the Temple City, to keep the cult looking the wrong way. Hammerhill's volunteers "
                                        "are holding the ziggurat behind you. Thassarian will never have to learn whether he could have held "
                                        "it alone."),
    # Words of Power
    (WORDS_OF_POWER, TAKEN, ALLIANCE, "Three priests, three spires, three words. I know all three, and I cannot tell you a single one: you "
                                      "would have no way to explain how you knew."),
    # Last Rites
    (LAST_RITES, TAKEN, ALLIANCE, "Leryssa is gone from the inn. Yaala says the general's own escort came for her, under the general's seal, "
                                  "and she could not refuse a direct order. I am sorry, $N. Some threads are knots."),
    (LAST_RITES, TURNED_IN, ALLIANCE, "Do you see now? If she had not been on that tower, Valanar would never have knelt before his master "
                                      "and boasted, and the Alliance would never have seen his face. History needed his offerings. I hate it. "
                                      "I hate that it held."),
    (LAST_RITES, TURNED_IN, ALLIANCE, "He will be back, you know. Valanar. At the end, in the Citadel, with his brothers. 'Naxxanar was merely "
                                      "a setback.' You will enjoy proving him wrong twice.", dict(delay=9000)),
    # The Keep Holds
    (THE_KEEP_HOLDS, TURNED_IN, ALLIANCE, "Two regiments of draenei, a general who thinks his own thoughts, and four Stormwind boys who will "
                                          "see their mothers. Eternus will call it a good week. So will I.", dict(delay=60000)),
]
