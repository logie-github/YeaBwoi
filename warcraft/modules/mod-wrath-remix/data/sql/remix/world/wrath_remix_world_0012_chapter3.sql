-- Moratari's transitions in Chapter III, The Curse of Grizzly Hills (the guide's bridges III-A and
-- III-B, and WRM-321), as Chapter I's and II's are made (wrath_remix_world_0006_bridges.sql): a new
-- quest from Moratari by the ender of the original quest it follows
-- (wrath_remix_world_0004_spawns.sql). Never Drak'Tharon Keep or what Drakuru says there: III-B
-- only comes before it, and WRM-321 after it.
--
--   WRM-301  Alliance  after Ursoc, the Bear God (12249)          opens Filling the Cages (11984)
--   WRM-311  Horde     after Ursoc, the Bear God (12236)          opens Filling the Cages (11984)
--   WRM-302  both      after Voices From the Dust (12068)         at Drak'Tharon's door, before Cleansing Drak'Tharon
--   WRM-321  both      after Cleansing Drak'Tharon (12238): to Light's Breach in Zul'Drak, Chapter IV
--
-- Cleansing Drak'Tharon is turned in to an Image of Drakuru the dungeon summons at its top, where
-- nobody can stand beside it: WRM-321's Moratari is the one at the door, by the Image of Drakuru
-- outside.

SET @MORATARI := 770001;

DELETE FROM `quest_template` WHERE `ID` IN (77301, 77302, 77311, 77321);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`, `VerifiedBuild`) VALUES
-- III-A: Ursoc at rest, and the hills' other wars (lumber camps, the Iron Thane, goblins and
-- shredders, the Conquest Pit) left to whoever wants them. Drakuru's breadcrumbs are Troll Season!
-- and Good Troll Hunting.
(77301, 2, 38, 32, 394, 5, 0, 0, 1101,
 'A Shorter Path Through the Woods',
 'Speak with Moratari again to travel to Samir, by Drakuru''s cages.',
 'Ursoc rests, and the forest is a little less sick than it was. Amberpine Lodge has a great deal more for you: lumber camps at war with each other, a dwarf with an iron anvil and a worse temper, goblins, shredders, wolves.$B$BThere are a dozen paths through these woods. Only one ends with a very interesting troll. His name is Drakuru. He would like to be your friend, and he has cages that need filling.$B$BIf you would rather walk every one of those paths yourself, the woods will keep them for you.',
 'Speak with Moratari.', 0),
(77311, 2, 38, 32, 394, 5, 0, 0, 690,
 'A Shorter Path Through the Woods',
 'Speak with Moratari again to travel to Samir, by Drakuru''s cages.',
 'Ursoc rests, and the forest is a little less sick than it was. Conquest Hold has a great deal more for you: a pit to fight in, lumber camps at war, an iron dwarf to bring down, goblins with opinions about all of it.$B$BThere are a dozen paths through these woods. Only one ends with a very interesting troll. His name is Drakuru. He would like to be your friend, and he has cages that need filling.$B$BIf you would rather walk every one of those paths yourself, the woods will keep them for you.',
 'Speak with Moratari.', 0),
-- III-B: no skip. The dungeon and what is said at its top stay as they are.
(77302, 2, 39, 32, 394, 5, 0, 0, 0,
 'A Friend of Questionable Judgment',
 'Speak with Moratari at the door of Drak''Tharon Keep.',
 'Drakuru wants you to climb Drak''Tharon and cleanse it. He has been very helpful. Elixirs, visions, a great deal of encouragement, and never once has he asked for anything in return.$B$BI have a theory about your new friend. I sincerely hope the theory is wrong.$B$BGo in. Do what he asks; history needs you to. Only remember, when you reach the top, that a troll who speaks to you through smoke is still a troll you cannot see.',
 'Speak with Moratari.', 0),
-- WRM-321: the travel to Zul'Drak.
(77321, 2, 40, 32, 394, 5, 0, 0, 0,
 'The Truth About Drakuru',
 'Speak with Moratari again to travel to Light''s Breach in Zul''Drak.',
 'The Prophet Tharon''ja is dead, Drak''Tharon is clean, and Drakuru has thanked you very warmly through a cloud of smoke. He would like you to visit him in Zul''Drak. In person, this time.$B$BThe Drakkari empire is eating itself, $n. Its trolls kill their own gods for their power, and the Scourge is glad to help. The Argent Crusade holds Light''s Breach at its edge, and Crusader Lord Lantinga wants answers about what the Scourge is doing there. I suspect he will find a familiar name in them.$B$BChapter IV begins at Light''s Breach. The road there is long and uneventful; let us not take it.',
 'Speak with Moratari.', 0);

DELETE FROM `quest_template_addon` WHERE `ID` IN (77301, 77302, 77311, 77321);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES
(77301, 12249), (77311, 12236), (77302, 12068), (77321, 12238);

DELETE FROM `quest_offer_reward` WHERE `ID` IN (77301, 77302, 77311, 77321);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77301, 1, 'Hold on. Samir will be delighted. Samir is always delighted.', 0),
(77311, 1, 'Hold on. Samir will be delighted. Samir is always delighted.', 0),
(77302, 1, 'Mind the stairs. And the trolls. And the troll.', 0),
(77321, 1, 'Hold on. Zul''Drak is warmer than the Grizzly Hills, and much less pleasant.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` IN (77301, 77302, 77311, 77321);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77301), (@MORATARI, 77302), (@MORATARI, 77311), (@MORATARI, 77321);
DELETE FROM `creature_questender` WHERE `quest` IN (77301, 77302, 77311, 77321);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@MORATARI, 77301), (@MORATARI, 77302), (@MORATARI, 77311), (@MORATARI, 77321);

DELETE FROM `remix_bridge` WHERE `quest` IN (77301, 77302, 77311, 77321);
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
-- Before Samir, facing him.
(77301, 11984, 3, 571, 3849.4, -1958.8, 208.5, 5.29, '12210'),
(77311, 11984, 3, 571, 3849.4, -1958.8, 208.5, 5.29, '12208'),
-- At the door: nothing skipped, nowhere to go.
(77302, 12238, 3, 65535, 0, 0, 0, 0, ''),
-- Before Crusader Lord Lantinga, facing him (In Search Of Answers, both sides).
(77321, 12902, 4, 571, 5149.1, -2206.5, 236.6, 2.57, '');

-- Filling the Cages opens with III-A or Drakuru's breadcrumbs, Troll Season! (Alliance) and Good
-- Troll Hunting (Horde): their NextQuestID made those its only ways in; the conditions say it now.
UPDATE `quest_template_addon` SET `NextQuestID` = 0 WHERE `ID` IN (12208, 12210);
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `SourceEntry` = 11984;
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`,
  `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(19, 0, 11984, 0, 0, 8, 0, 77301, 0, 0, 0, 0, 0, '', 'Wrath Remix: Filling the Cages after A Shorter Path Through the Woods (Alliance)'),
(19, 0, 11984, 0, 1, 8, 0, 77311, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or the Horde''s'),
(19, 0, 11984, 0, 2, 8, 0, 12210, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after Troll Season!'),
(19, 0, 11984, 0, 3, 8, 0, 12208, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after Good Troll Hunting');

DELETE FROM `remix_quest_reward` WHERE `quest` IN (77301, 77302, 77311, 77321);
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77301, 800), (77311, 800), (77302, 500), (77321, 1500);
