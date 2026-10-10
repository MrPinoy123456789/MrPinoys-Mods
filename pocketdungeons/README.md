# Pocket Dungeons

A server-side Fabric mod for MC 26.2. `/dungeon` to go in.

## Where to start

Read `plans/COMPLETED-MILESTONES.md` for what was built (the highest `## M{n}`
heading in that file is the current code-complete range),
`docs/playtests/LIVE_CHECKS.md` for what is built but not yet seen in play, and
`docs/reference/BUGS.md` for the numbered bug and design ledger (PD-n). The older
`docs/reference/LIVE_TEST_PASS.md` is the original live verification pass.

## Key features

- **Private dungeon instances** in a void dimension, seeded per player.
- **The compass, the haul and lives**: floors pay scrap into a haul; going
  home or finishing a dungeon banks it into your compass level; a failed
  dungeon keeps half. The party shares five lives, and side doors cost lives.
- **The Astrolabe Room**: choose your act and dungeon in the world, look
  through the window, pull the DESCEND lever.
- **Dungeons by act**: story dungeons (Mineshaft, Infestation, Ossuary, the
  Kennels, Copper Works, Deepslate, Frostworks and on), resource dungeons (Cow
  Pits, Lush Caves, the Endless Mine), and capstone bosses. Most end in a
  finale on the last floor. Affixes, sculk that listens, hostile wolves and more.
- **Room stations**: an enchanting table rerolls enchantments (lapis), a
  grindstone scraps gear, and a librarian and merchants buy and sell for emeralds.
- **Trip sidebar**: floor, lives, spawners, haul and compass while you play
  (`/dungeon display off` hides it).
- **Trial spawners and vaults**: dungeon rooms contain trial spawners that
  eject vault keys or emeralds; a key lasts the dungeon and turns into
  emeralds if unused when the haul banks.
- **Lodestone menu**: Start Dungeon, Manage Room, Inspect Compass, Manage Party
  (invites, bans, building rights) and View Lobbies.

## Active documents

| Document | What it is |
|---|---|
| `docs/DISCOVERIES.md` | Verified 26.2 API findings, so nobody re-derives them the hard way |
| `docs/DIALOGS_SPEC.md` | Menu shapes, specced before they are built |
| `docs/INTEGRATION.md` | The published datapack schema (`dungeon_room`, `dungeon_theme`, `dungeon_adventure`, `dungeon`, `anomaly_room`, `diary`, affixes, bags, roles, cube recipes), for pack authors |
| `tools/lemon/GUIDE.md` | The game in plain words, as Lemon explains it; the quickest accurate summary of the rules |
| `.claude/skills/` (workspace root) | How to build content, text, screens and tests for this mod |
| `plans/COMPLETED-MILESTONES.md` | Architectural summary of every completed milestone |

## Reference vault (`docs/reference/`)

Big historical docs, kept out of the agent's default scan path. Pull sections on
demand when a milestone handoff references them.

| Document | What it is |
|---|---|
| `docs/reference/ROOM_UX_PLAN.md` | The authoritative scope for M18-M22: goal, dependencies, touch points, done-when |
| `docs/reference/D3_PROGRESSION_PLAN.md` | The buildable plan promoted from the brainstorm: M10-M17 |
| `docs/reference/DOOR_LADDER_BRAINSTORM.md` | Design scratchpad for the door/ladder pass. Section 13 has the sound cue table for M22 |
| `docs/reference/LIVE_TEST_PASS.md` | The outstanding live client verification pass |
| `docs/reference/ROADMAP.md` | Milestone order |
| `docs/reference/VISION.md` | Why this mod exists: the hook, the pillars, the platform thesis |
| `docs/reference/PLAN.md` | The original technical spec: geometry, stamping, the room contract |
| `docs/reference/DIALOGS.md` | What is actually built, against the spec |
| `docs/reference/MYTHIC_PLUS_RECONCILIATION.md` | The affix system's design reasoning |
| `docs/reference/LORE.md` | The fiction, and where it couples to the mechanics |
| `docs/reference/LORE-STORY.md` | Story content for the lore pass |
| `docs/reference/LORE-DIARIES.md` | Diary content for the lore pass |

## Completed handoffs (`docs/d3-handoffs/archive/`)

Early-milestone handoffs, kept for reference. Later completed handoffs stay
in `docs/d3-handoffs/` itself, renamed `M{n}-handoff-completed.md`; any file
there without the `-completed` suffix is a genuinely open handoff.


## House conventions

Read `A:\MrPinoys Mods\CLAUDE.md` before writing anything a person will
read: no em dashes, no double hyphens, as punctuation.
