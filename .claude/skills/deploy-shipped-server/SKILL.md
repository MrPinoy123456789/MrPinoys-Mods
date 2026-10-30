---
name: deploy-shipped-server
description: Deploy Charter, Foundry, Foundry content and the Bedrock (Geyser) pack to the owner's shipped kingdom server, Kinetic d212ecae. Covers the Minecraft version check, the players online check, backups, the script, and the mistakes already made. Use when asked to deploy, restart or update the kingdom server. Not for Pocket Dungeons, which has its own skill and its own test server.
---

# Deploying to the shipped kingdom server

House rule: no em dashes and no spaced double hyphens (root `CLAUDE.md`). Read `docs/KINGDOM_SERVER.md` first.

## Hard rules

1. This is `d212ecae`, the owner's live Charter server. Pocket Dungeons never goes here; it deploys to `714a0605`
   only (skill `deploy-test-server`). Never touch `365f9682`.
2. Deploy only when the owner asks, in this conversation. Implementing a change does not authorise a deploy or a
   restart. Asking about the deploy is fine; the permission classifier refuses an unasked upload.
3. Never print or commit the API key (`tools/playtest/kinetic.env`).
4. If anyone other than the owner is online, ask before restarting. The script refuses; pass `--allow name` only
   after the owner says that account is theirs (a Bedrock account has a leading dot, such as `.Name`).
5. Never kill the process. `stop` saves first. Never upload a jar under a running server.

## Check the server's Minecraft version first

The owner moves the server between Minecraft versions by staging a folder (`/mods_26.2_set`, `/mods`) and ports the
mods on their own branches. Overwriting a ported jar with a stale build broke the server once (a 26.2 Foundry jar on
a 26.3 server, then a restart on the half staged set). Before uploading, list `/mods` and read the Fabric API jar
name: it ends in `+26.3` or similar. Build from the branch whose `gradle.properties` has the same
`minecraft_version`. The script checks this and aborts on a mismatch. If `/mods` holds a jar you did not build, back
it up and ask rather than replacing it.

## Run it

Build both first (`./gradlew.bat build` in `charter` and `foundry`; both must be green), then from the workspace root:

```bash
node tools/playtest/shipped-deploy.mjs --foundry --charter --content --bedrock --dry
node tools/playtest/shipped-deploy.mjs --foundry --charter --content --bedrock
```

Flags: `--foundry`, `--charter` (the jars), `--content` (weapon items, recipes and icons into `config/foundry`),
`--bedrock` (Geyser mappings and the Bedrock pack), `--allow a,b`, `--dry` (checks and lists, changes nothing). Jar
folders come from `FOUNDRY_DIR` and `CHARTER_DIR` when the build lives in a worktree. Pass `MSYS_NO_PATHCONV=1` in
Git Bash when a path argument starts with `/`.

It backs up each replaced jar to `/mods_backup` with a `before-<time>` suffix, saves, stops, uploads, starts, and
prints the log lines that matter (`Done`, `Foundry: N items registered, M problems`, `Charter state ready`, Geyser's
`Registered N custom items`). Report those lines, the backup names, and what you could not check. A restart drops
Geyser players too.

## After a Foundry content or texture change

Regenerate with `python tools/gen_weapons.py`, `python tools/gen_icons.py` and `python tools/gen_bedrock.py` (all in
`foundry`), rebuild, then deploy with `--content --bedrock`. A new texture changes the Java resource pack and the
Bedrock pack, so players re-download both.

## Known traps

- Chunky pregen can run out of heap (about 4 GB) and a crash auto resumes the task; see the manual.
- A mod jar for another Minecraft version fails at start with "Incompatible mods found". Remove the jar, restart,
  restore from `/mods_backup`.
- The panel returns 502 for a moment while the server restarts; retry once.
