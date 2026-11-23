# Pack inventory: the animation, toy, furniture and layer packages

What is in the Sims 4 packages the Pack import takes, read by `tools/pack_inventory.py` (`python3 -I tools/pack_inventory.py PATH...`) and by the app's own reader (`pack/PackClassifier.kt`, `props/SimsObject.kt`). A package is told by its resources, never by its file name. The files are the player's own; none is in the repository. Counts are from the nineteen packages the importer was built and checked on (LPGS, CherryPie, FEMDOMANIA, 0nizu).

## The packages

| Package | Kind | Size | Resources | Clips | Stages | Objects | SNR audio |
|---|---|---:|---:|---:|---:|---:|---:|
| 0nizu_SexToys_Tuning | other | 0.0 MB | 22 | 0 | 0 | 0 | 0 |
| CherryPie_CumMesh | body-layer | 0.1 MB | 42 | 0 | 0 | 1 | 0 |
| WW_CherryPie_Animation_CumMesh | animations | 0.1 MB | 6 | 2 | 1 | 0 | 0 |
| FEMDOMANIA_Bondage_and_Torture | animations | 0.2 MB | 52 | 25 | 7 | 0 | 0 |
| WW_CherryPie_Animation | animations | 0.5 MB | 12 | 5 | 2 | 0 | 0 |
| CherryPie_BondageBed | furniture | 6.9 MB | 94 | 0 | 0 | 10 | 0 |
| FEMDOMANIA_Foot_Fetish | animations | 0.5 MB | 142 | 70 | 26 | 0 | 0 |
| FEMDOMANIA_Cuckold | animations | 0.2 MB | 68 | 33 | 10 | 0 | 0 |
| [0nizu] Clit Vibrator | props | 2.2 MB | 71 | 2 | 6 | 5 | 1 |
| [0nizu] Dildo 01 | props | 2.7 MB | 97 | 2 | 2 | 9 | 0 |
| [0nizu] Dildo 02 - Double Ended | props | 4.7 MB | 75 | 2 | 2 | 6 | 0 |
| [0nizu] Electric penis masturbator | props | 3.8 MB | 79 | 2 | 2 | 4 | 8 |
| [0nizu] Male Torso Sex Doll | props | 1.2 MB | 62 | 4 | 4 | 3 | 0 |
| [0nizu] Realistic Handcuffs | props | 2.9 MB | 57 | 3 | 1 | 3 | 0 |
| [0nizu] Thick Sex Doll v2 | props | 2.4 MB | 61 | 2 | 2 | 4 | 0 |
| [0nizu] Thick Sex Doll | props | 1.3 MB | 55 | 2 | 3 | 3 | 0 |
| [FEMDOMANIA] New Cleopatra | animations | 0.2 MB | 64 | 31 | 15 | 0 | 0 |
| WW_LPGS_Animations | animations | 19.5 MB | 383 | 76 | 35 | 0 | 117 |
| FEMDOMANIA_Oraljob | animations | 0.2 MB | 66 | 32 | 12 | 0 | 0 |

Stages by number of people: 1: 23, 2: 74, 3: 19, 4: 9, 5: 2, 6: 1, 7: 1, 8: 1 (130 in all). A scene plays one or two people: **97 stages are played** (74 for two, 23 for one) and **33 for three to eight are not** (listed below).

What each is for:

- **LPGS** (`WW_LPGS_Animations`): 35 WickedWhims stages (76 clips) with the author's sounds (117 SNR audio files and 111 sound configurations; the app has no sound decoder yet: listed, not played), effect events (`EFFECT`, `CUM`) and two stages that name the handcuffs as a prop (`[HandCuffed]`).
- **CherryPie** (`WW_CherryPie_Animation`): the two oral stages `blowjob1`/`blowjobS` (a prop on the sloppy one). `WW_CherryPie_Animation_CumMesh` is one test stage, `CumMeshTestV2`, whose prop is the cum mesh object and whose prop clip moves it. `CherryPie_CumMesh` is that object (a 4065-vertex model with its own 16-bone rig): an animation-linked body layer, drawn only by stages that name it. `CherryPie_BondageBed` is ten colour variants of one wooden bed (23,828 triangles, 98-bone rig, a slot list): furniture, named by no stage.
- **FEMDOMANIA** (Cuckold, Bondage and Torture, Oraljob, Foot Fetish, New Cleopatra): 70 stages, 191 clips, tuning only (no objects, no audio). Many of their props are the game's own objects (a plate, a book: small numeric ids no package here defines); their prop clips are kept, the stages play without the prop.
- **0nizu's toys** (eight packages: clit vibrator, two dildos, electric masturbator, male torso doll, thick doll and v2, handcuffs): each an object package with its stages (22 in all, one or two people), the object's colour variants as separate OBJDs (the three dolls' BLACK, WHITE and PINK are OBJDs of one model with different material variants), and one prop clip per stage. `0nizu_SexToys_Tuning` is a purchase menu (a phone pie menu and a price list): kept, not used. `0nizu_SexToys_Scripts.ts4script` is the script that injects that phone menu into the game's objects (compiled Python: never run, read as file names and names only).

## Resource types per package

| Package | Resources by type |
|---|---|
| 0nizu_SexToys_Tuning | STBL ×18, TUNING ×2, SIMDATA ×1, DDS ×1 |
| CherryPie_CumMesh | STBL ×23, DDS ×4, THUMB ×4, MLOD ×2, OBJ-REF? ×1, MTBL? ×1, LITE ×1, MODL ×1, RIG ×1, SLOT ×1, FTPT ×1, COBJ ×1, OBJD ×1 |
| WW_CherryPie_Animation_CumMesh | CLIP ×2, CLHD ×2, NMAP ×1, TUNING ×1 |
| FEMDOMANIA_Bondage_and_Torture | CLIP ×25, CLHD ×25, NMAP ×1, TUNING ×1 |
| WW_CherryPie_Animation | CLIP ×5, CLHD ×5, NMAP ×1, TUNING ×1 |
| CherryPie_BondageBed | DDS ×37, STBL ×23, COBJ ×10, OBJD ×10, THUMB ×4, MLOD ×3, OBJ-REF? ×1, MTBL? ×1, LITE ×1, MODL ×1, FTPT ×1, RIG ×1, SLOT ×1 |
| FEMDOMANIA_Foot_Fetish | CLIP ×70, CLHD ×70, NMAP ×1, TUNING ×1 |
| FEMDOMANIA_Cuckold | CLIP ×33, CLHD ×33, NMAP ×1, TUNING ×1 |
| [0nizu] Clit Vibrator | THUMB ×20, STBL ×18, DDS ×7, COBJ ×5, OBJD ×5, CLIP ×2, CLHD ×2, MLOD ×1, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, NMAP ×1, SNS ×1, SNR ×1, TUNING ×1, OBJ-KEY? ×1 |
| [0nizu] Dildo 01 | THUMB ×36, STBL ×18, DDS ×11, OBJD ×9, COBJ ×9, CLIP ×2, CLHD ×2, MLOD ×1, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1, OBJ-KEY? ×1, SIMDATA ×1 |
| [0nizu] Dildo 02 - Double Ended | THUMB ×24, STBL ×18, DDS ×8, OBJD ×6, COBJ ×6, CLIP ×2, CLHD ×2, MLOD ×1, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1, OBJ-KEY? ×1 |
| [0nizu] Electric penis masturbator | STBL ×18, THUMB ×16, DDS ×12, SNR ×8, OBJD ×4, COBJ ×4, MLOD ×2, CLIP ×2, CLHD ×2, SNS ×2, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, TUNING ×1, NMAP ×1, RIG ×1, SLOT ×1, OBJ-KEY? ×1 |
| [0nizu] Male Torso Sex Doll | STBL ×18, THUMB ×12, DDS ×5, MLOD ×5, CLIP ×4, CLHD ×4, OBJD ×3, COBJ ×3, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1, OBJ-KEY? ×1 |
| [0nizu] Realistic Handcuffs | STBL ×18, THUMB ×12, DDS ×5, OBJD ×3, COBJ ×3, CLIP ×3, CLHD ×3, MLOD ×2, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1, OBJ-KEY? ×1 |
| [0nizu] Thick Sex Doll v2 | STBL ×18, THUMB ×16, DDS ×6, OBJD ×4, COBJ ×4, CLIP ×2, CLHD ×2, MLOD ×1, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1, OBJ-KEY? ×1 |
| [0nizu] Thick Sex Doll | STBL ×18, THUMB ×8, DDS ×5, MLOD ×5, CLHD ×4, OBJD ×3, COBJ ×3, CLIP ×2, LITE ×1, MODL ×1, FTPT ×1, MTBL? ×1, RIG ×1, SLOT ×1, TUNING ×1 |
| [FEMDOMANIA] New Cleopatra | CLIP ×31, CLHD ×31, NMAP ×1, TUNING ×1 |
| WW_LPGS_Animations | SNR ×117, SNS ×111, CLIP ×76, CLHD ×76, BLUEPRINT? ×1, NMAP ×1, TUNING ×1 |
| FEMDOMANIA_Oraljob | CLIP ×32, CLHD ×32, NMAP ×1, TUNING ×1 |

Types: CLIP animation (`0x6B20C4F3`), CLHD its header, TUNING the WickedWhims stage list (`0x7DF2169C`, XML), OBJD object definition, MODL/MLOD model and its LODs, RIG the object's skeleton, SLOT where Sims attach, FTPT footprint, COBJ catalog entry, DDS textures (DST5/DST1: DXT with the block fields regrouped; the app decodes them), STBL string tables, SNR audio (`0x01A527DB`), SNS its configuration (`0xFD04E3BE`), NMAP name map, LITE light, THUMB thumbnails.

## How an animation finds its props

A stage's `animation_props_list` lists props: `prop_id`, `prop_type` (`BASIC` in all of them), `prop_guids` (one or several object ids: an OBJD's *instance*, the number as a decimal; several for the colour variants) and `prop_animation_clip_name` (a CLIP that moves the object's own rig, whose root bone carries the prop's place in the animation's frame, the frame the actors' clips share). So the pairing is: stage → prop entry → object id → OBJD → MODL → MLOD (meshes), RIG (bones) and the prop clip. An OBJD stores its model, rig and slot keys with the instance's high half first. The material variant (`Set2-MaterialVariant`) is a string in the OBJD; the MLOD's material set (MTST) lists a material per variant keyed by the FNV-32 of that string. The cum mesh is the same mechanism: its stage names the object id of `CherryPie_CumMesh` and a prop clip.

| Object package | Objects | Mesh | Size (m, x fwd × y left × z up) | Stages that name it | Prop clips |
|---|---:|---|---|---|---|
| CherryPie_CumMesh | 1 | 4065 vertices, 7872 triangles | 0.01 × 0.05 × 0.01 | CherryPie:blowjobS, CumMeshTestV2 | PosePack_201906141419157145_set_1, PosePack_201906180017533040_set_5 |
| CherryPie_BondageBed | 10 | 16794 vertices, 23828 triangles | 1.35 × 0.99 × 0.60 | none (furniture) | - |
| [0nizu] Clit Vibrator | 5 | 11058 vertices, 21120 triangles | 0.13 × 0.09 × 0.04 | Clit vibrator | Lying on the floor | 0nizu_SoloClitVibrator_012024_o |
| [0nizu] Dildo 01 | 9 | 2208 vertices, 4412 triangles | 0.09 × 0.09 × 0.36 | Dildo 01 | Cowgirl | 0nizu_SoloDildo01_032025_o0 |
| [0nizu] Dildo 02 - Double Ended | 6 | 5673 vertices, 11004 triangles | 0.05 × 0.44 × 0.05 | Double Ended Dildo | Solo Masturbation | 0nizu_Dildo02DoubleEndedSoloMastur |
| [0nizu] Electric penis masturbator | 4 | 3630 vertices, 4472 triangles | 0.13 × 0.13 × 0.33 | Electric Masturbator | Standing | 0nizu_ElectricPenisMasturbator_Sta |
| [0nizu] Male Torso Sex Doll | 3 | 5122 vertices, 9733 triangles | 0.52 × 0.35 × 0.62 | BJDBs1 | 0nizu_MaleTorsoDollReverseCowgirl_ |
| [0nizu] Realistic Handcuffs | 3 | 6174 vertices, 6908 triangles | 0.08 × 0.29 × 0.03 | Handcuffed | Pronebone, pinneddownmissio2, pinneddownmissio3 | 0nizu_HandcuffedPronebone_110824_o, LPGS_pinneddowndb2handcuffed_01182, LPGS_pinneddowndb3handcuffed_01182 |
| [0nizu] Thick Sex Doll v2 | 4 | 4750 vertices, 8777 triangles | 0.41 × 0.55 × 0.29 | Thick Sex Doll V2 | Carry Standing | 0nizu_ThickSexDollV2_StandingCarry |
| [0nizu] Thick Sex Doll | 3 | 5854 vertices, 11014 triangles | 0.29 × 0.35 × 0.53 | Thick Doll | Missionary (BLACK), Thick Doll | Missionary (PINK), Thick Doll | Missionary (WHITE) | 0nizu_ThickDollMissionary_11124_o |

## Every stage

Each stage as the app reads it (`unit-tests/.../PackStageAuditTest`): the pack's category, the people, the stage kind (`anim/StageNaming`), the arousal map's class for it (`assets/arousal_map.json`: the category and the entry), the intimacy the dialogue layer takes it as (`PairKinds.of(name, category)`) and where it is tried first (furniture classes; every stage plays anywhere).

| Package | Stage | People | Category | Kind | Arousal | Intimacy | Furniture |
|---|---|---:|---|---|---|---|---|
| WW_CherryPie_Animation_CumMesh | CumMeshTestV2 | 1 | TEASING | TEASE | CLIMAX 1.0 KEEP | INTIMATE | BED |
| FEMDOMANIA_Bondage_and_Torture | ORGASM TORTURE | 2 | HANDJOB | HAND | CLIMAX 1.0 KEEP | INTIMATE | anywhere |
| FEMDOMANIA_Bondage_and_Torture | TEASE TORTURE | 2 | HANDJOB | HAND | TEASING 0.55 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Bondage_and_Torture | WASHING FLOOR | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Bondage_and_Torture | FEEL HER | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| FEMDOMANIA_Bondage_and_Torture | HOLD BREATH | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Bondage_and_Torture | SHACKLED PUNISHMENT | 1 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | TABLE |
| FEMDOMANIA_Bondage_and_Torture | LOOK AND FEEL | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| WW_CherryPie_Animation | Blowjob | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| WW_CherryPie_Animation | Blowjob Sloppy | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | FOOT CLEANING | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | BED |
| FEMDOMANIA_Foot_Fetish | FEET LOVE | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | SELF FEEDING | 1 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | GODDESS OF RELAX | 4 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR |
| FEMDOMANIA_Foot_Fetish | FEET CAGE | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR |
| FEMDOMANIA_Foot_Fetish | SO YUMMY | 3 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | TABLE |
| FEMDOMANIA_Foot_Fetish | FOOT MASSAGE | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | SUN BATH | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | MEAL TIME | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR |
| FEMDOMANIA_Foot_Fetish | LAZY READING | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR/COUCH |
| FEMDOMANIA_Foot_Fetish | WORSHIP HER FEET | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | BED |
| FEMDOMANIA_Foot_Fetish | PICNIC | 3 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | FOOT TABLE (2P) | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | FOOT TABLE (3P) | 3 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | FOOT TABLE (4P) | 4 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | MERRY CHRISTMAS (2P) | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | MERRY CHRISTMAS (3P) | 3 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | MERRY CHRISTMAS (4P) | 4 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | CAKE TIME | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | TABLE |
| FEMDOMANIA_Foot_Fetish | WORSHIPING BEUTIFUL FEET | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | SMEEL OF HER FEET | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | WORSHIPING AND SNIFFING | 4 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | COUCH |
| FEMDOMANIA_Foot_Fetish | HUMAN OTTOMAN | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Foot_Fetish | FORBIDDEN FANTASIES | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | BED |
| FEMDOMANIA_Foot_Fetish | FORBIDDEN FANTASIES (DREAMS COME TRUE) | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | BED |
| FEMDOMANIA_Foot_Fetish | DOG TRAINING | 2 | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR |
| FEMDOMANIA_Cuckold | CUCKOLD'S SERVICE | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| FEMDOMANIA_Cuckold | CUCKOLD'S WEDDING | 3 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| FEMDOMANIA_Cuckold | CUCKOLD'S WEEKDAYS | 3 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | BED |
| FEMDOMANIA_Cuckold | JUST WATCH | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| FEMDOMANIA_Cuckold | NO WAY OUT | 3 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |
| FEMDOMANIA_Cuckold | DREAMS COME TRUE | 3 (not played) | HANDJOB | HAND | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| FEMDOMANIA_Cuckold | PILLOW FOR SEX | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| FEMDOMANIA_Cuckold | FRIDAY RODEO | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| FEMDOMANIA_Cuckold | FRIDAY RODEO (With fruit service) | 4 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| FEMDOMANIA_Cuckold | BREATH OF SATISFACTION | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Clit Vibrator | Clit vibrator | Lying on the floor | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Clit Vibrator | Clit vibrator | Lying on the floor | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Clit Vibrator | Clit vibrator | Missionary | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Clit Vibrator | Clit vibrator | Blowjob | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| [0nizu] Clit Vibrator | Clit vibrator | New Discovery S1 | 1 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| [0nizu] Clit Vibrator | Clit vibrator | New Discovery S2 | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Dildo 01 | Dildo 01 | Cowgirl | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Dildo 01 | Dildo 01 | Cowgirl | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Dildo 02 - Double Ended | Double Ended Dildo | Solo Masturbation | 1 | VAGINAL | VAGINAL | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| [0nizu] Dildo 02 - Double Ended | Double Ended Dildo | Solo Masturbation | 1 | VAGINAL | VAGINAL | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| [0nizu] Electric penis masturbator | Electric Masturbator | Standing | 1 | HANDJOB | HAND | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| [0nizu] Electric penis masturbator | Electric Masturbator | Standing | 1 | HANDJOB | HAND | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| [0nizu] Male Torso Sex Doll | Male Doll | Reverse Cowgirl (BLACK) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Male Torso Sex Doll | Male Doll | Reverse Cowgirl (WHITE) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Male Torso Sex Doll | Male Doll | Reverse Cowgirl (PINK) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Male Torso Sex Doll | Male Doll | Cowgirl | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Realistic Handcuffs | Handcuffed | Pronebone | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Thick Sex Doll v2 | Thick Sex Doll V2 | Carry Standing | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Thick Sex Doll v2 | Thick Sex Doll V2 | Carry Standing | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| [0nizu] Thick Sex Doll | Thick Doll | Missionary (WHITE) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Thick Sex Doll | Thick Doll | Missionary (BLACK) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [0nizu] Thick Sex Doll | Thick Doll | Missionary (PINK) | 1 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(8P) | 8 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(7P) | 7 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(6P) | 6 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(5P) | 5 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(4P) | 4 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(3P) | 3 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | WORSHIP YOUR QUEEN(2P) | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | QUEEN'S CLEAN FEET | 4 (not played) | FOOTJOB | FOOT | FOOTJOB 0.9 NONE | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | QUEEN'S FAVOR | 4 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | CHAIR |
| [FEMDOMANIA] New Cleopatra | HUMAN THRONE | 5 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | anywhere |
| [FEMDOMANIA] New Cleopatra | QUEEN'S REST | 1 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | COUCH |
| [FEMDOMANIA] New Cleopatra | QUEEN'S REST (WITH A GUARD) | 2 | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | COUCH |
| [FEMDOMANIA] New Cleopatra | QUEEN'S PLEASURE (WITH A GUARD) | 3 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |
| [FEMDOMANIA] New Cleopatra | QUEEN'S PLEASURE | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |
| [FEMDOMANIA] New Cleopatra | HONE SPHINX | 3 (not played) | TEASING | TEASE | TEASING 0.55 NONE | INTIMATE | CHAIR |
| WW_LPGS_Animations | Peace Girl BlowJob 1 | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Peace Girl BlowJob 2 | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Peace Girl BlowJob 3 | 2 | CLIMAX | CLIMAX | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Missionary and kissing | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Stand and carry | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Stand and carry [Ahegao loop] | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Rough anal legs up | 2 | ANAL | ANAL | ANAL 1.0 ANAL | INTIMATE | BED |
| WW_LPGS_Animations | Side bed doggystyle | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Lying down handjob | 2 | HANDJOB | HAND | HANDJOB 0.95 MANUAL | INTIMATE | BED |
| WW_LPGS_Animations | Anal upside down | 2 | ANAL | ANAL | ANAL 1.0 ANAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Standing sideway | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | MIRROR |
| WW_LPGS_Animations | Rough kneeling doggystyle | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Table bent over doggystyle | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | TABLE |
| WW_LPGS_Animations | Threesome double penetration | 3 (not played) | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Standing still from behind 1 | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Standing still from behind 2 | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Standing still from behind 3 | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | She's feelin' it | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Face Fuck | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | BED |
| WW_LPGS_Animations | Femdom Cowgirl | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Pinned Down Missionary 1 | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Pinned Down Missionary 2 [HandCuffed] | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Pinned Down Missionary 3 [Dialogue+HandCuffed] | 2 | CLIMAX | CLIMAX | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Used Like A Fuck Doll 1 [Ahegao] | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Used Like A Fuck Doll 2 [HandGagged] | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Jerk him off | 2 | HANDJOB | HAND | HANDJOB 0.95 MANUAL | INTIMATE | CHAIR/COUCH |
| WW_LPGS_Animations | Deepthroat From Above | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | BED |
| WW_LPGS_Animations | FDom Floor Chairman "Oh Fuck" | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Doggy and Mouth fingering [Ahegao] | 2 | VAGINAL | VAGINAL | HANDJOB 0.95 MANUAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Flexible Sideway Missionary | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Rough Deepthroat On Bed | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | BED |
| WW_LPGS_Animations | Slow To Fast Desk Missionary | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | TABLE |
| WW_LPGS_Animations | Hentai Cowgirl [Ahegao] | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| WW_LPGS_Animations | Rough And Slow Missionary | 2 | VAGINAL | VAGINAL | VAGINAL 1.0 VAGINAL | INTIMATE | anywhere |
| WW_LPGS_Animations | Throated "Your Cum Tasted So Good" [Dialogue] | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Oraljob | PLEASURE FOR MISTRESS | 4 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | CHAIR |
| FEMDOMANIA_Oraljob | FLOOR PLEASURE | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Oraljob | ORAL SLAVE DUTY | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |
| FEMDOMANIA_Oraljob | MISTRESS ONLINE | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | CHAIR |
| FEMDOMANIA_Oraljob | DINNER IS SERVED | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | TABLE |
| FEMDOMANIA_Oraljob | WARMING UP | 3 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |
| FEMDOMANIA_Oraljob | HAPPY NEW YEAR | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Oraljob | GARRISON'S CATCH | 3 (not played) | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Oraljob | FACE'S COWGIRL | 2 | ORALJOB | ORAL | VAGINAL 1.0 VAGINAL | INTIMATE | BED |
| FEMDOMANIA_Oraljob | SHE IS ALWAYS ON TOP | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | anywhere |
| FEMDOMANIA_Oraljob | GOOD MORNING | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | BED |
| FEMDOMANIA_Oraljob | WATCHING THE EVENING SHOW | 2 | ORALJOB | ORAL | ORALJOB 1.0 ORAL | INTIMATE | COUCH |

## Not done

See the README's "Pack import" section: what is drawn rigid, the stages for three or more people, audio, base-game props, and what was not run on a phone.
