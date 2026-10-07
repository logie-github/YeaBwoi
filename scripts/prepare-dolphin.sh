#!/usr/bin/env bash
# Produces the Dolphin XD source tree:
#   1. checks out the pinned upstream Dolphin release (dolphin-version.env),
#   2. applies patches/*.patch (changes to Dolphin's own files),
#   3. copies overlay/ on top (Dolphin XD's own code, layouts and icons).
#
# Usage: scripts/prepare-dolphin.sh [destination]   (default: build/dolphin)
# Running it again resets the destination to a clean, freshly patched tree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/build/dolphin}"

# shellcheck source=../dolphin-version.env
source "$ROOT/dolphin-version.env"

export GIT_AUTHOR_NAME="Dolphin XD"
export GIT_AUTHOR_EMAIL="dolphinxd@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

if [ -d "$DEST/.git" ]; then
  echo "==> Resetting existing checkout in $DEST"
  git -C "$DEST" am --abort >/dev/null 2>&1 || true
  if ! git -C "$DEST" cat-file -e "$DOLPHIN_COMMIT^{commit}" 2>/dev/null; then
    git -C "$DEST" fetch --depth 1 origin "refs/tags/$DOLPHIN_TAG:refs/tags/$DOLPHIN_TAG"
  fi
  git -C "$DEST" checkout -q -f --detach "$DOLPHIN_COMMIT"
  git -C "$DEST" clean -q -ffd
else
  echo "==> Cloning Dolphin $DOLPHIN_TAG into $DEST"
  git clone --quiet --depth 1 --branch "$DOLPHIN_TAG" "$DOLPHIN_REPO" "$DEST"
fi

actual="$(git -C "$DEST" rev-parse HEAD)"
if [ "$actual" != "$DOLPHIN_COMMIT" ]; then
  echo "error: Dolphin $DOLPHIN_TAG is $actual, expected $DOLPHIN_COMMIT" >&2
  exit 1
fi

echo "==> Fetching submodules (skipping the Windows-only Qt and FFmpeg binaries)"
git -C "$DEST" \
  -c submodule."Externals/Qt".update=none \
  -c submodule."Externals/FFmpeg-bin".update=none \
  submodule update --init --recursive --depth 1 --jobs 8

echo "==> Applying patches"
shopt -s nullglob
patches=("$ROOT"/patches/*.patch)
if [ ${#patches[@]} -gt 0 ]; then
  if ! git -C "$DEST" am --quiet --committer-date-is-author-date "${patches[@]}"; then
    git -C "$DEST" am --abort || true
    echo "error: a patch in patches/ no longer applies to Dolphin $DOLPHIN_TAG" >&2
    exit 1
  fi
fi

echo "==> Copying overlay"
cp -R "$ROOT/overlay/." "$DEST/"
mapfile -t overlay_entries < <(cd "$ROOT/overlay" && ls -A)
git -C "$DEST" add -A -- "${overlay_entries[@]}"
# A fixed date keeps the commit hash (Dolphin's version string) the same for the same inputs.
GIT_AUTHOR_DATE="2000-01-01T00:00:00Z" GIT_COMMITTER_DATE="2000-01-01T00:00:00Z" \
  git -C "$DEST" commit --quiet --allow-empty -m "Add Dolphin XD sources and resources"

echo "==> Dolphin XD source ready: $DEST ($(git -C "$DEST" describe --always --long))"
