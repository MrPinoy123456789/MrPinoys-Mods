# Cheap Lemon: brain, knowledge pack and MCP server

Three pieces, all dependency-free Node 22:

| File | What it is |
|---|---|
| `tools/lemon/build-pack.mjs` | Builds `out/pack.md`: Lemon's rules, `GUIDE.md`, `HINTS.md`, the owner decisions log, open agenda items and a room table. It replaces reading the repo during play. |
| `tools/lemon/brain.mjs` | A live Lemon that costs very little. It runs the `wait` loop itself, handles joins, leaves, quiet and errors with rules, and calls a small model only for player questions, answers, reports and floor clears. It writes the notes file in `docs/playtests/`. |
| `tools/server/mcp.mjs` | An MCP server over `pdserver.mjs`, so Claude Code or Devin can drive Lemon without a shell. |

## 1. Build the pack

```bash
node tools/lemon/build-pack.mjs
```

Rebuild it whenever rooms, `AGENDA.md`, the decisions log, `GUIDE.md` or `HINTS.md`
change. `GUIDE.md` is a draft seeded from the decisions log; correct it. `HINTS.md`
is empty on purpose: write the nudge and clue for each puzzle room by hand, so a
model never invents a hint.

Prompt caching needs a minimum prompt length; Haiku's minimum is higher than
Sonnet's. At about 3k tokens the pack may sit below it. Check the `cache read`
count in the notes file's usage line. If it stays 0, the pack is still cheap, and
it caches once `GUIDE.md` and `HINTS.md` grow.

## 2. Run the brain (live sessions)

```bash
node tools/lemon/brain.mjs --player <name>
```

It needs `ANTHROPIC_API_KEY` and a running test server. The default model is Haiku
4.5; pass `--model claude-sonnet-5-5` for a sharper Lemon. `--dry` prints Lemon's
lines instead of sending them. Ctrl+C ends the session and hands Lemon back to the
guide. It also ends by itself after the player has been offline 15 minutes or the
server has been down 10.

Lines tagged `ESCALATE` in the notes need a stronger agent: bug reports, answers
missing from the briefing, admin actions. `CONFUSION` lines are the confusion log.
The brain does not write the playtest write-up. After the session, point Claude
Code (Sonnet) at the notes file and run `/playtest`.

## 3. MCP server (Claude Code or Devin as Lemon)

Tools: `status`, `sync`, `wait_events`, `context` (trimmed unless `full`),
`lemon_say`, `lemon_ask`, `lemon_reply`, `lemon_think`, `lemon_quiet`,
`lemon_mode`, `knowledge_pack`, `session_notes`. `--admin` adds `server_start`,
`server_stop`, `server_cmd`, `room_bias_hold` (hold the player's doors while a
bias is set up; it runs out on its own) and `room_bias` (steer the next floors
toward a room under test, `/dungeon admin bias`). Keep admin off for anything remote.

While the agent keeps making calls, the MCP server refreshes llm mode on its own
timer for every player it put in llm mode, so pausing `wait_events` to
investigate no longer drops questions to the guide fallback.

**Claude Code (local, stdio):**

```bash
claude mcp add lemon -- node "A:/MrPinoys Mods/pocketdungeons/tools/server/mcp.mjs" --admin
```

**Devin (remote, HTTP):** Devin runs in the cloud, so it has to reach your PC
through a tunnel. The server only listens on 127.0.0.1 and refuses every request
without the bearer token.

1. Set a long random secret in `PD_MCP_TOKEN`, then start the server:
   `node tools/server/mcp.mjs --http 8787`.
2. Tunnel it, for example `cloudflared tunnel --url http://127.0.0.1:8787`.
3. In Devin, add a custom MCP server (Streamable HTTP) at
   `https://<tunnel-host>/mcp` with the header `Authorization: Bearer <token>`.

Tell the agent: "Call knowledge_pack, then sync, then loop on wait_events for
player <name>, following its rules." Over HTTP, keep `wait_events` short (60 s,
the default) so client request timeouts do not cut it off.
