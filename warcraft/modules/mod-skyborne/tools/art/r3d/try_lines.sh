#!/bin/bash
S=$(cd "$(dirname "$0")/.." && pwd); T=${TMPDIR:-/tmp}
RACE=$1; TAG=$2; SK=$3; CAM=$4; OUT=$5; OVER=${6:-"{}"}
cd $S
CFG_OVER="$OVER" python3 -I races/lines.py $RACE r3d/skin_${TAG}n$SK.png r3d/skin_${TAG}t$SK.png
cd r3d
curl -s -o /dev/null http://127.0.0.1:8765/index.html || { (setsid nohup python3 -m http.server 8765 --bind 127.0.0.1 >$T/http8765.log 2>&1 &); sleep 2; }
node shot.js "http://127.0.0.1:8765/index.html?model=model_$RACE.json&skin=skin_${TAG}t$SK.png&extra=extra_$RACE.png&cam=$CAM&fov=20&w=700&h=700" $OUT 700 700 >/dev/null 2>&1 || echo "RENDER FAILED"
