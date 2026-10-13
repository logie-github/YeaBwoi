#!/bin/sh
# Brings Winlator (https://github.com/brunodev85/winlator, LGPL-2.1: Wine, Box64, the graphics
# drivers and an X server, which run the game) into the World of Warcraft app, as
# warcraft/android/winlator (git-ignored). The app's module builds it with its own code
# (app/build.gradle): Winlator's Java code, resources, native code, prebuilt libraries and assets,
# at the commit in warcraft/winlator-version.env, with warcraft/patches/winlator/ applied, and:
#
# - its code using the app's R class (one module), and without the resources the app has its own of;
# - its runtime's install path, which its programs have compiled in, rewritten for this app in
#   every archive in its assets (scripts/winlator-rewrite.py): /data/data/com.winlator/files/rootfs
#   becomes /data/data/com.logie.warcraft/rootfs, the same length, where the app puts it.
#
# WINLATOR_SRC is where to keep the checkout (default build/winlator-app).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/winlator-version.env
. "$ROOT/warcraft/winlator-version.env"
SRC="${WINLATOR_SRC:-$ROOT/build/winlator-app}"
OUT="$ROOT/warcraft/android/winlator"

if [ ! -d "$SRC/.git" ]; then
	git init -q "$SRC"
	git -C "$SRC" remote add origin "$WINLATOR_APP_REPO"
fi
git -C "$SRC" cat-file -e "$WINLATOR_APP_COMMIT^{commit}" 2> /dev/null ||
	git -C "$SRC" fetch -q --depth 1 origin "$WINLATOR_APP_COMMIT"
git -C "$SRC" checkout -q --force "$WINLATOR_APP_COMMIT"
git -C "$SRC" clean -qfdx
set -- "$ROOT"/warcraft/patches/winlator/*.patch
if [ -f "$1" ]; then
	git -C "$SRC" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
fi

main="$SRC/app/src/main"
rm -rf "$OUT"
mkdir -p "$OUT"
for dir in java res assets cpp jniLibs; do
	cp -r "$main/$dir" "$OUT/$dir"
done
cp "$main/AndroidManifest.xml" "$OUT/AndroidManifest.upstream.xml"

# Paths with Winlator's package in the source (compiled again, so any length).
grep -rlF "/data/data/com.winlator" "$OUT/java" "$OUT/cpp" | while read -r file; do
	sed -i "s|$WINLATOR_ROOTFS_PATH|$APP_ROOTFS_PATH|g; s|/data/data/com\.winlator/|/data/data/com.logie.warcraft/|g" "$file"
done
if grep -rF "com.winlator/" "$OUT/java" "$OUT/cpp" | grep -F "/data/"; then
	echo "Paths with Winlator's package left in its source." >&2
	exit 1
fi

# One module: Winlator's code uses the app's R.
find "$OUT/java" -name '*.java' | while read -r file; do
	sed -i 's/^import com\.winlator\.R;$/import com.logie.warcraft.R;/' "$file"
	if grep -qE '(^|[^.A-Za-z0-9_])R\.' "$file" && ! grep -q '^import com\.logie\.warcraft\.R;' "$file"; then
		sed -i '0,/^package .*;/s//&\nimport com.logie.warcraft.R;/' "$file"
	fi
done

# The resources the app has its own of: its name (in every language) and its file provider's paths.
python3 -I - "$OUT/res" <<'EOF'
import glob, re, sys
res = sys.argv[1]
for path in glob.glob(res + "/values*/strings.xml"):
    text = open(path, encoding="utf-8").read()
    new = re.sub(r'\s*<string name="app_name"[^>]*>.*?</string>', "", text, flags=re.S)
    if new != text:
        open(path, "w", encoding="utf-8").write(new)
EOF
rm -f "$OUT/res/xml/file_paths.xml"

# The runtime, for this app's path.
find "$OUT/assets" -name '*.tzst' | sort > "$OUT/.archives"
# shellcheck disable=SC2046
python3 "$ROOT/scripts/winlator-rewrite.py" "$WINLATOR_ROOTFS_PATH" "$APP_ROOTFS_PATH" $(cat "$OUT/.archives")
rm "$OUT/.archives"

echo "Winlator $WINLATOR_VERSION ($(git -C "$SRC" rev-parse --short HEAD)) in $OUT"
