#!/usr/bin/env bash
# The desktop scene film, end to end (tools/film/README.md): records the app's live simulation on the JVM, draws it with
# the engine, puts the words over it and makes the GIFs, MP4s, contact sheets and the activity reports.
#   tools/film/run.sh FILES_DIR LOOKS_DIR OUT_DIR
# FILES_DIR: the game files (each named by its file id: the locations' and the looks'); LOOKS_DIR: look files named by
# the recorder's cast (human_f.look, dwarf_m.look, nightelf_f.look, gnome_m.look, human_m.look, draenei_f.look).
# FILM_HOURS (default 24), FILM_SCENES (default embassy,goldshire,bedpair), UNIT_TESTS (a copy of unit-tests to build in,
# default wifecraft/unit-tests), EVERY (records per written picture, default 2: two a second of real time).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../.."
files="$1"; looks="$2"; out="$3"
mkdir -p "$out/track"
[ -x "$app/engine-test/build/film" ] || "$app/engine-test/build.sh"
locations="$app/android/app/src/main/assets/locations.json"
(cd "${UNIT_TESTS:-$app/unit-tests}" && FILM_OUT="$out/track" FILM_ROUTER="$app/engine-test/build/film" FILM_FILES="$files" \
    gradle --init-script "$app/../scripts/maven-mirror.init.gradle" test --tests 'com.logie.wifecraft.tools.SceneFilmRecorder' --rerun -q)
every="${EVERY:-2}"
draw() {   # draw PLAN NAME CAMERA [FROM TO]
    mkdir -p "$out/$2"
    "$app/engine-test/build/film" --track "$out/track/$1.jsonl" --files "$files" --locations "$locations" --looks "$looks" \
        --out "$out/$2/frames" --size 800x450 --every "$every" --camera "$3" ${4:+--from $4} ${5:+--to $5}
    python3 -I "$here/overlay.py" "$out/track/$1.jsonl" "$out/$2/frames" "$out/$2/overlaid"
    python3 -I "$here/assemble.py" "$out/$2/overlaid" "$out/$2" --title "$2"
}
for plan in $(echo "${FILM_SCENES:-embassy,goldshire,bedpair}" | tr , ' '); do
    [ -f "$out/track/$plan.jsonl" ] || continue
    draw "$plan" "${plan}_room" app
    case "$plan" in
        embassy) draw embassy embassy_orbit orbit 0 90000 ;;
        goldshire) draw goldshire goldshire_bed "fixed:6.3,-1.0,9.4,8.5,1.8,7.6,62" ;;
        bedpair) draw bedpair bedpair_bed "fixed:8.45,0.2,9.0,8.45,1.8,7.6,80" 0 20000 ;;
    esac
    python3 -I "$here/analyze.py" "$out/track/$plan.jsonl" "$out/${plan}_room/frames/frames.jsonl" > "$out/${plan}_report.txt"
    echo "$out/${plan}_report.txt"
done
