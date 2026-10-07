#!/usr/bin/env bash
# Regenerates the Android launcher icons in overlay/ from branding/icon-source.png.
# Needs ImageMagick (convert). The generated PNGs are committed, so CI doesn't run this.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/branding/icon-source.png"
RES="$ROOT/overlay/Source/Android/app/src/main/res"

# Adaptive icon layers are 108dp; legacy icons are 48dp.
declare -A DENSITY=([mdpi]=1 [hdpi]=1.5 [xhdpi]=2 [xxhdpi]=3 [xxxhdpi]=4)

for d in "${!DENSITY[@]}"; do
  scale=${DENSITY[$d]}
  layer=$(python3 -c "print(round(108 * $scale))")
  legacy=$(python3 -c "print(round(48 * $scale))")
  logo=$(python3 -c "print(round(108 * $scale * 0.54))")
  legacy_logo=$(python3 -c "print(round(48 * $scale * 0.80))")
  radius=$(python3 -c "print(round(48 * $scale * 0.22))")
  out="$RES/mipmap-$d"
  mkdir -p "$out"

  # Background: dark charcoal with a faint red glow behind the logo.
  convert -size "${layer}x${layer}" radial-gradient:'#3a0b10'-'#0b0b0d' \
    -strip "PNG32:$out/xd_icon_background.png"

  # Foreground: the logo centred inside the adaptive icon safe zone.
  convert "$SRC" -trim +repage -resize "${logo}x${logo}" \
    -background none -gravity center -extent "${layer}x${layer}" \
    -strip "PNG32:$out/xd_icon_foreground.png"

  # Monochrome (Android 13 themed icons): the logo's silhouette.
  convert "$out/xd_icon_foreground.png" -alpha extract -background white -alpha shape \
    -strip "PNG32:$out/xd_icon_monochrome.png"

  # Legacy icon for Android 7: logo on a rounded dark tile.
  convert -size "${legacy}x${legacy}" xc:none \
    \( -size "${legacy}x${legacy}" radial-gradient:'#3a0b10'-'#0b0b0d' \) \
    \( -size "${legacy}x${legacy}" xc:none -fill white \
       -draw "roundrectangle 0,0,$((legacy - 1)),$((legacy - 1)),$radius,$radius" \) \
    -delete 0 -alpha off -compose CopyOpacity -composite \
    \( "$SRC" -trim +repage -resize "${legacy_logo}x${legacy_logo}" \) \
    -compose Over -gravity center -composite \
    -strip "PNG32:$out/ic_launcher.png"
done

# Logo shown on the setup screen.
mkdir -p "$RES/drawable-nodpi"
convert "$SRC" -trim +repage -strip "PNG32:$RES/drawable-nodpi/xd_logo.png"

echo "Icons written to $RES"
