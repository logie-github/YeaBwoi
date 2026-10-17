-- Wrath Remix's prologue (STORY.md, "Prologue"): a new Timerunner, a veteran of the war against
-- the Lich King pulled out of a tavern in their own hour by Moratari, shown the war's end and its
-- cost (src/RemixIntro.cpp), arrives at the Infinite Bazaar in Crystalsong Forest, beneath
-- Dalaran, in the year the war began. Eternus tells them plainly what he wants and how it is done;
-- then, one thing at a time, Bronze and Alurmi, their class's instructor and lesson, the Bazaar's
-- keepers, the hourglass and the hearthstone, the anomalies; and the war's first day, in the
-- Borean Tundra. Each quest is given where the last one ended. WRM-nnn is quest 77nnn.

SET @MORATARI := 770001, @ETERNUS := 770002, @ALURMI := 770005,
    @ECHO_FOOTMAN := 770021, @ECHO_SAURFANG := 770022, @ECHO_BOLVAR := 770023,
    @ANOMALY_CREDIT := 770032, @INSTRUCTOR_CREDIT := 770033;

-- Every new character but a death knight wakes at the Bazaar, before Eternus and Moratari (death
-- knights keep Acherus and their own story; the module brings them to the Bazaar after it).
UPDATE `playercreateinfo` SET `map` = 571, `zone` = 2817, `position_x` = 5700.0, `position_y` = 1012.0,
  `position_z` = 174.6, `orientation` = 1.57 WHERE `class` <> 6;

-- Until build 56 Remix's quests were 7701nnn, which made the core keep a table of quests 7.7
-- million long (its fast lookups are indexed by id): gone, with everything that named them.
DELETE FROM `quest_template` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 7701000 AND 7701999;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 7701000 AND 7701999;
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 7701000 AND 7701999;

-- The first prologue (WRM-001 to 007, in the Caverns of Time, until build 64): gone too. Not 77006,
-- which is Chapter I's Alliance transition now (wrath_remix_world_0006_bridges.sql): this file can
-- change on its own, and its deletes must not take that one out.
DELETE FROM `quest_template` WHERE `ID` BETWEEN 77001 AND 77009 AND `ID` <> 77006;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 77001 AND 77009 AND `ID` <> 77006;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 77001 AND 77009 AND `ID` <> 77006;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 77001 AND 77009 AND `ID` <> 77006;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 77001 AND 77009 AND `quest` <> 77006;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 77001 AND 77009 AND `quest` <> 77006;
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 77001 AND 77009 AND `quest` <> 77006;

-- The prologue, one thing at a time: Moratari sends the Timerunner to Eternus (WRM-010), who
-- answers what he wants, how it is done and why them (WRM-011); Time's Toll and Bronze, at Alurmi's
-- stall (WRM-012); their class's instructor (WRM-020) and lesson (WRM-030 to 039,
-- wrath_remix_world_0025_lessons.sql); the Bazaar's keepers, the hourglass and the hearthstone
-- (WRM-021); the anomalies (WRM-013); and the front (WRM-014 / 015).
SET @PLAN_WHAT := 770049, @PLAN_HOW := 770050, @PLAN_WHY := 770058,
    @ZEPHYR := 770006, @SAAT := 770007, @CHRONOLOGIST := 770008;

DELETE FROM `quest_template` WHERE `ID` BETWEEN 77010 AND 77015 OR `ID` IN (77020, 77021);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `AllowableRaces`, `RewardItem1`, `RewardAmount1`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`,
  `RequiredNpcOrGo1`, `RequiredNpcOrGo2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount1`, `RequiredNpcOrGoCount2`, `RequiredNpcOrGoCount3`,
  `ObjectiveText1`, `ObjectiveText2`, `ObjectiveText3`, `VerifiedBuild`) VALUES
(77010, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'Out of Time',
 'Speak with Eternus at the heart of the Infinite Bazaar.',
 'This is the Infinite Bazaar, $n: a camp the Infinite Dragonflight keeps outside the flow of time. We are in Crystalsong Forest, beneath Dalaran, in the year the war began. The fleets of the Alliance and the Horde are only now reaching Northrend''s shores.$B$BEverything I showed you is still ahead of these people. The Wrathgate. Wintergarde. The fields of Icecrown. None of it has happened yet.$B$BYour friends are still at that table, by the way, in your own hour, mid-toast. You will be back before they notice you have gone.$B$BMy master, Eternus, sent me for you. He will tell you why himself. Go on: he is just there.',
 'Speak with Eternus.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77011, 2, 10, 1, 2817, 4, 0, 8, 0, 0, 0,
 'The Plan',
 'Ask Eternus what he wants of you, how it is to be done, and why he chose you.',
 'You have questions. Good. A Timerunner who asks none is no use to me.$B$BAsk me what I want, how we are to do it, and why it is you. I will answer plainly. Then we begin.',
 'Speak with Eternus.',
 @PLAN_WHAT, @PLAN_HOW, @PLAN_WHY, 1, 1, 1,
 'Asked what he wants', 'Asked how it is done', 'Asked why you', 0),
(77012, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'Time''s Toll',
 'Speak with Alurmi, at the head of the market lane on the camp''s west side.',
 'One more thing, and you will not like it. Time takes its toll on those who travel through it. You will feel weaker than the hero who sat in that tavern, because you are: most of what you could do has stayed behind, in your own hour. It comes back, and quickly. Every day of this war you will be stronger than the last.$B$BBronze helps. Bronze is what time leaves behind when you act in it, every errand and every foe, and Alurmi trades it for gear that grows as you do. Hers is the first stall of the market, on the camp''s west side.',
 'Speak with Alurmi.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77020, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'Old Habits',
 'Find your class''s Timeworn Instructor in the training yard, south-east of the market.',
 'One more stop, then Moratari will want you. Your class''s instructor is in the training yard, south-east of here, with the others.$B$BYour hands remember more than you would think. They will remind them.',
 'Speak with your class''s instructor.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77021, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'The Bazaar''s Keepers',
 'Speak with Zephyr, Sa''at and the Chronologist, then return to Moratari.',
 'Good: you remember which end of the sword to hold. Now the Bazaar.$B$BZephyr keeps its ways, at the waystation to the north-east: he can send you to Dalaran, to the landings, and to the camp of any chapter your strength allows. Sa''at, at his map table to the south, keeps watch over the dungeons and the raids and will tell you when each is ready for you. The Chronologist, in the chronicle corner to the north, keeps the record of where you are in the war, and can take you back to the start of a chapter. Speak with all three.$B$BAnd the hourglass in your bag is mine. Turn it anywhere, and it brings you here. Turn it here, and it takes you back to wherever you were. Not in the middle of a fight, though: time is fussy about that.',
 'Return to Moratari.',
 @ZEPHYR, @SAAT, @CHRONOLOGIST, 1, 1, 1, 'Zephyr spoken to', 'Sa''at spoken to', 'The Chronologist spoken to', 0),
(77013, 2, 10, 1, 2817, 4, 0, 8, 0, 41599, 2,
 'A Small Demonstration',
 'Defeat 3 Temporal Anomalies, then return to Moratari.',
 'Before Eternus sends you to the war, he wants to know you can survive it. So do I.$B$BTime does not like being changed. Where it is pulled on, it frays, and things come through the seams: anomalies. They are drawn to Timerunners, which is unfortunate for you and convenient for me.$B$BTell me when you are ready, and I will let them through. One at a time. I am not a monster.',
 'Return to Moratari.',
 @ANOMALY_CREDIT, 0, 0, 3, 0, 0, 'Temporal Anomaly defeated', '', '', 0),
(77014, 2, 10, 1, 2817, 4, 0, 8, 1101, 0, 0,
 'To the Front',
 'Speak with Eternus to be sent to Valiance Keep in the Borean Tundra.',
 'The war begins on the shore, $n, and that is where you begin.$B$BThe Alliance''s fleet is landing at Valiance Keep, in the Borean Tundra, and the Scourge is waiting for it. Eternus will fold the road for you. When you arrive, enlist: Recruitment Officer Blythe is signing up every sword that steps off a boat.$B$BI will be there before you. I usually am.',
 'Speak with Eternus.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77015, 2, 10, 1, 2817, 4, 0, 8, 690, 0, 0,
 'To the Front',
 'Speak with Eternus to be sent to Warsong Hold in the Borean Tundra.',
 'The war begins on the shore, $n, and that is where you begin.$B$BThe Horde''s fleet is landing at Warsong Hold, in the Borean Tundra, where Garrosh Hellscream is waiting for the Scourge to come to him. Eternus will fold the road for you. When you arrive, report to the Warsong Recruitment Officer at the landing.$B$BI will be there before you. I usually am.',
 'Speak with Eternus.',
 0, 0, 0, 0, 0, 0, '', '', '', 0);

-- One after another; none for death knights, who arrive later and by their own road. After Old
-- Habits, the class's lesson; whichever lesson it was opens the Bazaar's Keepers (each lesson's
-- NextQuestID, in wrath_remix_world_0025_lessons.sql).
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 77010 AND 77015 OR `ID` IN (77020, 77021);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `AllowableClasses`) VALUES
(77010, 0, 1503), (77011, 77010, 1503), (77012, 77011, 1503), (77020, 77012, 1503), (77021, 0, 1503),
(77013, 77021, 1503), (77014, 77013, 1503), (77015, 77013, 1503);

DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 77010 AND 77015 OR `ID` IN (77020, 77021);
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`, `VerifiedBuild`) VALUES
(77011, 1, 1, 'Ask. I have waited a long time for someone to ask.', 0),
(77021, 1, 1, 'Met them all? They are odd, but they are ours.', 0),
(77013, 1, 1, 'Are they gone? All of them? Anomalies are very bad at counting, so I like to check.', 0);

DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 77010 AND 77015 OR `ID` IN (77020, 77021);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77010, 1, 'So you are the one Moratari chose. Welcome, $n.$B$BYou have seen what this war costs. You fought through all of it, and you won, and the graves stayed full. I have watched it end that way more times than I can count.$B$BI am Eternus, of the Infinite Dragonflight. My kind are said to want time broken. I want it mended. Ask me what you will.', 0),
(77011, 1, 'Now you know what I know.$B$BOne last thing, and then Moratari will see you ready. Time will not make this easy for you.', 0),
(77012, 1, 'Eternus sent you? Then you will want an heirloom: gear that grows with you, so you never buy it twice.$B$BHere is some Bronze to start. Pick a piece from my lists; it will be with you all the way to Icecrown.', 0),
(77020, 1, 'There you are. I have trained a hundred Timerunners, and every one of them arrives looking for the strength they had. It is still in you.$B$BLet me show you.', 0),
(77021, 1, 'Now you know your way around. Your hearthstone remembers the Bazaar, too: it is your home between battles now.$B$BOne thing left. I want to see you fight.', 0),
(77013, 4, 'There. You are stronger than you were an hour ago, which is the whole point of you.$B$BTake these bags: you will carry a great deal of the war home with you. And the Bronze; Alurmi will want to see it. When you are ready, Eternus will send you to the front.', 0),
(77014, 1, 'Remember what you know, and keep it to yourself. When something can be done better, find a reason anyone would believe, and do it.$B$BGo. Moratari will be with you.', 0),
(77015, 1, 'Remember what you know, and keep it to yourself. When something can be done better, find a reason anyone would believe, and do it.$B$BGo. Moratari will be with you.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 77010 AND 77015 OR `quest` IN (77020, 77021);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77010), (@ETERNUS, 77011), (@ETERNUS, 77012), (@ALURMI, 77020), (@MORATARI, 77021), (@MORATARI, 77013),
(@MORATARI, 77014), (@MORATARI, 77015);

-- Old Habits ends at the Timerunner's own class's instructor (the others don't take it: their
-- script hides it, src/RemixNpcs.cpp).
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 77010 AND 77015 OR `quest` IN (77020, 77021);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@ETERNUS, 77010), (@ETERNUS, 77011), (@ALURMI, 77012), (@MORATARI, 77021), (@MORATARI, 77013), (@ETERNUS, 77014), (@ETERNUS, 77015),
(770101, 77020), (770102, 77020), (770103, 77020), (770104, 77020), (770105, 77020), (770106, 77020), (770107, 77020),
(770108, 77020), (770109, 77020), (770110, 77020), (770111, 77020);

-- To the front: before the officer who enlists the landing, facing them (wrath_remix_world_0006's
-- transitions table; the module takes the Timerunner there as Eternus is answered).
DELETE FROM `remix_bridge` WHERE `quest` IN (77014, 77015);
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
(77014, 11672, 1, 571, 2297.7, 5236.9, 11.4, 5.92, ''),
(77015, 11585, 1, 571, 2757.5, 6090.8, 77.9, 0.42, '');

-- Bronze for the prologue: enough at Alurmi's stall for a first heirloom.
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 77010 AND 77015 OR `quest` IN (77020, 77021);
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77010, 500), (77011, 1000), (77012, 1500), (77020, 500), (77021, 500), (77013, 6000), (77014, 2000), (77015, 2000);
