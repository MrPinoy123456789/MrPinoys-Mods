# Pocket Dungeons conventions

Mod-specific rules that apply to every milestone in this mod. The DSH
plugin's system prompt covers the workspace-wide rules (toolchain,
punctuation, jar verification, build verification, commit style, server-side
constraint). This file covers what is specific to pocketdungeons.

A handoff should say "follow `CONVENTIONS.md`" rather than repeating these
rules.

## The mixin budget (no longer one)

The mod began with exactly one mixin, `mixin/CustomClickMixin`, and this rule
asked every milestone to exhaust the Fabric-event and datapack routes first.
By 2026-10-10 the budget of one is gone: `pocketdungeons.mixins.json` lists 18
(accessors for text displays, trial spawners and vaults, plus the click catcher,
spawn rules, fire, explosions, sculk, the Wither and others). The habit stays:
add a mixin only when no event, datapack or accessor route does the job, name it
in the completion report, and keep it small and server-side only.
`DISCOVERIES.md` traps 14 and 15 are a worked example of that search paying off.

## Status and progress docs

- `plans/COMPLETED-MILESTONES.md`: architectural summaries of completed
  milestones. Append only; do not re-read the whole file when appending.
- `docs/reference/LIVE_TEST_PASS.md`: outstanding live client verification
  items. Append new sections; do not rewrite existing ones.
- `docs/reference/ROADMAP.md`: milestone order, not status. No checkboxes.
- Do not create a `PROGRESS.md` or recreate the retired `handoffs/` folder.

## Codec migration discipline

A superseded `DungeonLog.Entry` field is marked superseded in its javadoc and
its codec field kept, never deleted on the first pass. See "Per-player
persistent state" in `docs/reference/D3_PROGRESSION_PLAN.md` for the full
rationale.

## No client mod

The plugin already says "do not reference `net.minecraft.client.*` from
server code." For this mod the constraint is stricter: `"environment":
"server"` in `fabric.mod.json`, no `assets/` directory, no custom items, no
custom sounds, no custom sound files. Everything is vanilla blocks, vanilla
items, vanilla sound events, and server-side dialogs. This is load-bearing
for the server-side-only design.
