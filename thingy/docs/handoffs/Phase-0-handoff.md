# Thingy Framework, Phase 0 - Handoff

> Paste this whole file into a fresh chat to start work on this phase.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This is a workspace of ~15
server-side Fabric mods for Minecraft Java 26.2, all vanilla-client-compatible,
all server-only.

You are building a new mod called **Thingy** in `thingy/`, a virtual object
identity framework. This handoff covers **Phase 0 only**: a targeted bug fix in
an existing mod (`kamutotems`), with no framework code yet. Phase 0 exists so
the one validated bug in the whole plan is fixed in week one rather than held
hostage to seven phases of framework.

## Read these files first, in this order

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages. No
   em dashes, no double hyphens as punctuation. Use `:`, `;`, `,`, `()`, or
   two sentences. Command-line flags and code operators are not punctuation
   and stay as they are.
2. `thingy/docs/PLAN.md`: **the full plan.** Read the "Phase 0" section in
   full, plus "Core design principle", "Namespace rule", and "What we are not
   building" for context on where this fix sits. The Phase 0 section is your
   authoritative scope; this handoff does not repeat it.
3. `kamutotems/fabric/src/main/java/kamutotems/BossHost.java`: the file you are
   fixing. Read it in full. The bug is at lines 266-273 (the `onEntityLoad`
   tag-strip path), and the tracking maps it depends on are at lines 63-66.
4. `kamutotems/fabric/src/main/java/kamutotems/Boss.java`: the `Boss` record
   (lines 31-57). This is the shape of what you are persisting.
5. `kamutotems/fabric/src/main/java/kamutotems/Persist.java`: the existing
   persistence utility kamutotems already uses. Read it to match the pattern;
   do not invent a new persistence mechanism.

## The bug, precisely

`BossHost` tracks active bosses in three in-memory `HashMap`s keyed by entity
UUID and player UUID (`BossHost.java:63-66`):

```java
private static final Map<UUID, Boss> BY_ENTITY = new HashMap<>();
private static final Map<UUID, UUID> BY_PLAYER = new HashMap<>();
private static final Map<UUID, String> FREE_CLAIMS = new HashMap<>();
private static final Map<UUID, Integer> SIGIL_COUNTERS = new HashMap<>();
```

On orderly shutdown, `despawnAndRefundAll` runs before `save`
(`BossHost.java:212-215`), so no orphaned boss entities exist after a clean
stop. But on a crash, the boss entity persists in the world with the
`kamutotems_boss` entity tag and no in-memory record. On reload,
`onEntityLoad` finds it and strips the tag, silently demoting the boss to a
vanilla mob:

```java
// BossHost.java:266-273
private static void onEntityLoad(Entity entity, ServerLevel level) {
    // A boss not in our map is an orphan from a crash; leave it a vanilla mob.
    if (entity.entityTags().contains(TAG) && !BY_ENTITY.containsKey(entity.getUUID())) {
        entity.removeTag(TAG);
    }
}
```

Boss identity (tier, roll, kamu, aura, owner, boss bar), loot table, and aura
are all lost. The entity persists as a vanilla mob with scaled stats and a
custom name, but nothing tracks it anymore.

## The fix

Add a `SavedData` of boss records to kamutotems. On `ENTITY_LOAD`, reattach
from the record instead of stripping the tag.

The record must contain enough to reconstruct a `Boss` object: entity UUID,
owner UUID, tier, `BossRoll`, resolved `List<Kamu>`, `AuraSpec`,
`fromSigil`, `purchaseCounter`, `seed`. The `ServerBossEvent` bar is rebuilt
on reattachment (it is not itself serializable; it is rebuilt from the name
and re-added to the owner if online).

### Critical constraint: shape this as Phase 7's format

Phase 0's `SavedData` is shaped as the format Thingy's `VirtualEntity`
persistence will later adopt verbatim, not as a throwaway. Phase 7 absorbs it
by reference, not by migration. This means:

- The record is a `SavedData` (not a plain JSON file), using the same
  `SavedDataType` pattern kamutotems already uses or the pattern documented in
  `pocketdungeons`'s `WondrousState` for reference.
- The codec is a proper Mojang `Codec`, not hand-rolled NBT serialization, so
  Phase 7 can absorb it without rewriting.
- The record's fields are the virtual entity's identity and state, not
  Minecraft-specific implementation details. Think of it as "what makes this
  entity a kamutotems boss" rather than "what BossHost happens to track."

### What stays out of the record

`FREE_CLAIMS` and `SIGIL_COUNTERS` are daily-reset player economy state, not
entity identity. They stay in `boss_state.json` via the existing `Persist`
mechanism (`BossHost.java:204`, `BossHost.java:463-477`). Do not move them
into the boss record.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.**
   ```bash
   javap -cp "C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar" net.minecraft.world.level.saveddata.SavedData
   ```
   Checking a method exists is not the same as checking what it does. Three
   shipped bugs in this workspace came from that distinction.

2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla entities, and server-side logic.

3. **No em dashes and no double hyphens as punctuation**, anywhere a person
   reads: chat, dialogs, item lore, command output, log lines, markdown,
   javadoc, commit messages. See `A:\MrPinoys Mods\CLAUDE.md`.

4. **`./gradlew build` green after every commit**, not just at the end.

5. **Do not modify any file in `wondrous/`.** Wondrous is frozen for the
   duration of the entire Thingy plan. You are working in `kamutotems/` for
   Phase 0, but the freeze applies regardless.

6. **Match the existing code, do not reinvent its shape.** Read
   `kamutotems/fabric/src/main/java/kamutotems/Persist.java` and any existing
   `SavedData` usage in kamutotems before writing your own. Match the package
   conventions, the logging style, and the config-reading patterns
   (`KamuTotemsConfig`) already in use.

7. **Never invent a Minecraft API.** If you expect a method to exist and
   cannot find it, do not guess a plausible name. Stop, write down what you
   were looking for and what you found instead in
   `thingy/docs/DISCOVERIES.md` under a "Signatures" heading, and ask. A
   skipped task is fine. A wrong method call that compiles against the wrong
   overload is not.

## Compile command

```bash
cd "A:/MrPinoys Mods/kamutotems"
./gradlew.bat :fabric:compileJava --console=plain -q
```

No output means it passed. If it fails, that is your task to fix before
touching anything else.

## Working method

1. Read `BossHost.java` in full. Understand the lifecycle: `onServerStarted`
   loads `boss_state.json`, `onTick` sweeps dead bosses from the maps,
   `onDeath` drops loot and removes tracking, `onEntityLoad` is the orphan
   path you are replacing, `onDisconnect` despawns the player's boss.
2. Read `Boss.java` in full. Understand which fields are identity (tier, roll,
   kamu, aura, owner, fromSigil, purchaseCounter, seed) and which are runtime
   (entity, bar). The record persists identity; runtime is rebuilt on load.
3. Read `Persist.java` to match the existing persistence pattern.
4. Verify the `SavedData` / `SavedDataType` API against the 26.2 jar. Do not
   write from memory.
5. Design the record codec. It is a `Codec<BossRecord>` (or whatever you name
   it) using Mojang's `RecordCodecBuilder`. Fields: entity UUID, owner UUID,
   tier (int), roll (BossRoll has its own shape; check if it is already
   serializable), kamu ids (list of strings, resolved against catalog on
   load), aura (AuraSpec; check if it is already serializable), fromSigil
   (bool), purchaseCounter (int), seed (long).
6. Implement the `SavedData`. Wire it into `onServerStarted` (load) and
   `onShutdown` (save) alongside the existing `boss_state.json` load/save.
7. Replace `onEntityLoad`'s tag-strip path with a reattach path: look up the
   entity UUID in the persisted records, rebuild the `Boss` object, restore
   the bar (if owner is online), add to `BY_ENTITY` and `BY_PLAYER`.
8. Update `onTick`'s dead-boss sweep to also remove the persisted record when
   a boss dies or is removed.
9. Update `onDeath` to remove the persisted record.
10. Compile. Test what you can headless (the persistence write/read cycle is
    testable without a live server; the reattachment is live-only).
11. Write the documentation deliverable (see below).

## What you must not do

- **Do not modify `wondrous/`.** The freeze is absolute for the entire plan.
- **Do not move `FREE_CLAIMS` or `SIGIL_COUNTERS` into the boss record.** They
  are player economy state, not entity identity. They stay in
  `boss_state.json`.
- **Do not change the boss entity tag (`kamutotems_boss`).** Phase 7 will
  absorb the tag; changing it now creates a migration that Phase 7 then has
  to pay for.
- **Do not change the `Boss` constructor or its field names.** Phase 7 absorbs
  this record shape; instability now is migration debt later.
- **Do not strip the tag on load.** The entire point of this fix is that the
  tag-strip path is wrong. Replace it, do not refine it.

## Exit criteria

1. A boss present at crash restart reattaches with its identity, bar, and aura
   intact. The `onEntityLoad` tag-strip path is deleted.
2. `./gradlew.bat :fabric:compileJava` passes.
3. `./gradlew.bat :fabric:test` passes (if existing tests exist; do not break
   them).
4. The record format is documented in `thingy/docs/DISCOVERIES.md` as the
   Phase 7 target shape. Include the codec fields and the reattachment logic
   summary, so Phase 7 can absorb it by reference.
5. One commit, message explains why (crash-recovery fix, not every-restart
  fix; shaped for Phase 7 absorption).

## Documentation you owe on completion

Create `thingy/docs/DISCOVERIES.md` (the file does not exist yet) and seed it
with:

1. The boss record format: fields, codec shape, reattachment logic. Labeled
   as "Phase 7 target shape."
2. Any API findings you verified against the jar during this work
   (`SavedDataType` signatures, `SavedDataStorage` access patterns, whatever
   you had to look up).
3. Any traps you hit.

Use the same style as `pocketdungeons/docs/DISCOVERIES.md`: numbered entries,
code snippets where relevant, "so nobody re-derives them the hard way."

## If you get stuck

- An API that does not exist as you expected: this is what rule 7 exists for.
  Write it in `thingy/docs/DISCOVERIES.md` and ask.
- `BossRoll` or `AuraSpec` not having a codec: check whether they are already
  serializable via any existing path (do they round-trip through
  `boss_state.json` today?). If not, you may need to add a codec to them. That
  is in scope for this fix; do not stub it.
- The bar cannot be rebuilt because the owner is offline: this is expected.
  Rebuild the bar object, do not add any player to it, and add the owner when
  they next connect (the existing `onDisconnect` / reconnect path should
  handle re-adding; check whether it does).
- Anything not covered by the above: stop and describe the specific blocker
  rather than guessing past it.

## After Phase 0

Phase 1 (building the Thingy framework itself) is the next phase. It is
described in full in `thingy/docs/PLAN.md`. Do not start it until Phase 0 is
complete and the exit criteria above are met. Phase 1 is a much larger scope
(29 items, the full `thingy/api` and `thingy/fabric` modules) and warrants its
own handoff.
