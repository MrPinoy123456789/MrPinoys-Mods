# Lemon interviewer runbook

The shared runbook is [lemon/docs/LEMON_AGENT.md](../../lemon/docs/LEMON_AGENT.md).
Read it first, including its Pocket Dungeons briefing. The companion mod lives in
`lemon/`; the server tool and MCP live at the workspace root in `tools/playtest/`.

For Pocket Dungeons, read `LEMON_SPEC.md`, `playtests/AGENDA.md`,
`playtests/LIVE_CHECKS.md`, recent playtests, and `../tools/lemon/GUIDE.md`.
The merged context's `pocketdungeons` key contains the game snapshot; `lemon`
contains companion state. Game journals and Lemon's companion journal are
separate. Keep notes and the final write-up in this game's `docs/playtests/`.

From the workspace root:

```text
node tools/playtest/playtest.mjs status
node tools/playtest/playtest.mjs sync
node tools/playtest/playtest.mjs wait --player Alex --timeout 240
node tools/playtest/playtest.mjs context Alex
```

Reply or think before researching a player's ask. Never change their run or
restart the server without permission. Hand back with `lemon mode Alex guide`
when finished. Use the playtest skill for the write-up and rebuild the game
knowledge pack with `node pocketdungeons/tools/lemon/build-pack.mjs` afterward.
