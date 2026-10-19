#!/bin/bash
RACE=$1; TAG=$2; CAM=$3; COMBOS=$4; OUT=$5; SHARE=${6:-0.33}; COLS=${COLS:-"0 1 2 3 4"}
LV=""; for w in $COLS; do [ "$w" != "0" ] && LV="$LV $w"; done
S=$(cd "$(dirname "$0")/.." && pwd); T=${TMPDIR:-/tmp}
cd $S
curl -s -o /dev/null http://127.0.0.1:8765/index.html || { (cd $S/r3d && setsid nohup python3 -m http.server 8765 --bind 127.0.0.1 >$T/http8765.log 2>&1 &); sleep 2; }
for combo in $COMBOS; do hh=${combo#*:}; sk0=${combo%%:*}; for lv in $LV; do
  ( SKIN_N=$sk0 python3 -I decal/paint.py $RACE "Character\\$RACE\\Hair00_$hh.blp" $lv r3d/h_${TAG}_${hh}_$lv.png $SHARE >$T/h_$TAG$hh$lv.log 2>&1 || (echo "FAILED hair $hh level $lv"; tail -3 $T/h_$TAG$hh$lv.log)
    python3 -I decal/export_local.py decal/${RACE}FemaleShells.m2 decal/${RACE}FemaleShells00.skin r3d/model_H_${TAG}_${hh}_$lv.json h_${TAG}_${hh}_$lv.png >/dev/null ) &
done; done; wait
cd r3d
curl -s -o /dev/null -w "server %{http_code}\n" http://127.0.0.1:8765/index.html
C="cam=$CAM&fov=20&w=380&h=380"; n=0
for combo in $COMBOS; do sk=${combo%%:*}; hh=${combo#*:}
  case " $COLS " in *" 0 "*) (node shot.js "http://127.0.0.1:8765/index.html?model=model_$RACE.json&skin=skin_${TAG}d$sk.png&extra=extra_$RACE.png&$C" s_${TAG}_${sk}_0.png 380 380 >/dev/null 2>&1 || echo "RENDER FAILED $sk 0") & ;; esac
  for lv in $LV; do
    (node shot.js "http://127.0.0.1:8765/index.html?model=model_H_${TAG}_${hh}_$lv.json&skin=skin_${TAG}d$sk.png&extra=extra_$RACE.png&$C" s_${TAG}_${sk}_$lv.png 380 380 >/dev/null 2>&1 || echo "RENDER FAILED $sk $lv") &
    n=$((n+1)); if [ $((n % 4)) -eq 0 ]; then wait; fi
  done
done; wait
python3 - "$TAG" "$S/$OUT" "$COMBOS" "$COLS" <<'PY'
import sys
from PIL import Image, ImageDraw
tag,out,combos=sys.argv[1],sys.argv[2],sys.argv[3].split()
cols=[int(x) for x in sys.argv[4].split()]
L={i:'level %d' % i for i in range(5)}; L.update({5:'strip',6:'heart',7:'moon',8:'moon turned',9:'star',10:'crossed swords',11:'skull',12:'patch',13:'plate 1',14:'plate 2',15:'straps',16:'shards',17:'bone',18:'level 3b',19:'level 5'})
c=Image.new('RGB',(380*len(cols),len(combos)*400),(43,43,51)); d=ImageDraw.Draw(c)
for r,cb in enumerate(combos):
    sk,hh=cb.split(':')
    d.text((8,r*400+4),f'skin {sk}, hair {hh}',fill=(255,255,255))
    for i,l in enumerate(cols):
        c.paste(Image.open(f's_{tag}_{sk}_{l}.png'),(380*i,r*400+20)); d.text((380*i+8,r*400+24),L[l],fill=(255,255,255))
c.save(out); print(c.size)
PY
