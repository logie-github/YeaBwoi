-- Keep existing worlds in sync with the client narration records.
REPLACE INTO `soundentries_dbc` (`ID`, `SoundType`, `Name`, `File_1`, `File_2`, `File_3`, `File_4`, `File_5`, `File_6`, `File_7`, `File_8`, `File_9`, `File_10`, `Freq_1`, `Freq_2`, `Freq_3`, `Freq_4`, `Freq_5`, `Freq_6`, `Freq_7`, `Freq_8`, `Freq_9`, `Freq_10`, `DirectoryBase`, `Volumefloat`, `Flags`, `MinDistance`, `DistanceCutoff`, `EAXDef`, `SoundEntriesAdvancedID`) VALUES
(60001, 31, 'VoidElfFlyByNarration', 've.mp3', '', '', '', '', '', '', '', '', '', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'Sound\\CinematicVoices', 0.6899999976158142, 0, 8.0, 45.0, 0, 0),
(60002, 31, 'NightborneFlyByNarration', 'nb.mp3', '', '', '', '', '', '', '', '', '', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'Sound\\CinematicVoices', 0.6899999976158142, 0, 8.0, 45.0, 0, 0);
REPLACE INTO `cinematiccamera_dbc` (`ID`, `model`, `soundEntry`, `locationX`, `locationY`, `locationZ`, `rotation`) VALUES
(9011, 'Cameras\\FlybyDraenei.mdx', 60001, -3963.27001953125, -13938.7001953125, 99.68879699707031, 5.201079845428467),
(9012, 'Cameras\\FlybyUndead.mdx', 60002, 1658.5799560546875, 1662.9100341796875, 141.23399353027344, 3.141590118408203);
DELETE FROM `soundentries_dbc` WHERE `ID` IN (910001, 910002);
