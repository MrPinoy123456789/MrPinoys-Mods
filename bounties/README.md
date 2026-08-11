# Bounties

A rotating bounty board of kill quests for Minecraft Fabric 26.2. Vanilla clients
need nothing installed — everything is server-side.

## Layout

- `core/` — Pure Java rules. No Minecraft on its classpath.
- `fabric/` — The mod: commands, events, persistence, chat, rewards.

## Build

Needs JDK 25. The Gradle wrapper is included.

    ./gradlew build

Jar lands in `fabric/build/libs/bounties-0.1.0.jar`. Drop it in `mods` alongside
Fabric API.

## Run the core tests (no Gradle, no network)

    javac --release 25 -d build core/src/main/java/bounties/core/*.java core/src/test/java/bounties/core/*.java
    java -cp build bounties.core.BountiesTest

Expect `31 tests, 0 failed`.

## Commands

    /bounty                  show the board and your held bounties
    /bounty accept <1|2>    accept a board slot
    /bounty abandon <n>     drop a held bounty
    /bounty reload          re-read bounties.json (operator only)

## Config

Generated at `config/bounties/` on first boot.

`bounties.json` — the pool. Each entry is a mob id, kill count, diamond reward,
label, and display description.

`state.json` — per-player held bounties and progress. Written atomically.

## How the board works

The two public slots are derived from wall-clock time, not stored state. A new
bounty appears every 30 minutes, with slots staggered by 15 minutes so bounty
announcements do not land on the same wall-clock tick as quizengine's scheduled
trivia. Restarting the server reproduces the exact same board for the same moment.

Accepted bounties do not expire when the board rotates; each player may hold up
to three until completed or abandoned.

## Rewards

Diamonds are paid directly into the player's inventory, dropping at their feet
if full. No cross-mod economy is required.
