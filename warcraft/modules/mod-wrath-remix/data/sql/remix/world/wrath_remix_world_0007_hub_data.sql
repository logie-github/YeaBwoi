-- The Bazaar's records: the seven chapters, Zephyr's roads and Alurmi's wares.

DELETE FROM `remix_chapter`;
INSERT INTO `remix_chapter` (`chapter`, `name`, `level_min`, `level_max`, `alliance_map`, `alliance_x`, `alliance_y`, `alliance_z`,
  `horde_map`, `horde_x`, `horde_y`, `horde_z`, `summary`) VALUES
(1, 'The Northrend Invasion', 10, 20, 571, 2269.5, 5173.7, 11.1, 571, 2759.3, 6094.6, 77.9,
 'The fleets land on two shores: Borean Tundra and Howling Fjord.'),
(2, 'The Wrathgate', 20, 32, 571, 3712.4, -694.9, 215.6, 571, 3865.9, 1525.6, 90.0,
 'The Dragonblight, Wyrmrest, and the gate of Icecrown.'),
(3, 'The Curse of Grizzly Hills', 32, 42, 571, 3446.4, -2754.1, 199.4, 571, 3258.9, -2263.1, 114.4,
 'Worgen, the bear god Ursoc, and a troll named Drakuru.'),
(4, 'The Fallen Empire', 42, 52, 571, 5521.6, -2672.3, 304.0, 571, 5521.6, -2672.3, 304.0,
 'Zul''Drak: the Drakkari empire devours its own gods.'),
(5, 'The Lifewarden''s Warning', 52, 60, 571, 5596.1, 5824.4, -67.7, 571, 5596.1, 5824.4, -67.7,
 'Sholazar Basin, Freya''s garden, and the titans'' waygate.'),
(6, 'The Secrets of Ulduar', 60, 70, 571, 6186.8, -1052.9, 410.2, 571, 6186.8, -1052.9, 410.2,
 'The Storm Peaks: Thorim, Loken, and the halls beneath the mountain.'),
(7, 'Icecrown: The Last Necessary Battle', 70, 80, 571, 6164.5, -61.3, 388.2, 571, 6164.5, -61.3, 388.2,
 'The Argent Crusade, the Ebon Blade, the Frozen Halls and the Lich King.');

DELETE FROM `remix_travel`;
INSERT INTO `remix_travel` (`id`, `name`, `side`, `min_level`, `map`, `x`, `y`, `z`, `o`) VALUES
(1, 'The Infinite Bazaar', 0, 1, 571, 5700.0, 1007.0, 174.6, 1.57),
(2, 'Dalaran', 0, 1, 571, 5813.9, 449.1, 658.8, 1.6),
(3, 'The Caverns of Time', 0, 1, 1, -8360.7, -4327.7, -208.3, 1.6),
(10, 'Valiance Keep, Borean Tundra', 1, 10, 571, 2269.5, 5173.7, 11.1, 0.5),
(11, 'Warsong Hold, Borean Tundra', 2, 10, 571, 2759.3, 6094.6, 77.9, 3.5),
(12, 'Valgarde, Howling Fjord', 1, 10, 571, 567.4, -5011.0, 11.5, 4.5),
(13, 'Vengeance Landing, Howling Fjord', 2, 10, 571, 1918.6, -6175.9, 24.4, 2.9),
(20, 'Wintergarde Keep, Dragonblight', 1, 18, 571, 3712.4, -694.9, 215.6, 3.1),
(21, 'Agmar''s Hammer, Dragonblight', 2, 18, 571, 3865.9, 1525.6, 90.0, 3.1),
(22, 'Wyrmrest Temple, Dragonblight', 0, 18, 571, 3653.2, 247.6, 52.3, 1.6),
(30, 'Amberpine Lodge, Grizzly Hills', 1, 30, 571, 3446.4, -2754.1, 199.4, 1.6),
(31, 'Conquest Hold, Grizzly Hills', 2, 30, 571, 3258.9, -2263.1, 114.4, 1.6),
(40, 'The Argent Stand, Zul''Drak', 0, 40, 571, 5521.6, -2672.3, 304.0, 1.6),
(50, 'Nesingwary Base Camp, Sholazar Basin', 0, 50, 571, 5596.1, 5824.4, -67.7, 1.6),
(60, 'K3, The Storm Peaks', 0, 58, 571, 6186.8, -1052.9, 410.2, 1.6),
(70, 'The Argent Vanguard, Icecrown', 0, 68, 571, 6164.5, -61.3, 388.2, 1.6);

-- category: 1 heirloom armor, 5 heirloom weapons, 2 mounts, 3 companions, 4 supplies (a menu holds 32).
DELETE FROM `remix_shop`;
INSERT INTO `remix_shop` (`id`, `category`, `item`, `count`, `price`, `side`) VALUES
-- Heirlooms: they grow with you, the best help there is from 10 to 80.
(1, 1, 42949, 1, 300, 0), (2, 1, 42950, 1, 300, 0), (3, 1, 42951, 1, 300, 0), (4, 1, 42952, 1, 300, 0),
(5, 1, 42984, 1, 300, 0), (6, 1, 42985, 1, 300, 0), (7, 1, 44099, 1, 300, 0), (8, 1, 44100, 1, 300, 0),
(9, 1, 44101, 1, 300, 0), (10, 1, 44102, 1, 300, 0), (11, 1, 44103, 1, 300, 0), (12, 1, 44105, 1, 300, 0),
(13, 1, 44107, 1, 300, 0),
(20, 1, 48677, 1, 500, 0), (21, 1, 48683, 1, 500, 0), (22, 1, 48685, 1, 500, 0), (23, 1, 48687, 1, 500, 0),
(24, 1, 48689, 1, 500, 0), (25, 1, 48691, 1, 500, 0),
(30, 5, 42943, 1, 500, 0), (31, 5, 42944, 1, 500, 0), (32, 5, 42945, 1, 500, 0), (33, 5, 42946, 1, 500, 0),
(34, 5, 42947, 1, 500, 0), (35, 5, 42948, 1, 500, 0), (36, 5, 44091, 1, 500, 0), (37, 5, 44092, 1, 500, 0),
(38, 5, 44093, 1, 500, 0), (39, 5, 44094, 1, 500, 0), (40, 5, 44095, 1, 500, 0), (41, 5, 44096, 1, 500, 0),
(42, 5, 48716, 1, 500, 0), (43, 5, 48718, 1, 500, 0),
(50, 1, 42991, 1, 400, 0), (51, 1, 42992, 1, 400, 0),
-- Mounts: riding is taught for free as you level (20, 40, 60, 70, 77).
(60, 2, 43958, 1, 1500, 1), (61, 2, 44080, 1, 1500, 2), (62, 2, 44223, 1, 1500, 1), (63, 2, 44224, 1, 1500, 2),
(64, 2, 43962, 1, 1500, 0),
(70, 2, 43951, 1, 4000, 0), (71, 2, 43952, 1, 4000, 0), (72, 2, 43953, 1, 4000, 0), (73, 2, 43955, 1, 4000, 0),
(74, 2, 43954, 1, 5000, 0), (75, 2, 43986, 1, 5000, 0), (76, 2, 44178, 1, 5000, 0), (77, 2, 44151, 1, 6000, 0),
(78, 2, 44707, 1, 6000, 0), (79, 2, 44168, 1, 8000, 0),
-- Companions.
(90, 3, 44721, 1, 200, 0), (91, 3, 39896, 1, 200, 0), (92, 3, 20769, 1, 200, 0), (93, 3, 25535, 1, 300, 0),
(94, 3, 35504, 1, 300, 0), (95, 3, 38628, 1, 300, 0),
-- Supplies.
(100, 4, 41599, 1, 100, 0);
