-- Moratari's part in the war (STORY.md, "Moratari in the war"), written by tools/story.py from the
-- story_*.py files and romance.py: edit those and run it again. Her words on the game's quests, where
-- she walks with the Timerunner, what can be said to her and to the war's own people, the
-- interventions before the turn and their undoing after it, and the romance (RemixMoratari.cpp,
-- RemixOutings.cpp). This file owns: creatures 770500-770999, spawns 7704330-7704399, NPC texts
-- 7702600-7702999, its quests, the conditions on the quests it gates, the texts it changes, the gossip
-- flag of the game's creatures it gives something to say.

DROP TABLE IF EXISTS `remix_quest_whisper`;
CREATE TABLE `remix_quest_whisper` (
  `quest` INT UNSIGNED NOT NULL,
  `moment` TINYINT UNSIGNED NOT NULL,             -- 1 taken, 2 its objectives done, 3 turned in
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,     -- 0 both, 1 Alliance, 2 Horde
  `seq` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `speaker` INT UNSIGNED NOT NULL DEFAULT 770001,
  `delay_ms` INT UNSIGNED NOT NULL DEFAULT 3000,  -- after the line before it
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- said only once this is turned in
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0, -- said only until this is turned in
  `races` INT UNSIGNED NOT NULL DEFAULT 0,        -- a race mask (0: any)
  `text` TEXT NOT NULL,
  PRIMARY KEY (`quest`, `moment`, `side`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_companion`;
CREATE TABLE `remix_companion` (
  `id` INT UNSIGNED NOT NULL,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `zone` INT UNSIGNED NOT NULL DEFAULT 0,          -- 0: anywhere
  `from_quest` INT UNSIGNED NOT NULL DEFAULT 0,    -- turned in (0: from the start)
  `until_quest` INT UNSIGNED NOT NULL DEFAULT 0,   -- turned in ends it (0: never)
  `during_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- in the log (0: any time)
  `comment` VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_place`;
CREATE TABLE `remix_place` (
  `quest` INT UNSIGNED NOT NULL,
  `credit` INT UNSIGNED NOT NULL,                  -- given as they come within the radius
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL,
  `y` FLOAT NOT NULL,
  `radius` FLOAT NOT NULL DEFAULT 20,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,         -- played there instead (its end gives the credit)
  `text` TEXT NOT NULL,                            -- Moratari's words there ('' for none)
  PRIMARY KEY (`quest`, `credit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing`;
CREATE TABLE `remix_outing` (
  `id` INT UNSIGNED NOT NULL,
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL, `y` FLOAT NOT NULL, `z` FLOAT NOT NULL, `o` FLOAT NOT NULL,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,          -- played there (remix_scene)
  `private` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- in a phase of its own
  `undress` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- the Timerunner's gear unseen while it lasts
  `back` TINYINT UNSIGNED NOT NULL DEFAULT 1,       -- back where they were after
  `flight_from` INT UNSIGNED NOT NULL DEFAULT 0,    -- a flight on her: from this flight point (at x y z)
  `flight_to` INT UNSIGNED NOT NULL DEFAULT 0,
  `mount` INT UNSIGNED NOT NULL DEFAULT 0,          -- her shape under them
  `name` VARCHAR(120) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing_line`;
CREATE TABLE `remix_outing_line` (
  `outing` INT UNSIGNED NOT NULL,
  `at_ms` INT UNSIGNED NOT NULL,                    -- after the flight starts
  `text` TEXT NOT NULL,
  PRIMARY KEY (`outing`, `at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_look`;
CREATE TABLE `remix_look` (
  `display` INT UNSIGNED NOT NULL,                  -- the new look (CreatureDisplayInfo)
  `from_display` INT UNSIGNED NOT NULL,             -- copied from
  `extra` INT UNSIGNED NOT NULL,                    -- its clothes (CreatureDisplayInfoExtra)
  `from_extra` INT UNSIGNED NOT NULL,               -- copied from
  `bake` TINYINT UNSIGNED NOT NULL DEFAULT 0,       -- keep the copied look's baked texture (0: dressed from its items)
  `item1` INT UNSIGNED NOT NULL DEFAULT 0, `item2` INT UNSIGNED NOT NULL DEFAULT 0, `item3` INT UNSIGNED NOT NULL DEFAULT 0,
  `item4` INT UNSIGNED NOT NULL DEFAULT 0, `item5` INT UNSIGNED NOT NULL DEFAULT 0, `item6` INT UNSIGNED NOT NULL DEFAULT 0,
  `item7` INT UNSIGNED NOT NULL DEFAULT 0, `item8` INT UNSIGNED NOT NULL DEFAULT 0, `item9` INT UNSIGNED NOT NULL DEFAULT 0,
  `item10` INT UNSIGNED NOT NULL DEFAULT 0, `item11` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`display`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_talk`;
CREATE TABLE `remix_talk` (
  `id` INT UNSIGNED NOT NULL,
  `npc` INT UNSIGNED NOT NULL,                     -- the creature it's said to
  `parent` INT UNSIGNED NOT NULL DEFAULT 0,        -- 0: in its first menu; else after that entry's reply
  `ord` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `races` INT UNSIGNED NOT NULL DEFAULT 0,
  `gender` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 0 any, 1 male, 2 female
  `quest` INT UNSIGNED NOT NULL DEFAULT 0,         -- only while this is in the log, unfinished
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `option_text` VARCHAR(255) NOT NULL,
  `reply` INT UNSIGNED NOT NULL DEFAULT 0,         -- npc_text (0: the menu closes)
  `credit` INT UNSIGNED NOT NULL DEFAULT 0,        -- a quest's credit, given as it's said
  `action` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 1 scene [param], 2 close, 3 she waits, 4 she comes, 5 outing [param]
  `param` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `remix_companion` (`id`, `side`, `zone`, `from_quest`, `until_quest`, `during_quest`, `comment`) VALUES
(1, 1, 3537, 77014, 77121, 0, 'Chapter I: the Borean Tundra, while the Timerunner finds their feet'),
(2, 2, 3537, 77015, 77122, 0, 'Chapter I: the Borean Tundra, while the Timerunner finds their feet'),
(3, 0, 0, 0, 0, 77907, 'An Adventure of Our Own: the afternoon is theirs'),
(4, 0, 0, 77908, 77802, 0, 'After her tent: everywhere, until they go home');

INSERT INTO `remix_place` (`quest`, `credit`, `map`, `x`, `y`, `radius`, `scene`, `text`) VALUES
(77042, 770532, 571, 2332.0, 5470.0, 14.0, 101, 'Here. Under the dune. Put your ear to the sand.');

INSERT INTO `remix_quest_whisper` (`quest`, `moment`, `side`, `seq`, `speaker`, `delay_ms`, `after_quest`, `before_quest`, `races`, `text`) VALUES
(77014, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Valiance Keep. The fleet came in three days ago, and the Scourge has tested the walls every night since. You remember the smell of this place, I imagine: tar, salt, and far too many soldiers in one harbor.'),
(77014, 3, 1, 2, 770001, 7000, 0, 0, 0, 'I will walk with you while you find your feet. If you would rather I waited somewhere, say so.'),
(77015, 3, 2, 1, 770001, 3000, 0, 0, 0, 'Warsong Hold. Garrosh''s fortress, dug into a cliff that is already full of Nerubians, though no one inside knows it yet. Well. Almost no one.'),
(77015, 3, 2, 2, 770001, 7000, 0, 0, 0, 'I will walk with you while you find your feet. If you would rather I waited somewhere, say so.'),
(11672, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Blythe has heard about Outland, not about Icecrown. To this year, you are a hero of the last war, not this one. Enjoy it. It is the last time anyone will underestimate you.'),
(11672, 3, 1, 1, 770001, 5000, 0, 0, 0, 'The man at the general''s elbow is Counselor Talbot. Smile at him. Never turn your back on him. I will tell you why when I can.'),
(77041, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The general first, in the barracks up the hill. Remember: scouts. Not visions, not dreams, and certainly not ''a bronze dragon told me''.'),
(77041, 2, 1, 1, 770001, 3000, 0, 0, 0, 'Did you see the counselor''s face when you mentioned the ridge? I did. Remember it.'),
(11727, 1, 1, 1, 770001, 3000, 0, 0, 0, '''Valiance Keep cannot fall.'' It doesn''t, you know. It comes close, three times. Let us make it two.'),
(11727, 3, 1, 1, 770001, 3000, 0, 0, 0, '''By Magni''s beard.'' Hammerhill says it about eleven times a day. I counted once. I had the time.'),
(11797, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Crypt crawlers. They were nerubians once, citizens of Azjol-Nerub, before the Lich King took their kingdom and kept the bodies. Kill them kindly. It is the only kindness they will get.'),
(11797, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Not a soul lost on the barricade today. You will hear the medic say so, and she will not know who to thank. Let her thank the sergeant. He moved the catapults.'),
(11889, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Fishing nets against flying nerubians. Do not laugh: it works. Dwarven engineering is mostly refusing to admit something will not work.'),
(11889, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Hammerhill wants them to come by sea next, so he can give them ''the beating of their lives''. They are already dead. I have stopped arguing with dwarves about details.'),
(11897, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Two sinkholes in Hammerhill''s orders. Three in Eternus''s notes. Four in the ground. Blow his two; then come and find me.'),
(11897, 2, 1, 1, 770001, 3000, 0, 0, 0, 'See the cultists chanting at the northern sinkhole? Remember where they stand. We are coming back for them.'),
(77042, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The necrolytes first, at the northern sinkhole. Cultists chanting over a hole in the ground. Some people have no hobbies.'),
(77019, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Leryssa. Thassarian''s sister. She sold everything she owned to cross an ocean for a brother the whole world says is dead. I like her already.'),
(11927, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Before she asks you anything: I need you. Now. Take what I am about to give you, then say yes to her.'),
(77043, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Farshire Mine, east of the keep. Run, $N. I mean it: run.'),
(77043, 2, 1, 1, 770001, 3000, 0, 0, 0, 'Breathe. He''s alive. Look at the cut: a knife, not a claw. Leryssa was right to be frightened.'),
(11599, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Now you can tell her you found him. Alive. Watch her face when you do; it is worth the run.'),
(77044, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Hear that? ''The inner hold.'' He was getting close. That is why there was a knife.'),
(77044, 2, 1, 1, 770001, 3000, 0, 0, 0, 'The counselor''s hand. Of course it was. Do not confront him, $N. Not yet. If he falls here, he does not fall where history needs him to.'),
(77006, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The wine, the deserter, the tuskarr with a grudge: you have done the part that mattered. Corporal Venn is holding out where the trail ends.'),
(77045, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The necromancers'' yard, in the Temple City. Four of them, on their knees, waiting for the cult to decide what they are for.'),
(77045, 2, 1, 1, 770001, 3000, 0, 0, 0, 'Four. History said none. Let it argue.'),
(12088, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Thassarian was one of the first death knights to turn from the Lich King. He once went back into Acherus for an orc, against every covenant he had. He will do worse for his sister.'),
(12088, 3, 1, 1, 770001, 3000, 0, 0, 0, '''Before she gets hurt.'' Talk to me before you do anything else.'),
(77046, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The inn. She is upstairs, and the vindicator is by the window. Hold on.'),
(11956, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Dusk knows the way; she has carried him through worse. A lich who keeps his soul in a jar under the ice. Liches are so predictable.'),
(11938, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Twenty of them in the Temple City, to keep the cult looking the wrong way. Hammerhill''s volunteers are holding the ziggurat behind you. Thassarian will never have to learn whether he could have held it alone.'),
(11942, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Three priests, three spires, three words. I know all three, and I cannot tell you a single one: you would have no way to explain how you knew.'),
(12019, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Leryssa is gone from the inn. Yaala says the general''s own escort came for her, under the general''s seal, and she could not refuse a direct order. I am sorry, $N. Some threads are knots.'),
(12019, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Do you see now? If she had not been on that tower, Valanar would never have knelt before his master and boasted, and the Alliance would never have seen his face. History needed his offerings. I hate it. I hate that it held.'),
(12019, 3, 1, 2, 770001, 9000, 0, 0, 0, 'He will be back, you know. Valanar. At the end, in the Citadel, with his brothers. ''Naxxanar was merely a setback.'' You will enjoy proving him wrong twice.'),
(77047, 3, 1, 1, 770001, 60000, 0, 0, 0, 'Two regiments of draenei, a general who thinks his own thoughts, and four Stormwind boys who will see their mothers. Eternus will call it a good week. So will I.'),
(11585, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Garrosh Hellscream, son of Grom. He smells fear on everyone who walks into his hold. Let him smell nothing on you. It confuses him.'),
(11585, 3, 2, 1, 770001, 6000, 0, 0, 0, 'He and Saurfang have the same argument at that floor map every few minutes. Shipping lanes, supplies, Garrosh stamping on things. I have counted. I have had the time.'),
(77050, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Garrosh first; he is closest, and he will not believe you. Then Saurfang. Then the captain on the south wall, by the battlements.'),
(77050, 2, 2, 1, 770001, 3000, 0, 0, 0, '''You did a good job in warning us.'' He has had that line ready for a week, and no one ever gave him a reason to say it.'),
(11596, 1, 2, 1, 770001, 3000, 0, 0, 0, '''Do not let Hellscream dissuade you.'' Saurfang drank Mannoroth''s blood beside Grom, and he has spent every day since making sure Grom''s son does not do the same.'),
(11598, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Some of those cocoons hold soldiers, not peons. Cut them down and they will fight beside you. Some of them would otherwise be names on the wall of the hold, with a line through them.'),
(11598, 3, 2, 1, 770001, 3000, 0, 0, 0, 'The quarry is the Horde''s again. Now let us make sure nothing comes up under it.'),
(77051, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Holgoth, by the quartermaster''s stores. He has explosives and a plan. Goblin explosives, orc plan. That is usually how wars are won.'),
(11606, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Munitions crates, all over the quarry. Pick them up quickly; the nerubians like to sit on them.'),
(11608, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Four sinkholes. In the record, they are still open when the floor of the keep caves in. Not this time.'),
(11608, 3, 2, 1, 770001, 3000, 0, 0, 0, 'There. The floor of Warsong Hold stays a floor. Garrosh will never know he had nothing to stamp on.'),
(11602, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The eggs are on the scaffolding, high and dry. Each one is twenty fewer nerubians, Razgor says. I checked his arithmetic once. He is being modest.'),
(11634, 1, 2, 1, 770001, 3000, 0, 0, 0, 'To''bor, at the top of the hold. ''Across dat tundra be death, mon.'' He is not wrong, but he is very dramatic about it.'),
(11636, 1, 2, 1, 770001, 3000, 0, 0, 0, 'A thirty percent survival rate, and Yanni calls it an improvement. Hold on tight. I will be there when you land.'),
(11642, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Gorge calls Mobu a gnome. Mobu is a goblin. Do not correct him. Nobody corrects Gorge.'),
(11642, 3, 2, 1, 770001, 4000, 0, 0, 0, 'He is about to ask for a pneumatic tank transjigamarig and ten metal plates. The transjigamarig is in his old hut by the stables, in the mist. The plates are scattered over the landing and in the shallows. I have watched him look for them for three days. Let us save him the trouble.'),
(11643, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Into the mist. The Kvaldir cannot see you any better than you can see them. That is the only comfort I have to offer.'),
(11651, 3, 2, 1, 770001, 3000, 0, 0, 0, 'Six tanks, all that is left of the first expedition. Nine more lie burned out on the plains. Today, two of those nine come back to life.'),
(77054, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The Mist Lords are on the landing, inside the mist. Then Gorge. He will say no first. He always says no first.'),
(11652, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Three seats in that tank for the wounded. Fill them. The Scourge stitch the ones they find into something called a Stitched Warsong Horror. The name tells you everything.'),
(11652, 3, 2, 1, 770001, 3000, 0, 0, 0, '''So it''s true.'' He said that the first time too, and I always wondered who told him. Now I know.'),
(77056, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The war map, in the middle of the hold. Stand where Saurfang can see you.'),
(77056, 3, 2, 1, 770001, 50000, 0, 0, 0, 'He has never once said that without stamping on something. Remember this day, $N. It does not last.'),
(12235, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Wintergarde. I showed you this place once already, in the lower village, the night the plague came down. Naxxramas fell on it because Bolvar struck at the gate first. The Lich King answers his front door by dropping a citadel on the back one.'),
(12237, 1, 1, 1, 770001, 3000, 0, 0, 0, '''I picked a bad day to stop drinking!'' One of them says that every time. Every single time. I have started to look forward to it.'),
(12237, 3, 1, 1, 770001, 4000, 0, 0, 0, 'The roost. Look at the beam above the gryphons. Talk to me before the High Commander does.'),
(77223, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Lynore by the High Commander, Leoric up the hill, Urik at the roost. A veteran''s eye. You have one; use it.'),
(12253, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The geists in the square were the living, last week. They do not know they are hunting their own neighbors. Free whoever you can.'),
(12309, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Necrolord Amarion is in the crypt, raising the nobles buried there. In the record, the crypt is cleared the day after Chromie sends for you. A day is a long time in a crypt. Go tonight.'),
(12311, 1, 1, 1, 770001, 3000, 0, 0, 0, 'There is a shadow beside the necrolord: Thel''zan''s. Thel''zan himself is under the old village, in the mausoleum. That fight comes later, and you will win it.'),
(12311, 3, 1, 1, 770001, 5000, 0, 0, 0, 'A company to Fordragon Hold. In the record, Wintergarde''s help never reaches Bolvar: the dead at the gate are all Fordragon''s. Eternus will be very pleased with this one.'),
(12034, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Agmar, Kor''kron Commander. ''His word is life and his word is death'', he says of Hellscream. He reads everyone''s mail, too. Keep yours short.'),
(77236, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Torvus, by the burning brazier. Agmar demands that every letter be read before it is delivered. Torvus has never let him read one.'),
(12036, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The Pit of Narjun opens into Azjol-Nerub. You have been down there before, in your own year, and killed its king. Twice. The nerubian at the edge of the pit is Kilix; not everything down there serves the Lich King.'),
(77234, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The cultists are on Icemist''s ruins, breathing on the blightbeasts. The beasts are for the Wrathgate. Make them go to it short-handed.'),
(77234, 3, 2, 1, 770001, 4000, 0, 0, 0, 'Sintar is a Grand Apothecary. So is another apothecary you have heard of, whose name nobody here says yet. Watch what you carry for him.'),
(12189, 1, 2, 1, 770001, 3000, 0, 0, 0, '''The last piece of the victory puzzle.'' Venomspite, south of here. Chief Plaguebringer Middleton has the shipment, and he is very proud of it.'),
(12188, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The specters on the beach are Prince Arthas''s own soldiers, left to freeze when he burned his ships. The Royal Apothecary Society grinds his abandoned dead into its weapon. There is a lesson in that, but I am not sure anyone learns it.'),
(12200, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Green dragons come to the Emerald Dragonshrine to die, and they weep as they go. ''They cry a lot right before they die. Go figure.'' Do not let me hear you say that to a dragon, $N.'),
(12218, 1, 2, 1, 770001, 3000, 0, 0, 0, 'A field test on Wintergarde''s dead. Listen to Middleton afterwards: ''we will ravage the living... I mean the Scourge.'' He says it like a joke.'),
(77235, 1, 2, 1, 770001, 3000, 0, 0, 0, 'Molder by the east gate, Lawrence by the alchemy tables. Count, $N. Do not accuse.'),
(12221, 3, 2, 1, 770001, 4000, 0, 0, 0, '''This work is all classified.'' It is. It is also the weapon that falls on everyone at the Wrathgate. I would like very much to be wrong about that.'),
(12470, 1, 0, 1, 770001, 3000, 0, 0, 0, 'A time storm keeps immortals out of the Bronze Dragonshrine. It is the only thing Chromie and I have in common: neither of us may go in. You may. I will wait at the edge.'),
(12470, 2, 0, 1, 770001, 3000, 0, 0, 0, 'She will think the hourglass failed. It showed her exactly what she asked for; she cannot read it yet. Neither could I, for a long time.'),
(12470, 3, 0, 1, 770001, 16000, 0, 0, 0, 'Do not take it for fate, $N. The Lord of Time has spent ages refusing an ending he was shown. If he can refuse one, so can we. That is what Eternus would say, anyway.'),
(77204, 1, 0, 1, 770001, 3000, 0, 0, 0, 'The Wyrmrest Accord''s ambassadors are on this platform. None of them may learn who those two rangers are. Speak softly.'),
(77218, 1, 2, 1, 770001, 3000, 77236, 0, 0, 'The letter you sent Varok never reached his son. Agmar reads all the mail, and he burned that one as ''delicate matters''. I went and checked. I am glad.'),
(77048, 1, 1, 1, 770001, 3000, 0, 0, 0, 'The mine. He has moved his cot down to the bottom level, where the light is worse and the knives have further to climb.'),
(77049, 1, 1, 1, 770001, 3000, 0, 0, 0, 'Death''s Stand. Venn kept them there, waiting for orders from a keep that no longer had a counselor to write any.'),
(77230, 1, 2, 1, 770001, 3000, 0, 0, 0, 'The coast. Gorge is by the tanks; he always is.'),
(13242, 3, 2, 1, 770001, 24000, 77207, 0, 0, 'There it goes. History needed him angry, $N. I am sorry it needed it this way.'),
(77901, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Here. Sit. The sand is warm on this side.'),
(77902, 1, 0, 1, 770001, 3000, 0, 0, 0, 'One drink. I will hold you to one. You may hold me to two.'),
(77903, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Do not look at the dress yet. Look when we get there. I want to see your face.'),
(77904, 1, 0, 1, 770001, 3000, 0, 0, 0, 'At night the Accord sleeps on the lower tiers. The top is only wind.'),
(77906, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Eat something light first. I am told the first time is memorable.'),
(77907, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Leave the map in your pack today. We are getting lost on purpose.'),
(77907, 2, 0, 1, 770001, 3000, 0, 0, 0, 'Look at that. An afternoon with nothing left to do in it.'),
(77908, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Whenever you like. I have waited about a thousand years; another hour will not hurt.'),
(77909, 1, 0, 1, 770001, 3000, 0, 0, 0, 'Not far. Just past the last fire, where the wind is.'),
(77207, 1, 0, 1, 770001, 3000, 77904, 0, 0, 'Stay close to me today, $N. I do not know why. I only know I want you close.'),
(77048, 3, 1, 1, 770001, 9000, 77905, 0, 0, 'We are putting back what we did. Not all of it, though. Not us.'),
(77230, 3, 2, 1, 770001, 9000, 77905, 0, 0, 'We are putting back what we did. Not all of it, though. Not us.'),
(77800, 1, 0, 1, 770001, 3000, 77908, 0, 0, 'If you die up there, I will be very cross with you for a very long time. I have a very long time.'),
(77801, 1, 0, 1, 770001, 3000, 77909, 0, 0, '''See you later'', they all say. I am going to say it too, and I am going to mean it more than any of them.'),
(77802, 1, 0, 1, 770001, 3000, 77909, 0, 0, 'Look in your mailbox when you are home. I keep my promises. I am a dragon; it is very nearly all we have.');

INSERT INTO `remix_talk` (`id`, `npc`, `parent`, `ord`, `side`, `races`, `gender`, `quest`, `after_quest`, `before_quest`, `icon`, `option_text`, `reply`, `credit`, `action`, `param`) VALUES
(77001, 770001, 0, 1, 0, 0, 0, 0, 0, 0, 0, 'Wait for me here. I''d like to walk alone for a while.', 7702600, 0, 3, 0),
(77002, 770001, 0, 2, 0, 0, 0, 0, 0, 0, 0, 'Walk with me again.', 7702601, 0, 4, 0),
(77003, 25250, 0, 1, 0, 0, 0, 77041, 0, 0, 0, 'General, the crawlers on the beach are only a vanguard. My scouts found worm-trails under the western ridge, and their flyers will come over the inner wall.', 7702602, 770530, 0, 0),
(77004, 25301, 0, 1, 0, 0, 0, 77041, 0, 0, 0, 'Counselor, the general says you have the ridge reports. I''d like to see them.', 7702603, 0, 0, 0),
(77005, 25816, 0, 1, 0, 0, 0, 77041, 0, 0, 0, 'Sergeant, they''ll dig under the western ridge and come up behind your line. And their flyers will hit the inner defenses.', 7702604, 770531, 0, 0),
(77006, 25302, 0, 1, 0, 0, 0, 77044, 0, 0, 0, 'Colburn, what do you know about a unit called Unit S?', 7702605, 770533, 0, 0),
(77007, 25395, 0, 1, 0, 0, 0, 77044, 0, 0, 0, 'Private, who signed the order that sent Unit S east?', 7702606, 770534, 0, 0),
(77008, 770552, 0, 1, 0, 0, 0, 77045, 0, 0, 0, 'Private Harker? Get up. Valiance Keep sent me. Head for Death''s Stand.', 7702607, 770535, 0, 0),
(77009, 770553, 0, 1, 0, 0, 0, 77045, 0, 0, 0, 'Private Dunn. On your feet, soldier. Death''s Stand, now.', 7702608, 770536, 0, 0),
(77010, 770554, 0, 1, 0, 0, 0, 77045, 0, 0, 0, 'Corporal Mabry. Your men are getting out. Lead them to Death''s Stand.', 7702609, 770537, 0, 0),
(77011, 770555, 0, 1, 0, 0, 0, 77045, 0, 0, 0, 'Private Sorrel. Can you stand? Death''s Stand is west. Go.', 7702610, 770538, 0, 0),
(77012, 25251, 0, 1, 0, 0, 0, 77046, 0, 0, 0, 'Leryssa, the counselor knows you''re asking about Thassarian. Leave Valiance Keep tonight. Take the Kraken home.', 7702611, 770539, 0, 0),
(77013, 25826, 0, 1, 0, 0, 0, 77046, 0, 0, 0, 'Vindicator, the woman upstairs is in danger. Someone in the inner hold wants her silenced. Will you watch over her?', 7702612, 770540, 0, 0),
(77014, 25237, 0, 1, 0, 0, 0, 77050, 0, 0, 0, 'The nerubians are under the floor, Hellscream. They''ll come up through it, and down out of the sky.', 7702613, 770560, 0, 0),
(77015, 25237, 0, 2, 0, 0, 0, 77050, 0, 0, 0, 'And the cultists in the west serve a lich. Kel''Thuzad.', 7702614, 0, 0, 0),
(77016, 25256, 0, 1, 0, 0, 0, 77050, 0, 0, 0, 'High Overlord, the nerubians will come up through the floor of the keep. The quarry is riddled with their tunnels.', 7702615, 770561, 0, 0),
(77017, 25446, 0, 1, 0, 0, 0, 77050, 0, 0, 0, 'Captain, they''ll come over the south wall, and out of the sky. Put your marksmen up.', 7702616, 770562, 0, 0),
(77018, 25329, 0, 1, 0, 0, 0, 77054, 0, 0, 0, 'Gorge, keep a garrison on this coast. Ask Saurfang for the men. He''ll send them.', 7702617, 770563, 0, 0),
(77019, 27155, 0, 1, 0, 0, 0, 77223, 0, 0, 0, 'Commander, the riders who fell over the village will come back over the square. Pull your archers off the roofs before they do.', 7702618, 770580, 0, 0),
(77020, 27156, 0, 1, 0, 0, 0, 77223, 0, 0, 0, 'Highlord, close the mine road. The dead will come up it before the week is out.', 7702619, 770581, 0, 0),
(77021, 27317, 0, 1, 0, 0, 0, 77223, 0, 0, 0, 'Gryphon Commander, the roost that cracked on the first night won''t hold. Move your birds tonight.', 7702620, 770582, 0, 0),
(77022, 26649, 0, 1, 0, 0, 0, 77236, 0, 0, 0, 'Tell Saurfang this, from me: I have seen sons die proving themselves to their fathers. His should not lead the charge himself.', 7702621, 770583, 0, 0),
(77023, 27320, 0, 1, 0, 0, 0, 77235, 0, 0, 0, 'Deathguard, those blight spreaders by the east gate. Why can''t you use them on the siegesmiths?', 7702622, 770584, 0, 0),
(77024, 27846, 0, 1, 0, 0, 0, 77235, 0, 0, 0, 'Apothecary, I''m counting crates for Agmar''s Hammer. How much of the blight is going there?', 7702623, 770585, 0, 0),
(77025, 770550, 0, 1, 0, 0, 0, 77048, 0, 0, 0, 'William, the cult will try again, and next time no one will be there in time. Sail home tonight, under another name.', 7702624, 770600, 0, 0),
(77026, 26187, 0, 1, 0, 0, 0, 77049, 0, 0, 0, 'Corporal, these orders send your men home to Stormwind. The record will say Unit S was lost at the landing. Nobody will come looking.', 7702625, 770601, 0, 0),
(77027, 25329, 0, 2, 0, 0, 0, 77230, 0, 0, 0, 'Gorge, the Warchief is calling every soldier who can walk back to Orgrimmar. The garrison too, and the spare tanks.', 7702626, 770602, 0, 0),
(77028, 770001, 0, 3, 0, 0, 0, 77901, 0, 0, 0, 'Sit with her, and take the cup she offers.', 7702627, 0, 0, 0),
(77029, 770001, 77028, 1, 0, 0, 0, 77901, 0, 0, 0, 'How old are you, really?', 7702628, 0, 0, 0),
(77030, 770001, 77028, 2, 0, 0, 0, 77901, 0, 0, 0, 'Is it true that dragons sleep on gold?', 7702629, 0, 0, 0),
(77031, 770001, 77028, 3, 0, 1, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702630, 0, 0, 0),
(77032, 770001, 77028, 4, 0, 4, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702631, 0, 0, 0),
(77033, 770001, 77028, 5, 0, 8, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702632, 0, 0, 0),
(77034, 770001, 77028, 6, 0, 64, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702633, 0, 0, 0),
(77035, 770001, 77028, 7, 0, 1024, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702634, 0, 0, 0),
(77036, 770001, 77028, 8, 0, 2, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702635, 0, 0, 0),
(77037, 770001, 77028, 9, 0, 16, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702636, 0, 0, 0),
(77038, 770001, 77028, 10, 0, 32, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702637, 0, 0, 0),
(77039, 770001, 77028, 11, 0, 128, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702638, 0, 0, 0),
(77040, 770001, 77028, 12, 0, 512, 0, 77901, 0, 0, 0, 'You keep looking at me.', 7702639, 0, 0, 0),
(77041, 770001, 77028, 13, 0, 0, 0, 77901, 0, 0, 0, 'What do you do when you''re not saving history?', 7702640, 770700, 0, 0),
(77042, 770001, 0, 4, 1, 0, 0, 77902, 0, 0, 0, 'One drink, then. A Hero''s Welcome?', 0, 0, 5, 1),
(77043, 770001, 0, 5, 2, 0, 0, 77902, 0, 0, 0, 'One drink, then. The Filthy Animal?', 0, 0, 5, 2),
(77044, 770001, 0, 6, 0, 0, 0, 77903, 0, 0, 0, 'I''d love to. Show me this landing.', 0, 0, 5, 3),
(77045, 770001, 0, 7, 0, 0, 0, 77904, 0, 0, 0, 'Show me.', 0, 0, 5, 4),
(77046, 770001, 0, 8, 0, 0, 0, 77906, 0, 0, 0, 'I''m ready. Take me flying.', 0, 0, 5, 5),
(77047, 770001, 0, 9, 0, 0, 0, 77907, 0, 0, 0, 'Lay out the blanket. The crocolisks can wait.', 0, 0, 1, 907),
(77048, 770001, 0, 10, 0, 0, 0, 77908, 0, 0, 0, 'Take me to your tent.', 0, 0, 5, 6),
(77049, 770001, 0, 11, 0, 0, 0, 77909, 0, 0, 0, 'Walk with her to the edge of the camp.', 0, 0, 1, 908),
(77050, 770001, 0, 12, 0, 0, 0, 77905, 0, 0, 0, 'Sit beside her, and take her hand.', 7702641, 0, 0, 0),
(77051, 770001, 77050, 1, 0, 0, 0, 77905, 0, 0, 0, 'You''re not a monster. You gave them more time than history ever did.', 7702642, 0, 0, 0),
(77052, 770001, 77051, 1, 0, 0, 0, 77905, 0, 0, 0, 'From you. I love you, Moratari.', 7702643, 770704, 0, 0),
(77053, 770001, 0, 13, 0, 1, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702644, 0, 0, 0),
(77054, 770001, 0, 14, 0, 4, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702645, 0, 0, 0),
(77055, 770001, 0, 15, 0, 8, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702646, 0, 0, 0),
(77056, 770001, 0, 16, 0, 64, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702647, 0, 0, 0),
(77057, 770001, 0, 17, 0, 1024, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702648, 0, 0, 0),
(77058, 770001, 0, 18, 0, 2, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702649, 0, 0, 0),
(77059, 770001, 0, 19, 0, 16, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702650, 0, 0, 0),
(77060, 770001, 0, 20, 0, 32, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702651, 0, 0, 0),
(77061, 770001, 0, 21, 0, 128, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702652, 0, 0, 0),
(77062, 770001, 0, 22, 0, 512, 0, 0, 77902, 0, 0, 'Tell her she looks lovely today.', 7702653, 0, 0, 0),
(77063, 770001, 0, 23, 0, 0, 0, 0, 77903, 0, 0, 'Kiss her.', 0, 0, 1, 909);

DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702600 AND 7702999;
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(7702600, 'If you like. I will be where I always am: wherever you next need me. Call me back when you want me.', 'If you like. I will be where I always am: wherever you next need me. Call me back when you want me.', 1),
(7702601, 'I was hoping you would ask. Lead on, $N.', 'I was hoping you would ask. Lead on, $N.', 1),
(7702602, '<General Arlos glances at Counselor Talbot before he answers.>$B$BYour scouts are very... diligent, $N. The counselor has the ridge reports, and they say nothing of worms. The walls will hold.$B$BStill, a hero''s word is worth something. Take it to Sergeant Hammerhill. If anyone has a use for nervous talk about the ground, it is a dwarf.', '<General Arlos glances at Counselor Talbot before he answers.>$B$BYour scouts are very... diligent, $N. The counselor has the ridge reports, and they say nothing of worms. The walls will hold.$B$BStill, a hero''s word is worth something. Take it to Sergeant Hammerhill. If anyone has a use for nervous talk about the ground, it is a dwarf.', 1),
(7702603, '<Counselor Talbot smiles. It does not reach his eyes.>$B$BReports are for officers, champion. Kill the crawlers. Leave the thinking to those of us who were trained for it.$B$BAnd do tell your scouts to be careful on that ridge. It would be a shame to lose them.', '<Counselor Talbot smiles. It does not reach his eyes.>$B$BReports are for officers, champion. Kill the crawlers. Leave the thinking to those of us who were trained for it.$B$BAnd do tell your scouts to be careful on that ridge. It would be a shame to lose them.', 1),
(7702604, 'Worm-trails, ye say? Hah! The general can keep his reports. Thirty years in the mines taught me to trust the ground over the brass.$B$BI''ll shift two catapults to the south wall tonight and put spotters on the ridge. If you''re wrong, I''ve lost a night''s sleep. If you''re right... well. Then I owe ye a drink, and I''ll pay it.', 'Worm-trails, ye say? Hah! The general can keep his reports. Thirty years in the mines taught me to trust the ground over the brass.$B$BI''ll shift two catapults to the south wall tonight and put spotters on the ridge. If you''re wrong, I''ve lost a night''s sleep. If you''re right... well. Then I owe ye a drink, and I''ll pay it.', 1),
(7702605, '<Old Man Colburn squints at you.>$B$BUnit S? There''s no such town. Darkshire was sent to the Dragonblight. The Goldshire boys went to the Fjord. I know every unit, every brigade, every detachment that ever drew Valiance pay, and there''s no S.$B$BWhich means somebody made it up. And people don''t make up units to send them somewhere nice.', '<Old Man Colburn squints at you.>$B$BUnit S? There''s no such town. Darkshire was sent to the Dragonblight. The Goldshire boys went to the Fjord. I know every unit, every brigade, every detachment that ever drew Valiance pay, and there''s no S.$B$BWhich means somebody made it up. And people don''t make up units to send them somewhere nice.', 1),
(7702606, '<Private Brau glances toward the jailer before he answers, very quietly.>$B$B''S'' stood for suicide. That''s what the lads called it. A dozen of us, one death knight, and a Scourge ziggurat.$B$BThe orders came down from the inner hold, with the general''s seal. But the general never signed nothing, friend. I saw the hand. It was the counselor''s.$B$BDon''t say I told you. That one by the door hears everything.', '<Private Brau glances toward the jailer before he answers, very quietly.>$B$B''S'' stood for suicide. That''s what the lads called it. A dozen of us, one death knight, and a Scourge ziggurat.$B$BThe orders came down from the inner hold, with the general''s seal. But the general never signed nothing, friend. I saw the hand. It was the counselor''s.$B$BDon''t say I told you. That one by the door hears everything.', 1),
(7702607, 'Sent? Somebody sent... <Private Harker scrambles to his feet.> Yes, sir. Death''s Stand. I can walk. I can run, if it comes to it.', 'Sent? Somebody sent... <Private Harker scrambles to his feet.> Yes, sir. Death''s Stand. I can walk. I can run, if it comes to it.', 1),
(7702608, 'I knew it. I told Harker someone would come. He said I was mad. <Private Dunn laughs, too loudly, then stops.> Death''s Stand. Yes.', 'I knew it. I told Harker someone would come. He said I was mad. <Private Dunn laughs, too loudly, then stops.> Death''s Stand. Yes.', 1),
(7702609, 'Lead them. Right. <Corporal Mabry wipes his face with the back of a bound hand.> The cult said we''d be serving them by the full moon. Tell whoever sent you... no. I''ll tell them myself.', 'Lead them. Right. <Corporal Mabry wipes his face with the back of a bound hand.> The cult said we''d be serving them by the full moon. Tell whoever sent you... no. I''ll tell them myself.', 1),
(7702610, '<Private Sorrel stares at you as if you were a ghost.>$B$BThey said no one knew we were here. They said the ledger was burned. ...West. Yes. West.', '<Private Sorrel stares at you as if you were a ghost.>$B$BThey said no one knew we were here. They said the ledger was burned. ...West. Yes. West.', 1),
(7702611, 'Go home? <Leryssa laughs, not kindly.>$B$BI sold everything I owned to come here, $N. My brother is alive, somewhere out there in the snow, and you want me to sail away before I''ve seen his face?$B$BNo. Let them come. I''m not leaving without him.', 'Go home? <Leryssa laughs, not kindly.>$B$BI sold everything I owned to come here, $N. My brother is alive, somewhere out there in the snow, and you want me to sail away before I''ve seen his face?$B$BNo. Let them come. I''m not leaving without him.', 1),
(7702612, '<Vindicator Yaala''s eyes narrow.>$B$BSomeone in the inner hold. You choose your words carefully, $c. So do I.$B$BThe naaru teach that darkness hides best in plain sight. I will keep the girl in mine. If anyone comes for her, they will come through me.', '<Vindicator Yaala''s eyes narrow.>$B$BSomeone in the inner hold. You choose your words carefully, $c. So do I.$B$BThe naaru teach that darkness hides best in plain sight. I will keep the girl in mine. If anyone comes for her, they will come through me.', 1),
(7702613, 'Under my floor? <Garrosh sniffs.> Then they will find my boot waiting.$B$BI smelled your fear when you walked in, $r. Keep it out of my hold. The Warsong does not cower from bugs.', 'Under my floor? <Garrosh sniffs.> Then they will find my boot waiting.$B$BI smelled your fear when you walked in, $r. Keep it out of my hold. The Warsong does not cower from bugs.', 1),
(7702614, 'Kel''Thuzad? The Argent Dawn put that lich in the ground in the Plaguelands. <Garrosh spits.> I do not fight ghost stories, $r. I fight what bleeds.', 'Kel''Thuzad? The Argent Dawn put that lich in the ground in the Plaguelands. <Garrosh spits.> I do not fight ghost stories, $r. I fight what bleeds.', 1),
(7702615, '<Saurfang is quiet for a long moment.>$B$BRazgor says the quarry is soft. Holgoth says he has charges and no hands to carry them. He will have hands by nightfall.$B$BAnd the necromancers in the west wear the Cult''s robes. The Cult has one master. If he is here, his citadel is not far behind him. I will put scouts on the plains.$B$BSay nothing of this to Hellscream. He has enough to shout about.', '<Saurfang is quiet for a long moment.>$B$BRazgor says the quarry is soft. Holgoth says he has charges and no hands to carry them. He will have hands by nightfall.$B$BAnd the necromancers in the west wear the Cult''s robes. The Cult has one master. If he is here, his citadel is not far behind him. I will put scouts on the plains.$B$BSay nothing of this to Hellscream. He has enough to shout about.', 1),
(7702616, 'Settle down, soldier!$B$B<The captain looks up at the sky, then back at you.>$B$B...We''ll be ready for ''em. You did a good job in warning us.', 'Settle down, soldier!$B$B<The captain looks up at the sky, then back at you.>$B$B...We''ll be ready for ''em. You did a good job in warning us.', 1),
(7702617, 'A garrison? Here? Hellscream condemned this coast, an'' everybody on it! <Gorge chews on that.>$B$B...But Saurfang ain''t Hellscream. An'' I ain''t watchin'' them mist-men drag off my tanks while I''m out stompin'' Scourge.$B$BI''ll send a runner. And if anybody asks, it was my idea.', 'A garrison? Here? Hellscream condemned this coast, an'' everybody on it! <Gorge chews on that.>$B$B...But Saurfang ain''t Hellscream. An'' I ain''t watchin'' them mist-men drag off my tanks while I''m out stompin'' Scourge.$B$BI''ll send a runner. And if anybody asks, it was my idea.', 1),
(7702618, '<Commander Lynore Windstryke studies the sky over the square.>$B$BOur own dead, raining stormhammers on us. I''ve had the same nightmare every night since the citadel came down.$B$BYou''ve a soldier''s eye, $c. Fine. The archers come down to the walls.', '<Commander Lynore Windstryke studies the sky over the square.>$B$BOur own dead, raining stormhammers on us. I''ve had the same nightmare every night since the citadel came down.$B$BYou''ve a soldier''s eye, $c. Fine. The archers come down to the walls.', 1),
(7702619, 'Close the mine road? On whose authority? <Highlord Leoric Von Zeldig snorts.>$B$B...Mine, I suppose. Very well. If you''re wrong, I''ve lost a road. If you''re right, I''ve kept a keep.', 'Close the mine road? On whose authority? <Highlord Leoric Von Zeldig snorts.>$B$B...Mine, I suppose. Very well. If you''re wrong, I''ve lost a road. If you''re right, I''ve kept a keep.', 1),
(7702620, 'My roost? <Urik runs a hand over the cracked beam.>$B$BI''d thought it would last the week. Gryphons know when a perch is bad, though, and they''ve been restless.$B$BAll right. We move them tonight. If it''s still standing in the morning, you''re carrying the feed sacks.', 'My roost? <Urik runs a hand over the cracked beam.>$B$BI''d thought it would last the week. Gryphons know when a perch is bad, though, and they''ve been restless.$B$BAll right. We move them tonight. If it''s still standing in the morning, you''re carrying the feed sacks.', 1),
(7702621, '<Messenger Torvus is quiet for a moment.>$B$BYou speak as one who has buried a friend''s son. I will carry it, word for word.', '<Messenger Torvus is quiet for a moment.>$B$BYou speak as one who has buried a friend''s son. I will carry it, word for word.', 1),
(7702622, 'Because the apothecaries say they''re ''only for special missions''! <Deathguard Molder spits.>$B$BSpecial missions. I''ve a Scourge siege yard full of ballistae and they won''t lend me a single wagon. Whatever they''re saving them for, it had better be worth it.', 'Because the apothecaries say they''re ''only for special missions''! <Deathguard Molder spits.>$B$BSpecial missions. I''ve a Scourge siege yard full of ballistae and they won''t lend me a single wagon. Whatever they''re saving them for, it had better be worth it.', 1),
(7702623, 'Agmar''s Hammer? Oh, only the specimen. <Junior Apothecary Lawrence shuffles his papers.> The rest is consigned to the Grand Apothecary''s special detachment. I''m not to say where. I''m not even sure I''m supposed to have said that.$B$BYou won''t tell anyone I said that?', 'Agmar''s Hammer? Oh, only the specimen. <Junior Apothecary Lawrence shuffles his papers.> The rest is consigned to the Grand Apothecary''s special detachment. I''m not to say where. I''m not even sure I''m supposed to have said that.$B$BYou won''t tell anyone I said that?', 1),
(7702624, 'Another name? <William Allerton laughs, then stops.> You''re serious.$B$BThe ledger will say I died in this mine, won''t it? ...Fine. Leryssa knows the truth, and Thassarian''s alive. That''s enough for me.$B$BI always fancied myself a farmer. Somewhere warm, where the ground doesn''t open up under you.', 'Another name? <William Allerton laughs, then stops.> You''re serious.$B$BThe ledger will say I died in this mine, won''t it? ...Fine. Leryssa knows the truth, and Thassarian''s alive. That''s enough for me.$B$BI always fancied myself a farmer. Somewhere warm, where the ground doesn''t open up under you.', 1),
(7702625, 'Lost at the landing. <Corporal Venn reads the orders twice, then a third time.>$B$BWell. We were, weren''t we. For a while.$B$B''S'' for suicide, they called it. Maybe now it''s ''S'' for ''sent home''. I''ll take it, and I won''t ask whose seal that is.', 'Lost at the landing. <Corporal Venn reads the orders twice, then a third time.>$B$BWell. We were, weren''t we. For a while.$B$B''S'' for suicide, they called it. Maybe now it''s ''S'' for ''sent home''. I''ll take it, and I won''t ask whose seal that is.', 1),
(7702626, 'Orgrimmar? Martial law, they''re sayin''... <Gorge spits into the sand.>$B$BCondemned again, then. Figures. Every time I get this beach lookin'' like somethin'', somebody takes it away.$B$BFine! Take ''em! But the six tanks stay with me. Hellscream can come get those himself.', 'Orgrimmar? Martial law, they''re sayin''... <Gorge spits into the sand.>$B$BCondemned again, then. Figures. Every time I get this beach lookin'' like somethin'', somebody takes it away.$B$BFine! Take ''em! But the six tanks stay with me. Hellscream can come get those himself.', 1),
(7702627, '<Moratari pours two cups from a pot that was not there a moment ago.>$B$BCareful. It is hot. It has been hot for about four hundred years; I keep it in a pocket of an afternoon in Tanaris.$B$BWell? Ask me something.', '<Moratari pours two cups from a pot that was not there a moment ago.>$B$BCareful. It is hot. It has been hot for about four hundred years; I keep it in a pocket of an afternoon in Tanaris.$B$BWell? Ask me something.', 1),
(7702628, 'Old enough to remember when the Dark Portal was only a rumor, and young enough to still be embarrassed about it. Next question.', 'Old enough to remember when the Dark Portal was only a rumor, and young enough to still be embarrassed about it. Next question.', 1),
(7702629, 'Sand. My kind sleeps on sand. It is softer than gold, it gets everywhere, and I have not been comfortable since the Second War. Next question.', 'Sand. My kind sleeps on sand. It is softer than gold, it gets everywhere, and I have not been comfortable since the Second War. Next question.', 1),
(7702630, 'You have sixty years, if you are careful, and you are not careful. I am looking because I would like to remember this one.', 'You have sixty years, if you are careful, and you are not careful. I am looking because I would like to remember this one.', 1),
(7702631, 'You have tea in your beard. No, leave it. It suits you.', 'You have tea in your beard. No, leave it. It suits you.', 1),
(7702632, 'Ten thousand years, and you still blush. I did not know your people could.', 'Ten thousand years, and you still blush. I did not know your people could.', 1),
(7702633, 'I am trying to decide whether to kneel down or to pick you up. Both seem rude.', 'I am trying to decide whether to kneel down or to pick you up. Both seem rude.', 1),
(7702634, 'Your tail has been tapping the sand since I sat down. Is that a good sign? Tell me it is a good sign.', 'Your tail has been tapping the sand since I sat down. Is that a good sign? Tell me it is a good sign.', 1),
(7702635, 'Your tusks. I was wondering how anyone... Never mind. It was purely academic. Drink your tea.', 'Your tusks. I was wondering how anyone... Never mind. It was purely academic. Drink your tea.', 1),
(7702636, 'Your hands are cold. Here, hold the cup. No, hold it longer. There.', 'Your hands are cold. Here, hold the cup. No, hold it longer. There.', 1),
(7702637, 'You are holding that tiny cup as if it were a baby bird. I find that I like that very much.', 'You are holding that tiny cup as if it were a baby bird. I find that I like that very much.', 1),
(7702638, 'You have been grinning since I poured. They say trolls grin at everything. I hope they are wrong, and it is only me.', 'You have been grinning since I poured. They say trolls grin at everything. I hope they are wrong, and it is only me.', 1),
(7702639, 'You are lovely and you know it, and it is very annoying. Stop it. ...No. Do not stop it.', 'You are lovely and you know it, and it is very annoying. Stop it. ...No. Do not stop it.', 1),
(7702640, 'I... do not know. Nobody has ever asked.$B$B<Moratari looks at you over the rim of her cup for a long moment.>$B$BI think I would like to find out. Perhaps you could help.', 'I... do not know. Nobody has ever asked.$B$B<Moratari looks at you over the rim of her cup for a long moment.>$B$BI think I would like to find out. Perhaps you could help.', 1),
(7702641, '<Moratari''s hand is warm, and shaking.>$B$BEvery one of them was real. The young man on the barricade. The boys of Unit S. A garrison on a condemned coast. They were real, and they were ours, and now I have to help put the record back.$B$BTell me I am not a monster, $N.', '<Moratari''s hand is warm, and shaking.>$B$BEvery one of them was real. The young man on the barricade. The boys of Unit S. A garrison on a condemned coast. They were real, and they were ours, and now I have to help put the record back.$B$BTell me I am not a monster, $N.', 1),
(7702642, 'More time. <She laughs, wetly.> That is a very dragon thing to say. Where did you learn to talk like a dragon?', 'More time. <She laughs, wetly.> That is a very dragon thing to say. Where did you learn to talk like a dragon?', 1),
(7702643, '<For a long moment, Moratari says nothing at all.>$B$BI have watched ten thousand years of people say that to each other. I never once thought someone would say it to me.$B$B...I love you too. I think I have since the tea. Do not tell anyone. They will want to know what it does to the timeline, and I do not care.', '<For a long moment, Moratari says nothing at all.>$B$BI have watched ten thousand years of people say that to each other. I never once thought someone would say it to me.$B$B...I love you too. I think I have since the tea. Do not tell anyone. They will want to know what it does to the timeline, and I do not care.', 1),
(7702644, 'Flattery. Humans are so good at it. You have so little time, you have to be.', 'Flattery. Humans are so good at it. You have so little time, you have to be.', 1),
(7702645, 'And you look like a dwarf who has just been told so. Your ears have gone red. It is adorable.', 'And you look like a dwarf who has just been told so. Your ears have gone red. It is adorable.', 1),
(7702646, 'Ten thousand years to practice that line, and it still works. How annoying.', 'Ten thousand years to practice that line, and it still works. How annoying.', 1),
(7702647, 'Say it again, louder. I am all the way up here.', 'Say it again, louder. I am all the way up here.', 1),
(7702648, 'Your tail is doing the thing again. I have decided it is my favorite thing.', 'Your tail is doing the thing again. I have decided it is my favorite thing.', 1),
(7702649, 'An orc paying compliments. Somewhere, Garrosh just felt a chill and does not know why.', 'An orc paying compliments. Somewhere, Garrosh just felt a chill and does not know why.', 1),
(7702650, 'You say that without even breathing. It is a very efficient compliment. I accept it.', 'You say that without even breathing. It is a very efficient compliment. I accept it.', 1),
(7702651, 'You are a very large and very gentle person, and you say very large and gentle things. Stop it. Do not stop it.', 'You are a very large and very gentle person, and you say very large and gentle things. Stop it. Do not stop it.', 1),
(7702652, 'Dat''s what ya say to all de dragons, mon? ...I am sorry. I have wanted to try the accent for weeks.', 'Dat''s what ya say to all de dragons, mon? ...I am sorry. I have wanted to try the accent for weeks.', 1),
(7702653, 'Of course I do. So do you. We are a very good-looking pair, and everyone in the Bazaar is annoyed about it.', 'Of course I do. So do you. We are a very good-looking pair, and everyone in the Bazaar is annoyed about it.', 1);

CREATE TABLE IF NOT EXISTS `remix_original_story_npcflag` (
  `entry` INT UNSIGNED NOT NULL,
  `npcflag` INT UNSIGNED NOT NULL,
  PRIMARY KEY (`entry`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
UPDATE `creature_template` c JOIN `remix_original_story_npcflag` o ON o.`entry` = c.`entry` SET c.`npcflag` = o.`npcflag`;
DELETE FROM `remix_original_story_npcflag`;
INSERT INTO `remix_original_story_npcflag` (`entry`, `npcflag`) SELECT `entry`, `npcflag` FROM `creature_template` WHERE `entry` IN (25237, 25250, 25251, 25256, 25301, 25302, 25329, 25395, 25446, 25816, 25826, 26187, 26649, 27155, 27156, 27317, 27320, 27846) AND (`npcflag` & 3) = 0;
UPDATE `creature_template` c JOIN `remix_original_story_npcflag` o ON o.`entry` = c.`entry` SET c.`npcflag` = c.`npcflag` | 1;

INSERT INTO `remix_outing` (`id`, `map`, `x`, `y`, `z`, `o`, `scene`, `private`, `undress`, `back`, `flight_from`, `flight_to`, `mount`, `name`) VALUES
(1, 571, 5851.00, 640.60, 647.60, 1.050, 901, 0, 0, 1, 0, 0, 0, 'Cider at A Hero''s Welcome, Dalaran'),
(2, 571, 5886.90, 513.70, 641.70, 1.870, 902, 0, 0, 1, 0, 0, 0, 'Cider at The Filthy Animal, Dalaran'),
(3, 571, 5806.00, 444.00, 658.80, 4.450, 903, 0, 0, 1, 0, 0, 0, 'Dinner at Krasus'' Landing, Dalaran'),
(4, 571, 3528.00, 268.00, 342.80, 2.400, 904, 0, 0, 1, 0, 0, 0, 'Atop Wyrmrest Temple, at night'),
(5, 571, 5816.00, 447.00, 658.80, 4.450, 905, 0, 0, 1, 310, 252, 25833, 'On her back, from Dalaran to Wyrmrest Temple'),
(6, 571, 5712.00, 1068.00, 174.60, 4.712, 906, 1, 1, 1, 0, 0, 0, 'Moratari''s tent, the Bazaar');
INSERT INTO `remix_outing_line` (`outing`, `at_ms`, `text`) VALUES
(5, 4000, 'Lean with me. Not that way. ...There.'),
(5, 14000, 'The Violet Citadel, behind us. Do not wave. The Kirin Tor take anything that waves for a spell.'),
(5, 28000, 'Crystalsong. The trees ring when the wind is right. Listen.'),
(5, 44000, 'You are holding on very tightly. I do not mind.'),
(5, 62000, 'Wyrmrest. My kind built it before yours had names. I wanted you to see it the way we do: from above, and arriving.'),
(5, 80000, 'Thank you for trusting me with this. Dragons do not say that often. I am saying it.');

INSERT INTO `remix_look` (`display`, `from_display`, `extra`, `from_extra`, `bake`, `item1`, `item2`, `item3`, `item4`, `item5`, `item6`, `item7`, `item8`, `item9`, `item10`, `item11`) VALUES
(32755, 25458, 21382, 17276, 0, 0, 0, 0, 34726, 0, 0, 0, 0, 0, 0, 0),
(32756, 25458, 21383, 17276, 0, 0, 0, 12327, 0, 0, 0, 0, 0, 0, 0, 0);
DELETE FROM `creaturedisplayinfo_dbc` WHERE `ID` IN (32755, 32756);
DELETE FROM `creaturedisplayinfoextra_dbc` WHERE `ID` IN (21382, 21383);
DELETE FROM `creature_model_info` WHERE `DisplayID` IN (32755, 32756);
INSERT INTO `creaturedisplayinfo_dbc` (`ID`, `ModelID`, `SoundID`, `ExtendedDisplayInfoID`, `CreatureModelScale`, `CreatureModelAlpha`, `TextureVariation_1`, `TextureVariation_2`, `TextureVariation_3`, `PortraitTextureName`, `BloodLevel`, `BloodID`, `NPCSoundID`, `ParticleColorID`, `CreatureGeosetData`, `ObjectEffectPackageID`) VALUES (32755, 2209, 0, 21382, 1.25, 255, '', '', '', '', 1, 0, 292, 0, 0, 0);
INSERT INTO `creaturedisplayinfoextra_dbc` (`ID`, `DisplayRaceID`, `DisplaySexID`, `SkinID`, `FaceID`, `HairStyleID`, `HairColorID`, `FacialHairID`, `NPCItemDisplay1`, `NPCItemDisplay2`, `NPCItemDisplay3`, `NPCItemDisplay4`, `NPCItemDisplay5`, `NPCItemDisplay6`, `NPCItemDisplay7`, `NPCItemDisplay8`, `NPCItemDisplay9`, `NPCItemDisplay10`, `NPCItemDisplay11`, `Flags`, `BakeName`) VALUES (21382, 10, 1, 15, 2, 11, 4, 2, 0, 0, 0, 34726, 0, 0, 0, 0, 0, 0, 0, 0, '');
INSERT INTO `creature_model_info` (`DisplayID`, `BoundingRadius`, `CombatReach`, `Gender`, `DisplayID_Other_Gender`, `VerifiedBuild`) SELECT 32755, `BoundingRadius`, `CombatReach`, `Gender`, 0, 0 FROM `creature_model_info` WHERE `DisplayID` = 25458;
INSERT INTO `creaturedisplayinfo_dbc` (`ID`, `ModelID`, `SoundID`, `ExtendedDisplayInfoID`, `CreatureModelScale`, `CreatureModelAlpha`, `TextureVariation_1`, `TextureVariation_2`, `TextureVariation_3`, `PortraitTextureName`, `BloodLevel`, `BloodID`, `NPCSoundID`, `ParticleColorID`, `CreatureGeosetData`, `ObjectEffectPackageID`) VALUES (32756, 2209, 0, 21383, 1.25, 255, '', '', '', '', 1, 0, 292, 0, 0, 0);
INSERT INTO `creaturedisplayinfoextra_dbc` (`ID`, `DisplayRaceID`, `DisplaySexID`, `SkinID`, `FaceID`, `HairStyleID`, `HairColorID`, `FacialHairID`, `NPCItemDisplay1`, `NPCItemDisplay2`, `NPCItemDisplay3`, `NPCItemDisplay4`, `NPCItemDisplay5`, `NPCItemDisplay6`, `NPCItemDisplay7`, `NPCItemDisplay8`, `NPCItemDisplay9`, `NPCItemDisplay10`, `NPCItemDisplay11`, `Flags`, `BakeName`) VALUES (21383, 10, 1, 15, 2, 11, 4, 2, 0, 0, 12327, 0, 0, 0, 0, 0, 0, 0, 0, 0, '');
INSERT INTO `creature_model_info` (`DisplayID`, `BoundingRadius`, `CombatReach`, `Gender`, `DisplayID_Other_Gender`, `VerifiedBuild`) SELECT 32756, `BoundingRadius`, `CombatReach`, `Gender`, 0, 0 FROM `creature_model_info` WHERE `DisplayID` = 25458;

-- The creatures this file owns (770500 to 770999): credits never spawned, and copies of the game's.
DELETE FROM `creature_template` WHERE `entry` BETWEEN 770500 AND 770999;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 770500 AND 770999;
DELETE FROM `creature_template_addon` WHERE `entry` BETWEEN 770500 AND 770999;
DELETE FROM `creature_equip_template` WHERE `CreatureID` BETWEEN 770500 AND 770999;
DELETE FROM `creature_queststarter` WHERE `id` BETWEEN 770500 AND 770999;
DELETE FROM `creature_questender` WHERE `id` BETWEEN 770500 AND 770999;
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`, `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES
(770530, 'General Arlos warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770531, 'Sergeant Hammerhill warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770532, 'The fourth sinkhole collapsed', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770533, 'Old Man Colburn asked about Unit S', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770534, 'Private Brau asked who signed the order', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770535, 'Private Harker freed', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770536, 'Private Dunn freed', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770537, 'Corporal Mabry freed', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770538, 'Private Sorrel freed', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770539, 'Leryssa warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770540, 'Vindicator Yaala asked to watch over Leryssa', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770560, 'Garrosh Hellscream warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770561, 'High Overlord Saurfang warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770562, 'The Warsong Captain warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770563, 'Gorge asked to hold the coast', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770580, 'Commander Lynore Windstryke warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770581, 'Highlord Leoric Von Zeldig warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770582, 'Gryphon Commander Urik warned', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770583, 'A word sent with Messenger Torvus', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770584, 'Deathguard Molder asked about the blight spreaders', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770585, 'Junior Apothecary Lawrence asked about the shipments', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770600, 'William Allerton persuaded to sail home', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770601, 'Corporal Venn given the orders', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770602, 'Gorge told of the recall', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770700, 'Tea shared with Moratari', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770701, 'A drink with Moratari', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770702, 'Dinner at Krasus'' Landing', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770703, 'The view from Wyrmrest', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770704, 'Moratari comforted', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770705, 'A flight on Moratari''s back', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770706, 'An afternoon by the river', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770707, 'A night in Moratari''s tent', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, ''),
(770708, 'Moratari''s promise', NULL, 80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES
(770530, 0, 19373, 1, 1, 0),
(770531, 0, 19373, 1, 1, 0),
(770532, 0, 19373, 1, 1, 0),
(770533, 0, 19373, 1, 1, 0),
(770534, 0, 19373, 1, 1, 0),
(770535, 0, 19373, 1, 1, 0),
(770536, 0, 19373, 1, 1, 0),
(770537, 0, 19373, 1, 1, 0),
(770538, 0, 19373, 1, 1, 0),
(770539, 0, 19373, 1, 1, 0),
(770540, 0, 19373, 1, 1, 0),
(770560, 0, 19373, 1, 1, 0),
(770561, 0, 19373, 1, 1, 0),
(770562, 0, 19373, 1, 1, 0),
(770563, 0, 19373, 1, 1, 0),
(770580, 0, 19373, 1, 1, 0),
(770581, 0, 19373, 1, 1, 0),
(770582, 0, 19373, 1, 1, 0),
(770583, 0, 19373, 1, 1, 0),
(770584, 0, 19373, 1, 1, 0),
(770585, 0, 19373, 1, 1, 0),
(770600, 0, 19373, 1, 1, 0),
(770601, 0, 19373, 1, 1, 0),
(770602, 0, 19373, 1, 1, 0),
(770700, 0, 19373, 1, 1, 0),
(770701, 0, 19373, 1, 1, 0),
(770702, 0, 19373, 1, 1, 0),
(770703, 0, 19373, 1, 1, 0),
(770704, 0, 19373, 1, 1, 0),
(770705, 0, 19373, 1, 1, 0),
(770706, 0, 19373, 1, 1, 0),
(770707, 0, 19373, 1, 1, 0),
(770708, 0, 19373, 1, 1, 0);

-- William Allerton, alive (WRM-043)
CREATE TEMPORARY TABLE `remix_story_770550` SELECT * FROM `creature_template` WHERE `entry` = 25385;
UPDATE `remix_story_770550` SET `entry` = 770550, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'William Allerton', `npcflag` = 3, `unit_flags` = 768, `dynamicflags` = 0, `flags_extra` = 2;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770550`;
DROP TEMPORARY TABLE `remix_story_770550`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770550, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25385;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770550, 0, 0, 0, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770550, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25385;
-- The cult's assassin sent for Allerton, in a Valiance tabard (WRM-043)
CREATE TEMPORARY TABLE `remix_story_770551` SELECT * FROM `creature_template` WHERE `entry` = 25605;
UPDATE `remix_story_770551` SET `entry` = 770551, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Cult Assassin', `subname` = 'Cult of the Damned';
INSERT INTO `creature_template` SELECT * FROM `remix_story_770551`;
DROP TEMPORARY TABLE `remix_story_770551`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770551, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25605;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770551, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25605;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770551, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25605;
-- Unit S's captives at En'kilah (WRM-045)
CREATE TEMPORARY TABLE `remix_story_770552` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770552` SET `entry` = 770552, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Private Harker', `subname` = 'Unit S', `npcflag` = 1, `faction` = 35, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770552`;
DROP TEMPORARY TABLE `remix_story_770552`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770552, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770552, 0, 0, 8, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770552, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- Private Dunn
CREATE TEMPORARY TABLE `remix_story_770553` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770553` SET `entry` = 770553, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Private Dunn', `subname` = 'Unit S', `npcflag` = 1, `faction` = 35, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770553`;
DROP TEMPORARY TABLE `remix_story_770553`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770553, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770553, 0, 0, 8, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770553, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- Corporal Mabry
CREATE TEMPORARY TABLE `remix_story_770554` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770554` SET `entry` = 770554, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Corporal Mabry', `subname` = 'Unit S', `npcflag` = 1, `faction` = 35, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770554`;
DROP TEMPORARY TABLE `remix_story_770554`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770554, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770554, 0, 0, 8, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770554, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- Private Sorrel
CREATE TEMPORARY TABLE `remix_story_770555` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770555` SET `entry` = 770555, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Private Sorrel', `subname` = 'Unit S', `npcflag` = 1, `faction` = 35, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770555`;
DROP TEMPORARY TABLE `remix_story_770555`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770555, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770555, 0, 0, 8, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770555, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- The freed men of Unit S, at Death's Stand
CREATE TEMPORARY TABLE `remix_story_770556` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770556` SET `entry` = 770556, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Unit S Survivor', `subname` = NULL, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770556`;
DROP TEMPORARY TABLE `remix_story_770556`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770556, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770556, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25313;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770556, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- The men Hammerhill sent east, because the Timerunner warned him
CREATE TEMPORARY TABLE `remix_story_770557` SELECT * FROM `creature_template` WHERE `entry` = 25313;
UPDATE `remix_story_770557` SET `entry` = 770557, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Valiance Volunteer', `subname` = 'Sergeant Hammerhill''s', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770557`;
DROP TEMPORARY TABLE `remix_story_770557`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770557, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25313;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770557, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25313;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770557, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25313;
-- Vurenn's regiments, accepted at last (WRM-047)
CREATE TEMPORARY TABLE `remix_story_770558` SELECT * FROM `creature_template` WHERE `entry` = 25300;
UPDATE `remix_story_770558` SET `entry` = 770558, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Draenei Vanguard', `subname` = 'Harbinger Vurenn''s', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770558`;
DROP TEMPORARY TABLE `remix_story_770558`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770558, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25300;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770558, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25300;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770558, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25300;
-- Grunts on the collapsed sinkholes of Mightstone Quarry (the hands Saurfang sent)
CREATE TEMPORARY TABLE `remix_story_770570` SELECT * FROM `creature_template` WHERE `entry` = 25242;
UPDATE `remix_story_770570` SET `entry` = 770570, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Warsong Sapper', `subname` = NULL, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770570`;
DROP TEMPORARY TABLE `remix_story_770570`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770570, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25242;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770570, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25242;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770570, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25242;
-- The garrison on the condemned coast (WRM-054)
CREATE TEMPORARY TABLE `remix_story_770571` SELECT * FROM `creature_template` WHERE `entry` = 25242;
UPDATE `remix_story_770571` SET `entry` = 770571, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Warsong Coast Guard', `subname` = NULL, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770571`;
DROP TEMPORARY TABLE `remix_story_770571`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770571, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 25242;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770571, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 25242;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770571, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 25242;
-- Two more tanks, patched from Mobu's spare plates
CREATE TEMPORARY TABLE `remix_story_770572` SELECT * FROM `creature_template` WHERE `entry` = 27103;
UPDATE `remix_story_770572` SET `entry` = 770572, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Horde Siege Tank', `subname` = NULL, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770572`;
DROP TEMPORARY TABLE `remix_story_770572`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770572, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27103;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770572, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 27103;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770572, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27103;
-- The Plains of Nasam's wounded, brought home
CREATE TEMPORARY TABLE `remix_story_770573` SELECT * FROM `creature_template` WHERE `entry` = 27106;
UPDATE `remix_story_770573` SET `entry` = 770573, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Recovering Warsong Warrior', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770573`;
DROP TEMPORARY TABLE `remix_story_770573`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770573, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27106;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770573, 0, 0, 1, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770573, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27106;
-- The Plains of Nasam's wounded, brought home
CREATE TEMPORARY TABLE `remix_story_770574` SELECT * FROM `creature_template` WHERE `entry` = 27107;
UPDATE `remix_story_770574` SET `entry` = 770574, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Recovering Warsong Mage', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770574`;
DROP TEMPORARY TABLE `remix_story_770574`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770574, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27107;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES (770574, 0, 0, 1, 1, 0, 0, '');
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770574, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27107;
-- The tanks rolling out together in The Plains of Nasam's scene
CREATE TEMPORARY TABLE `remix_story_770575` SELECT * FROM `creature_template` WHERE `entry` = 27103;
UPDATE `remix_story_770575` SET `entry` = 770575, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Horde Siege Tank', `subname` = NULL, `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770575`;
DROP TEMPORARY TABLE `remix_story_770575`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770575, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27103;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770575, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 27103;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770575, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27103;
-- The company Wyrmbane sends to Bolvar at Fordragon Hold, until he recalls it
CREATE TEMPORARY TABLE `remix_story_770590` SELECT * FROM `creature_template` WHERE `entry` = 27162;
UPDATE `remix_story_770590` SET `entry` = 770590, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = '7th Legion Sentinel', `subname` = 'Wintergarde''s company', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770590`;
DROP TEMPORARY TABLE `remix_story_770590`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770590, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27162;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770590, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 27162;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770590, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27162;
-- One of the Infinite, watching the Timerunner atop Wyrmrest (a scene's)
CREATE TEMPORARY TABLE `remix_story_770591` SELECT * FROM `creature_template` WHERE `entry` = 27898;
UPDATE `remix_story_770591` SET `entry` = 770591, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Infinite Chrono-Magus', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770591`;
DROP TEMPORARY TABLE `remix_story_770591`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770591, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 27898;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770591, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 27898;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770591, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 27898;
-- Moratari in her evening dress (the romance's scenes)
CREATE TEMPORARY TABLE `remix_story_770720` SELECT * FROM `creature_template` WHERE `entry` = 770013;
UPDATE `remix_story_770720` SET `entry` = 770720, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770720`;
DROP TEMPORARY TABLE `remix_story_770720`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES (770720, 0, 32755, 1, 1, 0);
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770720, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 770013;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770720, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 770013;
-- Moratari in her tent (the romance's scene)
CREATE TEMPORARY TABLE `remix_story_770721` SELECT * FROM `creature_template` WHERE `entry` = 770013;
UPDATE `remix_story_770721` SET `entry` = 770721, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770721`;
DROP TEMPORARY TABLE `remix_story_770721`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES (770721, 0, 32756, 1, 1, 0);
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770721, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 770013;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770721, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 770013;
-- Moratari in her true shape, atop Wyrmrest (a bronze drake: the bronze she was before the Infinite)
CREATE TEMPORARY TABLE `remix_story_770722` SELECT * FROM `creature_template` WHERE `entry` = 31696;
UPDATE `remix_story_770722` SET `entry` = 770722, `KillCredit1` = 0, `KillCredit2` = 0, `ScriptName` = '', `AIName` = '', `gossip_menu_id` = 0, `name` = 'Moratari', `subname` = 'Infinite Dragonflight', `unit_flags` = 770;
INSERT INTO `creature_template` SELECT * FROM `remix_story_770722`;
DROP TEMPORARY TABLE `remix_story_770722`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) SELECT 770722, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 31696;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) SELECT 770722, 0, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras` FROM `creature_template_addon` WHERE `entry` = 31696;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) SELECT 770722, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, 0 FROM `creature_equip_template` WHERE `CreatureID` = 31696;

DELETE FROM `creature` WHERE `guid` BETWEEN 7704330 AND 7704399;
DELETE FROM `creature_addon` WHERE `guid` BETWEEN 7704330 AND 7704399;
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(7704330, 770550, 571, 1, 1, 0, 2768.00, 5339.90, 30.03, 3.050, 120, 0, 0, 'Wrath Remix: William Allerton, alive, Farshire Mine'),
(7704331, 770551, 571, 1, 1, 1, 2765.90, 5340.20, 30.03, 6.200, 30, 0, 0, 'Wrath Remix: the cult''s assassin over Allerton'),
(7704332, 770552, 571, 1, 1, 1, 3845.60, 3726.40, 47.40, 3.600, 120, 0, 0, 'Wrath Remix: Private Harker, captive, En''kilah'),
(7704333, 770553, 571, 1, 1, 1, 3847.80, 3729.60, 47.50, 3.600, 120, 0, 0, 'Wrath Remix: Private Dunn, captive, En''kilah'),
(7704334, 770554, 571, 1, 1, 1, 3836.40, 3728.20, 47.30, 0.300, 120, 0, 0, 'Wrath Remix: Corporal Mabry, captive, En''kilah'),
(7704335, 770555, 571, 1, 1, 1, 3843.00, 3721.00, 47.20, 1.600, 120, 0, 0, 'Wrath Remix: Private Sorrel, captive, En''kilah'),
(7704336, 770556, 571, 1, 1, 1, 3110.20, 3838.40, 22.85, 5.750, 120, 0, 0, 'Wrath Remix: Unit S, Death''s Stand'),
(7704337, 770556, 571, 1, 1, 1, 3117.60, 3839.20, 22.80, 3.400, 120, 0, 0, 'Wrath Remix: Unit S, Death''s Stand'),
(7704338, 770556, 571, 1, 1, 1, 3109.40, 3843.60, 22.80, 5.200, 120, 0, 0, 'Wrath Remix: Unit S, Death''s Stand'),
(7704339, 770556, 571, 1, 1, 1, 3117.90, 3846.20, 22.75, 3.900, 120, 0, 0, 'Wrath Remix: Unit S, Death''s Stand'),
(7704340, 770557, 571, 1, 1, 1, 3104.50, 3830.50, 23.20, 5.600, 120, 0, 0, 'Wrath Remix: volunteers, Death''s Stand'),
(7704341, 770557, 571, 1, 1, 1, 3120.60, 3830.40, 22.90, 4.200, 120, 0, 0, 'Wrath Remix: volunteers, Death''s Stand'),
(7704342, 770557, 571, 1, 1, 1, 3290.50, 3679.50, 32.55, 0.400, 120, 0, 0, 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(7704343, 770557, 571, 1, 1, 1, 3289.60, 3686.40, 32.55, 5.900, 120, 0, 0, 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(7704344, 770557, 571, 1, 1, 1, 3304.00, 3678.60, 32.55, 2.600, 120, 0, 0, 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(7704345, 770558, 571, 1, 1, 0, 2119.80, 5303.20, 32.86, 3.200, 120, 0, 0, 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(7704346, 770558, 571, 1, 1, 0, 2120.60, 5306.00, 32.86, 3.200, 120, 0, 0, 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(7704347, 770558, 571, 1, 1, 0, 2119.90, 5308.60, 32.86, 3.200, 120, 0, 0, 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(7704360, 770570, 571, 1, 1, 1, 2660.50, 6175.00, 39.00, 3.900, 120, 0, 0, 'Wrath Remix: a sapper on the southern sinkhole, Mightstone Quarry'),
(7704361, 770570, 571, 1, 1, 1, 2719.50, 6024.50, 31.30, 3.900, 120, 0, 0, 'Wrath Remix: a sapper on the eastern sinkhole, Mightstone Quarry'),
(7704362, 770570, 571, 1, 1, 1, 2881.50, 6279.50, 60.90, 3.900, 120, 0, 0, 'Wrath Remix: a sapper on the western sinkhole, Mightstone Quarry'),
(7704363, 770570, 571, 1, 1, 1, 2965.50, 6191.00, 62.10, 3.900, 120, 0, 0, 'Wrath Remix: a sapper on the northern sinkhole, Mightstone Quarry'),
(7704364, 770572, 571, 1, 1, 0, 2795.00, 6747.00, 7.60, 5.800, 120, 0, 0, 'Wrath Remix: a spare tank, the Coast of Echoes'),
(7704365, 770572, 571, 1, 1, 0, 2782.50, 6757.00, 7.60, 5.800, 120, 0, 0, 'Wrath Remix: a spare tank, the Coast of Echoes'),
(7704366, 770571, 571, 1, 1, 1, 2826.00, 6705.00, 10.80, 2.700, 120, 0, 0, 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(7704367, 770571, 571, 1, 1, 1, 2829.00, 6716.00, 10.80, 2.700, 120, 0, 0, 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(7704368, 770571, 571, 1, 1, 1, 2832.50, 6727.50, 10.60, 2.400, 120, 0, 0, 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(7704369, 770573, 571, 1, 1, 1, 2812.00, 6740.00, 9.80, 4.700, 120, 0, 0, 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(7704370, 770574, 571, 1, 1, 1, 2815.20, 6737.20, 10.00, 4.200, 120, 0, 0, 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(7704371, 770573, 571, 1, 1, 1, 2809.20, 6736.20, 9.80, 5.200, 120, 0, 0, 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(7704384, 770001, 571, 1, 1, 0, 3225.20, -730.90, 168.10, 2.510, 120, 0, 0, 'Wrath Remix: Moratari beside Middleton, Venomspite'),
(7704380, 770590, 571, 1, 1, 1, 4698.00, 1445.50, 188.60, 2.800, 120, 0, 0, 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(7704381, 770590, 571, 1, 1, 1, 4698.50, 1450.00, 188.70, 2.800, 120, 0, 0, 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(7704382, 770590, 571, 1, 1, 1, 4699.00, 1454.50, 188.80, 2.800, 120, 0, 0, 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(7704383, 770590, 571, 1, 1, 1, 4694.50, 1449.00, 188.60, 2.800, 120, 0, 0, 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(7704385, 770001, 571, 1, 1, 0, 3106.50, 3839.50, 22.80, 0.470, 120, 0, 0, 'Wrath Remix: Moratari at Death''s Stand while Forged Orders is in hand');
-- Who is seen (type 30, a spawn's own conditions by its guid): this file's spawns, and the game's that
-- some Timerunners no longer see. Quest states: 1 not taken, 2 objectives done, 8 in progress, 64 turned in.
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 30 AND (`SourceId` BETWEEN 7704330 AND 7704399 OR `SourceId` IN (99763, 115989));
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(30, 0, 770550, 7704330, 0, 47, 0, 77043, 74, 0, 0, 0, 0, '', 'Wrath Remix: William Allerton, alive, Farshire Mine'),
(30, 0, 770550, 7704330, 0, 8, 0, 77048, 0, 0, 1, 0, 0, '', 'Wrath Remix: William Allerton, alive, Farshire Mine'),
(30, 0, 770551, 7704331, 0, 47, 0, 77043, 8, 0, 0, 0, 0, '', 'Wrath Remix: the cult''s assassin over Allerton'),
(30, 0, 770552, 7704332, 0, 47, 0, 77045, 8, 0, 0, 0, 0, '', 'Wrath Remix: Private Harker, captive, En''kilah'),
(30, 0, 770553, 7704333, 0, 47, 0, 77045, 8, 0, 0, 0, 0, '', 'Wrath Remix: Private Dunn, captive, En''kilah'),
(30, 0, 770554, 7704334, 0, 47, 0, 77045, 8, 0, 0, 0, 0, '', 'Wrath Remix: Corporal Mabry, captive, En''kilah'),
(30, 0, 770555, 7704335, 0, 47, 0, 77045, 8, 0, 0, 0, 0, '', 'Wrath Remix: Private Sorrel, captive, En''kilah'),
(30, 0, 770556, 7704336, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704336, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704337, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704337, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704338, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704338, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704339, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770556, 7704339, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: Unit S, Death''s Stand'),
(30, 0, 770557, 7704340, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: volunteers, Death''s Stand'),
(30, 0, 770557, 7704340, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: volunteers, Death''s Stand'),
(30, 0, 770557, 7704341, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: volunteers, Death''s Stand'),
(30, 0, 770557, 7704341, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: volunteers, Death''s Stand'),
(30, 0, 770557, 7704342, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770557, 7704342, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770557, 7704343, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770557, 7704343, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770557, 7704344, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770557, 7704344, 0, 8, 0, 77049, 0, 0, 1, 0, 0, '', 'Wrath Remix: volunteers holding the Wailing Ziggurat'),
(30, 0, 770558, 7704345, 0, 8, 0, 77047, 0, 0, 0, 0, 0, '', 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(30, 0, 770558, 7704346, 0, 8, 0, 77047, 0, 0, 0, 0, 0, '', 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(30, 0, 770558, 7704347, 0, 8, 0, 77047, 0, 0, 0, 0, 0, '', 'Wrath Remix: Vurenn''s vanguard, Valiance Keep''s barracks'),
(30, 0, 770570, 7704360, 0, 8, 0, 11608, 0, 0, 0, 0, 0, '', 'Wrath Remix: a sapper on the southern sinkhole, Mightstone Quarry'),
(30, 0, 770570, 7704361, 0, 8, 0, 11608, 0, 0, 0, 0, 0, '', 'Wrath Remix: a sapper on the eastern sinkhole, Mightstone Quarry'),
(30, 0, 770570, 7704362, 0, 8, 0, 11608, 0, 0, 0, 0, 0, '', 'Wrath Remix: a sapper on the western sinkhole, Mightstone Quarry'),
(30, 0, 770570, 7704363, 0, 8, 0, 11608, 0, 0, 0, 0, 0, '', 'Wrath Remix: a sapper on the northern sinkhole, Mightstone Quarry'),
(30, 0, 770572, 7704364, 0, 8, 0, 11651, 0, 0, 0, 0, 0, '', 'Wrath Remix: a spare tank, the Coast of Echoes'),
(30, 0, 770572, 7704364, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: a spare tank, the Coast of Echoes'),
(30, 0, 770572, 7704365, 0, 8, 0, 11651, 0, 0, 0, 0, 0, '', 'Wrath Remix: a spare tank, the Coast of Echoes'),
(30, 0, 770572, 7704365, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: a spare tank, the Coast of Echoes'),
(30, 0, 770571, 7704366, 0, 8, 0, 77054, 0, 0, 0, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770571, 7704366, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770571, 7704367, 0, 8, 0, 77054, 0, 0, 0, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770571, 7704367, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770571, 7704368, 0, 8, 0, 77054, 0, 0, 0, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770571, 7704368, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the coast''s garrison, the Coast of Echoes'),
(30, 0, 770573, 7704369, 0, 8, 0, 11652, 0, 0, 0, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770573, 7704369, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770574, 7704370, 0, 8, 0, 11652, 0, 0, 0, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770574, 7704370, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770573, 7704371, 0, 8, 0, 11652, 0, 0, 0, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770573, 7704371, 0, 8, 0, 77230, 0, 0, 1, 0, 0, '', 'Wrath Remix: the plains'' wounded, recovering by Elder Mootoo'),
(30, 0, 770001, 7704384, 0, 8, 0, 12218, 0, 0, 0, 0, 0, '', 'Wrath Remix: Moratari beside Middleton, Venomspite'),
(30, 0, 770001, 7704384, 0, 8, 0, 12221, 0, 0, 1, 0, 0, '', 'Wrath Remix: Moratari beside Middleton, Venomspite'),
(30, 0, 770590, 7704380, 0, 8, 0, 12311, 0, 0, 0, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704380, 0, 8, 0, 77201, 0, 0, 1, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704381, 0, 8, 0, 12311, 0, 0, 0, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704381, 0, 8, 0, 77201, 0, 0, 1, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704382, 0, 8, 0, 12311, 0, 0, 0, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704382, 0, 8, 0, 77201, 0, 0, 1, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704383, 0, 8, 0, 12311, 0, 0, 0, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770590, 7704383, 0, 8, 0, 77201, 0, 0, 1, 0, 0, '', 'Wrath Remix: Wintergarde''s company at Fordragon Hold'),
(30, 0, 770001, 7704385, 0, 47, 0, 77049, 10, 0, 0, 0, 0, '', 'Wrath Remix: Moratari at Death''s Stand while Forged Orders is in hand'),
(30, 0, 25385, 99763, 0, 47, 0, 77043, 1, 0, 0, 0, 0, '', 'Wrath Remix: Allerton''s corpse, unless he was saved'),
(30, 0, 25301, 115989, 0, 8, 0, 12019, 0, 0, 1, 0, 0, '', 'Wrath Remix: Counselor Talbot, gone after Last Rites');

DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 770100 AND 770199;
DELETE FROM `gameobject` WHERE `guid` BETWEEN 7705300 AND 7705399;
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `IconName`, `castBarCaption`, `unk1`, `size`, `AIName`, `ScriptName`, `VerifiedBuild`) VALUES
(770100, 5, 7194, 'Moratari''s Tent', '', '', '', 1.0, '', '', 0),
(770101, 5, 159, 'Rug', '', '', '', 1.0, '', '', 0),
(770102, 5, 5914, 'Cushions', '', '', '', 1.0, '', '', 0),
(770103, 5, 6644, 'Candle', '', '', '', 1.0, '', '', 0),
(770104, 5, 6038, 'Lantern', '', '', '', 1.0, '', '', 0),
(770105, 5, 194, 'Brazier', '', '', '', 1.0, '', '', 0);
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `ScriptName`, `VerifiedBuild`, `Comment`) VALUES
(7705300, 770100, 571, 0, 0, 1, 32769, 5712.00, 1068.00, 174.50, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: Moratari''s tent, the Bazaar'),
(7705301, 770101, 571, 0, 0, 1, 32769, 5712.00, 1067.50, 174.52, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: her tent''s rug'),
(7705302, 770102, 571, 0, 0, 1, 32769, 5710.80, 1065.40, 174.52, 5.0124, 0, 0, 0.593498, -0.804835, 300, 100, 1, '', 0, 'Wrath Remix: cushions in her tent'),
(7705303, 770102, 571, 0, 0, 1, 32769, 5712.90, 1065.20, 174.52, 4.3124, 0, 0, 0.833492, -0.552531, 300, 100, 1, '', 0, 'Wrath Remix: cushions in her tent'),
(7705304, 770102, 571, 0, 0, 1, 32769, 5714.30, 1066.40, 174.52, 5.9124, 0, 0, 0.184338, -0.982863, 300, 100, 1, '', 0, 'Wrath Remix: cushions in her tent'),
(7705305, 770103, 571, 0, 0, 1, 32769, 5709.40, 1064.60, 174.52, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: a candle in her tent'),
(7705306, 770103, 571, 0, 0, 1, 32769, 5714.40, 1064.40, 174.52, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: a candle in her tent'),
(7705307, 770103, 571, 0, 0, 1, 32769, 5714.80, 1069.80, 174.52, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: a candle in her tent'),
(7705308, 770105, 571, 0, 0, 1, 32769, 5709.20, 1068.60, 174.52, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: her tent''s brazier'),
(7705309, 770104, 571, 0, 0, 1, 32769, 5713.60, 1073.80, 174.50, 4.7124, 0, 0, 0.707107, -0.707107, 300, 100, 1, '', 0, 'Wrath Remix: the lamp by her tent''s door');

DELETE FROM `quest_template` WHERE `ID` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `quest_template_addon` WHERE `ID` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `quest_offer_reward` WHERE `ID` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `quest_request_items` WHERE `ID` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `creature_queststarter` WHERE `quest` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `creature_questender` WHERE `quest` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `remix_departure` WHERE `quest` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `remix_bridge` WHERE `quest` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
DELETE FROM `remix_quest_reward` WHERE `quest` IN (77041, 77042, 77043, 77044, 77045, 77046, 77047, 77050, 77051, 77054, 77056, 77223, 77236, 77234, 77235, 77048, 77049, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`, `AllowableRaces`, `StartItem`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText1`, `ObjectiveText2`, `ObjectiveText3`, `ObjectiveText4`, `RequiredItemId1`, `RequiredItemCount1`, `VerifiedBuild`) VALUES
(77041, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'A Word in the General''s Ear', 'Warn General Arlos and Sergeant Hammerhill of what is coming to Valiance Keep, without telling them how you know.', 'You remember this week, don''t you? The crawlers on the beach. The flyers over the inner wall. And on the third night, the ground opening under the western ridge.$B$BThis time, they can be ready. Tell the general in the barracks, and the sergeant at the barricade. Not how you know, $N: no one in this year may learn that. Say your scouts saw worm-trails on the ridge. Soldiers believe scouts.$B$BAnd mind the counselor at the general''s elbow. Tell him nothing you would not tell the Scourge.', 'Return to Moratari.', 770530, 1, 770531, 1, 0, 0, 0, 0, 'General Arlos warned', 'Sergeant Hammerhill warned', '', '', 0, 0, 0),
(77042, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'The Fourth Sinkhole', 'Slay 6 Cultist Necrolytes at the northern sinkhole, then go with Moratari to the dunes behind the beach line.', 'Hammerhill thinks there were three sinkholes. There were four. The fourth opens under the dunes behind the beach line on the third night, and what comes out of it reaches the docks before anyone sounds a horn.$B$BIt has not opened yet. The worms are still digging it, and the cult''s necrolytes are feeding them from the northern sinkhole, chanting the dead awake under the sand.$B$BKill the necrolytes. Then come with me to the dunes. I will show you what a bronze dragon can do with wooden supports and a little time.', 'Return to Moratari.', 25651, 6, 770532, 1, 0, 0, 0, 0, '', 'The fourth sinkhole collapsed', '', '', 0, 0, 0),
(77043, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'Not Yet Late', 'Reach William Allerton in the Farshire Mine before the cult''s assassin does.', 'Leryssa is about to ask you to find a family friend of hers, William Allerton, at the Farshire mine. Say yes. Then run.$B$BIn the record I have, you find him on the floor of that mine with his throat cut and no claw marks on him. A man in a Valiance tabard climbed the shaft an hour before you, with orders from the inner hold and a knife.$B$BWilliam knows where they sent Thassarian. That is why he dies. Today, I would rather he didn''t.', 'Speak with William Allerton.', 770551, 1, 0, 0, 0, 0, 0, 0, '', '', '', '', 0, 0, 0),
(77044, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'Named for No Town', 'Ask Old Man Colburn at the inn and Private Brau in the barracks jail about Unit S, then tell Leryssa what you have learned.', 'Thassarian enlisted the same day I did. The day after, they had him in charge of something called Unit S. All the other units are named after towns, $c. Goldshire. Darkshire. Not ''S''.$B$BI started asking questions. Then someone came up my mine shaft with a knife.$B$BOld Colburn at the inn knows every unit that ever sailed from Stormwind; ask him. And there''s a deserter in the barracks jail, Brau, who was in Unit S before he ran. Ask him who signed the order.$B$BThen tell Leryssa. She deserves to know where her brother went.', 'Return to Leryssa at the Valiance Keep inn.', 770533, 1, 770534, 1, 0, 0, 0, 0, 'Old Man Colburn asked about Unit S', 'Private Brau asked who signed the order', '', '', 0, 0, 0),
(77045, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'No One Left Behind', 'Free Private Harker, Private Dunn, Corporal Mabry and Private Sorrel of Unit S, held in the Temple City of En''kilah, then return to Corporal Venn at Death''s Stand.', 'Venn thinks the rest of Unit S died on the beach where they landed. Most of them did. Four did not.$B$BThe cult took them alive, to the necromancers'' yard in the Temple City of En''kilah, south of here. In the record, nobody ever goes to look, and in a week the cult has four new servants who used to be Stormwind boys.$B$BGo and get them, $N. Quietly: tell them you were sent. Soldiers always believe they were sent.', 'Return to Corporal Venn at Death''s Stand.', 770535, 1, 770536, 1, 770537, 1, 770538, 1, 'Private Harker freed', 'Private Dunn freed', 'Corporal Mabry freed', 'Private Sorrel freed', 0, 0, 0),
(77046, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'Before She Gets Hurt', 'Warn Leryssa, and ask Vindicator Yaala to watch over her, at Valiance Keep''s inn. Moratari will take you there and back.', '''We must finish this before she gets hurt.'' He is right to be afraid, $N. In the record, the counselor''s men come for Leryssa within the week, and she is dragged to the top of Naxxanar as an offering.$B$BI can fold the road to Valiance Keep and back. Warn her. Ask the draenei vindicator at the inn to watch over her. And then... we will see what the hour allows.', 'Return to Moratari.', 770539, 1, 770540, 1, 0, 0, 0, 0, 'Leryssa warned', 'Vindicator Yaala asked to watch over Leryssa', '', '', 0, 0, 0),
(77047, 2, -1, 10, 3537, 5, 0, 0, 1101, 0, 'The Keep Holds', 'Speak with General Arlos in Valiance Keep''s barracks.', 'Talbot is dead, and the hold he kept on the general''s mind died with him. For the first time since he landed, Arlos is himself.$B$BHe is about to make three decisions, $N. The first two are his own. The third is about you. Come; I will fold the road.', 'Speak with General Arlos.', 0, 0, 0, 0, 0, 0, 0, 0, '', '', '', '', 0, 0, 0),
(77050, 2, -1, 10, 3537, 5, 0, 0, 690, 0, 'Beneath the Floor', 'Warn Garrosh Hellscream, High Overlord Saurfang and the Warsong Captain on the south wall of what is coming to Warsong Hold, without telling them how you know.', 'You remember the first week here, don''t you? The nerubians came up through the floor of the keep while Garrosh was still shouting at the boats. And out of the sky, every hour, over the south wall.$B$BThis time the Horde can be ready. Tell Garrosh: he will scoff. Tell Saurfang: he will listen, and he will tell no one he did. Tell the captain on the wall to put his marksmen up.$B$BNot how you know, $N. No one in this year may learn that. You smelled it in the quarry. You heard it in the rock. Orcs believe in instincts.', 'Return to Moratari.', 770560, 1, 770561, 1, 770562, 1, 0, 0, 'Garrosh Hellscream warned', 'High Overlord Saurfang warned', 'The Warsong Captain warned', '', 0, 0, 0),
(77051, 2, -1, 10, 3537, 5, 0, 0, 690, 0, 'Hands for Holgoth', 'Speak with Quartermaster Holgoth in Warsong Hold.', 'Saurfang promised Holgoth hands by nightfall. You are the hands.$B$BThe quartermaster has munitions in the quarry and a plan for the nerubians'' four sinkholes, and no one to carry either. In the record I have, the sinkholes are still open when the nerubians come up through the floor. Let us close them first.', 'Speak with Quartermaster Holgoth.', 0, 0, 0, 0, 0, 0, 0, 0, '', '', '', '', 0, 0, 0),
(77054, 2, -1, 10, 3537, 5, 0, 0, 690, 0, 'The Condemned Coast', 'Slay 6 Kvaldir Mist Lords in the mist over Garrosh''s Landing, and ask Gorge to keep a garrison on the coast.', 'Hellscream condemned this coast the day the mist came, and left Gorge, a goblin and six tanks to hold it. In the record I have, the Kvaldir come back out of the mist the week after the tanks roll, and there is no one left on this beach to stop them carrying off what the plains spared.$B$BThin the Mist Lords on the landing. Then tell Gorge to ask Saurfang for a garrison. Hellscream condemned the coast. He never said no one could guard it.', 'Return to Moratari.', 25496, 6, 770563, 1, 0, 0, 0, 0, '', 'Gorge asked to hold the coast', '', '', 0, 0, 0),
(77056, 2, -1, 10, 3537, 5, 0, 0, 690, 0, 'Shipping Lanes', 'Speak with High Overlord Saurfang in Warsong Hold.', 'Kel''Thuzad. You saw him with your own eyes, over the plains, and Garrosh heard the name from you before you ever saw him. Now he wants to march on Icecrown tonight, before the dread citadel is finished rising.$B$BIn the record I have, Saurfang talks him out of it, barely, and they shout at each other over that floor map for the rest of the war. Today, I think the argument ends differently. Go and stand beside the old one, $N. He will want a witness.', 'Speak with High Overlord Saurfang.', 0, 0, 0, 0, 0, 0, 0, 0, '', '', '', '', 0, 0, 0),
(77223, 2, -1, 20, 65, 5, 0, 0, 1101, 0, 'The Second Sky', 'Warn Commander Lynore Windstryke, Highlord Leoric Von Zeldig and Gryphon Commander Urik of what Naxxramas will do next.', 'Naxxramas has already fallen on Wintergarde, and the lower village is lost. That much I cannot change, $N. It happened before either of us arrived.$B$BBut I know where it strikes next. The risen gryphon riders come over the square from above. The dead come up the mine road. And the roost that cracked on the first night comes down with the birds in it.$B$BThe commander, the highlord and the gryphon master. A veteran''s eye, a hunch, a rumor from the crypt: say whatever they will believe.', 'Return to Moratari.', 770580, 1, 770581, 1, 770582, 1, 0, 0, 'Commander Lynore Windstryke warned', 'Highlord Leoric Von Zeldig warned', 'Gryphon Commander Urik warned', '', 0, 0, 0),
(77236, 2, -1, 20, 65, 5, 0, 0, 690, 0, 'Eyes and Ears', 'Send a word to High Overlord Saurfang with Messenger Torvus at Agmar''s Hammer.', 'Messenger Torvus carries Saurfang''s letters, the ones Agmar is not meant to read. Saurfang has sent his son to command the Horde''s forces at the Wrathgate, and the son is a better soldier than he is a careful one.$B$BSend a word back with Torvus. Not what you know, $N. A soldier''s superstition: you have seen sons die proving themselves to their fathers. Let a father read that, and see what he does with it.', 'Speak with Messenger Torvus.', 770583, 1, 0, 0, 0, 0, 0, 0, 'A word sent with Messenger Torvus', '', '', '', 0, 0, 0),
(77234, 2, -1, 20, 65, 5, 0, 0, 690, 0, 'Before the Second Wave', 'Slay 8 Anub''ar Cultists at Icemist Village, then speak with Doctor Sintar Malefious at Agmar''s Hammer.', 'Icemist is already gone. The Anub''ar razed it before the Horde arrived, and nothing either of us can do will raise those walls again.$B$BBut the Anub''ar are still there, and their cultists are breathing life into the blightbeasts on the village''s ruins. In the record, those beasts fly to the Wrathgate, and the taunka who would have held its flank die under them.$B$BKill the cultists before the beasts are ready. Then come back to Agmar''s Hammer: the Grand Apothecary has a shipment I want you to see.', 'Speak with Doctor Sintar Malefious at Agmar''s Hammer.', 26319, 8, 0, 0, 0, 0, 0, 0, '', '', '', '', 0, 0, 0),
(77235, 2, -1, 20, 65, 5, 0, 0, 690, 0, 'A Careful Inventory', 'Ask Deathguard Molder and Junior Apothecary Lawrence at Venomspite about the blight''s shipments, then return to Moratari.', 'The field test was a success. Middleton says so, and Middleton is very proud. Before you carry the blight to Agmar''s Hammer, I want you to count something, $N.$B$BThe deathguard who wanted the blight spreaders for his siege and was told they were for ''special missions''. The junior apothecary who signs for the shipments. Ask them. Carefully. Then keep whatever you learn to yourself: nobody in this year can hear it from you, not Agmar, not the Warchief, not anyone.', 'Return to Moratari.', 770584, 1, 770585, 1, 0, 0, 0, 0, 'Deathguard Molder asked about the blight spreaders', 'Junior Apothecary Lawrence asked about the shipments', '', '', 0, 0, 0),
(77048, 2, -1, 20, 3537, 5, 0, 0, 1101, 0, 'Lost at Sea', 'Persuade William Allerton, in the Farshire Mine, to sail home under another name. Moratari will take you there and back.', 'I need your help to put something back, $N, and I need you to understand why before you hate me for asking.$B$BWilliam Allerton was supposed to die in the Farshire mine. He did not, because of us. Now he is alive in the Borean Tundra, writing letters to Stormwind about Unit S, about the counselor, about a hero with a strange way of knowing where to be. Every letter is a thread pulling the war somewhere it never went.$B$BHe cannot stay. Not here, and not under his own name. But he does not have to die. I will not ask that of you, and I would not do it myself. Talk to him.', 'Return to Moratari.', 770600, 1, 0, 0, 0, 0, 0, 0, 'William Allerton persuaded to sail home', '', '', '', 0, 0, 0),
(77049, 2, -1, 20, 3537, 5, 0, 0, 1101, 0, 'Forged Orders', 'Give Corporal Venn at Death''s Stand the orders that send Unit S''s survivors and Hammerhill''s volunteers home.', 'History lost Unit S on a beach. If four men walk into Wintergarde with the counselor''s name on their lips, the 7th Legion starts asking questions it never asked, and the cult''s list of traitors reaches the wrong hands far too soon.$B$BThey cannot die again; I will not allow it. But they can go home, quietly. I have written orders sending them to Stormwind''s harbor, with Hammerhill''s volunteers to see them aboard. Chromie lent me the seal. Do not ask.$B$BGive them to Corporal Venn. He will understand more than you think.', 'Return to Moratari.', 770601, 1, 0, 0, 0, 0, 0, 0, 'Corporal Venn given the orders', '', '', '', 0, 0, 0),
(77230, 2, -1, 20, 3537, 5, 0, 0, 690, 0, 'The Record Keeps Warsong', 'Tell Gorge, on the Coast of Echoes, that the Warchief is calling every soldier who can walk back to Orgrimmar. Moratari will take you there and back.', 'Orgrimmar is under martial law. The Warchief calls every soldier who can walk back to the city, and in the record the coast at Garrosh''s Landing is condemned again by the end of the week: no garrison, no spare tanks, no one left on the beach but Gorge, a goblin and the mist.$B$BIn our record, there is a garrison there, because of us. It cannot stay, $N. Not with the Horde at war with itself in its own capital. Go and tell Gorge. He will take it badly. He takes everything badly.', 'Return to Moratari.', 770602, 1, 0, 0, 0, 0, 0, 0, 'Gorge told of the recall', '', '', '', 0, 0, 0),
(77901, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'A Cup of Something Hot', 'Sit with Moratari, and share her tea.', 'You have walked beside me for days, $N, and you have not once asked me anything about myself.$B$BEveryone asks. Dragons, they say: how old are you, how big are you really, do you sleep on gold. You have asked me where the next sinkhole is.$B$BI find I would like to be asked something. Sit with me a while. I have tea. Every dragon who keeps time keeps tea; it is the one thing that tastes the same in every century.', 'Return to Moratari.', 770700, 1, 0, 0, 0, 0, 0, 0, 'Tea shared with Moratari', '', '', '', 0, 0, 0),
(77902, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'Shore Leave', 'Have a drink with Moratari at an inn in Dalaran.', 'Eternus says we are ahead of the record. Two days, perhaps three. He says it as if it were a problem.$B$BI say it is shore leave. There is an inn in Dalaran (in this year the city has only just arrived, and the floors are still settling) where nobody knows either of us, and the cider is better than it has any right to be.$B$BOne drink, $N. As friends. Mostly as friends.', 'Return to Moratari.', 770701, 1, 0, 0, 0, 0, 0, 0, 'A drink with Moratari', '', '', '', 0, 0, 0),
(77903, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'Dinner at Krasus'' Landing', 'Have dinner with Moratari at Krasus'' Landing in Dalaran.', 'I have a confession. I bought a dress.$B$BIn the Bazaar you can buy anything from any year, and I spent an hour choosing, and a dragon should not spend an hour choosing anything that is not a century.$B$BThere is a landing on the edge of Dalaran where the wind comes up from the forest and the whole of Northrend lies under you like a map no one has ruined yet. Have dinner with me there, $N. Properly. Not as friends.', 'Return to Moratari.', 770702, 1, 0, 0, 0, 0, 0, 0, 'Dinner at Krasus'' Landing', '', '', '', 0, 0, 0),
(77904, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'The View from Wyrmrest', 'Go with Moratari to the top of Wyrmrest Temple at night.', 'You stood on Wyrmrest today among the Accord''s dragons, and none of them looked at you twice. They looked at me. They always do: one of the Infinite, at their own temple. They look at me the way you would look at a wolf in a kennel.$B$BI want to show you something, $N, and I want to show it to you from the top, where the dragons do not go at night. Come with me.', 'Return to Moratari.', 770703, 1, 0, 0, 0, 0, 0, 0, 'The view from Wyrmrest', '', '', '', 0, 0, 0),
(77905, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'What Eternus Left Out', 'Stay with Moratari a while.', 'I need to sit down. Not here. Somewhere no one from any year can see me.$B$B...He knew, $N. Eternus knew the war had to happen as it did, and he sent us to change it anyway, and he let me believe every life we saved was a life kept. I do not know what I am anymore. A keeper of time who spent a month vandalizing it.$B$BStay with me a while. Please. You do not have to say anything.', 'Return to Moratari.', 770704, 1, 0, 0, 0, 0, 0, 0, 'Moratari comforted', '', '', '', 0, 0, 0),
(77906, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'On Bronze Wings', 'Fly with Moratari from Dalaran to Wyrmrest Temple, on her back.', 'You have flown on gryphons and wyverns and those dreadful bats, and you have never once flown properly.$B$BI am offering, $N. My back, the sky over Dalaran, and the long way down to Wyrmrest. I have never let anyone ride me. Dragons do not, as a rule; it is considered very forward.$B$BI am considering being very forward.', 'Return to Moratari.', 770705, 1, 0, 0, 0, 0, 0, 0, 'A flight on Moratari''s back', '', '', '', 0, 0, 0),
(77907, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'An Adventure of Our Own', 'Evict 3 Mangal Crocolisks from the riverbanks of Sholazar Basin, then sit with Moratari by the river.', 'For a month I have walked beside you into somebody else''s story. Today I want one of our own.$B$BThere is a river in Sholazar where the Titans left the water running and forgot to turn it off. There are also crocolisks, who believe the sunny banks are theirs. I have brought a blanket and a basket. Shall we discuss it with them?', 'Return to Moratari.', 28002, 3, 770706, 1, 0, 0, 0, 0, '', 'An afternoon by the river', '', '', 0, 0, 0),
(77908, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'The Lamp That Is Always Lit', 'Spend the night in Moratari''s tent at the Bazaar.', 'I have a tent at the Bazaar. You have walked past it a hundred times: the one with the lamp that is always lit.$B$BNo one goes in. Not Eternus, not the gnomes who sweep the sand. It is the only place in any year that is only mine.$B$BI would like it to be ours, tonight. Leave your armor at the door, $N. Nothing in there will need a sword.', 'Return to Moratari.', 770707, 1, 0, 0, 0, 0, 0, 0, 'A night in Moratari''s tent', '', '', '', 0, 0, 0),
(77909, 2, -1, 10, 0, 5, 0, 0, 0, 0, 'Whatever Comes After', 'Walk with Moratari to the edge of the camp, and hear her promise.', 'Icecrown. The end of the war is up that glacier, and after the end you go home, to your own year. I cannot follow, $N. One of my kind who walks out of her own time does not come back, and someone has to keep the river running for you to go home along.$B$BI am not sad. I am... a little sad. Walk with me to the edge of the camp. I want to make you a promise, and I want to make it where the wind can hear.', 'Return to Moratari.', 770708, 1, 0, 0, 0, 0, 0, 0, 'Moratari''s promise', '', '', '', 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `NextQuestID`, `ExclusiveGroup`) VALUES
(77041, 11672, 0, 0),
(77042, 11897, 0, 0),
(77043, 11927, 0, 0),
(77044, 77043, 0, 0),
(77045, 77006, 0, 0),
(77046, 12088, 0, 0),
(77047, 12019, 0, 0),
(77050, 0, 0, 0),
(77051, 11598, 0, 0),
(77054, 11651, 0, 0),
(77056, 11652, 0, 0),
(77223, 12237, 0, 0),
(77236, 12034, 0, 0),
(77234, 12036, 0, 0),
(77235, 12218, 0, 0),
(77048, 0, 0, 0),
(77049, 0, 0, 0),
(77230, 0, 0, 0),
(77901, 0, 0, 0),
(77902, 77901, 0, 0),
(77903, 77902, 0, 0),
(77904, 77903, 0, 0),
(77905, 77904, 0, 0),
(77906, 77905, 0, 0),
(77907, 77906, 0, 0),
(77908, 77907, 0, 0),
(77909, 77908, 0, 0);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES
(77041, 'The general did not believe you. The sergeant did. Remember that, $N: in every war, it is the sergeants who listen.$B$BNow the war knows something it did not know yesterday, and nobody can say who told it. That is how we work, you and I.', 0),
(77042, 'Three sinkholes in the reports. Four in the ground. Three in history, now. I like those numbers better.$B$BHammerhill will sleep well on the third night and never know why. That is the best kind of rescue.', 0),
(77043, 'You... you''re not one of them. Thank the Light.$B$BThat man came up the shaft in a Valiance tabard. Said he had orders for me from the inner hold. Then he drew a knife.$B$BI know why, too. I''ve seen the ledger. I know where they sent Thassarian.', 0),
(77044, 'William''s alive? Light be praised. And the counselor... <Leryssa sits down, hard.> He''s at the general''s side every hour of the day.$B$BA dozen men and a death knight, sent to die on purpose. And Thassarian is one of them.$B$BPlease, $N. Find my brother before they finish what they started.', 0),
(77045, 'Harker? Dunn? <Corporal Venn grabs the nearest man by the shoulders and shakes him, laughing.> We buried you! In our heads we buried you!$B$BAnd these lads with them say Sergeant Hammerhill sent them. Hammerhill! The one man in that keep who reads a map.$B$BThank you, $N. Now go find Thassarian. He went north this morning, alone, and I don''t like it.', 0),
(77046, 'She will not run, and Yaala will not sleep. Whether that is enough... we will know at the top of Naxxanar.$B$BBack to Thassarian. Hold on.', 0),
(77047, '<General Arlos looks older than he did a week ago. He also looks awake.>$B$BCome in, $N. There is a great deal I need to say, and most of it is an apology.', 0),
(77050, 'Garrosh laughed at you. Saurfang did not. The captain will have his marksmen up before the next attack, and he will think it was his own idea by tomorrow.$B$BThat is how we work, you and I: the Horde will be ready, and no one will know who told it.', 0),
(77051, 'The old one sent you? <Holgoth scratches his chin.> Well, I''ll not argue with a gift. I''ve got munitions scattered all over that quarry and a dozen holes that need filling.', 0),
(77054, 'A garrison on the condemned coast, a supply lane from the sea, and two more tanks than history ever had. Saurfang will want to know who suggested it. Gorge will say it was his idea. Let him.', 0),
(77056, '<High Overlord Saurfang watches Hellscream at the war map.>$B$BStand here, $N. You will want to remember this.', 0),
(77223, 'Two of them believed you, and the third will do it anyway, to prove you wrong. That is the 7th Legion.$B$BFewer names on Wintergarde''s stones. Eternus will be pleased. So, I find, am I.', 0),
(77236, '<Messenger Torvus folds your note into his glove.>$B$BSaurfang''s son needs no nursemaid. But a father may want to hear it, and I will carry it. No one reads my glove.', 0),
(77234, 'You again. The one Agmar keeps sending on errands. <Doctor Malefious looks you over.> Good. I have an errand of my own.', 0),
(77235, 'More blight than Agmar ordered, consigned to ''the Grand Apothecary''s special detachment''. Spreaders kept back for ''special missions''. And an apothecary who thinks the word ''living'' slips out by accident.$B$BSay nothing, $N. Not yet. Eternus has a plan for the Wrathgate, and I think, now, I understand what it is for.', 0),
(77048, 'The Kraken sails at dawn. In the ledger, William Allerton died at Farshire, with his throat cut. In Westfall, a farmer called Will Allard will grow very good corn, and tell nobody where he learned to look over his shoulder.$B$BThank you, $N. That one I could not have done alone.', 0),
(77049, '''S'' for ''sent home''. He said that, didn''t he? I knew he would.$B$BIn the record, Unit S was lost at the landing, and nobody ever comes looking. In Stormwind, four mothers will open their doors to sons the army already wrote to them about. Some changes, $N, the war can keep, as long as it never hears of them.', 0),
(77230, 'Condemned again, as it was. The garrison sails for Orgrimmar, the spare tanks with them, and the wounded who could walk walked aboard. History has its coast back.$B$BI keep thinking about the moment at the war map, when he said ''Now we have a shipping lane'' and did not stamp on anything. It was real, $N. For one afternoon, it was real.', 0),
(77901, 'Thank you, $N. That was... nice. I had forgotten what nice was. Dragons are not built for it. We are built for centuries, and centuries have no time for tea.$B$BAsk me something again sometime. I will try to have better answers.', 0),
(77902, '''Mostly as friends'', I said. I am going to have to think about that ''mostly'' for a while.$B$BThe cider was good, though. So were you.', 0),
(77903, '<Moratari touches her lips, as if to make sure they are still hers.>$B$BWell. That will be very hard to put back.$B$BI am teasing. Mostly. I keep saying ''mostly''. I think it means yes.', 0),
(77904, 'You did not run. Everyone runs, the first time. Even other dragons.$B$BI am going to stop counting the times you surprise me, $N. I have run out of fingers, and I refuse to use my claws.', 0),
(77905, 'Thank you for staying. I know what comes next: we put things back, one by one, and some of them will hurt.$B$BBut not everything we did has to be undone. Some things, the war never needs to hear about.', 0),
(77906, 'My wings are aching, and I am grinning like a whelp. Do not tell anyone. I have a reputation for dignity.$B$BI do not, actually. But I would like one.', 0),
(77907, 'An afternoon with nothing in it but us. I had forgotten they came in that size.$B$BWe should do this again. In forty years or so, when the crocolisks have forgotten us.', 0),
(77908, '<Moratari is humming. Dragons do not hum.>$B$BFrom now on I walk with you. Everywhere. Let the posts stand empty and look mysterious without me.$B$BYou may tell people, if you like. Actually, do not. Let them wonder.', 0),
(77909, 'Whatever comes after, $N, you are in my record now, in the place I keep the things I do not let the river take.$B$BNow. There is a king at the top of that glacier, and you have a very important appointment with him. Shall we?', 0);
INSERT INTO `quest_request_items` (`ID`, `CompletionText`, `VerifiedBuild`) VALUES
(77041, 'Well? Did they listen?', 0),
(77042, 'Is it done?', 0),
(77043, '<William Allerton is pressed against the wall of the mine, a hand at his throat.>', 0),
(77044, 'Did you find William? Is he...', 0),
(77045, 'You came from Valiance Keep? Then you''re the first. Light, they really did send us here to die.', 0),
(77046, 'Did she listen?', 0),
(77050, 'Well? Who listened?', 0),
(77054, 'Is the landing quiet?', 0),
(77223, 'Did they listen?', 0),
(77234, 'What? Can''t you see that I''m busy?', 0),
(77235, 'Well? What did you count?', 0),
(77048, 'Will he go?', 0),
(77049, 'Did he take them?', 0),
(77230, 'Is the coast emptied?', 0),
(77901, 'More tea? There is always more. That is rather the point of it.', 0),
(77902, 'Was it one drink? I lost count. I never lose count.', 0),
(77903, 'The dress is back in the Bazaar. I am told I may keep it. I intend to.', 0),
(77904, 'The wind up there still has my hair. I shall have to go back for it.', 0),
(77906, 'My wings still ache. Do not look so pleased with yourself.', 0),
(77907, 'There is sand in the basket. There is always sand in my baskets.', 0);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (770001, 77041), (770001, 77042), (770001, 77043), (770550, 77044), (770001, 77045), (770001, 77046), (770001, 77047), (770001, 77050), (770001, 77051), (770001, 77054), (770001, 77056), (770001, 77223), (770001, 77236), (770001, 77234), (770001, 77235), (770001, 77048), (770001, 77049), (770001, 77230), (770001, 77901), (770001, 77902), (770001, 77903), (770001, 77904), (770001, 77905), (770001, 77906), (770001, 77907), (770001, 77908), (770001, 77909);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (770001, 77041), (770001, 77042), (770550, 77043), (25251, 77044), (26187, 77045), (770001, 77046), (25250, 77047), (770001, 77050), (25327, 77051), (770001, 77054), (25256, 77056), (770001, 77223), (26649, 77236), (26505, 77234), (770001, 77235), (770001, 77048), (770001, 77049), (770001, 77230), (770001, 77901), (770001, 77902), (770001, 77903), (770001, 77904), (770001, 77905), (770001, 77906), (770001, 77907), (770001, 77908), (770001, 77909);
INSERT INTO `remix_departure` (`quest`, `map`, `x`, `y`, `z`, `o`) VALUES
(77046, 571, 2272.60, 5186.20, 20.38, 3.400),
(77047, 571, 2110.20, 5300.60, 34.09, 2.300),
(77048, 571, 2765.60, 5337.80, 30.03, 0.200),
(77049, 571, 3111.00, 3840.00, 22.85, 5.750),
(77230, 571, 2822.50, 6708.50, 10.80, 2.700);
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
(77046, 0, 0, 571, 3292.80, 3679.60, 32.55, 0.600, ''),
(77048, 0, 0, 571, 5700.00, 1012.00, 174.60, 1.570, ''),
(77049, 0, 0, 571, 5700.00, 1012.00, 174.60, 1.570, ''),
(77230, 0, 0, 571, 5700.00, 1012.00, 174.60, 1.570, '');
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES (77041, 800), (77042, 800), (77043, 800), (77044, 800), (77045, 800), (77046, 800), (77047, 1500), (77050, 800), (77051, 800), (77054, 800), (77056, 1500), (77223, 800), (77236, 800), (77234, 800), (77235, 800), (77048, 1000), (77049, 1000), (77230, 1000), (77901, 300), (77902, 300), (77903, 500), (77904, 300), (77905, 300), (77906, 500), (77907, 300), (77908, 500), (77909, 1000);

DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `SourceEntry` IN (11596, 11599, 11606, 11727, 11956, 12036, 12189, 12221, 12251, 77006, 77017, 77018, 77048, 77049, 77050, 77121, 77122, 77221, 77222, 77230, 77901, 77902, 77903, 77904, 77905, 77906, 77907, 77908, 77909);
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(19, 0, 11596, 0, 0, 8, 0, 7784, 0, 0, 1, 0, 0, '', 'Wrath Remix: quest 11596 in the story'),
(19, 0, 11596, 0, 0, 8, 0, 8743, 0, 0, 1, 0, 0, '', 'Wrath Remix: quest 11596 in the story'),
(19, 0, 11596, 0, 0, 8, 0, 77050, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 11596 in the story'),
(19, 0, 11599, 0, 0, 47, 0, 77043, 74, 0, 0, 0, 0, '', 'Wrath Remix: quest 11599 in the story'),
(19, 0, 11606, 0, 0, 8, 0, 77051, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 11606 in the story'),
(19, 0, 11727, 0, 0, 8, 0, 77041, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 11727 in the story'),
(19, 0, 11956, 0, 0, 8, 0, 77046, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 11956 in the story'),
(19, 0, 12036, 0, 0, 8, 0, 77236, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 12036 in the story'),
(19, 0, 12189, 0, 0, 8, 0, 77234, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 12189 in the story'),
(19, 0, 12221, 0, 0, 8, 0, 77235, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 12221 in the story'),
(19, 0, 12251, 0, 0, 8, 0, 77223, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 12251 in the story'),
(19, 0, 77006, 0, 0, 8, 0, 77044, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77006 in the story'),
(19, 0, 77017, 0, 0, 8, 0, 77042, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77017 in the story'),
(19, 0, 77018, 0, 0, 8, 0, 11608, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77018 in the story'),
(19, 0, 77048, 0, 0, 8, 0, 77207, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77048 in the story'),
(19, 0, 77048, 0, 0, 8, 0, 77043, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77048 in the story'),
(19, 0, 77049, 0, 0, 8, 0, 77207, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77049 in the story'),
(19, 0, 77049, 0, 0, 8, 0, 77045, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77049 in the story'),
(19, 0, 77050, 0, 0, 8, 0, 11585, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77050 in the story'),
(19, 0, 77050, 0, 1, 8, 0, 11586, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77050 in the story'),
(19, 0, 77121, 0, 0, 8, 0, 77047, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77121 in the story'),
(19, 0, 77122, 0, 0, 8, 0, 77056, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77122 in the story'),
(19, 0, 77221, 0, 0, 8, 0, 77048, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77221 in the story'),
(19, 0, 77221, 0, 0, 8, 0, 77049, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77221 in the story'),
(19, 0, 77222, 0, 0, 8, 0, 77230, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77222 in the story'),
(19, 0, 77230, 0, 0, 8, 0, 77207, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77230 in the story'),
(19, 0, 77230, 0, 0, 8, 0, 13242, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77230 in the story'),
(19, 0, 77230, 0, 0, 8, 0, 77054, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77230 in the story'),
(19, 0, 77901, 0, 0, 8, 0, 11727, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77901 in the story'),
(19, 0, 77901, 0, 1, 8, 0, 11596, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77901 in the story'),
(19, 0, 77902, 0, 0, 8, 0, 77043, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77902 in the story'),
(19, 0, 77902, 0, 1, 8, 0, 77054, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77902 in the story'),
(19, 0, 77903, 0, 0, 8, 0, 77121, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77903 in the story'),
(19, 0, 77903, 0, 1, 8, 0, 77122, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77903 in the story'),
(19, 0, 77904, 0, 0, 8, 0, 77204, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77904 in the story'),
(19, 0, 77905, 0, 0, 8, 0, 77207, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77905 in the story'),
(19, 0, 77906, 0, 0, 8, 0, 77301, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77906 in the story'),
(19, 0, 77906, 0, 1, 8, 0, 77311, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77906 in the story'),
(19, 0, 77907, 0, 0, 8, 0, 77501, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77907 in the story'),
(19, 0, 77908, 0, 0, 8, 0, 77601, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77908 in the story'),
(19, 0, 77909, 0, 0, 8, 0, 77701, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77909 in the story'),
(19, 0, 77909, 0, 1, 8, 0, 77711, 0, 0, 0, 0, 0, '', 'Wrath Remix: quest 77909 in the story');

INSERT INTO `creature_questender` (`id`, `quest`) VALUES (770550, 11599);
-- The game's quests' texts this file changes, from their originals (kept the first time, and put
-- back first each time, so a text dropped from here is the game's again).
CREATE TABLE IF NOT EXISTS `remix_original_story_text` (
  `quest` INT UNSIGNED NOT NULL,
  `col` VARCHAR(16) NOT NULL,                     -- desc, log, done, reward, request
  `text` TEXT NULL,                               -- NULL: the quest had no such row
  PRIMARY KEY (`quest`, `col`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
UPDATE `quest_template` q JOIN `remix_original_story_text` o ON o.`quest` = q.`ID` AND o.`col` = 'desc' SET q.`QuestDescription` = o.`text`;
UPDATE `quest_template` q JOIN `remix_original_story_text` o ON o.`quest` = q.`ID` AND o.`col` = 'log' SET q.`LogDescription` = o.`text`;
UPDATE `quest_template` q JOIN `remix_original_story_text` o ON o.`quest` = q.`ID` AND o.`col` = 'done' SET q.`QuestCompletionLog` = o.`text`;
UPDATE `quest_offer_reward` r JOIN `remix_original_story_text` o ON o.`quest` = r.`ID` AND o.`col` = 'reward' SET r.`RewardText` = o.`text` WHERE o.`text` IS NOT NULL;
DELETE r FROM `quest_offer_reward` r JOIN `remix_original_story_text` o ON o.`quest` = r.`ID` AND o.`col` = 'reward' WHERE o.`text` IS NULL;
UPDATE `quest_request_items` i JOIN `remix_original_story_text` o ON o.`quest` = i.`ID` AND o.`col` = 'request' SET i.`CompletionText` = o.`text` WHERE o.`text` IS NOT NULL;
DELETE i FROM `quest_request_items` i JOIN `remix_original_story_text` o ON o.`quest` = i.`ID` AND o.`col` = 'request' WHERE o.`text` IS NULL;
DELETE FROM `remix_original_story_text`;
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11599, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 11599);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (11599, '<William Allerton presses a rag to the shallow cut along his neck.>$B$BLeryssa sent you? Light bless that stubborn girl. Another minute and you''d have found me on this floor with my throat open, and not a claw mark on me to explain it.$B$BThat was no ghoul, $c. That was one of ours. Or one wearing our colors.', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11608, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 11608);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (11608, 'That''ll stop ''em - at least I think it''ll stop ''em. Either way, you''ve done your part. That''s all an $r can ask for, no?$B$BFunny thing. The old one sent me six peons this morning, no questions asked. Somebody''s been talking in his ear.', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11643, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 11643);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (11643, 'Already? Buddy, I been lookin'' for that thing since we landed! How''d you know it was in the old hut? ...No. Don''t tell me. Now where''d I put those spare parts...', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11651, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 11651);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (11651, 'With the current state of our transports and shipping lanes, we haven''t been able to get a damned siege engine to Warsong. These tanks are all that we have left from the original expedition. Now that they''re operational, you''re gonna use ''em to stomp the Scourge offensive at the Plains of Nasam.$B$BAnd Mobu says he''s got plates left over for two more. Two more! I might hug the little gnome.', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11652, 'desc', (SELECT `QuestDescription` FROM `quest_template` WHERE `ID` = 11652);
UPDATE `quest_template` SET `QuestDescription` = 'Get in a tank and get out to the Plains of Nasam. Use the tank to kill as many Scourge as you can find. If you find any of our soldiers, grab ''em! Bring ''em back here!$B$BAnd you ain''t alone out there this time. Every tank that''ll run is rollin'' with you. Hellscream wants a show!$B$BTry to get close enough to that structure in the center and see what''s going on. If you do all of this and live, go to Hellscream!' WHERE `ID` = 11652;
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 11938, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 11938);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (11938, 'That should buy us some time. Isidorus has been cooperative, but I really needed that alone time with the lich.$B$B<Thassarian nods toward the Valiance volunteers at the ziggurat''s edge.>$B$BAnd thank Hammerhill''s lads for me. I did not ask for help. It came anyway. I am not used to that.', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);
INSERT INTO `remix_original_story_text` (`quest`, `col`, `text`) SELECT 12311, 'reward', (SELECT `RewardText` FROM `quest_offer_reward` WHERE `ID` = 12311);
INSERT INTO `quest_offer_reward` (`ID`, `RewardText`, `VerifiedBuild`) VALUES (12311, 'Now if we could stop the Scourge attacks coming from outside the keep we might be able to send help to Lord Fordragon at the Wrathgate.$B$B<Durkon looks at you for a long moment.>$B$BYou went straight for the bottom chamber, like you''d been down there before. Lucky guess. I''ll call it that.$B$BThe High Commander''s sending a company to Fordragon Hold tonight. Your doing too, I expect.', 0) ON DUPLICATE KEY UPDATE `RewardText` = VALUES(`RewardText`);

-- Never offered: flags, "rewarded" while they hold (RemixMoratari.cpp): the Timerunner has asked Moratari to
-- wait, and the others the content files name.
DELETE FROM `quest_template` WHERE `ID` IN (77040);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `LogTitle`, `LogDescription`, `QuestDescription`, `VerifiedBuild`) VALUES
(77040, 2, 1, 1, 0, 0, 'Walking Alone', '', 'Moratari waits at her posts while you walk alone.', 0);
