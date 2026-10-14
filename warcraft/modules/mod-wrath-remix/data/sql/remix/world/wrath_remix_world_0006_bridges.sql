-- Moratari's transitions in Chapter I (the guide's WRM-006 and WRM-121, one for each side and
-- coast). Each is a new quest from Moratari standing by the giver of the original quest it follows
-- (wrath_remix_world_0004_spawns.sql). It stands for a block of original quests: the next original
-- quest opens with either the transition or the block's own last quest, so nothing is ever marked
-- done that wasn't, and a Timerunner who'd rather do the block still can. Ids from the world
-- database (data/campaign_manifest.csv, tools/quest_audit.py).

SET @MORATARI := 770001;

DELETE FROM `quest_template` WHERE `ID` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`, `VerifiedBuild`) VALUES
-- Alliance, Borean Tundra: Leryssa's search for her brother, from William Allerton's house to the
-- siege where Thassarian is found.
(77006, 2, 15, 10, 3537, 5, 0, 0, 1101,
 'A Useful Omission',
 'Speak with Moratari again to go where Thassarian''s trail ends.',
 'Thassarian is alive. In the record, he is found. The road there is a dead man''s house, a barrel of wine, a deserter, a coward or two, and a tuskarr with a grudge. I have read it all for you.$B$BThis is what I do, $n: when the war asks you for an afternoon of errands, I see whether history needs them. This time it does not. Corporal Venn is holding out where the trail ends, and Thassarian is just beyond.$B$BIf you would rather walk every step yourself, the road is still there. I will not be offended. Much.',
 'Speak with Moratari.', 0),
-- Horde, Borean Tundra: the parts for Mobu's tank.
(77016, 2, 16, 10, 3537, 5, 0, 0, 690,
 'A Useful Omission',
 'Speak with Moratari again to have the tank''s parts accounted for.',
 'Mobu wants parts. Mobu always wants parts. A transjigamarig, some plates, a great deal of shouting.$B$BThis is what I do, $n: when the war asks you for an afternoon of errands, I see whether history needs them. History is quite certain the tank works. Let us skip to the part where it does, and see what the Horde does with it.$B$BIf you would rather fetch every bolt yourself, Mobu will still be grateful. Loudly.',
 'Speak with Moratari.', 0),
-- Alliance, Howling Fjord: Scout Knowles' missions and the drunken plan that ends at Mission:
-- Plague This!, before Operation: Skornful Wrath.
(77026, 2, 16, 10, 495, 5, 0, 0, 1101,
 'A Useful Omission',
 'Speak with Moratari again to be ready for Operation: Skornful Wrath.',
 'Westguard Keep has a great many missions for a newcomer: eternal flames, retrieved packages, Forsaken papers, and a goblin''s plan that only works if everyone involved is very drunk. They all end in the same place: Captain Adams ready to take Skorn.$B$BThis is what I do, $n: when the war asks you for an afternoon of errands, I see whether history needs them. The captain is ready now.',
 'Speak with Moratari.', 0),
-- Into the Dragonblight: the end of each coast's chapter.
(77121, 2, 20, 18, 3537, 5, 0, 0, 1101,
 'Into the Dragonblight',
 'Speak with Moratari again to travel to Wintergarde Keep.',
 'Both landings hold, in the version of the record that matters. Unfortunately, the war grows worse inland.$B$BWintergarde Keep, in the Dragonblight. Naxxramas floats over it, and High Commander Wyrmbane is about to learn exactly what that means. You should be there when he does.',
 'Speak with Moratari.', 0),
(77122, 2, 20, 18, 3537, 5, 0, 0, 690,
 'Into the Dragonblight',
 'Speak with Moratari again to travel to Agmar''s Hammer.',
 'Both landings hold, in the version of the record that matters. Unfortunately, the war grows worse inland.$B$BAgmar''s Hammer, in the Dragonblight. The Horde is pushing toward the gate of Icecrown, and Overlord Agmar believes victory is near. History is less sure. You should be there to find out.',
 'Speak with Moratari.', 0),
(77123, 2, 20, 18, 495, 5, 0, 0, 1101,
 'Into the Dragonblight',
 'Speak with Moratari again to travel to Wintergarde Keep.',
 'Skorn is taken, and the record holds. The fjord is not where the war will be decided, though.$B$BWintergarde Keep, in the Dragonblight. Naxxramas floats over it, and High Commander Wyrmbane is about to learn exactly what that means. You should be there when he does.',
 'Speak with Moratari.', 0),
(77124, 2, 20, 18, 495, 5, 0, 0, 690,
 'Into the Dragonblight',
 'Speak with Moratari again to travel to Agmar''s Hammer.',
 'The apothecaries'' work here is finished, and I would rather not think about what it is for. Not yet.$B$BAgmar''s Hammer, in the Dragonblight. The Horde is pushing toward the gate of Icecrown, and Overlord Agmar believes victory is near. You should be there.',
 'Speak with Moratari.', 0);

DELETE FROM `quest_template_addon` WHERE `ID` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES
(77006, 11599), (77016, 11642), (77026, 11291),
(77121, 12019), (77122, 11652), (77123, 11250), (77124, 11310);

DELETE FROM `quest_offer_reward` WHERE `ID` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77006, 1, 'I think our records can survive without another afternoon of interviews. Hold on to something.', 0),
(77016, 1, 'The tank works. History says so, and history is very rarely wrong about tanks.', 0),
(77026, 1, 'The captain is ready. So, I suspect, are you.', 0),
(77121, 1, 'Hold on. The Dragonblight is cold even for Northrend.', 0),
(77122, 1, 'Hold on. The Dragonblight is cold even for Northrend.', 0),
(77123, 1, 'Hold on. The Dragonblight is cold even for Northrend.', 0),
(77124, 1, 'Hold on. The Dragonblight is cold even for Northrend.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77006), (@MORATARI, 77016), (@MORATARI, 77026),
(@MORATARI, 77121), (@MORATARI, 77122), (@MORATARI, 77123), (@MORATARI, 77124);
DELETE FROM `creature_questender` WHERE `quest` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@MORATARI, 77006), (@MORATARI, 77016), (@MORATARI, 77026),
(@MORATARI, 77121), (@MORATARI, 77122), (@MORATARI, 77123), (@MORATARI, 77124);

DELETE FROM `remix_bridge`;
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
-- Before Corporal Venn (Thassarian, the Death Knight).
(77006, 12088, 1, 571, 3116.4, 3841.8, 23.3, 2.60, '11600 11601 11603 11604 11932 12086 11944'),
(77016, 11651, 1, 65535, 0, 0, 0, 0, '11643 11644'),
(77026, 11248, 1, 65535, 0, 0, 0, 0, '11199 11202 11327 11328 11330 11331 11332'),
-- Before High Commander Halford Wyrmbane, and Overlord Agmar.
(77121, 12235, 2, 571, 3764.6, -772.4, 196.1, 5.54, ''),
(77122, 12034, 2, 571, 3842.4, 1489.6, 92.1, 4.71, ''),
(77123, 12235, 2, 571, 3764.6, -772.4, 196.1, 5.54, ''),
(77124, 12034, 2, 571, 3842.4, 1489.6, 92.1, 4.71, '');

-- The next original quest opens with either the transition or the block's own end.
UPDATE `quest_template_addon` SET `PrevQuestID` = 0 WHERE `ID` IN (12088, 11248);
UPDATE `quest_template_addon` SET `NextQuestID` = 0 WHERE `ID` IN (11643, 11644);
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `SourceEntry` IN (12088, 11651, 11248);
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`,
  `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(19, 0, 12088, 0, 0, 8, 0, 77006, 0, 0, 0, 0, 0, '', 'Wrath Remix: Thassarian, the Death Knight after A Useful Omission'),
(19, 0, 12088, 0, 1, 8, 0, 11944, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after Surrounded!'),
(19, 0, 11651, 0, 0, 8, 0, 77016, 0, 0, 0, 0, 0, '', 'Wrath Remix: Tanks a lot... after A Useful Omission'),
(19, 0, 11651, 0, 1, 8, 0, 11643, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after both of Mobu''s parts quests'),
(19, 0, 11651, 0, 1, 8, 0, 11644, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... (the plates)'),
(19, 0, 11248, 0, 0, 8, 0, 77026, 0, 0, 0, 0, 0, '', 'Wrath Remix: Operation: Skornful Wrath after A Useful Omission'),
(19, 0, 11248, 0, 1, 8, 0, 11332, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after Mission: Plague This!');

DELETE FROM `remix_quest_reward` WHERE `quest` IN (77006, 77016, 77026, 77121, 77122, 77123, 77124);
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77006, 40), (77016, 40), (77026, 40), (77121, 75), (77122, 75), (77123, 75), (77124, 75);
