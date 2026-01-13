#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Extract version from build.gradle.kts (expects: version = "x.y.z")
VERSION=$(sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([^"]\+\)".*/\1/p' build.gradle.kts | head -n1)
if [[ -z "$VERSION" ]]; then
  echo "Failed to parse version from build.gradle.kts" >&2
  exit 1
fi

echo "Releasing version: $VERSION"

# Optional: ensure clean working tree
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes before releasing." >&2
  exit 1
fi

# Publish to Sonatype Central (nmcp plugin)
./gradlew publishAllPublicationsToCentralPortal

# Tag and push the tag
git tag -a "v$VERSION" -m "Release $VERSION"
git push origin "v$VERSION"

echo "Release v$VERSION completed."
