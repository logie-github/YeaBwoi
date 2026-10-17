-- Wrath Remix (STORY.md, "Before the turn"): until Sylvanas and Alleria stop him, Eternus is
-- folding the war his way, quietly. Some of the original quests on the story's path through the
-- Borean Tundra and the Dragonblight turn in a little differently than history has them: an NPC
-- notices a small thing that went better than it should have (their turn-in text), and Eternus
-- whispers that it was him (remix_quest_note: only before WRM-207, The Weight of Time; WrathRemix.cpp).
-- The originals are kept in remix_original_reward_text, and each text is made from its original.
CREATE TABLE IF NOT EXISTS `remix_original_reward_text` (
  `ID` INT UNSIGNED NOT NULL,
  `RewardText` TEXT,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO `remix_original_reward_text` (`ID`, `RewardText`)
SELECT `ID`, `RewardText` FROM `quest_offer_reward` WHERE `ID` IN (11672, 11727, 11797, 11889, 11897, 11956, 12237, 12253, 12309, 11585, 11586, 11595, 11602, 11636, 11652, 12034, 12218, 12221);
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'Good timing, too. The transport that went down off the point last night limped in at dawn instead, every soul aboard. Her captain swears the storm parted in front of him like a curtain. I''ll take a miracle where I can find one.') WHERE o.`ID` = 11672;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'Odd thing, though. The Scourge hit the south wall before dawn, and someone had already shifted two catapults over to meet them. Wasn''t me, and nobody else will own up to it.') WHERE o.`ID` = 11727;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'Medic tells me we didn''t lose a single soul on the barricade today. Twenty years I''ve been doing this. That doesn''t happen.') WHERE o.`ID` = 11797;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'And those frost wyrms? Turned back halfway through their run. Just wheeled about like they''d forgotten what they came for.') WHERE o.`ID` = 11889;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'The engineers found the last of the sinkholes already caved in on itself, before anything could crawl out of it. I''m not one to look a gift gryphon in the beak.') WHERE o.`ID` = 11897;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'The lich''s guards were half what Corporal Venn reported. Either the corporal can''t count, or someone thinned them out before you got here.') WHERE o.`ID` = 11956;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'And the strangest thing: the roost that cracked through on the first night is still standing. Every handler down there swore it would come down.') WHERE o.`ID` = 12237;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = REPLACE(r.`RewardText`, '<Halford says a prayer for the deceased.>', '<Halford says a prayer for the deceased.>$B$BFewer than we feared. Far fewer. The healers were ready for twice the wounded they''ve seen.') WHERE o.`ID` = 12253;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = REPLACE(r.`RewardText`, 'I barely made it out last time - which is what prompted', 'I barely made it out last time, me and my squire both. The beam that should''ve flattened us came down a breath late. Which is what prompted') WHERE o.`ID` = 12309;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', '<Garrosh glances at the sky.>$B$BThe shamans say the wind turned on the Scourge at dawn and blew their filth back into their own lines. Good. Let them choke on it.') WHERE o.`ID` = 11585;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', '<Garrosh glances at the sky.>$B$BThe shamans say the wind turned on the Scourge at dawn and blew their filth back into their own lines. Good. Let them choke on it.') WHERE o.`ID` = 11586;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'Strange morning. The stretch of wall that cracked in the last assault was braced before the masons ever got to it. Nobody''s owning up.') WHERE o.`ID` = 11595;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'One thing''s going our way, at least. The supply wagons from the coast got through without a scratch. First time since we landed.') WHERE o.`ID` = 11602;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'Thinner today, though. Lifted right off the tanks for an hour this mornin'', like somebody pulled a sheet off ''em.') WHERE o.`ID` = 11636;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = REPLACE(r.`RewardText`, 'You are dismissed.', 'And the tauren scouts I sent onto the plains came back early, every last one of them. A fog hid them from the abominations, they say. Lucky. $B$BYou are dismissed.') WHERE o.`ID` = 11652;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'And the wind riders came back from the morning sortie, every one of them. Somebody moved the sortie up an hour without telling me. I''d shake their hand.') WHERE o.`ID` = 12034;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', 'One disappointment: not a single living farmer anywhere near the test site. Someone moved them all out the night before. I do so hate it when the subjects are tipped off.') WHERE o.`ID` = 12218;
UPDATE `quest_offer_reward` o JOIN `remix_original_reward_text` r ON r.`ID` = o.`ID` SET o.`RewardText` = CONCAT(TRIM(TRAILING '\n' FROM r.`RewardText`), '$B$B', '<Doctor Malefious counts the vials again, and frowns.>$B$BOne short. You didn''t... no. No, of course you didn''t.') WHERE o.`ID` = 12221;

CREATE TABLE IF NOT EXISTS `remix_quest_note` (
  `quest` INT UNSIGNED NOT NULL,
  `text` TEXT NOT NULL,
  PRIMARY KEY (`quest`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DELETE FROM `remix_quest_note` WHERE `quest` IN (11672, 11727, 11797, 11889, 11897, 11956, 12237, 12253, 12309, 11585, 11586, 11595, 11602, 11636, 11652, 12034, 12218, 12221);
INSERT INTO `remix_quest_note` (`quest`, `text`) VALUES
(11672, 'That transport was lost with all hands, champion. Today it was not. No one will ever ask why, and that is how I prefer it.'),
(11727, 'The catapults stood on the wrong wall. I moved them in the night. The south wall keeps the men it would have lost.'),
(11797, 'A young man named Brennick died on that barricade, with his letters home still in his pack. He will post them tomorrow.'),
(11889, 'A frost wyrm only has to be a minute late to miss. I made them late.'),
(11897, 'There was a fourth sinkhole, champion. You will never see it. That is rather the point.'),
(11956, 'I am not in the habit of fighting liches. I am in the habit of being early.'),
(12237, 'That roost fell, and eleven gryphons with it. Things hold, when someone wants them to badly enough.'),
(12253, 'Twelve names fewer on Wintergarde''s stones. The bronze would call that a wound in time. I call it a good night''s work.'),
(12309, 'His squire''s name is Tobin. The beam did not wait for him, the first time. Today it did.'),
(11585, 'The wind blew the other way, champion, and the sick tents of Warsong Hold were full for a week. Weather is the easiest thing in the world to change. No one ever thanks it.'),
(11586, 'The wind blew the other way, champion, and the sick tents of Warsong Hold were full for a week. Weather is the easiest thing in the world to change. No one ever thanks it.'),
(11595, 'That wall came down, and the grunts beneath it with it. I braced it in the night. Let Razgor wonder.'),
(11602, 'Those wagons burned. I sent the Scourge''s scouts the long way round. Small kindnesses, champion.'),
(11636, 'The mist hides things that should not be hidden. I lifted it, for an hour. It was all I could spare.'),
(11652, 'Six tauren went out onto those plains, and four came home. Today, six. I am told the fog was very convincing.'),
(12034, 'Juktok lost two riders that morning, flying into the sun. I moved the sortie an hour. The sun stayed where it was.'),
(12218, 'That blight drifted over Wintergarde''s farms, the first time. I emptied them the night before. You will see it again, champion. When you do, I would like it to find no one waiting.'),
(12221, 'I took one vial. I want to know exactly what it is I mean to stop.');
