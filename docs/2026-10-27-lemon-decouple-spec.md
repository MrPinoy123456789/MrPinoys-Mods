# Spec: Lemon as its own mod

Status: approved 2026-10-27. Decision 1 is option A, and the amendment is now in
`docs/DESIGN.md` §4 rule 3. Decisions 2 and 3 are approved as recommended.
Implementation status: L1 to L4 code and documentation are implemented. Lemon's
standalone build passes its pure tests and all 15 gametests. PD's three focused
bridge gametests pass both with and without the independent Lemon jar. Node event,
cursor and profile tests pass. After fixing the Heist harness's entity-section
readiness and floor ownership isolation, the full PD suite passes all 407 required
gametests, pure tests and dedicated-server integration. The Heist verification
blocker is resolved without weakening its wave-count assertion.
L5 remains separate from this Lemon merge. Charter source is on
`feature/charter-build` in a separate worktree; the Charter tool profile is
prepared, but its source-side integration and live verification are still owed.
L6 is still owed and requires an approved deployment plus participating players.
No remote server has been deployed or restarted for this implementation.

Lemon lives inside Pocket Dungeons today. This spec moves the companion and the
playtest interviewer into a mod of their own, `lemon`, so any mod in the suite
(Charter first) can be playtested through her, while Pocket Dungeons keeps
everything that makes her part of its game.

## 1. What Lemon is today

Six classes in `pocketdungeons/src/main/java/pocketdungeons/`, about 1,700 lines,
plus the server tool in `pocketdungeons/tools/server/`.

**Already generic (moves as it is):**

| Piece | Where |
|---|---|
| The allay body and the speech bubble | `LemonBody`, `LemonBubble` |
| Bubble splitting, the `lemon,` chat prefix | `LemonSpeech` (and `LemonSpeechTest`) |
| say, ask, reply, think, quiet, llm and guide mode, pending questions, the fallback timer, fight holds, linger and idle | `Lemon` |
| Driver commands and the player's `/lemon quiet` and `/lemon on` | `LemonCommands` |
| Six timing knobs: `lemonAskIdleSeconds`, `lemonFallbackSeconds`, `lemonIdleSeconds`, `lemonLingerSeconds`, `lemonLlmLapseSeconds`, `lemonThinkSeconds` | `PocketDungeonsConfig` |
| The server tool: RCON and Kinetic transport, `wait`, `sync`, the chat cursor, the MCP server | `tools/server/*.mjs` |

**Tied to Pocket Dungeons (each needs a replacement):**

| Tie | Where | What it does |
|---|---|---|
| Party and instance | `Lemon.partyOf`, `Lemon.soloForChat` | Reads `InstanceRegistry` and `PartyService` to decide whether plain chat goes to Lemon and who overhears it |
| Playtest journal | `PlaytestJournal.lemonAsk`, `lemonAnswer`, `lemonHeard`, `report` | Lemon's lines are written into Pocket Dungeons' journal |
| The keystone | `Lemon.register` | Right-clicking a keystone summons her |
| Her journal | `Lemon.journalReached` | Right-clicking her opens the room's lodestone menu |
| The diary archive | `LemonArchive` | Handing her a diary keeps it and earns a tip |
| Tours | `FirstVisitTutorial`, `StationTutorial` call `Lemon.tour` and read back a `Delivery` | The first-visit and station tutorials speak through her |
| The context snapshot | `PlayerContext` calls `Lemon.view` | `/dungeon admin context` includes her state |
| Entity sweeps | `RoomStore`, `RoomTemplateGenerator`, `RoomBuilderCommands`, `InstanceTeardown`, `Instances`, `RunLifecycle` call `Lemon.isPart` and `Lemon.withoutLemon` | Room saves and teardowns skip her body |
| Command names | `pdserver.mjs` | The tool sends `dungeon lemon ...` and `dungeon admin context` |

## 2. The suite rule this runs into

`docs/DESIGN.md` §4, rule 3: **never depend on another mod at compile time. No
`depends`, no api module, no shared classes.** §3 adds "no cross-mod features in
code", and the accepted price is duplication ("four copies of eight lines is
cheaper than a shared library").

So Pocket Dungeons cannot import a Lemon mod. Every tie in the table above has to
be crossed with something that is not code: **commands run by name, entity tags,
and log lines.** That is the design below. Decision 1 is whether to do it at all.

## 3. Decisions for the owner

### Decision 1: the shape

| Option | What it is | For | Against |
|---|---|---|---|
| **A. A Lemon mod, reached by commands** (recommended) | `lemon` is a standalone, server-side mod. Pocket Dungeons talks to her only by running `/lemon ...` commands through the dispatcher and reading her entity tag. Without the Lemon jar, Pocket Dungeons still works (section 4.4) | One Lemon for every mod. The interviewer has had the most bugs in the suite (llm mode lapses, cursor replays, missed asks); they get fixed once | Needs an amendment to DESIGN §4 (below). A Pocket Dungeons server needs a second jar to keep her |
| **B. Copy her into each mod** | Charter gets its own copy of the engine (`charter.mc.lemon`); the server tool learns each mod's command names | Fits the rules as they stand | Two copies of the buggiest code in the suite, and they drift. Every harness fix lands twice |
| **C. Leave her in Pocket Dungeons** | Use her as a plain interviewer for Charter (she already works outside a dungeon) and only make the server tool aware of Charter | No code moves | Charter playtests depend on the Pocket Dungeons jar being installed, and nothing is decoupled |

**Recommendation: A.** The amendment it needs, added to DESIGN §4 rule 3:

> A mod may run another mod's command by name when that command is registered,
> and must still work, with a plainer result, when it is not. Commands are the
> only bridge; no classes, no `depends`.

This is the same shape as the facts rule (`docs/ROADMAP.md`): fire and forget,
and never broken by the other mod being missing.

### Decision 2: who is "with you"

Today plain chat goes to Lemon when the player is solo in their Pocket Dungeons
party or alone on the server, and the whole party overhears her. The Lemon mod
cannot see parties.

| Option | Rule |
|---|---|
| **A. Distance** (recommended) | Plain chat goes to Lemon when no other player is within 48 blocks in the same level. Players within 48 blocks overhear. Dungeon instances are far apart, so a solo run still counts as alone |
| B. Prefix only | Only `lemon, ...` ever reaches her. Simple, but a solo player has to type the prefix every time |
| C. Vanilla teams | A scoreboard team is the party. Exact, but nobody sets teams today |

**Recommendation: A.** One change to check live: party members far apart inside
one instance stop overhearing each other.

### Decision 3: where the server tool lives

**Recommendation:** move `pocketdungeons/tools/server/` to the workspace root as
`tools/playtest/`, with one small profile per game mod (its context command and its
log line patterns). The alternative is to leave it where it is and add Charter's
profile there, which keeps Charter's tooling inside another mod's folder.

## 4. The design (option A)

### 4.1 The Lemon mod

A new top-level mod, `lemon/`, laid out like the other small mods: server-side,
no Mixins, its own config at `config/lemon/lemon.json` with the six timing knobs
and the same defaults.

It owns:

- the body, bubble and speech (moved from Pocket Dungeons as they are)
- every driver command, now `/lemon say|ask|reply|think|quiet|mode <player> ...`
  (operator level), plus the player's `/lemon quiet`, `/lemon on` and
  `/lemon report <text>`
- `/lemon context <player>`: her state only (present, waiting, mode, pending
  questions), one line of JSON
- her own journal: ask, answer, heard and report lines in
  `<world>/lemon/journal/<yyyy-mm-dd>/<uuid>.jsonl`
- her log lines, unchanged in form (`Lemon ask <name> text`, `Lemon answer`, ...),
  so the server tool's patterns keep working

**New: hooks, as config.** She knows nothing about any game, so what a click on
her means comes from config:

```json
"onInteract":         "",
"onInteractHolding":  {}
```

`onInteract` is a command run as the player when they right-click her.
`onInteractHolding` maps an item tag or id to a command run instead while the
player holds it. Pocket Dungeons' server config sets them (section 4.2). Empty
means a click does nothing.

**New: commands for games to call**, operator level, each returning a result a
caller can read:

| Command | Result |
|---|---|
| `/lemon summon <player>` | 1 if she came |
| `/lemon tour <player> <text>` | 1 shown, 2 held for a fight, 3 held for quiet (today's `Lemon.Delivery`: `SHOWN`, `DEFERRED_FIGHT`, `HELD_QUIET`) |

**Entity tag.** Every body and bubble carries the scoreboard tag `lemon.part`.
That string is the contract for every other mod's entity sweeps.

### 4.2 Pocket Dungeons after the move

| Today | After |
|---|---|
| `Lemon.isPart(e)` in six sweeps | `e.entityTags().contains("lemon.part")` (one local helper, one constant) |
| `Lemon.withoutLemon(level, box, run)` | The same, done by the sweep: tagged entities are left out of saves |
| The keystone calls `Lemon.summon` | It runs `lemon summon <player>` |
| `Lemon.tour` returns a `Delivery` | `FirstVisitTutorial` and `StationTutorial` run `lemon tour` and read the result |
| `journalReached` opens the lodestone menu | Pocket Dungeons registers `/dungeon lemonmenu` and sets `onInteract` to it |
| `LemonArchive` takes a diary | Pocket Dungeons registers `/dungeon lemonarchive`, set in `onInteractHolding` for the diary item; it says the tip through `lemon say` |
| `PlayerContext` includes `Lemon.view` | Dropped. The tool merges `/lemon context` with `/dungeon admin context` |
| Lemon lines in `PlaytestJournal` | Written by the Lemon mod instead. Pocket Dungeons keeps every game event |
| Six `lemon*` knobs in `pocketdungeons.json` | Read by the Lemon mod. Pocket Dungeons logs once that the old keys are ignored |

All six Lemon classes leave Pocket Dungeons except what `LemonArchive` does with
diaries, which stays as the archive command.

### 4.3 Charter

Nothing in Charter needs to change for her to work. Two additions make the
interview better, both in Charter's own code:

- `/charter admin context <player>`: faction, office, keep, granary, any siege
  or custody, as one line of JSON
- log lines the tool can wake on: founding, claiming, a camp raised, a keep
  fallen, an arrest (the siege log has most of these already)

### 4.4 Without the Lemon jar

Pocket Dungeons must still run with no Lemon installed (DESIGN §4 as amended):

- tours fall back to an action bar line and a chat line
- the keystone's right-click does nothing extra
- sweeps find no tagged entities, which is correct
- `/dungeon admin context` works as before, without her part

A gametest covers this: the tours and the keystone with the `lemon` command not
registered.

### 4.5 The server tool

`tools/playtest/` (Decision 3), mod agnostic:

- `lemon ...` verbs send `/lemon ...` instead of `/dungeon lemon ...`
- `context <player>` runs `/lemon context` and the context command of every game
  profile whose mod is loaded, and prints them as one JSON object
- `wait-filter.mjs` reads its event patterns from the profiles
- `pdserver.mjs` stays as a name, so the skills and runbooks keep working, or is
  renamed `playtest.mjs` with `pdserver.mjs` left as a forwarder (my preference)
- local mode still runs Pocket Dungeons' `runServer`; the Lemon jar is added to
  that run's mods folder as a runtime file, never a compile dependency

## 5. Build order

| Step | Work | Done when |
|---|---|---|
| L1 | Create `lemon/` and move the engine, body, bubble, speech, commands and knobs. Add the tag, the hooks, `summon`, `tour` and `context`. Move `LemonSpeechTest` and the engine parts of `LemonGameTest` | The Lemon mod builds and its tests pass on their own |
| L2 | Pocket Dungeons: tags in the sweeps, dispatcher calls for the keystone and tours, `lemonmenu` and `lemonarchive`, the fallback, delete the moved classes | Pocket Dungeons builds with no `Lemon` class; its tests pass with and without the Lemon jar |
| L3 | The server tool: move to `tools/playtest/`, per-game profiles, merged context, MCP server renamed | `wait-filter.test.mjs` and `chat-cursor.test.mjs` pass, plus new profile cases |
| L4 | Docs: split `LEMON_SPEC.md` into the Lemon mod's spec and Pocket Dungeons' character notes; make `LEMON_AGENT.md` generic with a section per game; update the playtest and deploy skills | No doc names `dungeon lemon` or the old tool path |
| L5 | Charter: `/charter admin context` and the event lines, with tests | A Charter profile wakes `wait` on a founding |
| L6 | Live on 714a0605: one Pocket Dungeons run and one Charter scenario with Lemon interviewing both | Every `lemon ask` reaches the agent; tours, keystone, menu and archive behave as before |

L1 and L2 must land together: between them, Pocket Dungeons has no Lemon. L3 and L5
can be built in parallel with them.

## 6. Risks

- **Old fixes regressing.** Most of `Lemon.java` is fixes from live playtests
  (PD-77, PD-79, PD-138 and the llm mode keepalive). The move must be a move, not
  a rewrite; the gametests come across with it.
- **Command timing.** A dispatcher call runs at once on the main thread, as the
  direct calls do now, so tour ordering should not change. To be confirmed in L2.
- **Two jars to ship.** A Pocket Dungeons server without the Lemon jar loses her
  quietly. The deploy skill must upload both.
- **The `/lemon` name.** Pocket Dungeons already registers `/lemon quiet` and
  `/lemon on`. Those move with her, so L1 and L2 must land in the same build.

## 7. Not in this spec

- Guide mode (hints, the FAQ). It is not built yet; when it is, each game should
  publish its own guide text as data the Lemon mod reads.
- Any change to who Lemon is or how she talks.
