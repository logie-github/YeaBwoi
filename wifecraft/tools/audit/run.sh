#!/usr/bin/env bash
# The placement audit, end to end (tools/audit/README.md): plans every case with the app's own simulation on the JVM, measures
# them on the real models with engine-test/audit (one process a room), writes the report, the sample contact sheets (one case
# per animation and place) and the worst ones.
#   tools/audit/run.sh FILES_DIR LOOKS_DIR POSES_DIR OUT_DIR
# FILES_DIR the game files (each named by its id); LOOKS_DIR harness look files (human_m.look, human_f.look, orc_m.look,
# dwarf_m.look, nightelf_f.look, undead_f.look, tauren_m.look, gnome_m.look, gnome_f.look, goblin_m.look, bloodelf_m.look,
# draenei_f.look); POSES_DIR converted poses (pose1.anim .. pose15.anim, the harness's anim text). Optional: UNIT_TESTS (a
# copy of unit-tests to build in), AUDIT_ROOMS (goldshire,brill,thunderbrew,stage), AUDIT_COUCH ("x z": the test couch's
# seat as `contact COUCH sit` measures it), AUDIT_SNAP=1 (everyone stood on the floor, as the engine did before kRaised),
# WORST (how many of the worst to draw, default 40).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../.."
files="$1"; looks="$2"; poses="$3"; out="$4"
mkdir -p "$out"
[ -x "$app/engine-test/build/audit" ] || "$here/build.sh"
(cd "${UNIT_TESTS:-$app/unit-tests}" && AUDIT_OUT="$out" AUDIT_POSES="$poses" AUDIT_LOOKS="$looks" AUDIT_FILES="$files" \
    gradle --init-script "$app/../scripts/maven-mirror.init.gradle" test --tests 'com.logie.wifecraft.tools.PlacementAuditPlanner' --rerun -q)
for plan in "$out"/plan_*.txt; do
    room="$(basename "$plan" .txt)"; room="${room#plan_}"
    "$app/engine-test/build/audit" "$plan" "$out/audit_$room.csv" 2> "$out/log_$room.txt" &
done
wait
python3 -I "$here/report.py" "$out" --json "$out/report.json" --top "${WORST:-40}" > /dev/null
python3 -I "$here/renders.py" "$out" "$out/sample" --sample
python3 -I "$here/renders.py" "$out" "$out/worst" --worst "${WORST:-40}"
echo "$out/report.txt"
