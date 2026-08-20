# Phase 2 — Repository Scan and Assessment

**Audit date:** 2026-08-20
**Audit scope:** All 16 Fabric projects were inspected before the scope correction.
**Remediation scope:** The 14 active projects only; `archived/cobblebending` and `archived/dailyquests` are excluded from Phases 3–5. Their findings remain below as informational history and are not remediation requirements.
**Source reviewed:** 428 production Java files. No production Kotlin files are present. Generated/decompiled scratch material, tests, Gradle caches, build outputs, and `dist/` artifacts were excluded.

## Executive summary

The suite has a generally sound server-only architecture: all 16 `fabric.mod.json` files declare `"environment": "server"`, and no production source imports `net.minecraft.client`, a Fabric client API, or `ClientModInitializer`. No dedicated-server classloading defect was found in this pass.

The audit found several correctness and persistence risks that should be addressed before broad style refactoring. The most important are Kamu Totems data loss during serialization/rename operations, unsafe background iteration over server-thread state in Daily Quests and Quiz Engine, Ballot archive/delete ordering that can lose poll history, and a Hearsay iterator mutation that can discard queued speech.

No confirmed obsolete Fabric event API was found for the Minecraft 26.2 / Fabric API 0.156.0 target. The repository does use Loom `1.17-SNAPSHOT` throughout, two archived builds emit Gradle deprecation warnings, and Ballot intentionally retains one deprecated compatibility method.

## Severity definitions

- **High:** Can lose persistent player/server data, crash a normal runtime path, or materially corrupt core behavior.
- **Medium:** Produces incorrect gameplay, intermittent state loss, leaks references, or violates a documented transaction contract.
- **Low:** Redundant/dead code, stale documentation, weak validation, or maintenance debt with limited immediate impact.
- **Review:** Behavior may be intentional and needs a product decision before changing it.

## High-priority findings

### H1 — Kamu polarity is lost when the component catalog is persisted and reloaded

- **Files:** `kamutotems/fabric/src/main/java/kamutotems/KamuData.java`
- `components.json` does not serialize aura polarity. The load path then uses a compatibility constructor that defaults polarity to `NONE`.
- Aura-capable kamu can therefore become unusable as aura modifiers after the generated catalog is loaded.
- **Phase 3:** Add polarity to the schema, preserve backward compatibility for existing files, and add a round-trip test.

### H2 — Renaming a Kamuy can erase its bound pool

- **Files:** `kamutotems/fabric/src/main/java/kamutotems/NameMenu.java`
- The rename path reconstructs the Kamuy through a six-argument compatibility constructor whose omitted pool defaults to an empty list.
- A cosmetic rename can discard pooled-but-unslotted kamu.
- **Phase 3:** Preserve the existing pool in the copy operation and add a rename regression test.

### H3 — Invalid persisted slot tiers can crash effect resolution

- **Files:** `kamutotems/core/src/main/java/kamutotems/core/Resolver.java`
- `TIER_MULT[s.tier() - 1]` trusts persisted/configured tier data without validating the 1–3 range.
- A malformed save or configuration can throw `ArrayIndexOutOfBoundsException` during combat.
- **Phase 3:** Validate at deserialization boundaries and defensively reject/skip invalid slots in the resolver with a warning at the Minecraft boundary.

### H4 — Daily Quests background persistence iterates mutable server-thread state (archived; informational only)

- **Files:** `archived/dailyquests/src/main/java/dailyquests/DailyState.java`
- The scheduled writer iterates a `LinkedHashMap` while the server thread can mutate it.
- This can produce inconsistent snapshots or `ConcurrentModificationException`. A failed deferred write also clears `dirty` before I/O and does not restore it, so changes may not be retried.
- **Phase 3:** Snapshot under synchronization on the server thread or synchronize all access; restore dirty state on failure; test failed-write retry behavior.

### H5 — Quiz Engine leaderboard persistence has the same cross-thread race

- **Files:** `quizengine/fabric/src/main/java/quizengine/mc/Leaderboard.java`
- A background scheduler snapshots/iterates the same `LinkedHashMap` that the server thread mutates.
- This contradicts the class's single-threaded state contract and can fail intermittently.
- **Phase 3:** Use an immutable synchronized snapshot or move serialization scheduling back to the server thread.

### H6 — Ballot can delete a live poll after archive writing fails

- **Files:** `ballot/fabric/src/main/java/ballot/mc/PollStore.java`
- `archive()` proceeds to deletion even though `writeAtomically` does not report whether `archive.json` was successfully written.
- Poll history can be lost on an I/O failure.
- **Phase 3:** Return an explicit write result, delete only after successful durable archive replacement, and test an injected write failure.

### H7 — Ballot removes a poll from memory before confirming file deletion

- **Files:** `ballot/fabric/src/main/java/ballot/mc/PollStore.java`
- The in-memory entry is removed before `Files.deleteIfExists` succeeds.
- On failure, runtime and disk disagree, and the poll can reappear after reload.
- **Phase 3:** Perform disk mutation first or restore memory on failure; report failure to the caller.

### H8 — Hearsay can remove a newly queued speech segment immediately

- **Files:** `hearsay/fabric/src/main/java/hearsay/Bubbles.java`
- During `speech.values().removeIf`, expiry handling inserts the replacement segment under the same map key and then returns `true` for the current value.
- Iterator removal can remove the replacement; mutating the map through another path while iterating its values is also fragile.
- **Phase 3:** Collect transitions separately and apply them after iteration, with a multi-segment speech regression test.

### H9 — Cobble Economy audit records can be lost at shutdown

- **Files:** `cobbleeconomy/fabric/src/main/java/cobbleeconomy/TransactionLog.java`
- `close()` only flips `running`; it neither joins the daemon writer nor synchronously drains the queue.
- Queued transaction records may be lost during shutdown despite the loop's drain intent.
- **Phase 3:** Stop accepting records, drain/flush, close the writer, and join with a bounded shutdown policy.

## Medium-priority findings

### M1 — Chat Donkey registers the orphan sweep twice

- **Files:** `chatdonkey/fabric/src/main/java/chatdonkey/ChatDonkeyMod.java`
- `OrphanSweep.register(events)` is called twice, but only the second returned instance is ticked.
- The first instance retains registered listeners and can accumulate queued entity references indefinitely.
- **Phase 3:** Register once and keep the single owned instance.

### M2 — Cobble Economy inventory removal is not truly all-or-nothing

- **Files:** `cobbleeconomy/fabric/src/main/java/cobbleeconomy/ItemBank.java`
- Validation and shrinking happen in one loop. If a later defensive recheck fails, earlier slots remain reduced.
- **Phase 3:** Complete validation first, then mutate, or record and roll back exact deltas.

### M3 — Hearsay's first shuffle-bag cycle is deterministic

- **Files:** `hearsay/core/src/main/java/hearsay/core/LinePools.java`
- A new bag begins in configuration order and is shuffled only after the first cycle.
- **Phase 3:** Shuffle when creating every bag, including the initial one; retain deterministic seeded tests.

### M4 — Hearsay consumes ambient listener budget before a line is deliverable

- **Files:** `hearsay/fabric/src/main/java/hearsay/Speech.java`
- Budget is charged before hush, candidate, cooldown, raid, and sleeping checks.
- Failed attempts can silence a listener for the full quiet period without speech.
- **Phase 3:** Charge only after all delivery checks pass, matching the existing `offer` flow.

### M5 — Villager interaction is reported as a completed trade

- **Files:** `hearsay/fabric/src/main/java/hearsay/HearsayMod.java`, `hearsay/fabric/src/main/java/hearsay/Reactions.java`
- `UseEntityCallback` detects use/opening, not trade completion; no main-hand filter is applied.
- **Phase 3:** Rename the event semantics or hook an actual trade-completion path if available for 26.2.

### M6 — Kamu event matcher accepts a missing required event ID

- **Files:** `kamutotems/core/src/main/java/kamutotems/core/EventMatcher.java`
- A matcher requiring an ID rejects only a non-null different ID; an absent incoming ID passes.
- **Phase 3:** Require equality whenever the matcher declares an event ID.

### M7 — Kamu reaction depth cap is likely off by one

- **Files:** `kamutotems/core/src/main/java/kamutotems/core/ReactionEngine.java`
- Truncation occurs at `applied >= depthCap - 1` before applying the current match, so a cap of four allows only three applications.
- **Phase 3:** Define cap semantics in tests, then adjust the condition.

### M8 — Echo Queue persists a dimension but resolves in the caster's current level

- **Files:** `kamutotems/fabric/src/main/java/kamutotems/EchoQueue.java`
- The originating dimension is stored but ignored during delayed target resolution.
- Dimension travel before payout changes where target lookup occurs.
- **Phase 3:** Resolve against the stored level, or remove the field and explicitly document current-level behavior.

### M9 — Pocket Dungeons ignores `maxGridSpan` configuration

- **Files:** `pocketdungeons/src/main/java/pocketdungeons/RoomSelector.java`, `PocketDungeonsConfig.java`
- Validation uses a hard-coded span of 12 rather than the configured value.
- **Phase 3:** Pass validated config into planning and test non-default limits.

### M10 — Quiz content reload is neither exception-safe nor atomic

- **Files:** `quizengine/fabric/src/main/java/quizengine/mc/Content.java`
- Runtime Gson parse/type exceptions are not caught. Registries are assigned one at a time, so a later failure leaves a mixed old/new configuration.
- **Phase 3:** Parse all files into local values, validate them, then swap the complete content snapshot atomically.

### M11 — Wondrous `/links` branch is built but never registered

- **Files:** `wondrous/fabric/src/main/java/wondrous/WondrousCommands.java`
- Help advertises a command branch that is never attached to the root command.
- **Phase 3:** Attach the branch and add command-tree coverage.

### M12 — Wayfarers trade GUI sizing ignores buy listings

- **Files:** `wayfarers/fabric/src/main/java/wayfarers/TradeGui.java`
- Row calculation uses only sell count even though rendering includes a separator and buy entries.
- Larger buy lists can be truncated.
- **Phase 3:** Size from total rendered slots and clamp/paginate to the supported menu size.

### M13 — Wayfarers inventory-capacity preflight ignores components

- **Files:** `wayfarers/fabric/src/main/java/wayfarers/Payment.java`
- `canHold` treats equal base items as merge-compatible even if custom data/components differ.
- A player can be charged after a false-positive capacity check and receive the product as a drop.
- **Phase 3:** Use same-item-and-components semantics for partial-stack capacity.

### M14 — Small Talk request particles are not assignee-specific

- **Files:** `smalltalk/src/main/java/smalltalk/task/RequestOfferer.java`
- A tracking player sees a pending marker if the villager has an offered task for any player.
- **Phase 3:** Match `Task.assignee()` to the tracking player's UUID.

### M15 — Wondrous station placement confirmation can misattribute a vanilla block

- **Files:** `wondrous/fabric/src/main/java/wondrous/CraftStation.java`, `LazySprinkler.java`
- Placement stores only dimension/position and confirms on the next tick. It does not bind the pending record to a player, hand, stack, or actual placement result.
- Another matching vanilla block appearing at that location before confirmation can be promoted to a custom station/sprinkler.
- **Phase 3:** Prefer a reliable placement event/mixin result, or store enough identity and verify item consumption/placer context.

### M16 — Archived Cobble Bending block identity omits dimension (informational only)

- **Files:** `archived/cobblebending/src/main/java/cobblebending/BentBlocks.java`
- The global map is keyed only by `BlockPos` although records include dimension.
- Equal coordinates in different dimensions collide.
- **Phase 3:** Key by dimension plus position.

### M17 — Archived Cobble Bending can choose a farther block over a nearer entity (informational only)

- **Files:** `archived/cobblebending/src/main/java/cobblebending/Boulder.java`
- Block and entity raycast hits are handled in fixed order without comparing distances.
- **Phase 3:** Select the nearest hit along the movement segment.

### M18 — Bounties may miss indirect player kills

- **Files:** `bounties/fabric/src/main/java/bounties/BountyMod.java`
- Kill credit checks `DamageSource.getEntity()` directly; projectile ownership may be represented by the causing entity instead.
- **Phase 3:** Verify 26.2 damage-source semantics and credit the responsible `ServerPlayer` for arrows/other owned projectiles.

### M19 — Bounty temp-file recovery lacks an atomic-move fallback

- **Files:** `bounties/fabric/src/main/java/bounties/BountyState.java`
- Startup recovery uses only `ATOMIC_MOVE`, while normal writes already handle unsupported atomic moves.
- **Phase 3:** Reuse the same move helper/fallback for recovery and regular writes.

### M20 — Ballot Mixin state survives an exceptional vanilla return

- **Files:** `ballot/fabric/src/main/java/ballot/mc/mixin/BlockItemMixin.java`
- Thread-local state is removed at `RETURN`. If vanilla placement throws before returning, state remains until a later placement overwrites it.
- **Phase 3:** Prefer a single wrapping injection if supported, or otherwise ensure cleanup on exceptional paths.

## Low-priority smells and maintenance debt

### L1 — Duplicate/dead Growy Can implementation

- **Files:** `wondrous/fabric/src/main/java/wondrous/GrowyCan.java`, `Growth.java`, `WondrousMod.java`
- Both implementations use `growy_can`, but only `Growth.register` is initialized.
- **Phase 3:** Confirm `GrowyCan` is superseded, then remove it or consolidate behavior.

### L2 — Unused Cobble Bending projectile cooldown field

- **Files:** `archived/cobblebending/src/main/java/cobblebending/Boulder.java`, `Hurl.java`
- `Boulder` stores a cooldown it never reads; `Hurl` already applies cooldown.
- **Phase 3:** Remove the field/constructor parameter after confirming no serialization or reflection dependency.

### L3 — Ballot loaded keys bypass existing validation

- **Files:** `ballot/fabric/src/main/java/ballot/mc/PollStore.java`
- `loadOne` accepts JSON filename keys without applying `isValidKey`.
- **Phase 3:** Validate and quarantine or skip malformed filenames.

### L4 — Stale Pocket Dungeons and Small Talk documentation

- **Files:** `pocketdungeons/src/main/java/pocketdungeons/PocketDungeonsMod.java`, `smalltalk/src/main/java/smalltalk/task/Task.java`
- Entrypoint/task comments describe earlier implementation states.
- **Phase 3:** Update while changing the affected subsystems.

### L5 — Repeated service-locator/static mutable state patterns

- Several Fabric modules hold process-global mutable maps and initialized-once service references. Most are server-thread confined, but ownership and stop/reload cleanup are inconsistent.
- **Phase 3:** Do not introduce a cross-mod shared framework. Instead, add explicit per-mod lifecycle cleanup and immutable snapshots where needed.

## Review items requiring product intent

### R1 — Duplicate bounty acceptance

- **Files:** `bounties/core/src/main/java/bounties/core/PlayerBounties.java`, `bounties/fabric/src/main/java/bounties/BountyCommands.java`
- The same bounty can be accepted repeatedly, and one kill advances every matching copy.
- Decide whether stacking identical rewards is intended before changing behavior.

### R2 — Streak gap semantics

- **Files:** `kamutotems/core/src/main/java/kamutotems/core/Streak.java`
- Completion increments on any later date; skipped-day handling relies on a separate `onMissedDay` call.
- Decide whether the value object itself should enforce consecutive dates.

### R3 — Deprecated Ballot migration method

- **Files:** `ballot/core/src/main/java/ballot/Rules.java`
- `Rules.unconfigured()` is explicitly deprecated but retained so old files load.
- Keep until the supported migration window closes; do not remove as a mechanical cleanup.

## Loader/build assessment

- All projects target Minecraft 26.2, Fabric Loader 0.19.3, and Java 25.
- All 16 mod descriptors are server-only.
- No production client imports were found.
- The build audit identified two deprecated Spirit Wolves APIs: `ServerPlayerEvents.ALLOW_DEATH` and `Item.builtInRegistryHolder()`. Phase 3 replaced them with `ServerLivingEntityEvents.ALLOW_DEATH` and `BuiltInRegistries.ITEM.wrapAsHolder(...)`.
- Loom `1.17-SNAPSHOT` is used across projects. This is reproducibility risk rather than a confirmed source API defect; pin a stable compatible Loom release when available and validated.
- Archived Cobble Bending and Daily Quests builds report deprecated Gradle features that will become incompatible with Gradle 10. Run those builds with `--warning-mode all` before a Gradle wrapper upgrade.

## Proposed Phase 3 execution order

1. Add regression tests for active-project findings and reproduce each failure where practical.
2. Fix destructive persistence/data-copy issues: H1, H2, H5, H6, H7, H9. H4 is excluded with the archived mods.
3. Fix runtime crash/corruption paths: H3, H8, M2, M10.
4. Fix gameplay correctness issues M1–M20, preserving intentional behavior in R1–R3.
5. Remove confirmed dead/redundant code and update stale documentation.
6. Build every project and run all core/engine tests; repair failures until clean.
7. Review the full diff for server-only safety, configuration compatibility, and accidental cross-mod coupling.

## Phase 1 verification status

- Combined documentation diff passes `git diff --check`.
- All six Mixin injections have an immediate explanation of target, interception point, and reason.
- Documentation changes add comments/Javadocs only; no executable source was changed in Phases 1–2.
- Verification builds already completed successfully for archived Cobble Bending, archived Daily Quests, Ballot (148 tests), and Bounties (38 tests). Generated build artifacts were removed from the working diff afterward.
- Full-suite compilation remains part of Phase 4 after approved Phase 3 changes.
