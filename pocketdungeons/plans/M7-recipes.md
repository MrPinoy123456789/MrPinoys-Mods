# M7 — Recipes

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §5.4 · Status: `../PROGRESS.md`

**Goal:** server folklore. Someone comes back with *New Dungeon Discovered* and
their friends have to ask what they did.

**Blocked on:** M1 (themes must exist) and enough play for `DungeonLog` to have
been recording them. **Blocks:** nothing.

---

## The idea

Each door already carries **level + affix**. M1 adds **theme**, which turns the
three doors into a composer: your last three door choices *are* a recipe.

**No new interface and no new command.** `Instances.selectorDoorStep` and
`/dungeon choose <1|2|3>` already do the work.

**Order matters.** `Cave → Deepslate → Ancient City` produces something
`Village → Village → Village` does not, and the same three themes in a different
sequence are a different result.

---

## T7.1 — Record the last N themes

Add to `DungeonLog`'s codec with `optionalFieldOf` and a default, so old saves
migrate silently — the same trick the affix field uses.

N = 3 to start. Store the *completed* themes, not the offered ones; a recipe
should cost three finished runs.

---

## T7.2 — The recipe table

Datapack JSON: `[theme, theme, theme] → dungeon id`.

Loaded the same way `dungeon_room` is — `listResources` across **all**
namespaces, so **pack authors ship their own recipes**. This folds straight into
`VISION.md` §6: the platform pitch is *bring your own mods, we generate the
dungeons*, and recipes are the sharpest expression of it.

Make it `/reload`-driven from day one (M0 did the plumbing) — a recipe table that
needs a restart to iterate will never get authored.

---

## T7.3 — Discovery needs a floor

Fully hidden recipes means most players find zero, and a mechanic nobody triggers
is not a mechanic.

**Make the ingredients visible even when the combinations are not.** The dungeon
log already records runs; surfacing which themes a player has *completed* lets
them reason without being told the answer.

This is exactly how Minecraft handles crafting: you can see the items, not the
recipe.

⚠ **Do not add a recipe book, a progress bar, or a hint system.** The goal is
word of mouth. A UI that tells players what to try converts folklore into a
checklist and the feature dies.

---

## T7.4 — What a recipe unlocks

A dungeon that is not otherwise offered. Keep the first one **cheap and
data-shaped** — a themed dungeon with a distinctive palette and loot table, not a
new generator ruleset. Rule-breaking dungeons are M8 and they are a different
kind of work entirely.

---

## Done when

- [ ] Completing three specific themes in order offers a dungeon that is not in
      the normal pool
- [ ] The same three themes in a different order do **not**
- [ ] A player can see which themes they have completed, and cannot see the
      combinations
- [ ] A pack author adds a recipe with a JSON file and `/reload`
- [ ] Old saves load with no migration step
- [ ] `./gradlew build` green
