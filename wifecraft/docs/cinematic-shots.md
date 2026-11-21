# Cinematic shots by position class

The reference behind cinematic mode's shot library (`android/app/src/main/assets/shot_library.json`, read by
`simulation/ShotLibrary.kt` and `CinematicDirector`). It lists the usual camera conventions for filming two adult
performers, grouped by position class, and how the app turns them into shots.

## The shot vocabulary

| Shot | What it shows | Used for |
| --- | --- | --- |
| Establishing or wide | The pair and the room | Opening a scene or a new position, and resetting the viewer between close shots. The safe shot that covers everything |
| Profile or side medium | Both bodies from the side, square to the line between them | The main coverage shot for most positions: it shows the contact and both partners at once |
| Three-quarter | Between the profile and a partner's front | Favouring one partner's face and body while still showing the other |
| Over the shoulder | One face, seen past the near partner's shoulder | Faces and reactions in partner-facing positions |
| Point of view (POV) | What one partner sees, usually looking down | Positions where one partner stands or lies over the other. The app cannot put the eye inside a body, so its version sits high over the near partner's shoulder |
| Face close-up or reaction | One face, or both faces when they are close together | Kissing, emotion, and cutting away from the action |
| Insert or detail | A close shot of part of the action that is already in the master shot | Hands and hips. Cut in briefly between wider shots |
| Low angle | Looking up from near the floor | Standing positions and a partner on top. It gives strong coverage but is often unflattering, so it is used briefly |
| High angle or top-down | Looking down at the pair | Lying positions. Softer and less explicit |

How the shots are moved and joined:

- **The 180-degree rule.** The camera stays on one side of the line between the partners, so they keep their screen sides
  across cuts.
- **Slow push-in and pull-out.** Moving in builds intimacy. Pulling out gives distance or ends the scene.
- **Slow orbit.** A shot keeps moving while it is held.
- **Handheld sway.** Gonzo and POV footage moves loosely, as if held.
- **Coverage.** A wide master shot, then medium, close and insert shots cut from it.

## By position class

Height is measured from the partners' hips. Distance is in body heights (1 is about 1.8 yards). Dwell is how long a shot
is held, in seconds.

| Class (app) | Typical shots | Height | Distance | Side | Motion | Dwell |
| --- | --- | --- | --- | --- | --- | --- |
| Kissing and foreplay (`KISS`) | Two faces in profile, single face close-ups and reactions, one establishing wide | Eye level to slightly above | 0.6 to 0.8 close, 1.2 to 1.6 medium | Square to the line. Faces taken from in front | Slow push-in, dolly in | 3 to 7 |
| Partner-facing, one lying (`FACING_LYING`) | Profile medium, top-down, the lying face from above, the hips from above, from behind the partner on top | 8 to 63 degrees above (high for faces and hips) | 0.5 to 1.6 | Profile side, one end | Slow orbit, small push | 3 to 9 |
| From behind (`REAR`) | Profile medium (the main shot), high from behind, the front partner's face from in front, a low three-quarter detail | 4 to 42 degrees | 0.55 to 1.5 | Both ends of the line, on one side | Slow orbit | 3 to 8 |
| On top (`RIDING`) | Low medium from the lying partner's side, profile, three-quarter, the top partner's face, a low detail (just over the bed or couch; never on the floor) | -12 to 24 degrees | 0.5 to 1.6 | Three-quarter front | Slow orbit, push | 3 to 8 |
| Seated (`SEATED`) | Both three-quarters, profile, two faces, a hip detail | 0 to 24 degrees | 0.45 to 1.5 | The side the pair faces | Slow orbit | 3 to 8 |
| Standing (`STANDING`) | Profile medium, low medium, handheld medium, two faces, a hip detail, a wide | -12 to 22 degrees | 0.45 to 1.6 | Profile | Handheld sway, orbit | 3 to 8 |
| Side by side, lying (`SIDE_LYING`) | Three-quarter front, top-down, the faces from above, profile, the hips from above | 12 to 63 degrees | 0.5 to 1.6 | The side the pair faces | Slow orbit | 3 to 8 |
| Oral (`ORAL`) | A high view over the standing partner's shoulder (POV), profile, the kneeling partner's face, three-quarter, low medium | -12 to 58 degrees | 0.55 to 1.6 | Over the receiving partner's shoulder | Handheld sway | 3 to 9 |
| Manual (`MANUAL`) | Hands from above (insert), two faces, profile, three-quarter, a face | 0 to 40 degrees | 0.5 to 1.6 | Profile or three-quarter | Slow push | 3 to 8 |
| Two people, nothing more known (`PARTNER`) | Profile, three-quarter, two faces, over the shoulder, high wide (a corner high shot in a small room), establishing wide | -12 to 55 degrees | 0.6 to 3.4 | Profile | Orbit | 3 to 9 |
| One person (`SOLO`) | Handheld medium, low medium, face, pull-out, high wide, establishing wide | -12 to 55 degrees | 0.6 to 3.4 | Front three-quarter | Sway, pull-out | 4 to 9 |

## What decides which shot

Each class has a set of 3 to 6 candidate shots, each with a weight (`sets` in the JSON). The director weighs them by:

- **Shot style.** Classic film, Intimate, Close-ups, Wide or Random (`styles`).
- **The animation's stage.** A start favours wide shots and an end favours faces (`stages`).
- **The cadence**, in Classic film. Wide, medium, close, detail, medium, close, medium, detail (`cadence`).

The Randomness setting flattens the weights and loosens the cadence. The director never picks the same shot twice
running. It picks the line's side once per scene, preferring the side the pair faces and the side with more room.

Each shot then drifts on a seeded random walk: round, up and in. The drift stays within half the shot's own range,
scaled by Randomness. Every eye must pass the director's clearance tests: walls, furniture boxes, the floor and ceiling,
nobody blocking a face, and no furniture between the camera and the action.

## Placement rules

- **Furniture at its real shape** (`PropSolids`, from the measured extent): a piece's base up to its surface, its solid
  parts from the floor to their tops, and on a piece more than 1.4 yards taller than its surface (a canopy bed) the canopy:
  a slab over the whole footprint and 0.3 yards past it, 35% of the rise deep (0.45 to 1.2 yards). The eye never stands in
  any of them or in a piece's full-height box, no sight line to what a shot looks at or to a framed face passes through
  them (the furniture in use included), and a canopy may cover at most 12% of the picture in front of the action.
- **Over the shoulder.** The near person's body (bounding spheres at the head, shoulders, chest and hips) may cover at most
  25% of the picture; a steep point of view looking down past them, 40%. `over_shoulder_a` sits 124 to 144 degrees round,
  12 to 22 up, 0.95 to 1.15 body heights from the face.
- **Set by the surface.** A shot with `eyeOverSurface` (the low hip detail, `pelvis_low`) puts its eye that many yards
  over the bed or seat the pair is on, its pitch made to fit, and is never picked on the floor.
- **Wide shots fit the room.** A wide shot of the pair is brought in to the floor's clear reach that way (less 0.3 yards)
  and under the ceiling. Where that leaves less than 0.9 body heights, it moves to the azimuth on the scene's side with the
  most room, as high as the ceiling allows and looking down at least 15 degrees (a corner high shot), or is dropped.
- **Hip and head points.** From the playing pair clip's reading (anim/Requirements: the pelvis and the head over the
  ground in the pair's shared frame, the posture, the support's height), placed at the pair's anchor and heading; the
  heights over the support by posture (`CinematicAnchors`). Without a reading: the activity's rough points.

## Sources

- Wikipedia: "Gonzo pornography", "Point-of-view pornography", "Camera coverage", "Insert (filmmaking)".
- InsideHook, "How to Film a Sex Tape According to Porn Industry Professionals" (an adult video editor on low angles,
  high angles and wide shots).
- No Film School, "Sensual, Emotional, & Titillating: 3 Ways to Shoot a Sex Scene" (body close-ups, face close-ups, and
  moving in or out).
- Videomaker, "Camera angles: a complete guide" (what high, low and close shots convey).
- Linda Williams, *Hard Core* (UC Press), from its published summary: the genre's drive to make the act visible.

No source found maps every position to its shots. Where the sources were silent, the per-class table above is a
judgement built from these general conventions.
