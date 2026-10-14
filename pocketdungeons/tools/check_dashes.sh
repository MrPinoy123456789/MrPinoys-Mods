#!/usr/bin/env bash
# House rule check (root CLAUDE.md): no em dash and no spaced double hyphen in text you wrote.
#
#   tools/check_dashes.sh            scan the lines this branch ADDED against master (or PD_BASE)
#   PD_BASE=other-branch tools/check_dashes.sh
#
# Scans the added lines of: commits since the merge base with the base branch, staged changes, unstaged
# changes to tracked files, and untracked files (the whole file, since all of it is new). Prints
# file:line: text for each hit and exits 1; exits 0 when clean. Only added lines count, so old text that
# still has a dash is left alone.
#
# Not violations: command line flags (--stacktrace), i-- style code, a single hyphen in a compound word,
# horizontal rules and table separators, and a spaced "--" in a git command (end of options marker). A line
# that must keep one (a quoted spec, somebody else's text) can carry the marker  dashes-ok  anywhere on it.
# Binary files and this script are skipped.
set -u
BASE="${PD_BASE:-master}"
cd "$(dirname "$0")/.." || exit 2
cd "$(git rev-parse --show-toplevel)" || exit 2
if ! git rev-parse --verify --quiet "$BASE" > /dev/null; then echo "base branch $BASE does not exist"; exit 2; fi
MB="$(git merge-base "$BASE" HEAD)" || exit 2
EM="$(printf '\342\200\224')"

scan_awk='
function bad(line) {
  if (index(line, "dashes-ok") > 0) return 0
  if (index(line, em) > 0) return 1
  if (index(line, " -- ") > 0) {
    if (line ~ /git [^#]* -- /) return 0
    return 1
  }
  if (line ~ / --$/) return 1
  return 0
}
'

HITS=0
# 1. Added lines of everything tracked: working tree against the merge base (commits + staged + unstaged).
OUT="$(git diff -U0 --no-color --no-ext-diff "$MB" -- ":/" ":(top,exclude)pocketdungeons/tools/check_dashes.sh" 2>/dev/null | awk -v em="$EM" "$scan_awk"'
  /^\+\+\+ / { file = substr($0, 7); if ($0 == "+++ /dev/null") file = ""; next }
  /^@@ / { match($0, /\+[0-9]+/); ln = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
  /^\+/ { if (file != "" && bad(substr($0, 2))) print file ":" ln ": " substr($0, 2); ln++; next }
')"
if [ -n "$OUT" ]; then echo "$OUT" | cut -c1-220; HITS=1; fi

# 2. Untracked files: every line is new.
while IFS= read -r f; do
  [ "$f" = "pocketdungeons/tools/check_dashes.sh" ] && continue
  case "$f" in */check_dashes.sh) continue ;; esac
  grep -I -q . "$f" 2>/dev/null || continue
  O2="$(awk -v em="$EM" -v f="$f" "$scan_awk"'{ sub(/\r$/, ""); if (bad($0)) print f ":" FNR ": " $0 }' "$f")"
  if [ -n "$O2" ]; then echo "$O2" | cut -c1-220; HITS=1; fi
done < <(git ls-files --others --exclude-standard)

if [ "$HITS" -ne 0 ]; then echo "DASHES FOUND: rewrite with : ; , ( ) or a new sentence (see CLAUDE.md)"; exit 1; fi
echo "dashes clean against $BASE"
exit 0
