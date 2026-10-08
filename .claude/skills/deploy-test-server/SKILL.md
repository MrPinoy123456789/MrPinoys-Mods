---
name: deploy-test-server
description: Build, upload and restart the Pocket Dungeons mod on the Kinetic test server, and check it came up. Encodes the safety rules (test server 714a0605 only, never 365f9682, check kinetic.env first, always --restart, ask before restarting with players online) and the pdserver.mjs commands. Use when asked to deploy, build and restart, check server status, or drive the test server.
---

# Deploying to the test server

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Tools are in `A:\MrPinoys Mods\pocketdungeons\tools\server`
(`pdserver.mjs`, README.md there has every command).

## Hard rules

1. **Target:** the Kinetic test server `714a0605` on kineticpanel.net. **Never touch `365f9682`.** It is not a
   test target.
2. **Check `tools/server/kinetic.env` before any start, stop or deploy.** It redirects every command to the
   panel server named there (`KINETIC_SERVER_ID`). Every start/stop/upload prints which server it is about to
   act on; read that line. If the id is not `714a0605`, stop and tell the owner.
3. **Always deploy with `--restart`.** A jar uploaded without a restart leaves the running server on the old jar,
   and lazy class loading then throws (it broke `/dungeon map` with a ZipException once).
4. **Players online:** check `status` first. If anyone is online, ask the owner before restarting; a restart
   boots them. Zero players means go ahead.
5. Deploy only when asked. Committing, deploying and restarting are separate decisions the owner makes.
6. Never kill the server process; `stop` saves the world first.

## Steps

1. Be on a clean, tested tree: `gradlew test runGameTest` green (see `add-tests`). Commit first if the owner
   asked to "commit and deploy" (commit message ends with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`).
2. Build the jar into `A:\MrPinoys Mods\dist`: `.\gradlew.bat dist --console=plain` (from `pocketdungeons`).
   `deploy` needs exactly one `MrPinoys_Pocket_Dungeons-*.jar` there (not `-sources`); delete stale ones first.
3. Check state: `node tools/server/pdserver.mjs status` (from `pocketdungeons`). Note players online.
4. Deploy: `node tools/server/pdserver.mjs deploy --restart`. It uploads to `/mods`, stops cleanly, starts, and
   waits for Ready (about 25 s; a hung start is rare, read the last log lines it prints).
5. Verify: `node tools/server/pdserver.mjs status` should say `UP. There are 0 of a max of 20 players online`
   and show recent log lines. Look for `Loaded N cube recipes`, `All N core loot tables verified present`, and
   no `ERROR`/`Exception`/`Failed to load` lines. Pack problems show here as findings.
6. Report: the commit, that it is up, the checks you saw, and what you could not check (no player online means no
   in-game check; say a live check is owed and which one in `docs/playtests/LIVE_CHECKS.md`).

## Other commands (from the README)

`cmd <command>` runs a console command and prints the reply (`cmd list`, `cmd dungeon admin validate`);
`context <player>` prints a player's state as JSON; `chat`/`wait`/`sync` read events; `lemon say|ask|reply|think`
talk through Lemon. On the remote server a command that needs its reply is followed by an unknown marker
command, so the log shows `Unknown or incomplete command` lines; that is by design (PD-148), not a fault.
Long waits: run them in the background and let the notification arrive rather than polling in a loop; the owner
has interrupted long polling waits.

## If something goes wrong

- Upload failed or Ready never reached: `status`, read the log tail, retry once; if it still fails, report the
  exact output instead of trying other hosting routes.
- Wrong server id printed: stop immediately, change nothing, tell the owner.
- A new jar loaded but content looks stale: `dungeon admin validate` through `cmd` lists pack findings.
