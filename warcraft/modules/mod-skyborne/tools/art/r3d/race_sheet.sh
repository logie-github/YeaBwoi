#!/bin/bash
RACE=$1; TAG=$2; CAM=$3; COMBOS=$4; OUT=$5; SHARE=${6:-0.33}; COLS=${COLS:-"0 1 2 3 4"}
S=/tmp/claude-0/-home-user-YeaBwoi/c06350a4-89d8-5aa6-9491-88b79be5b5d2/scratchpad
cd $S
for combo in $COMBOS; do hh=${combo#*:}; for lv in ${COLS/0/}; do
  ( python3 -I decal/hairpatch5.py $RACE "Character\\$RACE\\Hair00_$hh.blp" $lv r3d/h_${TAG}_${hh}_$lv.png $SHARE >/tmp/claude-0/h_$TAG$hh$lv.log 2>&1 || (echo "FAILED hair $hh level $lv"; tail -3 /tmp/claude-0/h_$TAG$hh$lv.log)
    python3 -I decal/export_local.py decal/${RACE}FemaleShells.m2 decal/${RACE}FemaleShells00.skin r3d/model_H_${TAG}_${hh}_$lv.json h_${TAG}_${hh}_$lv.png >/dev/null ) &
done; done; wait
cd r3d
curl -s -o /dev/null -w "server %{http_code}\n" http://127.0.0.1:8765/index.html
C="cam=$CAM&fov=20&w=380&h=380"; n=0
for combo in $COMBOS; do sk=${combo%%:*}; hh=${combo#*:}
  case " $COLS " in *" 0 "*) (node shot.js "http://127.0.0.1:8765/index.html?model=model_$RACE.json&skin=skin_${TAG}d$sk.png&extra=extra_$RACE.png&$C" s_${TAG}_${sk}_0.png 380 380 >/dev/null 2>&1 || echo "RENDER FAILED $sk 0") & ;; esac
  for lv in ${COLS/0/}; do
    (node shot.js "http://127.0.0.1:8765/index.html?model=model_H_${TAG}_${hh}_$lv.json&skin=skin_${TAG}d$sk.png&extra=extra_$RACE.png&$C" s_${TAG}_${sk}_$lv.png 380 380 >/dev/null 2>&1 || echo "RENDER FAILED $sk $lv") &
    n=$((n+1)); if [ $((n % 4)) -eq 0 ]; then wait; fi
  done
done; wait
python3 - "$TAG" "$S/$OUT" "$COMBOS" "$COLS" <<'PY'
import sys
from PIL import Image, ImageDraw
tag,out,combos=sys.argv[1],sys.argv[2],sys.argv[3].split()
cols=[int(x) for x in sys.argv[4].split()]
L={i:'level %d' % i for i in range(9)}
c=Image.new('RGB',(380*len(cols),len(combos)*400),(43,43,51)); d=ImageDraw.Draw(c)
for r,cb in enumerate(combos):
    sk,hh=cb.split(':')
    d.text((8,r*400+4),f'skin {sk}, hair {hh}',fill=(255,255,255))
    for i,l in enumerate(cols):
        c.paste(Image.open(f's_{tag}_{sk}_{l}.png'),(380*i,r*400+20)); d.text((380*i+8,r*400+24),L[l],fill=(255,255,255))
c.save(out); print(c.size)
PY
