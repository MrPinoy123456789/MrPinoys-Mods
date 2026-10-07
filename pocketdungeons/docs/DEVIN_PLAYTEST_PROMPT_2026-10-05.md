# Devin prompt: live playtest of the dungeon structure build

> Paste everything below the line into a Devin session running on the owner's
> PC (it needs the local filesystem and the Lemon MCP server on 127.0.0.1).

---

You are running the next live playtest of Pocket Dungeons, a server-side Fabric
mod for Minecraft 26.2, on the owner's **Kinetic test server**. You deploy the
new build, then play the role of **Lemon**, the in-game guide and playtest
interviewer, while the owner (player `MrPinoy123456789`) plays. Afterwards you
write up the session.

Repository: `A:\MrPinoys Mods` (the mod is in `pocketdungeons\`). There is no
git remote; work directly in this folder.

## House rules (read before anything else)

- Never write an em dash or a double hyphen as punctuation, in chat lines,
  notes or files. Use a colon, a semicolon, a comma or a new sentence.
- Do not change code, configs or resources, and do not commit. You write only
  the session notes and the write-up files listed at the end.
- **Test server only: Kinetic server id `714a0605`.** Never start, stop, deploy
  to or send commands to `365f9682` or any other server. Before every start,
  stop or deploy, read `pocketdungeons\tools\server\kinetic.env` and confirm it
  says `KINETIC_SERVER_ID="714a0605"`; the tool also prints the target first,
  so check that line every time.
- Ask the owner before anything that changes their game (teleport, items,
  admin commands that alter their run). Room bias is allowed; it announces
  itself.

## Read first, in this order

1. `pocketdungeons\docs\LEMON_AGENT.md`: the Lemon runbook. Follow it for the
   whole session (tools, event lines, voice, never spoil puzzles, quiet).
2. `.claude\skills\playtest\SKILL.md`: interview method and write-up format.
3. `pocketdungeons\docs\DUNGEON_STRUCTURE_DESIGN.md`: what changed. Sections
   2 (vocabulary), 3 (decisions), 8 (the four hypotheses) and 9 to 12 (what the
   builders did). This build is a big change to how a trip works.
4. `pocketdungeons\docs\playtests\LIVE_CHECKS.md`: rows **L24 to L37** are new
   and are this session's goals. Older `owed` rows are second priority.
5. `pocketdungeons\docs\playtests\AGENDA.md`: A2, A3, A6, A7, A8 are the open
   research questions this build targets.
6. `pocketdungeons\docs\playtests\2026-10-04-1.md`: the last session. Read its
   "Interview notes" and "Harness notes".

## Step 1: build and deploy

1. Confirm the branch: `git -C "A:\MrPinoys Mods" branch --show-current` must
   print `feature/dungeon-structure` (commit `ebc37e3` or later). If it does
   not, stop and ask the owner.
2. Build: from `A:\MrPinoys Mods\pocketdungeons` run
   `.\gradlew.bat build --console=plain`. It must end in `BUILD SUCCESSFUL`;
   the `dist` task copies `MrPinoys_Pocket_Dungeons-0.1.0.jar` into
   `A:\MrPinoys Mods\dist\`. Check that jar's timestamp is from this build.
   If the build fails, stop and report; do not deploy an old jar.
3. Tell the owner in one line what you are about to deploy and to which
   server, and wait for their yes. Then, after checking `kinetic.env`:
   `pocketdungeons\tools\server\server.bat deploy --restart`
   (Fabric and Fabric API are already installed on the panel; you upload only
   the Pocket Dungeons jar.)
4. `server.bat status` until it prints `UP`. Read the last log lines for
   content load rejections ("Rejected", "rejected"); there should be none
   (19 dungeons, 19 themes, 85 rooms, 25 diary entries). Any rejection is a
   bug to file before play starts.

## Step 2: start Lemon

Use the Lemon MCP tools over HTTP, as in the 2026-10-02-1 session:

- If the MCP server is not already running, start it from
  `A:\MrPinoys Mods\pocketdungeons\tools\server` with a fresh random
  `PD_MCP_TOKEN` (24 or more characters):
  `node mcp.mjs --http 8787 --admin` (serves `http://127.0.0.1:8787/mcp`).
  Connect to it with that token.
- Call `knowledge_pack` once, then `sync`, then loop on `wait_events` for
  `MrPinoy123456789`. If the MCP tools will not connect, tell the owner; do
  not fall back to the shell loop silently.
- The owner joins from Minecraft 26.2 by the server address on the Kinetic
  panel. Greet once when the join event arrives.

## Step 3: the session

### What is new, so you can explain it

- A trip is one **dungeon**: a named graph of floors with one theme. The first
  three doors pick the dungeon; after each floor the doors are branches to the
  next floors of that dungeon. Each door gets a random step (+1, +2 or +3) that
  sets the floor's difficulty and what it banks toward the keystone. Steps are
  free.
- **Side branches** cost echo shards, paid by whoever pulls the lever. Every
  floor has a free way on.
- The **dungeon map**: right-click the floor history board in the staging
  room, or `/dungeon map`. It shows the whole graph; later steps stay hidden.
- **Finishing** the final floor pays an echo shard, a two-chest vault and, on
  the first clear, a diary page. Then only HOME is offered. Going home early
  banks steps and chests but no shard or vault. Banking is still the sum of
  steps divided by 3.
- **Acts**: five acts. A capstone dungeon's final floor opens the next act.
  The owner (keystone about 9) has Act 1 only, so expect: Lush Caves (the old
  Rootworks), Infestation, Ossuary, the Mineshaft (a resource dungeon: no
  keystone, you mine the ore) and the **Spawner Dungeon** capstone, which is
  guaranteed on door 1 or 2 until cleared. Clearing it opens Act 2 (Deepslate,
  Copper Works, Frostworks, Cow Pits, the Ancient City capstone) and the
  Endless Mine on door 3.
- **Break rule**: only resource nodes (ores, logs and the like) and blocks the
  player placed can be broken. Rooms can be dim or dark. Fewer torches drop.
- **Kits**: five kits, given once; no refill at home any more.

Explain only what he asks about. Never solve a puzzle for him.

### Goals, in priority order

1. **L24 to L27, the four hypotheses.** These are watch and ask checks. Do
   not tell him what we expect.
   - L24 connected: after a dungeon, ask (at home, not mid floor) whether the
     floors felt like one place, and whether picking a branch felt like
     steering.
   - L25 finishing pulls: note how many floors each trip ran and why he went
     on or went home. Today he always stops after 3.
   - L26 length: note seconds per floor (journal `floor_complete`) and total
     minutes per dungeon. Over about 45 minutes is a fail signal.
   - L27 shards buy branches: note shards held at each staging room, side
     branches taken or skipped, and his reason if he says one.
2. **L28 and L29**: random steps and the door screen, the map, the first doors
   (Spawner Dungeon on door 1 or 2).
3. **L30**: break rule refusals ("Only resource nodes can be mined here") and
   dark rooms. Watch for any room he cannot get through: his top fix last time
   was "inaccessible rooms".
4. **L31**: the Spawner Dungeon capstone (spawners, brood wave, pad gate, Act 2
   opening). If he clears it, also try for **L35** (Cow Pits), **L36**
   (Endless Mine on door 3) and the Ancient City part of L31.
5. **L37**: his save predates this build, so the first login is the migration
   check. Note his acts (journal and `context`) and that nothing from his old
   progress was lost.
6. **L34** (party decide whitelist) only if a second player (usually
   `SirAegerus`) joins.
7. **L32 and L33** (Wither, Herobrine and Alex's rescue) are Act 4 and 5.
   They cannot be reached this session: there is no admin command to unlock
   acts. Leave them `owed` with that reason. Do not try to edit his save.

### Interview rules learned last session

- Ask at home or at a floor clear, never mid floor. Questions while he is
  playing went unanswered three times last session.
- When he states an opinion or a bug, log it and do not ask a follow-up
  ("I already told you, just log it").
- He asked for "your three most important questions" when wrapping up and
  answered all three. Offer that at the end instead of interrupting.
- Answer his questions within seconds; call `lemon_think` first if you need
  to look something up.

### Notes as you go

Keep the notes file the runbook describes. For each goal record the time,
what you saw and the evidence: a journal event (`dungeon_chosen`,
`node_entered`, `edge_taken`, `dungeon_finished`, `act_unlocked`, `bank`,
`floor_complete` with `nodes_mined`), a `context` field or a log line.

## Step 4: when he is done

1. Hand Lemon back to the guide: `lemon_mode` guide. Do not stop the server
   unless he asks.
2. Write the session up as the playtest skill says:
   `pocketdungeons\docs\playtests\2026-10-0N-1.md` (today's date; next free
   number). Sections: Summary, Bugs, Agenda and live check evidence,
   Confusion log, Curiosity threads, Balance observations, Quotes, Interview
   notes, Harness notes, Raw session notes.
3. Update `LIVE_CHECKS.md` rows you worked: `passed <date> (<file>)`,
   `failed <date>: <one line>`, or `owed` with what blocked it.
4. Add evidence lines under the matching items in `AGENDA.md`.
5. File every bug in `pocketdungeons\docs\reference\BUGS.md` starting at
   **PD-149**, with severity, repro and likely cause. **Lemon harness faults
   are bugs too** (missed or late asks, wait cursor replays, llm mode lapses,
   missing join events): file them with PD numbers, not just as notes.
6. Add per-floor rows to `BALANCE.md` (seconds, spawners, durability used,
   nodes mined).
7. Do not commit. Tell the owner the files are ready and list the top three
   findings in chat.
