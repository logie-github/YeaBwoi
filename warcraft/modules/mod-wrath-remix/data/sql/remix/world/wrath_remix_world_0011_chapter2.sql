-- Moratari's transitions in Chapter II, The Wrathgate (the guide's bridges II-A, II-B and II-C,
-- WRM-201 to 222), as Chapter I's are made (wrath_remix_world_0006_bridges.sql): a new quest from
-- Moratari standing by the ender of the original quest it follows
-- (wrath_remix_world_0004_spawns.sql), standing for a block of original quests. The next original
-- quest opens with either the transition or the block's own end, so nothing is marked done that
-- wasn't, and the block is still there to play. Never the Wrathgate itself, nor the Battle for
-- the Undercity: II-B only follows the Wrathgate, and II-C the battle.
--
--   WRM-201  Alliance  after Find Durkon! (12309)           opens Into Hostile Territory (12325)
--   WRM-211  Horde     after The Forsaken Blight (12221)    opens The Kor'kron Vanguard! (12224)
--   WRM-202  Alliance  after Return To Angrathar (12499)    by Alexstrasza, before Reborn From The Ashes
--   WRM-212  Horde     after Return To Angrathar (12500)    by Alexstrasza, before Darkness Stirs
--   WRM-221  Alliance  after The Battle For The Undercity (13377): to Amberpine Lodge, Chapter III
--   WRM-222  Horde     after The Battle For The Undercity (13267): to Conquest Hold, Chapter III,
--                      opening The Conqueror's Task (12468) in place of its Dragonblight breadcrumb
--
-- Ids and chains from the Remix world database (data/campaign_manifest.csv, tools/quest_audit.py).

SET @MORATARI := 770001;

DELETE FROM `quest_template` WHERE `ID` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `RewardXPDifficulty`, `RewardMoney`, `Flags`,
  `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `QuestCompletionLog`, `VerifiedBuild`) VALUES
-- II-A, Alliance: Wintergarde's errands between Durkon's rescue and the push into hostile
-- territory (the crypt, the tome and the sermon; the mine, the demo-gnome and the war machine).
(77201, 2, 24, 20, 65, 5, 0, 0, 1101,
 'The Road to Angrathar',
 'Speak with Moratari again to return to High Commander Wyrmbane, ready to take the fight into hostile territory.',
 'Cavalier Durkon lives, and Wintergarde has plans for you: a crypt to search, a tome to translate, a sermon to sit through, a mine to clear, and a gnome who is very good at blowing things up.$B$BHistory cannot seem to agree on the number of wagons. It is very certain about what happens at Angrathar. The High Commander is ready to carry the fight to the Scourge, and that is where you are needed.$B$BIf you would rather search every crypt yourself, they will keep. The dead are patient.',
 'Speak with Moratari.', 0),
-- II-A, Horde: Agmar's Hammer's errands around the Forsaken's blight (the taunka's oath, the
-- frost wyrms, the Icemist chief in the nerubians' pit).
(77211, 2, 25, 20, 65, 5, 0, 0, 690,
 'The Road to Angrathar',
 'Speak with Moratari again to return to Overlord Agmar, ready for the Kor''kron Vanguard.',
 'The blight works. The apothecaries are delighted, which ought to worry everyone. Agmar''s Hammer has a great deal more for you: an oath for the taunka to swear, frost wyrms to bring down, a high chief to pull out of a nerubian pit.$B$BHistory cannot seem to agree on the number of wagons. It is very certain about what happens at Angrathar. Overlord Agmar is sending his best to the Kor''kron Vanguard, and Saurfang''s son will not wait for stragglers.$B$BIf you would rather swear every oath yourself, Agmar will still have work. He always has work.',
 'Speak with Moratari.', 0),
-- II-B: no skip. After the Wrathgate, by Alexstrasza, before what she asks next.
(77202, 2, 29, 20, 65, 5, 0, 0, 1101,
 'What We Cannot Change',
 'Speak with Moratari at the Wrathgate.',
 'You saw it. Bolvar in the dragonfire, the Forsaken''s plague over everyone, Saurfang''s son fallen before the gate. I was here before you, $n. Many times.$B$BI wanted to stop it. Andormu has shown me the paths that follow if we do. There are no kind paths through this choice. Today the Alliance and the Horde learn they cannot fight the Lich King and each other at once, and that lesson matters more than any of us.$B$BThe Life-Binder has words for you. Listen to her. Then go home: the King of Stormwind is about to learn what happened here.',
 'Speak with Moratari.', 0),
(77212, 2, 29, 20, 65, 5, 0, 0, 690,
 'What We Cannot Change',
 'Speak with Moratari at the Wrathgate.',
 'You saw it. Bolvar in the dragonfire, the Forsaken''s plague over everyone, Saurfang''s son fallen before the gate. I was here before you, $n. Many times.$B$BI wanted to stop it. Andormu has shown me the paths that follow if we do. There are no kind paths through this choice. Today the Horde learns that its own can betray it, and the Alliance that the Horde can bleed for the same cause. That lesson matters more than any of us.$B$BThe Life-Binder has words for you. Listen to her. Then go to Warsong Hold: a father is waiting for news no one should have to carry.',
 'Speak with Moratari.', 0),
-- II-C (WRM-221): after the battle, the aftermath's errands, and Chapter III.
(77221, 2, 30, 20, 65, 5, 0, 0, 1101,
 'After Angrathar',
 'Speak with Moratari again to travel to Amberpine Lodge in the Grizzly Hills.',
 'The Undercity is taken. Putress is dead, Varimathras is dead, and the King of Stormwind came within a breath of starting the next war on the spot. Jaina talked him out of it. History is grateful.$B$BThere are orders after this, of course: musters, reports, a great many letters to families. We will record the names of those lost. The rest of the marching orders can wait.$B$BThe Grizzly Hills, $n. Something is wrong at the heart of an old forest, the people of Silverbrook are not what they seem, and a troll named Drakuru is making friends. Chapter III begins at Amberpine Lodge.',
 'Speak with Moratari.', 0),
(77222, 2, 30, 20, 65, 5, 0, 0, 690,
 'After Angrathar',
 'Speak with Moratari again to travel to Conquest Hold in the Grizzly Hills.',
 'Lordaeron''s throne room is the Horde''s again. Varimathras is dead, Putress is dead, and your warchief stood where Arthas killed his father and watched the Horde bury its own. The Alliance came within a breath of starting the next war. Jaina stopped it. History is grateful.$B$BThere are orders after this, of course: musters, reports, a great many words for the families. We will record the names of those lost. The rest of the marching orders can wait.$B$BThe Grizzly Hills, $n. Something stirs beneath an old forest, the bear god''s children are restless, and a troll named Drakuru is making friends. Chapter III begins at Conquest Hold.',
 'Speak with Moratari.', 0);

DELETE FROM `quest_template_addon` WHERE `ID` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES
(77201, 12309), (77211, 12221), (77202, 12499), (77212, 12500), (77221, 13377), (77222, 13267);

DELETE FROM `quest_offer_reward` WHERE `ID` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`, `VerifiedBuild`) VALUES
(77201, 1, 'Hold on to something. The High Commander does not like to be kept waiting.', 0),
(77211, 1, 'Hold on. Saurfang the Younger is a good man to stand beside. Remember that.', 0),
(77202, 1, 'Go on. The Life-Binder is waiting, and Stormwind will not wait much longer.', 0),
(77212, 1, 'Go on. The Life-Binder is waiting, and so is a father at Warsong Hold.', 0),
(77221, 1, 'Hold on. The Grizzly Hills are kinder than the Dragonblight, which is not saying much.', 0),
(77222, 1, 'Hold on. The Grizzly Hills are kinder than the Dragonblight, which is not saying much.', 0);

DELETE FROM `creature_queststarter` WHERE `quest` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(@MORATARI, 77201), (@MORATARI, 77202), (@MORATARI, 77211), (@MORATARI, 77212), (@MORATARI, 77221), (@MORATARI, 77222);
DELETE FROM `creature_questender` WHERE `quest` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(@MORATARI, 77201), (@MORATARI, 77202), (@MORATARI, 77211), (@MORATARI, 77212), (@MORATARI, 77221), (@MORATARI, 77222);

DELETE FROM `remix_bridge` WHERE `quest` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `remix_bridge` (`quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped`) VALUES
-- Before High Commander Halford Wyrmbane's Into Hostile Territory, and Overlord Agmar's The
-- Kor'kron Vanguard!
(77201, 12325, 2, 571, 3764.6, -772.4, 196.1, 5.54, '12311 12312 12319 12320 12321 12272 12275 12276 12277 12281'),
(77211, 12224, 2, 571, 3842.4, 1489.6, 92.1, 4.71, '11983 12008 12053 12071 12072 12064 12069 12140'),
-- At the Wrathgate: nothing skipped, nowhere to go.
(77202, 13347, 2, 65535, 0, 0, 0, 0, ''),
(77212, 13242, 2, 65535, 0, 0, 0, 0, ''),
-- To Chapter III's start for each side (remix_chapter's): Amberpine Lodge's Lieutenant Dumont, and
-- Conquest Hold, where Conqueror Krenna's The Conqueror's Task begins the Horde's chapter (it asks
-- for a breadcrumb from the Dragonblight, To Conquest Hold, But Be Careful!, which WRM-222 stands
-- for).
(77221, 12292, 3, 571, 3446.4, -2754.1, 199.4, 3.90, ''),
(77222, 12468, 3, 571, 3258.9, -2263.1, 114.4, 0.50, '12487');

-- Into Hostile Territory opens with either II-A or both of the block's ends (The Scourge war
-- machine, and A Righteous Sermon). Their NextQuestID made the block's ends its only way in; the
-- conditions say it now.
UPDATE `quest_template_addon` SET `NextQuestID` = 0 WHERE `ID` IN (12281, 12321);
-- The Kor'kron Vanguard!: either II-A, or the four camps' last quests as the world has it.
-- The Conqueror's Task: either WRM-222 or the breadcrumb.
UPDATE `quest_template_addon` SET `PrevQuestID` = 0 WHERE `ID` = 12468;
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `SourceEntry` IN (12325, 12224, 12468);
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`,
  `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(19, 0, 12325, 0, 0, 8, 0, 77201, 0, 0, 0, 0, 0, '', 'Wrath Remix: Into Hostile Territory after The Road to Angrathar'),
(19, 0, 12325, 0, 1, 8, 0, 12281, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after Understanding the Scourge War Machine'),
(19, 0, 12325, 0, 1, 8, 0, 12321, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... and A Righteous Sermon'),
(19, 0, 12224, 0, 0, 8, 0, 77211, 0, 0, 0, 0, 0, '', 'Wrath Remix: The Kor''kron Vanguard! after The Road to Angrathar'),
(19, 0, 12224, 0, 1, 8, 0, 12008, 0, 0, 0, 0, 0, '', 'Requires quest rewarded'),
(19, 0, 12224, 0, 1, 8, 0, 12072, 0, 0, 0, 0, 0, '', 'Requires quest rewarded'),
(19, 0, 12224, 0, 1, 8, 0, 12140, 0, 0, 0, 0, 0, '', 'Requires quest rewarded'),
(19, 0, 12224, 0, 1, 8, 0, 12221, 0, 0, 0, 0, 0, '', 'Requires quest rewarded'),
(19, 0, 12468, 0, 0, 8, 0, 77222, 0, 0, 0, 0, 0, '', 'Wrath Remix: The Conqueror''s Task after After Angrathar'),
(19, 0, 12468, 0, 1, 8, 0, 12487, 0, 0, 0, 0, 0, '', 'Wrath Remix: ... or after To Conquest Hold, But Be Careful!');

DELETE FROM `remix_quest_reward` WHERE `quest` IN (77201, 77202, 77211, 77212, 77221, 77222);
INSERT INTO `remix_quest_reward` (`quest`, `bronze`) VALUES
(77201, 40), (77211, 40), (77202, 25), (77212, 25), (77221, 75), (77222, 75);
