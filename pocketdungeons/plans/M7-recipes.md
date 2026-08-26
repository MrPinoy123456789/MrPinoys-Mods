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

## Correction, 2026-08-25: the doors do not carry a theme yet

The section above was written before M1 landed, and it describes M1's *intent*
rather than what M1 shipped. Read against the code, three of its assumptions are
false, and T7.1 has nothing to record until they are fixed. Written down here
rather than quietly worked around, per `PROGRESS.md` process rule 3.

What M1 actually shipped:

1. `RoomManifest.queryAnyRotation(mask, role, theme)` filters rooms on a theme
   string, and `RoomManifest.matchesTheme` treats an empty room theme list as
   "matches every request". **No room JSON in the mod declares a `theme` field.**
   Every room therefore matches every theme, so requesting one selects exactly
   the same rooms as requesting none.
2. Processor lists are attached **per room** (`DungeonRoomMeta.processors`, read
   at `LayoutStamper.java:100`), never per run. The three proof lists
   `theme_deepslate`, `theme_prismarine` and `theme_blackstone` exist and are
   referenced by nothing. `theme_grove` is wired to exactly one corridor room
   (`dungeon_room/grove.json`, M6 T6.3), which is a themed *room*, not a themed
   *dungeon*.
3. The only caller that passes a theme at all is `/dungeon admin untimed`
   (`DungeonCommands.java:333`), a debug command. The real player path,
   `Instances.generateBehindLobby`, passes `null` (`Instances.java:1220`).

None of this reopens M1. M1's own "done when" bar was one `.nbt` plus three JSON
files producing three visibly different dungeons through `/dungeon admin
stamptest`, and that bar is met. What is missing is the *player* path: a run does
not have a theme, and the doors do not offer one. That is M7's first task, below,
and everything else in this milestone is blocked on it.

---

## T7.0 - A run has a theme, and the doors offer it

**New. Blocks T7.1, T7.2, T7.3 and T7.4.**

### The datapack type

`data/<namespace>/dungeon_theme/<name>.json`, loaded across **all** namespaces
exactly the way `dungeon_room` is:

```json
{
  "name": "Deepslate",
  "processors": "pocketdungeons:theme_deepslate"
}
```

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | Display name, shown on the door and in `/dungeon log` |
| `processors` | yes | A `minecraft:worldgen/processor_list` id, applied **dungeon wide** |
| `room_theme` | no | Handed to the existing `RoomSelector`/`RoomManifest` theme filter. Omit unless rooms are actually tagged with it |
| `discoverable` | no | Defaults true. False keeps the theme off the doors; see T7.4 |
| `loot_suffix` | no | T7.4 only. See below |

The file's base name is the theme id, the same convention `dungeon_room` uses.

### Loading

New `ThemeManifest` class, modelled directly on `RoomManifest`:

- `listResources("dungeon_theme", id -> id.getPath().endsWith(".json"))` over
  `server.getResourceManager()`, sorted by key so load order is deterministic.
- A plain Gson data holder with no Minecraft imports for the parse
  (`DungeonThemeMeta`, mirroring `DungeonRoomMeta`), so it stays unit testable.
- Validation at load, following `RoomManifest.validateProcessors`: a
  `processors` id that is not in `Registries.PROCESSOR_LIST` is a **rejection**,
  logged and collected in `rejections()`, not a silent untinted dungeon.
- Registered on the same `/reload` listener path `RoomManifest.register()` uses,
  including its documented startup ordering guard: the resource reload runs
  before `SERVER_STARTED`, so the static `server` field is null on the first
  pass and the explicit load in `Instances.register()` covers it.
- A `/dungeon admin theme list` reader alongside the existing manifest command,
  so rejections can be read back without a restart.

### Applying a theme to a run

Thread a nullable run theme down the stamp path:

`Instances.generateBehindLobby` → `LayoutStamper.stampBehindLobby` →
`LayoutStamper.stamp` → `TemplateStamper.place`.

At the `TemplateStamper.place` call site (`LayoutStamper.java:100`), the
precedence rule is:

> **A room's own `processors` wins. The run theme fills in where the room
> declares none.**

So a `grove` corridor stays a grove inside a deepslate run, which is the wanted
behaviour: the grove is a strange chamber, and a strange chamber that quietly
recolours itself to match the walls around it is not strange any more. Put that
sentence in the javadoc; it is the kind of rule that gets "simplified" out.

**The player's room cell is never themed.** It is a raw `RoomStore` blob placed
through `TemplateStamper.placeRotated`, which runs no processors by design, and
`stampBehindLobby` skips the entrance cell entirely anyway.

`room_theme`, when present, is passed as the existing `theme` argument to
`LayoutPlanner.plan`, replacing the hardcoded `null` in `generateBehindLobby`.
Leave it unset for the three shipping themes: filtering on a theme that no room
declares makes planning fail with a legible error and no rooms, which is a worse
day than an unfiltered themed palette.

### Offering a theme on the doors

- `Keystone.Offer` gains a `String theme` component: a theme id, or null when no
  themes are loaded at all.
- `Keystone.offers(int level)` gains an owner parameter and picks one theme per
  door. **Seeded from `hash(owner, level, step)`**, never from run randomness:
  reuse `AffixMath`'s SplitMix64 finaliser (`AffixMath.seed`, currently private;
  promote it rather than copying it) so the three doors are identical every time
  they are drawn. The watcher and the dialog both re-render offers, and a door
  that changes what it offers between two renders is the same class of bug M4
  T4.2 and T4.4 were written to avoid.
- The three doors should offer three *different* themes where the loaded set
  allows it: shuffle the discoverable theme list with the seeded `Random` and
  deal one per step, wrapping if fewer than three are loaded.
- `DialogScreens.doorOffer` shows the theme name as a body line, beside the
  level and the affix blurb. No explanation of what the theme does; the palette
  is the explanation.
- `InstanceRecord` gains a `String theme` field, set in `generateBehindLobby`
  from the chosen offer. In memory only, like every other live instance field.

### Shipping content

The three M1 processor lists, finally reachable in play:
`dungeon_theme/deepslate.json`, `prismarine.json`, `blackstone.json`.

### Done when

- [ ] Three doors show three different theme names
- [ ] The dungeon behind the chosen door is visibly that palette
- [ ] A grove room inside a themed run is still a grove
- [ ] Re-opening the same door dialog twice shows the same three themes
- [ ] No `dungeon_room/*.json` changed

---

## T7.1 — Record the last N themes

Add to `DungeonLog`'s codec with `optionalFieldOf` and a default, so old saves
migrate silently: the same trick the affix field uses.

```java
Codec.STRING.listOf().optionalFieldOf("recent_themes", List.of())
        .forGetter(Entry::recentThemes)
```

N = 3 to start, as a named constant. Store the *completed* themes, not the
offered ones; a recipe should cost three finished runs.

Written in `Instances.completeRun` (`Instances.java:1705`), for **each
completing member against their own entry**, not the owner's. That matches the
rule the banked-offer block right beside it already follows: companions progress
off their own ladder. The value comes from `record.theme`.

Push and truncate: append, then keep the last N. A run with no theme (an admin
build, or a server with no `dungeon_theme` files loaded) records nothing rather
than recording a null, so the window never contains a hole.

### Done when

- [ ] A `dungeon_log.dat` written before this task loads with an empty window
- [ ] Three completed runs leave three ids in order, oldest first
- [ ] A fourth pushes the first one out
- [ ] A companion who finishes gets their own window, not the owner's

---

## T7.2 — The recipe table

Datapack JSON: `[theme, theme, theme] → dungeon id`.

`data/<namespace>/dungeon_recipe/<name>.json`:

```json
{
  "themes": ["deepslate", "prismarine", "blackstone"],
  "result": "drowned_vault"
}
```

`result` names a `dungeon_theme`. `themes` is an ordered list, matched against
the **tail** of the player's window, so order falls out of the data structure
rather than needing a rule of its own.

Loaded the same way `dungeon_room` is: `listResources` across **all**
namespaces, so **pack authors ship their own recipes**. This folds straight into
`VISION.md` §6: the platform pitch is *bring your own mods, we generate the
dungeons*, and recipes are the sharpest expression of it.

Make it `/reload`-driven from day one (M0 did the plumbing). A recipe table that
needs a restart to iterate will never get authored. Practically: register it on
the same listener as `ThemeManifest`, and load themes before recipes so a recipe
naming an unloaded theme can be **rejected at load**, loudly, in `rejections()`.
A recipe that silently never fires is indistinguishable from a recipe nobody has
found, which is the worst possible failure mode for this feature.

### Where a match is offered

**Decision: a matched recipe replaces door 3's offer. Three physical doors
stay.**

`Instances.selectorDoorStep` resolves doors by hardcoded positions along the
wall (`along` 7, 8 and 9, `Instances.java:1147`). A fourth door is template and
geometry work for no design gain. Door 1 must stay the safe `+1`: it is the
option a player takes when they cannot afford a surprise. Door 3 is already the
fragile, highest-upgrade, highest-stakes door, so it is where an unusual dungeon
belongs.

The replaced offer keeps door 3's level and its `FRAGILE` affix. Only the theme
changes, to the recipe's result.

### Nothing is consumed

There is no spend step and no "used recipe" flag. Completing the recipe dungeon
pushes its own theme into the window, which breaks the match on its own.
Declining it and running anything else shifts the window too. Anything more is
state that can disagree with the window sitting beside it.

### Done when

- [ ] `[a, b, c]` completed in that order puts the recipe theme on door 3
- [ ] `[a, c, b]` does not
- [ ] A recipe naming an unloaded theme appears in `rejections()` at load
- [ ] A recipe added to a scratch datapack is live after `/reload`, no restart

---

## T7.3 — Discovery needs a floor

Fully hidden recipes means most players find zero, and a mechanic nobody triggers
is not a mechanic.

**Make the ingredients visible even when the combinations are not.** T7.0 does
most of this already: three theme names sit in front of the player on every door
choice, so the ingredient list is public by construction.

The floor is one line added to `/dungeon log` (`DungeonCommands.java:407`): the
themes this player has completed, with counts. Nothing else. It answers "which
have I actually finished", which the player could count for themselves.

This is exactly how Minecraft handles crafting: you can see the items, not the
recipe.

⚠ **Do not add a recipe book, a progress bar, or a hint system.** The goal is
word of mouth. A UI that tells players what to try converts folklore into a
checklist and the feature dies. Specifically: do not show the current three-run
window back to the player, do not mark a theme "used in a recipe", and say
nothing when a recipe matches beyond the door's own name.

Counts need a second field: a completed-theme tally with no truncation, added to
`DungeonLog.Entry` under the same migration rule as T7.1. The T7.1 window is the
last three in order and is not a tally; do not try to serve both from one list.

### Done when

- [ ] `/dungeon log` lists completed themes and counts
- [ ] Nothing anywhere shows a combination, a window, or a hint

---

## T7.4 — What a recipe unlocks

A dungeon that is not otherwise offered. Keep the first one **cheap and
data-shaped**: a themed dungeon with a distinctive palette and loot table, not a
new generator ruleset. Rule-breaking dungeons are M8 and they are a different
kind of work entirely.

Concretely, one `dungeon_theme` JSON with its own processor list, plus one
`dungeon_recipe` JSON pointing at it. The theme is never offered on a door by the
seeded picker: `"discoverable": false` keeps it out of the shuffle pool, so the
only way to see it is to earn it.

### The loot table

`loot_suffix` on the theme. `TrialContent` builds table ids by string in three
places (`TrialContent.java:316`, `:333`, `:361`). Add a resolver that tries
`chests/tier_N<suffix>` and **falls back to the unsuffixed table when the
suffixed one is not loaded**, so a theme can override the vault without having to
author a supply table and a completion table as well.

The suffix reaches the two stamp-time call sites through the same
`LayoutStamper.stamp` → `RoomContent.apply` → `TrialContent.applyLoot` thread
T7.0 already opens, and reaches `placeCompletionChests` from `record.theme` in
`completeDungeon` (`Instances.java:1790`). **Do not add a component to
`InstanceLayout` for this**: it is a 16-component record with several
construction sites including `forClearingOnly`, and nothing about teardown wants
to know the theme.

### Done when

- [ ] The recipe dungeon has a palette no ordinary run produces
- [ ] Its vault pulls a different table, and a missing suffixed table degrades to
      the normal one
- [ ] The recipe theme never appears on a door that was not earned
- [ ] No generator rule changed

---

## Done when (milestone)

- [ ] Completing three specific themes in order offers a dungeon that is not in
      the normal pool
- [ ] The same three themes in a different order do **not**
- [ ] A player can see which themes they have completed, and cannot see the
      combinations
- [ ] A pack author adds a recipe with a JSON file and `/reload`
- [ ] Old saves load with no migration step
- [ ] `./gradlew build` green

---

## Testable without a client

In the existing pure-JDK style (`AffixMathTest`, `DungeonRoomMetaTest`,
`PlanSelectorTest`), which is where the load-bearing claims of this milestone
live:

| Test | Claim |
|---|---|
| `DungeonThemeMetaTest` | Parse, defaults, and rejection of a missing `processors` |
| `KeystoneOfferTest` | The three doors are stable across 100 repeat calls for one owner and level, and differ across owners |
| `RecipeMatchTest` | `[a,b,c]` matches; `[a,c,b]` does not; a short window does not; a longer window matches on its tail |
| `DungeonLogTest` | A pre-M7 entry loads with an empty window; push and truncate keeps the last N in order |

The palette, the door labels, the `/reload` round trip and a recipe actually
firing need the live pass every other milestone's client checks are deferred to.

---

## Files this milestone touches

| File | Change |
|---|---|
| `DungeonThemeMeta.java` | **new** Gson data holder, no Minecraft imports |
| `ThemeManifest.java` | **new** loader, `/reload` listener, `current()`, `rejections()` |
| `DungeonRecipes.java` | **new** loader plus the ordered tail match |
| `Keystone.java` | `Offer` gains `theme`; `offers()` gains an owner and seeds a theme per door |
| `AffixMath.java` | promote `seed(UUID,int)` so the door picker can reuse the finaliser |
| `Instances.java` | `generateBehindLobby` passes the theme; `InstanceRecord.theme`; `completeRun` records it; recipe override on door 3 |
| `InstanceRecord.java` | `String theme` |
| `LayoutStamper.java` | run theme parameter, room-processors-win precedence |
| `RoomContent.java` / `TrialContent.java` | loot suffix thread and the fallback resolver |
| `DungeonLog.java` | `recent_themes` window plus the completed-theme tally |
| `DialogScreens.java` | theme name on the door offer |
| `DungeonCommands.java` | `/dungeon log` themes line, `/dungeon admin theme list` |
| `PocketDungeonsMod.java` | register the two new loaders |
| `data/pocketdungeons/dungeon_theme/*.json` | three shipping themes plus the recipe result |
| `data/pocketdungeons/dungeon_recipe/*.json` | the first recipe |
| `INTEGRATION.md` | two new datapack surfaces, with their validation failures verbatim |
