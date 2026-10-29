---
name: deploy-test-server
description: Build and deploy Pocket Dungeons plus the standalone Lemon companion to the Kinetic test server. Enforces target 714a0605, fresh jars, restart, and approval with players online. Use when asked to deploy, restart, or check the test server.
---

# Deploying to the test server

Follow the workspace punctuation rules. Tools live at
`A:\MrPinoys Mods\tools\playtest`; read its README. Commands below run from the
workspace root. The canonical CLI is `playtest.mjs`; `pdserver.mjs` forwards to it.

## Hard rules

1. Only target Kinetic test server `714a0605`. Never touch `365f9682`.
2. Before a start, stop or deploy, verify the selected `KINETIC_SERVER_ID` without
   printing the API key. Configuration comes from environment variables or
   `tools/playtest/kinetic.env`. Legacy local files and the main checkout's copy
   are migration fallbacks for worktrees. Read the target announcement; stop if it differs.
3. Deploy only when the owner asks. Commits, deployments and restarts are separate
   decisions. A request to implement code does not authorize a remote restart.
4. Check status first. If any player is online, ask the owner before restarting.
5. Always use `deploy --restart`. Uploading a replacement under a running server
   leaves old classes active and can break lazy class loading.
6. Never kill the process. `stop` saves the world before shutting down.
7. Deploy never builds. Build fresh **both** `MrPinoys_Pocket_Dungeons-*.jar` and
   `MrPinoys_Lemon-*.jar` into `dist/`, with no sources jars or ambiguous versions.
   Ask before deleting old artifacts. Verify both timestamps after the last edit.
8. Back up the deployed jars before uploading. Never print or commit credentials.

## Verify and build

Run the PD suite from `pocketdungeons` and Lemon's build from `lemon`. Each must be
green before deploying. PD's default tests run without Lemon; its optional
`-PlemonTestJar=../lemon/build/libs/MrPinoys_Lemon-0.1.0.jar` gametest run exercises
the command bridge with the independent jar, never a compile dependency.

From the workspace root, build both jars in the same chain as the approved deploy:

```text
pocketdungeons\gradlew.bat -p pocketdungeons dist --console=plain
lemon\gradlew.bat -p lemon dist --console=plain
node tools/playtest/playtest.mjs status
node tools/playtest/playtest.mjs deploy --restart
```

The deploy uploads both jars and sets the PD click hooks in
`config/lemon/lemon.json`, preserving timing values and existing custom hooks.
`onInteract` opens `dungeon lemonmenu`; the written-book hook runs
`dungeon lemonarchive`, which validates the diary marker itself. Other games do
not need PD installed to use Lemon.

`pocketdungeons/tools/ship.sh --deploy` also builds Lemon before its upload step;
it still refuses a dirty tree, stale PD jar, or players online without approval.
Do not commit just to get around that guard unless the owner requested a commit.

## Acceptance and reporting

After Ready, run:

```text
node tools/playtest/playtest.mjs status
node tools/playtest/playtest.mjs cmd dungeon admin validate
```

Look for `Pack validation passed: no findings.`, expected recipe and loot-table
startup messages, and no mod errors. Check Lemon is loaded. With a participating
player, verify a tour, main-hand keystone summon, menu click, diary archive,
ask/reply delivery through wait, quiet, and merged context. A second Charter
scenario is required to complete the extraction spec's two-game live check.
Without a player, say those checks are still owed; do not claim acceptance.

Report the commit if any, both jar names and sizes, target id, backup/rollback
path, status, validation result and unverified live checks. If upload or Ready
fails, inspect status and log tail, retry once if appropriate, then report the
exact failure. Never switch hosting routes or hotfix production.

Remote command replies use marker commands, so `Unknown or incomplete command`
marker lines are expected. Long waits should run in the background, not a polling
loop. Use `lemon/docs/LEMON_AGENT.md` for interviewer operation.
