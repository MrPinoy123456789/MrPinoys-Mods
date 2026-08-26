# Pocket Dungeons

A server-side Fabric mod for MC 26.2. `/dungeon` to go in.

## Where to start

New to this mod or picking a milestone back up? Read `PROGRESS.md` first: it
says what is done, what is in progress, and which milestone is current. Then
find that milestone's handoff under `handoffs/` and paste it into a fresh
chat.

## Live documents

| Document | What it is |
|---|---|
| `VISION.md` | Why this mod exists: the hook, the pillars, the platform thesis |
| `PLAN.md` | The technical spec: geometry, stamping, the room contract |
| `ROADMAP.md` | Milestone order |
| `PROGRESS.md` | **The only file that records status.** Read this first |
| `plans/M<n>-*.md` | How each milestone gets built |
| `handoffs/M<n>-handoff.md` | Drop-in prompts to start a milestone in a fresh chat |
| `INTEGRATION.md` | The published `dungeon_room`/`dungeon_theme`/`dungeon_recipe` datapack schema, for pack authors |
| `DIALOGS_SPEC.md` | Menu shapes, specced before they are built |
| `DIALOGS.md` | What is actually built, against the spec |
| `DISCOVERIES.md` | Verified 26.2 API findings, so nobody re-derives them the hard way |
| `MYTHIC_PLUS_RECONCILIATION.md` | The affix system's design reasoning |
| `CLIENT_TEST_CHECKLIST.md` | The manual verification pass for a live client |
| `DOOR_LADDER_BRAINSTORM.md` | An uncommitted scratchpad for the design pass after M9. Nothing in it ships until it is scoped into a plan |
| `LORE.md` | The fiction, and where it couples to the mechanics. **Nothing in it is shipped or approved to ship**; `VISION.md` §9 still says "not a lore project" and revising that is an open decision |

`docs/archive/` holds documents superseded by the above, kept for history.
Each carries a two-line header saying what replaced it and when it was
archived.

## House conventions

Read `A:\MrPinoys Mods\CLAUDE.md` before writing anything a person will
read: no em dashes, no double hyphens, as punctuation.
