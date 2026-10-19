#!/usr/bin/env bash
# Build, test and (optionally) deploy Pocket Dungeons with one short call.
#
#   tools/ship.sh                 clean build, pure tests, gametests, dist; prints only the result lines
#   tools/ship.sh --quick         the same without `clean`
#   tools/ship.sh --pure          compileJava, compileTestJava and the pure `test` task only: no lock, no
#                                 gametest, no dist. Run it (and tools/check_dashes.sh) before the full suite.
#   tools/ship.sh --pure-fast     the same pure tests, but run 6 at a time by tools/pure_tests.py (about half the time);
#                                 skips the dedicated server run that `test` also does. Use while iterating.
#   tools/ship.sh --no-dist       skip the dist jar copy (the usual choice for a branch that will not be deployed)
#   tools/ship.sh --only <Name>   compile, then run only the gametests whose id contains <Name> (a class such as
#                                 HerdGameTest, or a method name); no pure tests, no dist. Takes a slot.
#   tools/ship.sh --deploy        after a green run: refuse if the tree is dirty or the jar is older than
#                                 the last commit, deploy --restart to the Kinetic test server, run validate
#   tools/ship.sh --deploy --force-players   deploy even if players are online (ask the owner first)
#
# Every run prints its elapsed time and the required gametest count. The full log goes to
# A:/tmp/ship-<worktree name>.log, one file per worktree, so agents never overwrite each other's log.
#
# Locking. The gametest server (Fabric GameTestServer) does not bind a network port and each worktree has its
# own build/run/gameTest directory. The one thing that did bind a fixed port was dungeonIntegrationTest (a real
# dedicated server on 25581, run by `test`); build.gradle.kts now reads PD_INTEGRATION_PORT and this script
# gives every run a free one, so --pure needs no lock. What parallel suites still share is the machine
# (12 cores, about 2 GB per Gradle daemon plus the test JVM), so a full suite takes one of PD_SHIP_SLOTS
# slots (default 3). Slot 1 is the old global lock A:/tmp/gradle-suite.lock, so an agent still on the old
# script and one on this script exclude each other. A slot left behind by a crash is reclaimed after 45 minutes.
#
# It never commits and never pushes. Deploy only when the owner asked for it.
set -u
cd "$(dirname "$0")/.." || exit 2
# Drive C: filled up during the 2026-10-12 build (many parallel gradle runs). Keep Gradle's cache and all temp
# writes on A: when it exists; the cache was copied there once. Harmless where A:/gradle-home is absent.
# Temp defaults to A:/tmp-ci, not A:/tmp: A:/tmp grew past 700,000 entries and the Jansi cleanup in Gradle lists the
# whole directory, which ran out of heap and killed full runs (2026-10-19). Logs and lock slots stay in A:/tmp.
if [ -d "A:/gradle-home" ]; then
  export GRADLE_USER_HOME="A:/gradle-home"
  mkdir -p "${PD_TMP:-A:/tmp-ci}"
  export TMP="${PD_TMP:-A:/tmp-ci}" TEMP="${PD_TMP:-A:/tmp-ci}" TMPDIR="${PD_TMP:-A:/tmp-ci}"
fi
START=$(date +%s)
SLOTS="${PD_SHIP_SLOTS:-3}"
NAME="$(basename "$(dirname "$PWD")")"
LOG="A:/tmp/ship-$NAME.log"
CLEAN="clean"
DEPLOY=0
FORCE=0
PURE=0
DIST=1
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) CLEAN="" ;;
    --deploy) DEPLOY=1 ;;
    --force-players) FORCE=1 ;;
    --pure) PURE=1 ;;
    --pure-fast) PURE=2 ;;
    --no-dist) DIST=0 ;;
    --only)
      shift
      if [ $# -eq 0 ] || [ -z "$1" ]; then echo "--only needs a gametest class or method name"; exit 2; fi
      ONLY="$1" ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done
if [ "$PURE" -ne 0 ] && { [ "$DEPLOY" -eq 1 ] || [ -n "$ONLY" ]; }; then echo "--pure and --pure-fast cannot be combined with --deploy or --only"; exit 2; fi
if [ -n "$ONLY" ] && [ "$DEPLOY" -eq 1 ]; then echo "--only cannot be combined with --deploy (a deploy needs the whole suite)"; exit 2; fi

mkdir -p A:/tmp
HELD=""
elapsed() {
  local s=$(( $(date +%s) - START ))
  printf '%dm%02ds' $((s / 60)) $((s % 60))
}
finish() {
  [ -n "$HELD" ] && rm -rf "$HELD" 2>/dev/null
  echo "elapsed $(elapsed)"
}
trap finish EXIT

# `test` includes dungeonIntegrationTest, a real dedicated server that binds a port. build.gradle.kts reads the
# port from PD_INTEGRATION_PORT (default 25581), so each run takes a free one in 25600-25999 and two worktrees
# never fight over it. Not 25565 (the test server) and not 25581/25582 (the old fixed ports).
pick_port() {
  local p tries=0
  while [ "$tries" -lt 50 ]; do
    p=$((25600 + RANDOM % 400))
    if ! (echo > "/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "$p"; return 0; fi
    tries=$((tries + 1))
  done
  echo 25581
}
export PD_INTEGRATION_PORT="$(pick_port)"

slot_path() { if [ "$1" -eq 1 ]; then echo "A:/tmp/gradle-suite.lock"; else echo "A:/tmp/gradle-suite.lock.$1"; fi; }

take_slot() {
  local waited=0 i p
  while true; do
    i=1
    while [ "$i" -le "$SLOTS" ]; do
      p="$(slot_path "$i")"
      if [ -d "$p" ] && [ -n "$(find "$p" -maxdepth 0 -mmin +45 2>/dev/null)" ]; then rm -rf "$p"; fi
      if mkdir "$p" 2>/dev/null; then
        HELD="$p"
        echo "$NAME $$ $(date +%H:%M:%S)" > "$p/owner" 2>/dev/null
        echo "slot $i of $SLOTS taken (waited ${waited}s)"
        return 0
      fi
      i=$((i + 1))
    done
    sleep 5; waited=$((waited + 5))
    if [ "$waited" -ge 3600 ]; then echo "gave up waiting for a suite slot"; exit 3; fi
  done
}

if [ "$PURE" -eq 2 ]; then
  python tools/pure_tests.py | tail -40
  exit "${PIPESTATUS[0]}"
fi
if [ "$PURE" -eq 1 ]; then
  ./gradlew.bat compileJava compileTestJava test --continue --console=plain > "$LOG" 2>&1
  grep -a -E "BUILD |FAILED|AssertionError|error:|Exception in thread" "$LOG" | cut -c1-240 | head -15
  if ! grep -a -q "BUILD SUCCESSFUL" "$LOG"; then echo "PURE NOT GREEN (full log: $LOG)"; exit 1; fi
  echo "PURE GREEN ($(grep -a -c -E '^> Task :[A-Za-z0-9]+Test$' "$LOG") pure test tasks ran)"
  exit 0
fi

take_slot

if [ -n "$ONLY" ]; then
  # A gametest id is pocketdungeons-gametest:<class_in_snake_case>_<method_in_snake_case>. A bare class name
  # (HerdGameTest) matches all its methods; a lowercase-first name matches that method in any class; a full id
  # or a pattern with ':' or '*' in it is passed through as given.
  snake() { echo "$1" | sed -E 's/([a-z0-9])([A-Z])/\1_\2/g' | tr 'A-Z' 'a-z'; }
  case "$ONLY" in
    *:*|*\**) FILTER="$ONLY" ;;
    [A-Z]*) FILTER="pocketdungeons-gametest:$(snake "$ONLY")*" ;;
    *) FILTER="pocketdungeons-gametest:*$(snake "$ONLY")*" ;;
  esac
  echo "gametest filter: $FILTER"
  export PD_GAMETEST_FILTER="$FILTER"
  ./gradlew.bat -I tools/gradle/gametest-filter.init.gradle compileJava compileTestJava runGameTest --continue --console=plain > "$LOG" 2>&1
else
  TASKS="test runGameTest"
  [ "$DIST" -eq 1 ] && TASKS="$TASKS dist"
  ./gradlew.bat $CLEAN $TASKS --continue --console=plain > "$LOG" 2>&1
fi
grep -a -E "BUILD |FAILED|AssertionError|GameTestAssertException|error:|All .* required|failed at" "$LOG" | cut -c1-240 | head -15
if ! grep -a -q "BUILD SUCCESSFUL" "$LOG"; then echo "SUITE NOT GREEN (full log: $LOG)"; exit 1; fi
REQ=$(grep -a -o -E "All [0-9]+ required tests passed" "$LOG" | tail -1)
echo "SUITE GREEN (${REQ:-no required-test line found})"

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
