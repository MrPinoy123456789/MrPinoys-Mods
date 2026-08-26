# M6 — Supply

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §3.6.1, §3.7 · Status: `../PROGRESS.md`

**Goal:** *"could a player progress without ever leaving?"* stops being
aspirational and starts being true.

**Blocked on:** M1 (tiered palettes need themes). **Blocks:** M7 in practice.

> **Self-sufficiency is a constraint, not a mode.** There is no "no-overworld
> mode" to build. Hold the constraint and a server owner who empties the
> overworld gets the standalone experience for free, while a suite server gets
> both — one design, nothing tuned twice.

---

## T6.1 — The rule that makes the tables work

> **Guaranteed floors for consumables. Weighted rolls for treasure.**

*"You can get X"* and *"you can always get more X"* are different properties. A
weighted chance at bones is fine for flavour and fatal for a taming economy.

| Guaranteed floor, per run | Weighted roll |
|---|---|
| Food | Treasure |
| Torches / light | Enchanted gear |
| **Bones** (M5 depends on this) | Rare decoratives |
| Building blocks | Trophy items |
| Seeds and dirt | |

Implement as a floor pass over the tier tables, not as a separate table: a floor
that lives in a different file from the rolls is a floor that drifts.

> **As built.** The floor is inlined as guaranteed pools at the top of all six
> tier tables (`chests/tier_1..3`, plus each `_ominous` twin). There was nothing
> to share out into a referenced sub-table even if the rule allowed it: the
> blocks pool differs by tier by design (T6.2), so the tiers are not copies of
> one floor, they are three floors.
>
> One thing the plan did not anticipate. The tier tables are reached through a
> **vault** (needs a key) or the **reward chests** (need the clock), so a floor
> that lived only there would be gated after all. `chests/supply` (the free
> chest beside a vault, gated on nothing) was a single tier-agnostic table.
> It is now three (`chests/supply_tier_1..3`), carrying the same floor, which is
> the one-line change in `TrialContent.applyLoot` that makes the floor genuinely
> per-run rather than per-reward.

---

## T6.2 — Tiered building blocks, as the point

Not filler. A tier-3 run must yield materials a tier-1 run never does, exactly
the way ore tiers work.

| Tier | Palette |
|---|---|
| 1 | stone, wood, iron, moss |
| 2 | deepslate, copper, prismarine, crying obsidian |
| 3 | end stone, ancient-city materials, rare decoratives |

Pair with M1's per-theme palettes so **dismantling a deep dungeon yields blocks a
shallow one does not**. This is what makes the decorate pillar *run on* the crawl
pillar instead of sitting beside it, and it is the answer to "a keystone level is
just a number" — the room becomes a physical record of how deep you have been.

**Already solved, do not re-solve:** the lingering dungeon (M2) is a renewable
block source. The walls regenerate every run, so stone bricks, polished andesite
and sea lanterns are effectively infinite.

---

## T6.3 — Wood, seeds and dirt

**Wood is the real structural gap** — no trees, and wood gates crafting tables,
sticks, chests and tool handles.

**Fix diegetically: a grove / garden room type.** Dirt, saplings, water, light.
One template and one `dungeon_room/*.json` entry, **no Java**. A strange
overgrown chamber deep underground fits the tone exactly.

**Seeds and dirt are the highest-leverage guarantee in this milestone.** Once a
player has dirt, water and seeds, the room becomes their farm and food stops
being a loot problem permanently. That is the Skyblock bootstrap, and it is the
one thing §3.7 genuinely borrows.

> **As built.** Two corrections, neither of them Java.
>
> **It is two JSON files, not one, and no new `.nbt`.** The grove is a
> `worldgen/processor_list` (`theme_grove`) plus a `dungeon_room` entry
> (`grove.json`) that points the existing `rooms/mossy_tee` template at it. That
> is M1's whole argument being cashed in: stone bricks become oak logs, polished
> andesite becomes grass, and the mossy floor patch and its two corner columns
> become persistent oak leaves. A second template was never needed, and adding
> one would have meant regenerating an `.nbt` through
> `RoomTemplateGenerator`, which *is* Java.
>
> **No water, and that is a decision rather than an omission.** The generator
> already records why a flooded room does not work here: source blocks spread out
> through the doorways into the neighbouring cell, and the doorways are two blocks
> wide. Restricting a rule processor to floor level to get a flush pool is
> possible but fragile, and it would put a waterfall in the two corner columns,
> which are the same block. So the sealed dungeon has no water at all, and the
> farm does not need it: crops grow on dry farmland, and **bone meal is a
> guaranteed floor** (T6.1). Water arrives as a T6.4 *product* instead, a
> `water_bucket` in the tier-2 pool, which is the same argument as the ender
> chest applied to a resource rather than a station.
>
> **The sapling is guaranteed, not weighted.** Measured on a live server, the
> grove lands in about 12% of dungeons and raising its weight does not move that
> number: it already wins whenever it is eligible, and eligibility is a
> tee-masked corridor cell at depth 1 or more. That is the right frequency for a
> strange overgrown chamber and the wrong frequency for the only wood in the
> world, so `oak_sapling` has its own guaranteed pool beside dirt and seeds. The
> grove is the room type the milestone asked for; the sapling is what makes wood
> a floor rather than a find.

---

## T6.4 — Nether and End: products, not ingredients

Blaze rods, ender pearls and obsidian are unreachable — the crafting chain does
not exist here. So the tables must supply the **product**: the ender chest, the
brewing stand, the anvil.

**This is exactly where station unlocks stop being arbitrary and become the
economy.** A station is not a reward for its own sake; it is the only route to a
chain the sealed world cannot otherwise reach.

> **As built.** Tier 2 carries the workshop, on a 40% pool: brewing stand,
> cauldron, anvil, glass bottles, nether wart, soul sand, blaze powder, and the
> `water_bucket` T6.3 hands over to this task. Tier 3 carries the unreachable
> chains, on a pool with no chance gate at all. Every tier-3 chest yields one
> product: ender chest, enchanting table, obsidian, chorus flower, end rod, blaze
> rod, ender pearls. That asymmetry is deliberate. A weighted chance at the only
> route to a chain is the same failure the floor rule exists to prevent, and a
> tier-3 chest is already earned twice over, by the keystone level and by the
> clock.

Lava is now **Molten's** job (M4) — do not add a second faucet here. If Molten
proves too narrow a channel, widen it after this milestone shows whether it
actually pinches.

---

## T6.5 — The plumbing

Four defaults assume an overworld. None of this is a mode; all of it is
preference order.

| | Today | Needs |
|---|---|---|
| Entry | Overworld lodestone | `/dungeon` as a **first-class route**, not a fallback |
| Exit | Room lodestone → overworld | Absent or no-op when there is nowhere to go |
| Join | Vanilla spawns in the overworld | Teleport to the room — `ServerPlayConnectionEvents.JOIN` is already registered with `JOIN_RECOVERY_DELAY_TICKS = 20` |
| Stray fallback | `getRespawnData()` → world spawn (`Instances.java:1867`) | Prefer the room |

> **As built.** The four rows turned out to be two changes, because three of the
> four ways out already funnel through one method.
>
> **Entry.** `/dungeon` now mints the first keystone itself when the player holds
> none, instead of refusing and naming `/dungeon key`. On a server with no
> overworld there is no lodestone to right-click and nobody to send looking for
> one, so a route that needs a second command first is still a fallback. It is
> the same free keystone `/dungeon key` already hands out on the same terms, and
> it is gated behind a new `Instances.ownsReenterableInstance` check so a player
> standing outside their own live run cannot mint a key by walking back into it.
>
> **Exit, join and stray.** All three end at `Instances.teleport`, whose
> missing-dimension fallback was a straight trip to the world spawn. It now calls
> `sendHome`, which asks `reenterOwnedInstance` for the player's room first and
> only then falls back to the world spawn. The join-recovery path with no return
> point calls it too. This is preference order and not a mode: on a suite server
> the return point resolves, `sendHome` is never reached, and nothing about the
> behaviour changed. On a server whose overworld is gone it is the only branch
> that can fire, and it lands the player in their room. The exit itself is left
> asking for the return point first on purpose, and gets the "no-op when there is
> nowhere to go" behaviour for free through the same fallback, one level down.

> Note: the dimension sets `bed_works: false` and `respawn_anchor_works: false`,
> so wool is decoration, not a respawn chain. Immaterial in practice — nobody
> dies and join teleports to the room — but do not let an audit lean on it.

---

## Done when

- [ ] A server with a **completely emptied overworld** is playable start to finish
- [ ] Every consumable in the floor table appears in every run, at every tier
- [ ] A tier-3 run visibly yields blocks a tier-1 run cannot
- [ ] Wood is obtainable without leaving, via a room type and no Java
- [ ] Nothing was tuned twice for "standalone" versus "suite"
- [ ] `./gradlew build` green
