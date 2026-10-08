#!/usr/bin/env bash
# Regenerates a game's launcher icons and in-app artwork from games/<game>/branding/:
#   logo.png     the game logo with a transparent background
#   header.jpg   the main menu banner
#   cover.jpg    (optional) square cover art; if present it becomes a full-bleed app icon,
#                otherwise the icon is the logo on a gradient (ICON_BG_INNER/OUTER in art.env)
#
# Usage: scripts/generate-art.sh <game>     (needs ImageMagick; the output is committed)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GAME="${1:?usage: $0 <game>}"
BRAND="$ROOT/games/$GAME/branding"
RES="$ROOT/games/$GAME/overlay/Source/Android/app/src/main/res"

ICON_BG_INNER="#3a0b10"
ICON_BG_OUTER="#0b0b0d"
LOGO_WIDTH=480
# shellcheck source=/dev/null
[ -f "$BRAND/art.env" ] && source "$BRAND/art.env"

LOGO="$BRAND/logo.png"
COVER="$BRAND/cover.jpg"
px() { python3 -c "print(round($1))"; }

# Adaptive icon layers are 108dp, of which the middle 72dp is visible; legacy icons are 48dp.
declare -A DENSITY=([mdpi]=1 [hdpi]=1.5 [xhdpi]=2 [xxhdpi]=3 [xxxhdpi]=4)

for d in "${!DENSITY[@]}"; do
  scale=${DENSITY[$d]}
  layer=$(px "108 * $scale")
  legacy=$(px "48 * $scale")
  radius=$(px "48 * $scale * 0.22")
  out="$RES/mipmap-$d"
  mkdir -p "$out"

  if [ -f "$COVER" ]; then
    # Cover art: shown so that ~90% of it is inside the visible 72dp, with blurred edges
    # filling the rest of the layer (only seen in launcher animations).
    art=$(px "72 * $scale / 0.9")
    convert "$COVER" -resize "${layer}x${layer}^" -gravity center -extent "${layer}x${layer}" -blur 0x$(px "4 * $scale") \
      \( "$COVER" -resize "${art}x${art}" \) -gravity center -composite \
      -strip "PNG24:$out/xd_icon_background.png"
    convert -size "${layer}x${layer}" xc:none -strip "PNG32:$out/xd_icon_foreground.png"
    convert "$COVER" -resize "${legacy}x${legacy}" \
      \( -size "${legacy}x${legacy}" xc:none -fill white \
         -draw "roundrectangle 0,0,$((legacy - 1)),$((legacy - 1)),$radius,$radius" \) \
      -alpha off -compose CopyOpacity -composite -strip "PNG32:$out/ic_launcher.png"
  else
    logo=$(px "108 * $scale * 0.54")
    legacy_logo=$(px "48 * $scale * 0.80")
    convert -size "${layer}x${layer}" radial-gradient:"$ICON_BG_INNER"-"$ICON_BG_OUTER" \
      -strip "PNG32:$out/xd_icon_background.png"
    convert "$LOGO" -trim +repage -resize "${logo}x${logo}" \
      -background none -gravity center -extent "${layer}x${layer}" \
      -strip "PNG32:$out/xd_icon_foreground.png"
    convert -size "${legacy}x${legacy}" xc:none \
      \( -size "${legacy}x${legacy}" radial-gradient:"$ICON_BG_INNER"-"$ICON_BG_OUTER" \) \
      \( -size "${legacy}x${legacy}" xc:none -fill white \
         -draw "roundrectangle 0,0,$((legacy - 1)),$((legacy - 1)),$radius,$radius" \) \
      -delete 0 -alpha off -compose CopyOpacity -composite \
      \( "$LOGO" -trim +repage -resize "${legacy_logo}x${legacy_logo}" \) \
      -compose Over -gravity center -composite \
      -strip "PNG32:$out/ic_launcher.png"
  fi

  # Monochrome (Android 13 themed icons), inside the safe zone: the logo's silhouette, or
  # with MONO_STYLE=lettering only its colorful lettering (for logos on a plain frame).
  mono=$(px "108 * $scale * 0.56")
  if [ "${MONO_STYLE:-silhouette}" = lettering ]; then
    convert "$LOGO" -trim +repage \
      \( +clone -colorspace HSL -channel G -separate +channel -threshold 55% \) \
      \( -clone 0 -alpha extract -threshold 50% \) \
      \( -clone 1 -clone 2 -compose Multiply -composite -morphology Open Disk:2 \) \
      -delete 0-2 -compose Over -alpha off -resize "${mono}x${mono}" -background black -gravity center -extent "${layer}x${layer}" \
      -alpha copy -fill white -colorize 100 -strip "PNG32:$out/xd_icon_monochrome.png"
  else
    convert "$LOGO" -trim +repage -resize "${mono}x${mono}" \
      -background none -gravity center -extent "${layer}x${layer}" \
      -alpha extract -background white -alpha shape -strip "PNG32:$out/xd_icon_monochrome.png"
  fi
done

mkdir -p "$RES/drawable-nodpi"
# Logo for the setup screen and the main menu.
convert "$LOGO" -trim +repage -resize "${LOGO_WIDTH}x" -strip "PNG32:$RES/drawable-nodpi/xd_logo.png"
# Splash screen icon: the logo inside the circle Android shows (2/3 of the canvas).
convert "$LOGO" -trim +repage -resize 340x340 -background none -gravity center -extent 576x576 \
  -strip "PNG32:$RES/drawable-nodpi/xd_splash.png"
# Main menu banner.
convert "$BRAND/header.jpg" -resize 1600x -strip -interlace none -quality 82 "$RES/drawable-nodpi/xd_header.jpg"

echo "Artwork for $GAME written to $RES"
