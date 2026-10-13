#!/bin/sh
# Builds the World of Warcraft app's custom races package (warcraft/races) into the folder given
# (default build/races): mod-worgoblin-high-elf and Thorium at their pins (races.env), the stock
# DBC tables out of the server data (server-data.env: the same 12340 tables as the client's),
# scripts/build-races.py over them, then two zips:
#
#   WarcraftRaces-client.zip   the client's patch folder (Data/patch-Z.MPQ/): the module's models,
#                              textures, sounds and character creation screen (with the app's
#                              fixes to it, warcraft/races/client/RaceScreen.lua, after it in
#                              GlueXML.toc), and the built tables in DBFilesClient (the module's
#                              own tables are left out)
#   WarcraftRaces-server.zip   dbc/ (the built tables, over the server's), world.sql, races.txt,
#                              achievements.txt (the achievements and criteria the races add,
#                              which ctl.sh keeps aside while they're off), VERSION
#
# Needs git, curl, unzip, python3 with pymysql, and a MariaDB server to build in (DB_HOST, DB_PORT,
# DB_USER, DB_PASSWORD; default root on 127.0.0.1:3306) with the mariadb or mysql client.
# WOW_DATA_ZIP: the server data zip if it's already here (CI's server test downloads it too).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/races/races.env
. "$ROOT/warcraft/races/races.env"
# shellcheck source=../warcraft/server-data.env
. "$ROOT/warcraft/server-data.env"
WORK="${1:-$ROOT/build/races}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"

fetch() { # folder, repository, commit
	if [ ! -d "$1/.git" ]; then
		git init -q "$1"
		git -C "$1" remote add origin "$2"
	fi
	git -C "$1" fetch -q --depth 1 origin "$3"
	git -C "$1" checkout -q --force FETCH_HEAD
}
echo "== sources"
fetch "$WORK/module" "$RACES_REPO" "$RACES_COMMIT"
fetch "$WORK/thorium" "$THORIUM_REPO" "$THORIUM_COMMIT"

echo "== stock tables"
if [ ! -f "$WORK/stockdbc/ChrRaces.dbc" ]; then
	zip="${WOW_DATA_ZIP:-}"
	if [ -z "$zip" ]; then
		zip="$WORK/data.zip"
		curl -fsSL --retry 5 --retry-all-errors -o "$zip" "$SERVER_DATA_URL"
	fi
	echo "$SERVER_DATA_SHA256  $zip" | sha256sum -c -
	rm -rf "$WORK/stockdbc"
	unzip -q -j "$zip" 'dbc/*' -d "$WORK/stockdbc"
	[ "$zip" != "$WORK/data.zip" ] || rm -f "$zip"
fi
ls "$WORK/stockdbc" | wc -l

echo "== tables and world SQL"
rm -rf "$WORK/out"
python3 -I "$ROOT/scripts/build-races.py" \
	--modpaks "$WORK/module/modpaks" --manifest "$ROOT/warcraft/races/manifest" \
	--meta "$WORK/thorium/internal/dbc/meta" --meta "$ROOT/warcraft/races/meta" \
	--dbc "$WORK/stockdbc" --out "$WORK/out" \
	--client "$WORK/module/data/patch-A.MPQ" --client-exclude "$RACES_CLIENT_EXCLUDE" \
	--glue "$ROOT/warcraft/races/client/RaceScreen.lua" \
	--host "${DB_HOST:-127.0.0.1}" --port "${DB_PORT:-3306}" --user "${DB_USER:-root}" --password "${DB_PASSWORD:-}"
cat "$WORK/out/races.txt"

echo "== packages"
version="races $(git -C "$WORK/module" rev-parse --short=7 HEAD), built by $(git -C "$ROOT" rev-parse --short=7 HEAD 2> /dev/null || echo "?")"
printf '%s\n' "$version" > "$WORK/out/VERSION"
python3 -I - "$WORK/module/data/patch-A.MPQ" "$WORK/out" "$WORK" "$RACES_CLIENT_EXCLUDE" "$WORK/stockdbc" \
	"$ROOT/warcraft/races/client/RaceScreen.lua" <<'EOF'
import os, struct, sys, zipfile

patch, out, work, exclude, stock, screen = sys.argv[1:7]
excluded = [word.lower() for word in exclude.split(",") if word]

def ids(path):
    with open(path, "rb") as f:
        data = f.read()
    count, _, size, _ = struct.unpack_from("<IIII", data, 4)
    return {struct.unpack_from("<I", data, 20 + i * size)[0] for i in range(count)}

# The achievements and criteria only the races have: the server drops a character's record of
# them while they're off, so ctl.sh keeps them aside.
with open(os.path.join(out, "achievements.txt"), "w") as f:
    for word, table in (("achievements", "Achievement.dbc"), ("criteria", "Achievement_Criteria.dbc")):
        added = sorted(ids(os.path.join(out, "dbc", table)) - ids(os.path.join(stock, table)))
        f.write(word + " " + " ".join(map(str, added)) + "\n")

def add(z, path, name, data=None):
    # Fixed times and order: the same inputs make the same zip.
    info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    if data is None:
        with open(path, "rb") as f:
            data = f.read()
    z.writestr(info, data, compresslevel=6)

# The app's character screen fixes (RaceScreen.lua) load after the module's own screen. The
# module's two-name randomizer stays out: the realm takes one name (letters only), and it took
# over the appearance's Randomize too; the game's own gives a name of the race (NameGen).
TOC = "Interface/GlueXML/GlueXML.toc"
SCREEN = "RaceScreen.lua"
LEFT_OUT = {"TwoNames_CharacterCreate.lua"}

def toc(path):
    with open(path, "rb") as f:
        text = f.read().decode("utf-8")
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.split(newline)
    missing = LEFT_OUT - {line.strip() for line in lines}
    if missing:
        sys.exit(f"{TOC}: no {', '.join(sorted(missing))} to leave out")
    lines = [line for line in lines if line.strip() not in LEFT_OUT]
    last = [i for i, line in enumerate(lines) if line.strip() == "GlueLocalizationPost.xml"]
    if len(last) != 1:
        sys.exit(f"{TOC}: no GlueLocalizationPost.xml to put {SCREEN} before")
    at = last[0]
    while at > 0 and lines[at - 1].startswith("##"):
        at -= 1
    lines.insert(at, SCREEN)
    return newline.join(lines).encode("utf-8")

def files(top):
    for folder, dirs, names in os.walk(top):
        dirs.sort()
        for name in sorted(names):
            yield os.path.join(folder, name)

with zipfile.ZipFile(os.path.join(work, "WarcraftRaces-client.zip"), "w", allowZip64=True) as z:
    count = 0
    for path in files(patch):
        rel = os.path.relpath(path, patch).replace(os.sep, "/")
        # The module's tables are for all of its races: the built ones go in their place.
        if rel.lower().startswith("dbfilesclient/") or any(word in rel.lower() for word in excluded):
            continue
        if rel.startswith("Interface/GlueXML/") and os.path.basename(rel) in LEFT_OUT:
            continue
        add(z, path, rel, toc(path) if rel == TOC else None)
        count += 1
    add(z, screen, "Interface/GlueXML/" + SCREEN)
    count += 1
    for path in files(os.path.join(out, "dbc")):
        add(z, path, "DBFilesClient/" + os.path.basename(path))
        count += 1
    print(f"client: {count} files")

with zipfile.ZipFile(os.path.join(work, "WarcraftRaces-server.zip"), "w", allowZip64=True) as z:
    for path in files(os.path.join(out, "dbc")):
        add(z, path, "dbc/" + os.path.basename(path))
    for name in ("world.sql", "races.txt", "achievements.txt", "VERSION"):
        add(z, os.path.join(out, name), name)
EOF
ls -la "$WORK"/WarcraftRaces-*.zip
