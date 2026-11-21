#!/bin/bash
# The pair alignment audit (README, "Alignment audit"): run.sh PACK RIG_PACKAGE OUT_DIR [FLESH_DIR]
#   PACK          a WickedWhims-format pack (.package, or the .zip/.7z it comes in)
#   RIG_PACKAGE   a package with the Sims humanoid rigs (WickedWhims' TURBODRIVER_WickedWhims_Tuning.package)
#   OUT_DIR       stages.csv, summary.txt, worst.tsv, table.csv (numbers only) are written here
#   FLESH_DIR     measured bodies (engine-test/audit's human_m.flesh, human_f.flesh, dwarf_m.flesh, gnome_f.flesh)
# ALIGN_RENDER (stage names or ids, "|"-separated) with ALIGN_FILES, ALIGN_LOOKS, ALIGN_LOCATIONS also writes
# engine-test/audit plans (OUT_DIR/render/*/plan.txt) for stills: engine-test/build/audit PLAN OUT.csv.
# A woman in Eve: ALIGN_EVE (races, default human) reads FLESH_DIR/eve_<race>.flesh (engine-test/audit: eveset, eve SLOT 1,
# flesh) and adds human_m+eve_<race> and eve_<race>+human_m; ALIGN_COMBOS keeps only the named body pairs; ALIGN_RENDER_COMBO
# the pair the stills are of (ALIGN_EVE_SET, ALIGN_EVE_FIT, ALIGN_EVE_LOOK: the Eve set, eve_fit.json, her look).
set -e
cd "$(dirname "$0")/../../unit-tests"
ALIGN_PACK="$1" ALIGN_RIG="$2" ALIGN_OUT="$3" ALIGN_FLESH="${4:-}" \
  gradle --init-script ../../scripts/maven-mirror.init.gradle test --tests 'com.logie.wifecraft.tools.PairAlignmentAudit' --rerun -q
cat "$3/summary.txt"
