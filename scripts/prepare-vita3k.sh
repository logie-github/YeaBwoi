#!/usr/bin/env bash
# Produces V3 Gold's source tree:
#   1. checks out the pinned upstream Vita3K commit (v3gold/vita3k-version.env) and its submodules,
#   2. applies v3gold/patches/*.patch (changes to Vita3K's own files),
#   3. copies v3gold/overlay/ on top (V3 Gold's code, artwork and resources),
#   4. copies Vita3K's data and built-in shaders into the Android app's assets, as Vita3K's own
#      CI does.
#
# Usage: scripts/prepare-vita3k.sh [destination]   (default: build/vita3k)
# Running it again resets the destination to a clean, freshly patched tree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/build/vita3k}"

# shellcheck source=../v3gold/vita3k-version.env
source "$ROOT/v3gold/vita3k-version.env"

export GIT_AUTHOR_NAME="V3 Gold"
export GIT_AUTHOR_EMAIL="v3gold@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

if [ -d "$DEST/.git" ]; then
  echo "==> Resetting existing checkout in $DEST"
  git -C "$DEST" am --abort >/dev/null 2>&1 || true
  if ! git -C "$DEST" cat-file -e "$VITA3K_COMMIT^{commit}" 2>/dev/null; then
    git -C "$DEST" fetch --quiet --depth 1 origin "$VITA3K_COMMIT"
  fi
  git -C "$DEST" checkout -q -f --detach "$VITA3K_COMMIT"
  git -C "$DEST" clean -q -ffd
else
  echo "==> Fetching Vita3K $VITA3K_COMMIT into $DEST"
  mkdir -p "$DEST"
  git -C "$DEST" init -q
  git -C "$DEST" remote add origin "$VITA3K_REPO"
  git -C "$DEST" fetch --quiet --depth 1 origin "$VITA3K_COMMIT"
  git -C "$DEST" checkout -q --detach FETCH_HEAD
fi

actual="$(git -C "$DEST" rev-parse HEAD)"
if [ "$actual" != "$VITA3K_COMMIT" ]; then
  echo "error: checked out $actual, expected $VITA3K_COMMIT" >&2
  exit 1
fi

echo "==> Fetching submodules"
git -C "$DEST" submodule update --init --recursive --depth 1 --jobs 8

echo "==> Applying patches"
shopt -s nullglob
patches=("$ROOT"/v3gold/patches/*.patch)
if [ ${#patches[@]} -gt 0 ]; then
  # --keep-cr: some of Vita3K's files have Windows line endings.
  if ! git -C "$DEST" am --quiet --keep-cr --committer-date-is-author-date "${patches[@]}"; then
    git -C "$DEST" am --abort || true
    echo "error: a patch in v3gold/patches/ no longer applies to Vita3K $VITA3K_COMMIT" >&2
    exit 1
  fi
fi

echo "==> Copying v3gold/overlay"
cp -R "$ROOT/v3gold/overlay/." "$DEST/"
mapfile -t overlay_entries < <(cd "$ROOT/v3gold/overlay" && ls -A)
git -C "$DEST" add -A -- "${overlay_entries[@]}"
# A fixed date keeps the commit hash (Vita3K's version string) the same for the same inputs.
GIT_AUTHOR_DATE="2000-01-01T00:00:00Z" GIT_COMMITTER_DATE="2000-01-01T00:00:00Z" \
  git -C "$DEST" commit --quiet --allow-empty -m "Add V3 Gold sources and resources"

echo "==> Copying Vita3K's data and shaders into the app's assets"
assets="$DEST/android/app/assets"
mkdir -p "$assets"
rm -rf "$assets/data" "$assets/shaders-builtin"
cp -R "$DEST/data" "$assets/data"
cp -R "$DEST/vita3k/shaders-builtin" "$assets/shaders-builtin"

echo "==> V3 Gold source ready: $DEST ($(git -C "$DEST" rev-parse --short HEAD))"
