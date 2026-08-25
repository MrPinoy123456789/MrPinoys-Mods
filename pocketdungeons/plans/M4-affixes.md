# M4 — Affixes

> Roadmap: `../ROADMAP.md` · Why: `../MYTHIC_PLUS_RECONCILIATION.md` §4, §5, §7 ·
> Status: `../PROGRESS.md`

**Goal:** the ladder gets texture. Affixes stack by level, seed from the key, and
**every one of them hands you something.**

**Blocked on:** nothing hard. **Blocks:** M5 (Feral is an affix).

---

## The rule, before any code

> **Every affix bends a rule and pays for it with a gift.** (§5.0)

Blizzard's own answer to affix fatigue was not more affixes — it was affixes
players can *use*. If a proposed affix cannot be given a kiss, **cut it**; do not
ship the exception that quietly repeals the rule.

---

## T4.1 — `Affix` enum → stackable set

`Keystone.Affix` is `NONE / OMINOUS / FRAGILE` (`Keystone.java:63–65`).

**Storage needs no migration.** `DungeonLog` stores
`Codec.STRING.optionalFieldOf("keystone_affix", "")`. Comma-join a set into the
same field: `"ominous"` parses as a one-element set, `""` as empty.

**Only three sites branch on the affix today** — refactor each to `contains(...)`:

| Site | Current |
|---|---|
| `Instances.java:360` | `affix == Keystone.Affix.OMINOUS` |
| `Instances.java:1137` | `record.affix == Keystone.Affix.OMINOUS` |
| `Keystones.java:63` | `affix == Keystone.Affix.FRAGILE` |

`Instances.java:1325` (`Keystone.Affix.parse`) is the parse site; make it the set
parser. `NONE` becomes "the empty set" — consider deleting the enum constant
rather than carrying a member that means "no members".

---

## T4.2 — Thresholds 5 / 11 / 17

Decided in §4.1. Not 5/10/15 — that leaves levels 15–25 completely flat, ten
levels of nothing sitting exactly where the most invested players live. 5/11/17
spreads three changes across the whole ladder, largest gap six.

| Question | Answered by |
|---|---|
| *How many* affixes? | Level thresholds 5 / 11 / 17 |
| *Which* ones? | **Seeded from the keystone**, same source as the layout |
| One more, electively? | The door choice, at any level |

⚠ **No weekly rotation.** It was cut (§4). Do not reintroduce a wall-clock seed —
seeding from the key keeps the name a pure function of `(level, affixSet)`, which
the instance watcher depends on when it rewrites stale keystones in place.

---

## T4.3 — Depletion takes the max

**`max` across the active set, hard-capped at 2× — never the sum, never the
product** (§7.1). A level-20 key must not shed most of a ladder on one bad night.
Loss aversion already runs at roughly twice the felt weight of an equivalent
gain, so a doubled depletion is felt as roughly quadrupled.

---

## T4.4 — Naming

`<intensifier> <affix> Keystone [<level>]`, remaining affixes in a bracketed
subtitle, mirroring `BossNames.build`'s `<title> [<subtitle>]` shape.

Intensifier by level: **Baby** (1–5), **Lowkey** (6–10), **Highkey** (11–15),
**Menace** (16–20), **Unhinged** (21–25).

| Affix | Label |
|---|---|
| `OMINOUS` | Cooked |
| `FRAGILE` | Big L |
| `FERAL` | Feral |

Render in **enum declaration order, always**. Any randomness in the label churns
on every reconciliation.

Vocabulary follows Kamu Totems' *convention* with **entirely separate words** —
`kamutotems/INTEGRATION.md`'s stranger rule.

---

## T4.5 — The four new affixes

| Affix | Curse | Kiss | Cost |
|---|---|---|---|
| **Swarming** | `total_mobs` / `simultaneous_mobs` up | More bodies is more drops | trivial — a number in a file we already write |
| **Overclocked** | `trialSpawnerCooldownTicks` scaled down | Faster waves clears faster against the clock | trivial |
| **Molten** | Stamp-time lava and magma hazards | **The only source of lava in the game** | low |
| **Silenced** | You cannot use consumables | **The mobs cannot hear you** | low |

`TrialContent` writes the trial-spawner config at stamp time from
`data/pocketdungeons/trial_spawner/tier_N/{normal,ominous}.json`, carrying
`total_mobs`, `simultaneous_mobs`, `ticks_between_spawn`, `spawn_potentials[]`.
**Swarming and Overclocked are edits to a document the mod already generates.**

**Molten** is load-bearing for M6: a sealed dungeon has no lava, and lava gates
furnace fuel and (with water) obsidian, the ender chest and the Nether-adjacent
product chain. Hazard blocks *are* the reward.

**Silenced** is the most interesting affix in the list. Curse and kiss are the
same sentence, and it hands back a *tactical* route decision — sneak the
encounter or fight it, with no potions to fall back on — which §3.3 concedes
procedural layouts otherwise destroy. One item-use listener plus a spawner-config
change.

⚠ **Do not data-drive affixes yet.** Wait until 5–6 exist in Java and the varying
knobs are known (§6.1, M8).

---

## Done when

- [x] A level-16 key reads a stacked name, e.g.
      `Menace Cooked Keystone [16] [Swarming, Molten]` — **note:** the door's
      elective affix (Cooked/Big L) sits *on top of* the level's seeded
      affixes, not inside their count, so a level-16 Cooked key carries three
      affixes total (one elective + two seeded), not the single-subtitle
      example this line originally sketched. Decided explicitly with the user
      before implementation.
- [x] Old saves holding `"ominous"` load as a one-element set, no migration
      (`AffixMathTest.testParseCompat`/`testJoinRoundTrip`)
- [x] Two affixes that both touch depletion produce `max`, not a product
      (`AffixMathTest.testDepletionMultiplier`, `KeystoneMathTest`'s
      multiplier-cap case)
- [x] Each of the five affixes can be described to a player as "it does X, and
      you get Y" without hesitating — `Affix.blurb`, one per affix, rendered as
      lore on the item and in the `/dungeon key` dialog
- [x] The keystone name is stable across a watcher reconciliation — the name is
      a pure function of `(level, affixSet)` and the seeded pick is a pure
      function of `(owner, level)`; `AffixMathTest.testSeededForStability`
      checks 100 repeat calls agree and a round trip through the stored form
      agrees
- [x] `./gradlew build` green — `affixMathTest` added alongside the other
      pure-Java verification tasks

**Still open, in-world with a client:** the stacked chest reward table, Molten's
passability, and Silenced's two halves (denied consumable + reduced spawner
range) have not been walked live yet — see `PROGRESS.md`'s T4.5 note.
