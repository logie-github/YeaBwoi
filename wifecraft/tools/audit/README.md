# The placement audit

This tool checks every animation here, in every place the app can play it, for a set of body pairs. It looks for bodies inside each other or on one axis, bodies inside furniture, bodies floating over the surface they should rest on, and anything under the floor. It measures the real skinned meshes frame by frame, and draws contact sheets to look at.

Everything runs on the desktop only (Mesa EGL). Nothing here has run on a phone.

## Run it

```
wifecraft/tools/audit/build.sh                                  # builds engine-test/build/audit
wifecraft/tools/audit/run.sh FILES_DIR LOOKS_DIR POSES_DIR OUT_DIR
```

The arguments:

- **`FILES_DIR`**: the game files, each named by its file id. It must hold the locations used here (the Lion's Pride Inn, the Brill house, Thunderbrew and the Embassy as the stage's floor), the test couch 6390430, and every look's files.
- **`LOOKS_DIR`**: harness look files. These are the races' default looks in plain clothes: `human_m`, `human_f`, `orc_m`, `dwarf_m`, `nightelf_f`, `undead_f`, `tauren_m`, `gnome_m`, `gnome_f`, `goblin_m`, `bloodelf_m` and `draenei_f`, each ending in `.look`.
- **`POSES_DIR`**: the pose pack's clips, converted by the app's retargeter, as `pose1.anim` to `pose15.anim`.

`run.sh` also reads these environment variables:

| Variable | What it does |
| --- | --- |
| `UNIT_TESTS` | A copy of `unit-tests` to build in. |
| `AUDIT_ROOMS` | Which rooms to run. |
| `AUDIT_COUCH` | `"x z"`: the test couch's seat as `contact COUCH sit` measures it. |
| `AUDIT_SNAP=1` | Stands everyone on the floor under them. This is how the engine placed people before `kRaised`, so it reproduces the old fault. |
| `WORST` | How many of the worst cases to draw. The default is 40. |

To compare two runs of the same cases (old code against new), use:

```
python3 -I tools/audit/compare.py BEFORE_DIR AFTER_DIR OUT_DIR CASE_ID...
```

It makes one picture per case: the same cameras and moments, the BEFORE row above the AFTER row, and both runs' measures in a caption.

## The steps

1. **Plan** (`unit-tests/.../tools/PlacementAuditPlanner.kt`, a JVM test that does nothing without `AUDIT_OUT`). The app's own simulation (`SceneSimulation`) puts the people where the app would:
   - It uses `partnerOn` for a pair on furniture, `partner` on the floor and for the emote fallback, `performOn` for one pose on furniture, and `perform` on the floor.
   - The furniture comes from the location's rows and spots, as `MainActivity` and `SceneSessionScreen` give them.
   - The animations are the 15 sitting poses, nine pairs made of them (side by side, 0.6 body units apart, as an author would set them), five two-role fixtures (`anim/PoseFixtures`), the eight partner emotes and twelve solo emotes.
   - The bodies are ten races at human scale (dwarves and gnomes at their own size). The pairs are human man and woman, orc man and human woman, night elf woman and blood elf man, dwarf man and human woman, tauren man and night elf woman, undead woman and human man, and goblin man and draenei woman. Gnome pairs are used for the emotes only.
   - It writes `plan_ROOM.txt` (the commands for `engine-test/audit`) and `cases.tsv`.
2. **Measure** (`engine-test/audit.cpp`). Each case dresses the people, places them as the app's sink does (raised with `Scene::kRaised` when the simulation lifts the clip), plays the clip or emote, and at each listed moment reads the posed skinned vertices. The measures are:
   - **Bodies in each other**: each body's vertices against the other body's capsules. The capsules are fitted to its own vertices, one per bone region, with a radius at the 70th percentile of the vertex distances. Hands are left out and reported apart. The tool reports the deepest point and the share of vertices more than 2 cm inside.
   - **One axis**: the pelvises' distance across the floor (flagged under 0.25 yards) and the trunks' gap (flagged when they overlap by more than 0.12 yards).
   - **Inside furniture**: the furniture's solid is made from the model's own triangles. On a 2.5 cm grid, each vertical line's solid stretches run from an underside (a triangle facing down) to a top (facing up). The depth is the shorter way out, up or sideways, capped at 0.30 yards.
   - **Floating**: for a body that rests (sit, lie, kneel), the gap between its lowest vertex and the top under it.
   - **Under the floor**: how far the lowest vertex is below the floor.

   The tool writes `audit_ROOM.csv`, one row per moment.
3. **Report** (`report.py`). It takes each case's worst moment, ranks the worst first, and gives statistics per placement and a summary per animation in `report.txt` and `report.json`. A case is flagged at these thresholds:

   | Fault | Minor over | Bad over |
   | --- | --- | --- |
   | Bodies in each other | 0.04 yards | 0.10 yards |
   | Inside furniture | 0.04 yards | 0.10 yards |
   | Floating | 0.06 yards | 0.12 yards |
   | Under the floor | 0.03 yards | 0.08 yards |

   One axis is always bad.
4. **Look** (`renders.py`). It draws front, three-quarter and side views at a measured moment. Lying cases are seen from higher up. It lays them out one row per case with the flags: `sample/` holds one case per animation and place, and `worst/` the worst N.

## What the rules in the app are

These come from what the audit found:

- **A clip raised onto a bed or seat stays raised.** The engine's `placeActor` stood every actor on the floor under it, which dropped the fit's raise and buried bodies 0.3 to 0.8 yards in the mattress or seat. Now `Pose.lift` reaches the sink and the engine places such a person raised (`kRaised`).
- **Flesh under joints.** No joint the body rests on may come nearer its support than the flesh around it (`RequirementClassifier.groundLift`, `ActorRequirement.liftUnits`). On the floor this raises the clip; on furniture, the fit puts the support lower by that much.
- **Floor sits and seat sits.** A sit with the hips more than 0.12 m up needs a seat (`FLOOR_SIT_MAX`), because on the bare floor it hung in the air.
- **A sitting body's feet must be off the solid it sits on.**
- **Two roles kept out of each other** (`simulation/PairSpacing`). The trunks, the arms and thighs against the other's trunk, and the two bodies' thighs are kept apart. The second role moves sideways, at most 0.7 yards.
- **Seats take their measured height.** A chair's or couch's spot height and the Lion's Pride chair's row (0.479 yards) are used instead of the usual figure.

## What it does not cover

- **No real partner pack.** No real WickedWhims two-actor pack exists here. The pairs are the pose pack's poses set side by side and synthetic fixtures, so the rules are general but unproven on real partner packs. Run the audit on a real pack when one is supplied: convert its clips, add its pairs to the planner, and run it.
- **Races' flesh.** The rules read the clip's skeleton, the reference body's, not each race's mesh. Bulky bodies (tauren, goblin, orc) still sink at the hips and thighs into seats and floors. A mesh-based contact in the engine would be needed.
- **The fixtures' arms.** The fixtures' arms stand straight out, so their pairs still overlap at the arms.
- **Beds' real size.** The beds' slots come from the standard bed size, not the model's. The real beds measured here are wider, so a sit on the edge lands inside the mattress and the shins go into it. The furniture rows would need the measured extents.
- **Emote spacing.** The game's own emotes are played as the game made them. The pair's 1.2-yard spacing keeps them apart, so the Kiss doesn't meet.
- **Single floor poses.** A single pose ordered on the bare floor (`perform`, no furniture) is not raised.
- **The test couch.** The couch is not in any location; it is the test prop.

Never commit game files, plans, CSVs or pictures: they belong in a scratch folder.
