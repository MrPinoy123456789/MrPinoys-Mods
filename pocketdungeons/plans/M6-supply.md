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

Implement as a floor pass over the tier tables, not as a separate table — a floor
that lives in a different file from the rolls is a floor that drifts.

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

---

## T6.4 — Nether and End: products, not ingredients

Blaze rods, ender pearls and obsidian are unreachable — the crafting chain does
not exist here. So the tables must supply the **product**: the ender chest, the
brewing stand, the anvil.

**This is exactly where station unlocks stop being arbitrary and become the
economy.** A station is not a reward for its own sake; it is the only route to a
chain the sealed world cannot otherwise reach.

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
