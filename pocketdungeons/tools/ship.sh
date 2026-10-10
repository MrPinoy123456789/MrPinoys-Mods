#!/usr/bin/env bash
# Build, test and (optionally) deploy Pocket Dungeons with one short call.
#
#   tools/ship.sh                 clean build, pure tests, gametests, dist; prints only the result lines
#   tools/ship.sh --quick         the same without `clean`
#   tools/ship.sh --deploy        after a green run: refuse if the tree is dirty or the jar is older than
#                                 the last commit, deploy --restart to the Kinetic test server, run validate
#   tools/ship.sh --deploy --force-players   deploy even if players are online (ask the owner first)
#
# The suite takes a lock directory so two runs (two agents) never bind the test server port at once.
# It never commits and never pushes. Deploy only when the owner asked for it.
set -u
cd "$(dirname "$0")/.." || exit 2
LOCK="A:/tmp/gradle-suite.lock"
LOG="A:/tmp/ship.log"
CLEAN="clean"
DEPLOY=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --quick) CLEAN="" ;;
    --deploy) DEPLOY=1 ;;
    --force-players) FORCE=1 ;;
    *) echo "unknown option $arg"; exit 2 ;;
  esac
done

mkdir -p A:/tmp
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  sleep 10; waited=$((waited + 10))
  if [ "$waited" -ge 2400 ]; then echo "gave up waiting for $LOCK"; exit 3; fi
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

./gradlew.bat $CLEAN test runGameTest dist --continue --console=plain > "$LOG" 2>&1
grep -a -E "BUILD |FAILED|AssertionError|GameTestAssertException|error:|All .* required|failed at" "$LOG" | cut -c1-240 | head -15
if ! grep -a -q "BUILD SUCCESSFUL" "$LOG"; then echo "SUITE NOT GREEN (full log: $LOG)"; exit 1; fi
echo "SUITE GREEN"

[ "$DEPLOY" -eq 1 ] || exit 0

if [ -n "$(git status --porcelain)" ]; then echo "REFUSING TO DEPLOY: uncommitted changes"; exit 4; fi
JAR=$(ls ../dist/MrPinoys_Pocket_Dungeons-*.jar 2>/dev/null | grep -v sources | head -1)
if [ -z "$JAR" ]; then echo "REFUSING TO DEPLOY: no jar in dist/"; exit 4; fi
if [ "$(stat -c %Y "$JAR")" -lt "$(git log -1 --format=%ct)" ]; then echo "REFUSING TO DEPLOY: dist jar is older than the last commit"; exit 4; fi
STATUS=$(node tools/server/pdserver.mjs status 2>&1 | head -3)
echo "$STATUS" | head -2
if ! echo "$STATUS" | grep -q "There are 0 of" && [ "$FORCE" -ne 1 ]; then
  echo "REFUSING TO DEPLOY: players are online (use --force-players after asking the owner)"; exit 5
fi
node tools/server/pdserver.mjs deploy --restart 2>&1 | tail -3
node tools/server/pdserver.mjs cmd dungeon admin validate 2>&1 | tail -3
