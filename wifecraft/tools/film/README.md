# The desktop scene film

See what a live scene does over a game day without a phone. The JVM runs the app's own simulation:
`LiveScene` with its `LifeSim`, the NPCs' chatter, the location's furniture and spots, and a `WorldClock` at x6, built as
`MainActivity` and `SceneSessionScreen` build it. The engine draws what the screen's sink would have told it, frame by frame.
Desktop only (Mesa EGL). The phone's GPU, its timing and its file loading may differ. None of this has run on a phone.

## Run it

```
wifecraft/engine-test/build.sh                       # builds build/film (and the harness)
wifecraft/tools/film/run.sh FILES_DIR LOOKS_DIR OUT_DIR
```

- `FILES_DIR` holds the game files, each named by its file id: the locations' files and the looks' files.
- `LOOKS_DIR` holds harness look files named for the cast: `human_f`, `dwarf_m`, `nightelf_f`, `gnome_m`, `human_m` and
  `draenei_f`, each ending in `.look`.
- Optional environment variables:
  - `FILM_HOURS`: game hours to run. The default is 24.
  - `FILM_SCENES`: which scenes to run. The default is `embassy,goldshire,bedpair`.
  - `UNIT_TESTS`: a copy of `unit-tests` to build in, for when the shared build folder is busy.
  - `EVERY`: how many records go into each written picture. The default is 2, which gives two pictures per real second.

The script runs four steps:

1. **Record** (`unit-tests/.../tools/SceneFilmRecorder.kt`, a JVM test that does nothing without `FILM_OUT`). It runs
   each plan at 60 frames a second and writes `OUT_DIR/track/PLAN.jsonl`, which holds:
   - a header with the cast, the furniture and the pair clips;
   - a frame record every 250 ms, with each person's place, pose, engine animation or clip and its start, speech,
     thought, mood, needs and life activity, plus the camera and the hour;
   - every sink call, as an event;
   - the forced moments (`cue`) and the scene's answers to them.

   NPCs walk the routes the app gives them: `film --serve` answers `SceneView.glWalkRoute`'s walk tests with the engine.
2. **Draw** (`engine-test/film.cpp`). It loads the location, dresses the cast at human scale (dwarves and gnomes keep their
   own size), and replays each record: placement, game animation or imported clip at its start time, and the light for
   the hour. The camera is one of three:
   - `app`: the scene camera as recorded;
   - `orbit`: circles the room;
   - `fixed:EX,EY,EZ,TX,TY,TZ,FOV`: a camera you place.

   `--every`, `--from` and `--to` choose the pictures. It writes `frames.jsonl` with each head's place on screen and each
   person's feet above the floor.
3. **Words** (`overlay.py`). Draws speech bubbles, thoughts, names with what each person is doing, the clock and cue
   captions.
4. **Watch** (`assemble.py`). Makes a GIF of 8 MB or less, an MP4 when ffmpeg is installed, and contact sheets for
   morning, noon and night. `analyze.py TRACK [frames.jsonl]` writes `PLAN_report.txt`: the activity log and the
   problems it can see, such as flapping, people standing in each other or in furniture, conversations where they don't
   face each other, quick animation changes and feet off the floor.

## Plans

| Plan | Location | Cast | Cues | Run |
| --- | --- | --- | --- | --- |
| `embassy` | Stormwind Embassy, which has only a table | 4: a human woman, a dwarf man, a night elf woman and a gnome man | one is hungry, one chat, a dance, a joke, one exhausted | 24 hours |
| `goldshire` | Lion's Pride Inn bedroom: bed, chair, table | 2: a human man and a draenei woman | one is hungry, one is tired, chat, dance, invite to sit, then a cuddle pair on the bed retried every 5 s | 24 hours |
| `bedpair` | the same inn without the walk test, so walks are straight as in the JVM tests | the same 2 | the bed pair, which the routed run never reaches | 4 hours |

The bed pairs are synthetic two-role clips made from `anim/PoseFixtures` bodies, classified and fitted to the bed by the
app's own code.

Never commit game files, tracks or renders: they belong in a scratch folder.
