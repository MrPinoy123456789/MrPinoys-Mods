# Pocket Dungeons

A server-side Fabric mod for MC 26.2. `/dungeon` to go in.

## Where to start

Read `plans/COMPLETED-MILESTONES.md` for what was built (the highest `## M{n}`
heading in that file is the current code-complete range), and
`docs/reference/LIVE_TEST_PASS.md` for the outstanding live verification pass.

## Key features

- **Private dungeon instances** in a void dimension, seeded per player.
- **Keystone progression**: run dungeons to level up your keystone, unlock
  higher tiers and greater doors.
- **Room stations**: place a smithing table to reroll enchantments (lapis)
  and spawn a blacksmith NPC that sells random gear for emeralds. Place a
  Herobrine Cube to extract and imbue powers.
- **Tracker screen**: a physical screen in the player's room shows the
  active guided task and, once the tutorial is done, weekly bounty progress.
  Replaces the originally planned scoreboard sidebar.
- **Trial spawners and vaults**: dungeon rooms contain trial spawners that
  eject vault keys (50%) or emeralds (50%).

## Active documents

| Document | What it is |
|---|---|
| `docs/DISCOVERIES.md` | Verified 26.2 API findings, so nobody re-derives them the hard way |
| `docs/DIALOGS_SPEC.md` | Menu shapes, specced before they are built |
| `docs/INTEGRATION.md` | The published datapack schema (`dungeon_room`, `dungeon_theme`, `dungeon_adventure`, `anomaly_room`, `diary`), for pack authors |
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
