"""After the turn: putting back what the record cannot keep (STORY.md, "Moratari in the war"; tools/story.py
writes it).

The future Sylvanas has told the Timerunner the war must happen as it did, and at the turn Moratari
learned what Eternus left out. She asks the Timerunner to help her put back what they changed
together, without taking back a single life:

- WRM-048 Lost at Sea: William Allerton, alive and asking questions about Unit S, sails home under
  another name; the ledger keeps him dead at Farshire.
- WRM-049 Forged Orders: Unit S's freed men and Hammerhill's volunteers are sent home to Stormwind's
  harbor; the record keeps Unit S lost at the landing.
- WRM-230 The Record Keeps Warsong: after the Wrathgate, the coast's garrison, the spare tanks and the
  plains' wounded go back to Orgrimmar under the Warchief's martial law; and Garrosh's lesson breaks
  at his friend's son's armour.

Wintergarde's company at Fordragon Hold is recalled by the High Commander himself (WRM-201's text, in
wrath_remix_world_0011_chapter2.sql); the word sent to Saurfang never reaches his son (Agmar burned
it: a whisper in story_dragonblight.py). After Angrathar, the end of Chapter II, waits for these.
"""
from story_common import (ALLIANCE, HORDE, TAKEN, DONE, TURNED_IN, WEIGHT_OF_TIME, MORATARI, OBJECTIVES_DONE, IN_PROGRESS, rewarded,
                          state)
from story_borean_alliance import (NOT_YET_LATE, NO_ONE_LEFT_BEHIND, LOST_AT_SEA, FORGED_ORDERS, LIVING_ALLERTON, VENN)
from story_borean_horde import (THE_CONDEMNED_COAST, RECORD_KEEPS_WARSONG, GORGE, GARROSH, SAURFANG, DARKNESS_STIRS)

AFTER_ANGRATHAR_A, AFTER_ANGRATHAR_H = 77221, 77222
BAZAAR = (571, 5700.0, 1012.0, 174.6, 1.57)

C_ALLERTON_SAILS, C_VENN_ORDERS, C_GORGE_RECALL = 770600, 770601, 770602
SCENE_LESSON_BREAKS = 152

CREATURES = [
    dict(entry=C_ALLERTON_SAILS, name="William Allerton persuaded to sail home", credit=True),
    dict(entry=C_VENN_ORDERS, name="Corporal Venn given the orders", credit=True),
    dict(entry=C_GORGE_RECALL, name="Gorge told of the recall", credit=True),
]

# She waits where she took the Timerunner, to take them back: by Allerton in the mine and on Gorge's
# beach she already stands (WRM-006's, WRM-016's); at Death's Stand, only while Forged Orders is in hand.
SPAWNS = [
    dict(guid=7704385, entry=MORATARI, x=3106.5, y=3839.5, z=22.8, o=0.47, equip=0,
         visible=[[state(FORGED_ORDERS, IN_PROGRESS | OBJECTIVES_DONE)]], why="Moratari at Death's Stand while Forged Orders is in hand"),
]

QUESTS = [
    dict(id=LOST_AT_SEA, title="Lost at Sea", side=ALLIANCE, sort=3537, level=-1, min=20, bronze=1000,
         gates=[[rewarded(WEIGHT_OF_TIME), rewarded(NOT_YET_LATE)]],
         log="Persuade William Allerton, in the Farshire Mine, to sail home under another name. Moratari will take you there and back.",
         desc="I need your help to put something back, $N, and I need you to understand why before you hate me for asking.$B$BWilliam "
              "Allerton was supposed to die in the Farshire mine. He did not, because of us. Now he is alive in the Borean Tundra, writing "
              "letters to Stormwind about Unit S, about the counselor, about a hero with a strange way of knowing where to be. Every letter "
              "is a thread pulling the war somewhere it never went.$B$BHe cannot stay. Not here, and not under his own name. But he does not "
              "have to die. I will not ask that of you, and I would not do it myself. Talk to him.",
         done="Return to Moratari.",
         departure=(571, 2765.6, 5337.8, 30.03, 0.20),
         bridge=dict(at=BAZAAR),
         objectives=[(C_ALLERTON_SAILS, 1, "William Allerton persuaded to sail home")],
         request="Will he go?",
         reward="The Kraken sails at dawn. In the ledger, William Allerton died at Farshire, with his throat cut. In Westfall, a farmer called "
                "Will Allard will grow very good corn, and tell nobody where he learned to look over his shoulder.$B$BThank you, $N. "
                "That one I could not have done alone."),
    dict(id=FORGED_ORDERS, title="Forged Orders", side=ALLIANCE, sort=3537, level=-1, min=20, bronze=1000,
         gates=[[rewarded(WEIGHT_OF_TIME), rewarded(NO_ONE_LEFT_BEHIND)]],
         log="Give Corporal Venn at Death's Stand the orders that send Unit S's survivors and Hammerhill's volunteers home.",
         desc="History lost Unit S on a beach. If four men walk into Wintergarde with the counselor's name on their lips, the 7th Legion "
              "starts asking questions it never asked, and the cult's list of traitors reaches the wrong hands far too soon.$B$BThey cannot "
              "die again; I will not allow it. But they can go home, quietly. I have written orders sending them to Stormwind's harbor, "
              "with Hammerhill's volunteers to see them aboard. Chromie lent me the seal. Do not ask.$B$BGive them to Corporal Venn. He will "
              "understand more than you think.",
         done="Return to Moratari.",
         departure=(571, 3111.0, 3840.0, 22.85, 5.75),
         bridge=dict(at=BAZAAR),
         objectives=[(C_VENN_ORDERS, 1, "Corporal Venn given the orders")],
         request="Did he take them?",
         reward="'S' for 'sent home'. He said that, didn't he? I knew he would.$B$BIn the record, Unit S was lost at the landing, and nobody "
                "ever comes looking. In Stormwind, four mothers will open their doors to sons the army already wrote to them about. Some "
                "changes, $N, the war can keep, as long as it never hears of them."),
    dict(id=RECORD_KEEPS_WARSONG, title="The Record Keeps Warsong", side=HORDE, sort=3537, level=-1, min=20, bronze=1000,
         gates=[[rewarded(WEIGHT_OF_TIME), rewarded(DARKNESS_STIRS), rewarded(THE_CONDEMNED_COAST)]],
         log="Tell Gorge, on the Coast of Echoes, that the Warchief is calling every soldier who can walk back to Orgrimmar. Moratari will "
             "take you there and back.",
         desc="Orgrimmar is under martial law. The Warchief calls every soldier who can walk back to the city, and in the record the coast "
              "at Garrosh's Landing is condemned again by the end of the week: no garrison, no spare tanks, no one left on the beach but "
              "Gorge, a goblin and the mist.$B$BIn our record, there is a garrison there, because of us. It cannot stay, $N. Not with the "
              "Horde at war with itself in its own capital. Go and tell Gorge. He will take it badly. He takes everything badly.",
         done="Return to Moratari.",
         departure=(571, 2822.5, 6708.5, 10.8, 2.70),
         bridge=dict(at=BAZAAR),
         objectives=[(C_GORGE_RECALL, 1, "Gorge told of the recall")],
         request="Is the coast emptied?",
         reward="Condemned again, as it was. The garrison sails for Orgrimmar, the spare tanks with them, and the wounded who could walk walked "
                "aboard. History has its coast back.$B$BI keep thinking about the moment at the war map, when he said 'Now we have a "
                "shipping lane' and did not stamp on anything. It was real, $N. For one afternoon, it was real."),
]

GATES = {
    AFTER_ANGRATHAR_A: [[rewarded(LOST_AT_SEA), rewarded(FORGED_ORDERS)]],
    AFTER_ANGRATHAR_H: [[rewarded(RECORD_KEEPS_WARSONG)]],
}

TALK = [
    dict(npc=LIVING_ALLERTON, quest=LOST_AT_SEA, credit=C_ALLERTON_SAILS,
         option="William, the cult will try again, and next time no one will be there in time. Sail home tonight, under another name.",
         reply="Another name? <William Allerton laughs, then stops.> You're serious.$B$BThe ledger will say I died in this mine, won't it? "
               "...Fine. Leryssa knows the truth, and Thassarian's alive. That's enough for me.$B$BI always fancied myself a farmer. "
               "Somewhere warm, where the ground doesn't open up under you."),
    dict(npc=VENN, quest=FORGED_ORDERS, credit=C_VENN_ORDERS,
         option="Corporal, these orders send your men home to Stormwind. The record will say Unit S was lost at the landing. Nobody will "
                "come looking.",
         reply="Lost at the landing. <Corporal Venn reads the orders twice, then a third time.>$B$BWell. We were, weren't we. For a while.$B$B"
               "'S' for suicide, they called it. Maybe now it's 'S' for 'sent home'. I'll take it, and I won't ask whose seal that is."),
    dict(npc=GORGE, quest=RECORD_KEEPS_WARSONG, credit=C_GORGE_RECALL,
         option="Gorge, the Warchief is calling every soldier who can walk back to Orgrimmar. The garrison too, and the spare tanks.",
         reply="Orgrimmar? Martial law, they're sayin'... <Gorge spits into the sand.>$B$BCondemned again, then. Figures. Every time I get "
               "this beach lookin' like somethin', somebody takes it away.$B$BFine! Take 'em! But the six tanks stay with me. Hellscream can "
               "come get those himself."),
]

SCENES = [
    # Darkness Stirs, as it's turned in (Saurfang's son's armour): Garrosh's lesson breaks.
    dict(id=SCENE_LESSON_BREAKS, name="Darkness Stirs: the lesson breaks", quest=DARKNESS_STIRS, trigger=2, anchor=SAURFANG,
         steps=[
             ("wait", 6000),
             ("bind", 1, GARROSH),
             ("bind", 2, SAURFANG),
             ("face", 1, 2),
             ("say", 1, "Supply lanes. Patience. Maps on the floor. And your son burned at their gate all the same, old one."),
             ("say", 2, "Not now, Hellscream."),
             ("say", 1, "No. Not now. Not ever again. No more waiting. When the Horde marches on Icecrown, it marches on my word."),
         ]),
]

WORDS = [
    (LOST_AT_SEA, TAKEN, ALLIANCE, "The mine. He has moved his cot down to the bottom level, where the light is worse and the knives have "
                                   "further to climb."),
    (FORGED_ORDERS, TAKEN, ALLIANCE, "Death's Stand. Venn kept them there, waiting for orders from a keep that no longer had a counselor to "
                                     "write any."),
    (RECORD_KEEPS_WARSONG, TAKEN, HORDE, "The coast. Gorge is by the tanks; he always is."),
    (DARKNESS_STIRS, TURNED_IN, HORDE, "There it goes. History needed him angry, $N. I am sorry it needed it this way.",
     dict(after=WEIGHT_OF_TIME, delay=24000)),
]
