-- Wrath Remix's prologue (the guide's WRM-001 to WRM-005 and WRM-007): Moratari, the experiment's
-- terms, a first fight, the Bazaar in Crystalsong Forest, and the choice of coast. New quests in
-- Remix's range: WRM-nnn is quest 77nnn (77000 + nnn).

SET @MORATARI := 770001, @ETERNUS := 770002, @ANDORMU := 770003, @NOZARI := 770004,
    @ALURMI := 770005, @CHRONOLOGIST := 770008, @MOMENTUS := 770009,
    @ECHO_LANDING := 770021, @ECHO_WRATHGATE := 770022, @ECHO_ICECROWN := 770023, @ANOMALY_CREDIT := 770032;

-- Every new character but a death knight begins in the Caverns of Time, where Moratari waits
-- (death knights keep Acherus and their own story; the module brings them to the Bazaar after it).
UPDATE `playercreateinfo` SET `map` = 1, `zone` = 440, `position_x` = -8360.7, `position_y` = -4327.7,
  `position_z` = -208.3, `orientation` = 1.6 WHERE `class` <> 6;

-- Until build 56 they were 7701nnn, which made the core keep a table of quests 7.7 million long
-- (its fast lookups are indexed by id): gone, with everything that named them.
DELETE FROM `quest_template` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 7701000 AND 7701999;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 7701000 AND 7701999;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 7701000 AND 7701999;
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 7701000 AND 7701999;

DELETE FROM `quest_template` WHERE `ID` BETWEEN 77001 AND 77009;
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `RewardItem1`, `RewardAmount1`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`,
  `RequiredNpcOrGo1`, `RequiredNpcOrGo2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount1`, `RequiredNpcOrGoCount2`, `RequiredNpcOrGoCount3`,
  `ObjectiveText1`, `ObjectiveText2`, `ObjectiveText3`, `VerifiedBuild`) VALUES
(77001, 2, 10, 1, 440, 4, 0, 8, 0, 0,
 'The Weight of Time',
 'Examine the three time-echoes near Moratari, then speak with her again.',
 'There is an interesting problem with Northrend, $n. Nearly everyone wishes parts of its history could be different. I understand why. But change one event, and we may lose the very people who saved Azeroth.$B$BI would like to study what you can accomplish without disturbing that outcome.$B$BBefore we begin, look at what we are protecting. I have frozen three moments of the war for you. Walk among them.',
 'Return to Moratari.',
 @ECHO_LANDING, @ECHO_WRATHGATE, @ECHO_ICECROWN, 1, 1, 1,
 'Echo of the Landing examined', 'Echo of the Wrathgate examined', 'Echo of Icecrown examined', 0),
(77002, 2, 10, 1, 440, 3, 0, 8, 0, 0,
 'An Agreement Across the Hours',
 'Hear Andormu''s terms for the experiment.',
 'Do you see the contradiction? A terrible chapter can still contain indispensable pages. We are going to read the important ones.$B$BThe Keepers of Time have agreed to let us try, on terms. Andormu insists on reading them to every Timerunner himself. He is standing over there, being patient at me.',
 'Speak with Andormu.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77003, 2, 10, 1, 440, 4, 0, 8, 41599, 2,
 'A Small Demonstration',
 'Defeat 3 Temporal Anomalies, then return to Moratari.',
 'Terms agreed, mostly. Now a small demonstration.$B$BWhere history is held this tightly, it frays at the edges, and the frayed bits get ideas. We call them anomalies. They are harmless to history and only moderately harmful to you.$B$BTell me when you are ready, and I will let them through, one at a time. I am not a monster.',
 'Return to Moratari.',
 @ANOMALY_CREDIT, 0, 0, 3, 0, 0, 'Temporal Anomaly defeated', '', '', 0),
(77004, 2, 10, 1, 2817, 3, 0, 8, 0, 0,
 'The Bazaar Beneath the Violet Sky',
 'Travel to the Infinite Bazaar in Crystalsong Forest. Speak with Alurmi and the Chronologist there, then report to Eternus.',
 'You will need somewhere to stand between the chapters. We have made one: the Infinite Bazaar, on the ground of Crystalsong Forest, beneath Dalaran. Very scenic. Very purple.$B$BAlurmi trades in Bronze, the Chronologist keeps the record of where you are in it, and Eternus is expecting you. Tell me when you wish to go and I will open the way.',
 'Report to Eternus at the Infinite Bazaar.',
 @ALURMI, @CHRONOLOGIST, 0, 1, 1, 0, 'Alurmi spoken to', 'Chronologist spoken to', '', 0),
(77005, 2, 10, 10, 2817, 4, 0, 8, 0, 0,
 'The War We Must Remember',
 'Choose where your war begins, Borean Tundra or Howling Fjord, and meet Moratari there.',
 'Two shores, one war. Your people landed on both, and both landings matter to what comes after.$B$BChoose where you will begin. I will send you, and Moratari will be waiting. She promises not to inventory every crate the army brought.',
 'Find Moratari at the landing you chose.',
 0, 0, 0, 0, 0, 0, '', '', '', 0),
(77007, 2, 10, 10, 2817, 3, 0, 8, 0, 0,
 'In the Shadow of the City',
 'Return to the Infinite Bazaar. Hear Momentus explain Infinite Power, then report to Nozari.',
 'Between chapters, come back to the Bazaar. Zephyr there can fold the roads of Northrend for you, and your hearthstone now remembers the camp.$B$BMomentus has been pestering me to explain Infinite Power to you. Let him. Then give Nozari your report; she keeps the incident log, and she will want to hear about every anomaly you met.',
 'Report to Nozari at the Infinite Bazaar.',
 @MOMENTUS, 0, 0, 1, 0, 0, 'Momentus heard out', '', '', 0);

DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 77001 AND 77009;
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES
(77001, 0), (77002, 77001), (77003, 77002), (77004, 77003), (77005, 0), (77007, 77005);

DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 77001 AND 77009;
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`, `VerifiedBuild`) VALUES
(77001, 1, 1, 'Well? Did they look back at you? They sometimes do.', 0),
(77003, 1, 1, 'Are they gone? All of them? Anomalies are very bad at counting, so I like to check.', 0),
(77004, 1, 1, 'Moratari''s Timerunner. Good.', 0),
(77007, 1, 1, 'Report.', 0);

DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 77001 AND 77009;
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77001, 1, 'The landing, the gate, the throne. Every one of those moments is terrible, and every one of them is necessary. If we remove the pain from that history, we may remove the people who were shaped by it.$B$BI would rather not. Not yet, anyway. I have notes to take.', 0),
(77002, 1, 'The experiment may shorten the road. It will not rewrite the destination. The Wrathgate must fall. The Frozen Throne must stand until its appointed hour.$B$BThe Infinite Dragonflight will shorten what may be shortened. The Keepers will watch what may not. You, Timerunner, will be the only one in the record who knows the difference.', 0),
(77003, 4, 'There. History barely noticed. You, on the other hand, are slightly stronger than you were, which is the whole point.$B$BTake these bags: you will be carrying a great deal of the war home with you. And the Bronze. Alurmi will want to see it.', 0),
(77004, 1, 'So you are Moratari''s Timerunner. Good.$B$BThis Bazaar is yours between chapters. Your hearthstone now remembers it. Rest here, spend your Bronze, learn what your instructors can teach you, and when you are ready, the war is waiting.', 0),
(77005, 1, 'You made it, and the landing is still here. Good start.$B$BWe will be studying the war, not inventorying every crate sent to fight it. When the next important scene arrives, I will see that you are there. When something unimportant gets in the way, I will see that it does not.', 0),
(77007, 1, 'Recorded. Every anomaly, every errand the record forgave, every hour saved. The total, so far, is a champion. That is acceptable.$B$BReturn to your chapter, Timerunner. The Bazaar will be here.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 77001 AND 77009;
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77001), (@MORATARI, 77002), (@MORATARI, 77003), (@MORATARI, 77004),
(@ETERNUS, 77005), (@MORATARI, 77007);

DELETE FROM `creature_questender` WHERE `quest` BETWEEN 77001 AND 77009;
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@MORATARI, 77001), (@ANDORMU, 77002), (@MORATARI, 77003), (@ETERNUS, 77004),
(@MORATARI, 77005), (@NOZARI, 77007);

-- Bronze for the prologue: the third quest's is enough for a first heirloom from Alurmi.
DELETE FROM `remix_quest_reward` WHERE `quest` BETWEEN 77001 AND 77009;
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77001, 50), (77002, 25), (77003, 300), (77004, 50), (77005, 100), (77007, 50);
