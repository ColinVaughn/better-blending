#!/usr/bin/env bash
# Prints the changelog for a release tag as Markdown.
#
# The "## <version>" section of CHANGELOG.md wins when there is one, so a release can
# carry hand-written notes. Otherwise the notes are the commit subjects since the
# previous tag, which needs the full history (actions/checkout with fetch-depth: 0).
set -euo pipefail

tag="$1"
version="${tag#v}"

if [ -f CHANGELOG.md ]; then
  # Everything under "## <version>" (optionally "## [<version>]" or with a date after
  # it) up to the next "## " heading.
  section=$(awk -v v="$version" '
    /^## / {
      if (found) exit
      heading = $0
      sub(/^## +\[?/, "", heading)
      split(heading, words, /[] ]/)
      if (words[1] == v) { found = 1; next }
    }
    found { print }
  ' CHANGELOG.md | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac)
  if [ -n "$section" ]; then
    printf '%s\n' "$section"
    exit 0
  fi
fi

previous=$(git describe --tags --abbrev=0 --match 'v*' "${tag}^" 2>/dev/null || true)
range="${previous:+${previous}..}${tag}"
git log --no-merges --format='- %s' "$range"
