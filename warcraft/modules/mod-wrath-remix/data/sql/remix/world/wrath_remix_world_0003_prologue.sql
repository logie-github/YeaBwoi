-- Wrath Remix's prologue (STORY.md, "Prologue"): a new Timerunner wakes at the Infinite Bazaar in
-- Crystalsong Forest, beneath Dalaran. Moratari tells them where and when they are; Eternus of the
-- Infinite Dragonflight shows them three of the war's dead and asks for their help to change it;
-- Moratari gives them back what the journey took (Bronze, Alurmi, their class's instructor) and
-- tests them against the anomalies time sends; Eternus sends them to the war's first day, in the
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

-- The first prologue (WRM-001 to 007, in the Caverns of Time, until build 64): gone too.
DELETE FROM `quest_template` WHERE `ID` BETWEEN 77001 AND 77009;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 77001 AND 77009;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 77001 AND 77009;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 77001 AND 77009;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 77001 AND 77009;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 77001 AND 77009;
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 77001 AND 77009;

DELETE FROM `quest_template` WHERE `ID` BETWEEN 77010 AND 77015;
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `AllowableRaces`, `RewardItem1`, `RewardAmount1`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`,
  `RequiredNpcOrGo1`, `RequiredNpcOrGo2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount1`, `RequiredNpcOrGoCount2`, `RequiredNpcOrGoCount3`,
  `ObjectiveText1`, `ObjectiveText2`, `ObjectiveText3`, `VerifiedBuild`) VALUES
(77010, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'Out of Time',
 'Speak with Eternus at the Infinite Bazaar.',
 'You are in Northrend, $n: Crystalsong Forest, beneath the city of Dalaran. The fleets of the Alliance and the Horde are only now reaching these shores. The war against the Lich King has barely begun, and you are years before your own time.$B$BThis is the Infinite Bazaar. My master, Eternus of the Infinite Dragonflight, pulled you out of your own time and into this one. The journey has a price: time takes a traveller''s strength and gives it back slowly. That is why you feel as you do. It will pass.$B$BWe call those who make the journey Timerunners. Eternus will tell you why he needs one. He is waiting, just there.',
 'Speak with Eternus.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77011, 2, 10, 1, 2817, 4, 0, 8, 0, 0, 0,
 'What the War Will Cost',
 'Speak with the three echoes around the Bazaar, then return to Eternus.',
 'I have watched this war more times than I can count, Timerunner. I know every battle in it, and every grave.$B$BAround this camp I have frozen three of the war''s dead at the moment they fell: a footman who died on the second day and rose again for the Scourge, Dranosh Saurfang at the gate of Icecrown, and Bolvar Fordragon in the dragonfire beside him.$B$BSpeak with them. Hear what this war takes. Then come back, and tell me it must happen exactly as it did.',
 'Return to Eternus.',
 @ECHO_FOOTMAN, @ECHO_SAURFANG, @ECHO_BOLVAR, 1, 1, 1,
 'Echo of a Fallen Footman heard', 'Echo of Dranosh Saurfang heard', 'Echo of Bolvar Fordragon heard', 0),
(77012, 2, 10, 1, 2817, 3, 0, 8, 0, 0, 0,
 'Time''s Toll',
 'Speak with Alurmi and with your class''s instructor at the Bazaar, then return to Moratari.',
 'Before I send you anywhere, you need your strength back, and it will not come back on its own.$B$BBronze is what time leaves behind when you act in it. Earn it, and Alurmi will trade it for gear that grows as you do. Your class''s instructor, among the others at the east side of the camp, will teach you what the journey made you forget.$B$BMoratari has made the arrangements. See them both, and then her.',
 'Return to Moratari.',
 @ALURMI, @INSTRUCTOR_CREDIT, 0, 1, 1, 0, 'Alurmi spoken to', 'Your class''s instructor spoken to', '', 0),
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

-- One after another; none for death knights, who arrive later and by their own road.
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 77010 AND 77015;
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `AllowableClasses`) VALUES
(77010, 0, 1503), (77011, 77010, 1503), (77012, 77011, 1503), (77013, 77012, 1503), (77014, 77013, 1503), (77015, 77013, 1503);

DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 77010 AND 77015;
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`, `VerifiedBuild`) VALUES
(77011, 1, 1, 'Well? Did they tell you it was worth it?', 0),
(77012, 1, 1, 'Better? You will be.', 0),
(77013, 1, 1, 'Are they gone? All of them? Anomalies are very bad at counting, so I like to check.', 0);

DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 77010 AND 77015;
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77010, 1, 'So you are the one Moratari found. Welcome, Timerunner.$B$BYou are wondering why the Infinite Dragonflight would want a hero. Let me show you.', 0),
(77011, 1, 'There are thousands more. Every one of them a story history will not let finish.$B$BThe bronze dragonflight guards history as though it were sacred. The bronze let Stratholme burn, and called it duty. I would rather save someone. With heroes who know how this war ends, I can: a warning here, a life there, a battle won a day sooner. Small kindnesses, Timerunner. Enough of them, and the Wrathgate never happens.$B$BHelp me. Moratari will see that you are ready.', 0),
(77012, 1, 'Good. Bronze for every errand finished and every foe felled; Alurmi for the gear; your instructor for the rest, and as you grow, the lessons come back to you on their own.$B$BOne more thing: this Bazaar is your home between battles. Your hearthstone now remembers it.', 0),
(77013, 4, 'There. You are stronger than you were an hour ago, which is the whole point of you.$B$BTake these bags: you will carry a great deal of the war home with you. And the Bronze; Alurmi will want to see it. When you are ready, Eternus will send you to the front.', 0),
(77014, 1, 'Remember what the echoes told you. You know how this war goes, and they do not. When I ask you for a kindness, do it.$B$BGo.', 0),
(77015, 1, 'Remember what the echoes told you. You know how this war goes, and they do not. When I ask you for a kindness, do it.$B$BGo.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 77010 AND 77015;
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77010), (@ETERNUS, 77011), (@ETERNUS, 77012), (@MORATARI, 77013), (@MORATARI, 77014), (@MORATARI, 77015);

DELETE FROM `creature_questender` WHERE `quest` BETWEEN 77010 AND 77015;
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@ETERNUS, 77010), (@ETERNUS, 77011), (@MORATARI, 77012), (@MORATARI, 77013), (@ETERNUS, 77014), (@ETERNUS, 77015);

-- To the front: before the officer who enlists the landing, facing them (wrath_remix_world_0006's
-- transitions table; the module takes the Timerunner there as Eternus is answered).
DELETE FROM `remix_bridge` WHERE `quest` IN (77014, 77015);
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
(77014, 11672, 1, 571, 2297.7, 5236.9, 11.4, 5.92, ''),
(77015, 11585, 1, 571, 2757.5, 6090.8, 77.9, 0.42, '');

-- Bronze for the prologue: enough by the demonstration for a first heirloom from Alurmi.
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 77010 AND 77015;
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77010, 500), (77011, 1000), (77012, 1000), (77013, 6000), (77014, 2000), (77015, 2000);
