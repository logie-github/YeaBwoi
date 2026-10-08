#!/usr/bin/env bash
# Prints, one per line, the apps in games/ whose APK has changed since that app's last GitHub
# release. A push that only changes Dolphin Colosseum then doesn't publish a new Dolphin XD, and
# the other way round. Changes to the code all apps share rebuild every app.
#
# Usage: scripts/apps-to-build.sh   (needs the full git history with tags, and gh for the releases)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Everything besides games/<game>/ that goes into an APK. The workflow file isn't listed: after
# changing build settings there, run the workflow by hand to rebuild the apps.
shared=(dolphin-version.env patches overlay scripts/prepare-dolphin.sh ci)

# Newest first.
releases="$(gh release list --limit 200 --json tagName --jq '.[].tagName')"

for dir in games/*/; do
  game="$(basename "$dir")"
  RELEASE_TAG_PREFIX=""
  # shellcheck source=../games/xd/game.env
  source "${dir%/}/game.env"

  last="$(grep -m1 -x "$RELEASE_TAG_PREFIX-[0-9]*" <<<"$releases" || true)"
  if [ -z "$last" ] || ! git rev-parse -q --verify "refs/tags/$last^{commit}" >/dev/null; then
    echo "$game: no earlier release, building" >&2
    echo "$game"
    continue
  fi

  changes="$(git diff --name-only "refs/tags/$last" HEAD -- \
    "${shared[@]}" "games/$game" ":(exclude)games/$game/branding")"
  if [ -n "$changes" ]; then
    echo "$game: changed since $last, building" >&2
    echo "$game"
  else
    echo "$game: unchanged since $last, skipping" >&2
  fi
done
