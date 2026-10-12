#!/usr/bin/env bash
# Make (or refresh) the worktree for one roadmap task.
#
#   tools/new_worktree.sh <task-id> <topic>      e.g.  tools/new_worktree.sh T10 quick-affix-fixes
#
# Creates <repo root>/.claude/worktrees/rm-<id> (id lower-cased) on the branch feature/rm-<id>-<topic>, started
# from the integration branch feature/act-1-2-roadmap, and prints the worktree path on the last line. Safe to run
# again: an existing worktree is kept, the integration branch is merged into it again when its tree is clean, and
# the path is printed. Works from the main checkout or from any worktree. It never pushes.
set -u
BASE="feature/act-1-2-roadmap"
if [ $# -ne 2 ]; then echo "usage: tools/new_worktree.sh <task-id> <topic>"; exit 2; fi
ID="$(echo "$1" | tr 'A-Z' 'a-z')"
TOPIC="$(echo "$2" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//')"
case "$ID" in t[0-9][0-9]*) ;; *) echo "task id looks wrong: $1 (expected something like T10)"; exit 2 ;; esac
if [ -z "$TOPIC" ]; then echo "topic is empty after cleaning: $2"; exit 2; fi

COMMON="$(git rev-parse --path-format=absolute --git-common-dir)" || exit 2
ROOT="$(dirname "$COMMON")"
DIR="$ROOT/.claude/worktrees/rm-$ID"
BRANCH="feature/rm-$ID-$TOPIC"

if ! git rev-parse --verify --quiet "$BASE" > /dev/null; then echo "base branch $BASE does not exist"; exit 2; fi

if [ -d "$DIR" ]; then
  CUR="$(git -C "$DIR" branch --show-current)"
  if [ "$CUR" != "$BRANCH" ]; then
    echo "worktree $DIR already exists on branch $CUR, not $BRANCH; leaving it alone"
    echo "$DIR"
    exit 1
  fi
  if [ -z "$(git -C "$DIR" status --porcelain)" ]; then
    git -C "$DIR" merge --no-edit "$BASE" | tail -1
  else
    echo "worktree has uncommitted changes; not merging $BASE"
  fi
  echo "$DIR"
  exit 0
fi

mkdir -p "$ROOT/.claude/worktrees"
if git rev-parse --verify --quiet "$BRANCH" > /dev/null; then
  git worktree add -q "$DIR" "$BRANCH" > /dev/null || exit 1
  git -C "$DIR" merge --no-edit "$BASE" | tail -1
else
  git worktree add -q -b "$BRANCH" "$DIR" "$BASE" > /dev/null || exit 1
fi
echo "branch $BRANCH from $BASE"
echo "$DIR"
