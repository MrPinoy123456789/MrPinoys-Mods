# Small Talk — Design Spec v0.6

> **Status:** core release implemented, 2026-08-16 (identity, dialogue
> engine, familiarity, gift/talk verbs, empty task registry). Everything
> from §12 onward in the build order (§19) is design + implementation plan,
> not yet code, unless a section says otherwise.
>
> Server-side-only Fabric mod. **Minimum Minecraft 26.2** (matches the rest of
> the suite's `gradle.properties`; earlier drafts said "1.21.6," which was the
> version-numbering scheme in effect when the vanilla-dialog dependency was
> first identified — see §0). **Standalone** — no dependency on Rehome,
> Hearsay, or anything else.

*Villagers have names, personalities, something to say about today — and
things they'd like you to do.*

**Product goals (v0.6 adds two to the original three):**

1. **Attachment** — villagers feel like people you know, not vending machines.
2. **Daily return** — a reason to log in *today specifically*.
3. **Engagement depth** — something to progress over weeks, without a grind.
4. **Social proof** *(new)* — attachment reads stronger multiplayer, when a
   villager can reference another player by name.
5. **Low ceiling, high floor** *(new)* — the loop must be satisfying in five
   minutes and must never demand more. The moment Small Talk feels like a
   chore, the whole premise (§7's "the register changing is the reward")
   collapses.

---

## 0. What changed from v0.1 → v0.2 → v0.3 → v0.4 → v0.5 → v0.6

### v0.5 → v0.6: from "shipped core" to "the daily loop"

v0.5 was a complete, correct dialogue-and-gifting mod with no reason for a
player to return on a *particular* day. v0.6 doesn't change anything already
shipped — it plans the smallest addition that creates a daily reason to
visit, plus three cheap, high-attachment-per-line additions identified by
product review:

- **§12 Requests, narrowed.** Of the six task types in the old build order,
  only **Fetch** ships next, gated behind a **daily cadence** so it's a
  reason to return *tomorrow*, not a queue to drain tonight. Delivery,
  Quarry, Décor, Visit, and Games are demoted from "build order" to
  "backlog, revisit after Fetch has playtest data" (§19).
- **§13 Birthdays** (new). A second UUID-hash-derived, zero-storage date,
  reusing exactly the trick §2 already uses for names — one special line and
  a doubled gift reaction, once a year, per villager.
- **§14 Shared-knowledge lines** (new). Villagers occasionally reference a
  *different* player in their memory log (§8) in dialogue. Uses data this
  mod already stores; no new storage.
- **§15 Cross-mod and world correctness, promoted to core.** The Rehome
  `HOME`-check gap (§1) and zombie-cure identity loss (old §18.1) were both
  "open questions" in v0.5. Both are attachment-destroying if left alone, so
  v0.6 promotes them to planned work with concrete implementation steps
  rather than leaving them as footnotes.
- **Documentation-reality correction:** §4.1's datapack schema
  (`data/<ns>/small_talk/dialogue/...`) was never actually built that way —
  the shipped `dialogue/DialogueLinesConfig.java` reads
  `config/smalltalk/lines.json` instead, a flat personality → context → line
  pool file with the same readOrCreate contract as `SmallTalkConfig`. §4.1
  below now describes what exists; the original datapack-per-context vision
  is noted as a possible future migration, not current behavior.

v0.1 was a dialogue mod. v0.2 kept that as the shippable core but provisioned
for **Requests** (§12), the system where residents ask you for things. v0.3
removes an entire subsystem: **there is no passive/ambient delivery anymore.**
v0.4 was a misfire — see below — and v0.5 is the actual fix: gifting moved
entirely into the Dialog system, no hand-item interaction left at all.

### v0.4 → v0.5: v0.4 was the wrong mod's fix

v0.4 (below) replaced the gift verb with emerald-only wolf-style taming,
after a real playtest accident: a player right-clicked a fletcher while
holding their axe and it vanished, consumed as an unwanted gift. That
diagnosis was right. The fix wasn't: **emerald-plus-taming was the idea for
Rehome's befriend flow** (a homeless *stranger* deciding to follow you),
not Small Talk's gift verb (a *resident* you already know). Small Talk
shipped it anyway, which is how a mod about residents ended up with a
befriend mechanic that only makes sense for someone you've never met.

**The actual fix has two parts:**

1. **The category system (§2) is restored.** Liked/disliked quirks, the
   `itemCategories` allowlist, `GiftReaction`'s loved/neutral/disliked tiers
   — all of it, unchanged from v0.3. `TOOLS` is back in the default
   category list too (see point 2 for why that's now safe).
2. **Gifting is no longer triggered by holding an item and right-clicking.**
   The real bug in v0.3 was never "which items are giftable" — it was
   "gifting fires off whatever happens to be in your hand." v0.4 tried to
   fix that by narrowing the item list to something you'd never hold by
   accident (an emerald). v0.5 fixes it by removing the trigger entirely:
   **the talk-verb dialogue's Gift button opens an inventory-filtered
   picker dialog, you choose an item from a list, you confirm, and only
   then is anything consumed** (§6.2). There is no longer any interaction
   tied to what's in the player's hand at all when talking to a resident —
   see §6's revised gesture table. This is also why `TOOLS` is safe to
   re-include: nothing happens until you deliberately open the picker and
   pick something, so an axe in your hand while walking past a villager is
   never at risk again, regardless of category.

**Everything in Small Talk is now a vanilla Dialog screen or a command a
Dialog button runs — nothing is triggered by hand-held items, sneaking
aside.** That was always the intent behind "Standalone" and "no client mod
needed" in this spec's header; v0.4's emerald verb was the one place that
principle had quietly slipped.

### v0.3 → v0.4: the taming detour (superseded by v0.5, kept for the record)

v0.3's gift verb accepted anything in a configured `itemCategories` map (§14)
and reacted according to whether the item matched a villager's derived
liked/disliked quirk (§2). First real playtest: a player right-clicked a
fletcher while holding their axe — mid-chop, not trying to gift anything —
and the axe vanished, because `minecraft:iron_axe` was in the default `TOOLS`
category and gifts are consumed unconditionally. Any item you might
routinely be holding for an unrelated reason was a live footgun, and tools
are exactly that category of item. The diagnosis held up; the fix — emerald-only
wolf-style taming — didn't, for the reasons above. See v0.4 → v0.5.

### v0.1 → v0.2

Four decisions were pulled forward because they're expensive to retrofit:

| Decision | Why |
|---|---|
| **Vanilla-dialog-capable version floor** | Requests need vanilla dialogs for yes/no. Shipping before that's available means stranding users or building a chat fallback later. |
| **A world-level task registry exists from day one** | A delivery between two villagers belongs to neither and survives both unloading. Retrofitting persistence into a mod built on "no save data" is a rewrite. |
| **`Errand` interface defined** | "Visit my house" is a temporary brain override. Define the shape now, fill it in later. |
| **`offers` field reserved in the dialogue schema** | Datapack formats are public API. Adding a field later breaks every community line pack. |

### v0.2 → v0.3: no more passive chatter

v0.2 specified proximity-triggered ambient chatter delivered via a
per-villager `TextDisplay` speech bubble — spawn, mount as passenger, sweep
orphans on crash/unload. Once **Hearsay** (a sibling mod in this suite) was
scoped, that turned out to be a near-exact duplicate: Hearsay already ticks
villagers for weather/dawn/dusk edge-triggers, already rate-limits per player,
already spawns and sweeps `TextDisplay` passengers tagged for orphan cleanup.
Two mods independently doing that is how you get double-greetings and two
sweep systems racing over similar-but-different marker tags.

**Resolution:** delivery mechanism is now the boundary between the two mods,
not just the resident/stranger split in §1.

- **Hearsay** owns everything passive: ambient world flavor, overheard
  two-hander scenes, anything that fires off a proximity/time/weather tick
  without the player doing anything.
- **Small Talk** owns everything the player deliberately triggers, and
  delivers all of it through **vanilla Dialog screens**, never a floating
  bubble. There is no proximity tick, no `chatterRadius`, no per-player global
  rate limit, no orphan sweep — none of that machinery exists in this mod
  anymore, because nothing spawns unprompted.

Concretely: sneak-right-click a resident (§6.1) and the Dialog that opens
**greets you with whatever the context engine (§4) would have said
ambiently** — on fire, first meeting after an absence, just weathered a raid
together, whatever's most specific right now. The context-evaluation logic
from v0.2 is unchanged; only its trigger moved from "every 40 ticks, in
radius" to "the instant you open the dialog." Nothing about the content or
the specificity bias is lost — it surfaces on click instead of on proximity.

This also folds a chunk of §12.2's request-offering machinery into the same
mechanism: a resident with an `OFFERED` task shows the request dialog on that
same click, in place of (not in addition to) the ambient-context greeting.

### 0.1 v0.3 revision: right-click opens the menu, not the trade screen

Originally the talk verb was sneak-right-click, so a bare right-click on a
resident kept going straight to vanilla trade (§6, v0.2's "trading is never
obstructed" principle). That's now reversed: **plain right-click on a
resident opens the Small Talk dialogue menu**, with a **Trade** button that
opens the vanilla trade screen. Trading is no longer zero-clicks away for
residents — it's one click into the menu, then Trade. See §6 for the full
gesture table and §18.7 for the tradeoff this accepts.

---

## 1. Concern boundary

| | Rehome | Hearsay | Small Talk |
|---|---|---|---|
| Owns | getting a villager somewhere | ambient world/village atmosphere | what a villager is like *to you specifically*, once you ask |
| Subject | **strangers** — villagers with no home | any villager | **residents** — villagers with a claimed bed |
| Trigger | player interaction (gift/follow) | passive: proximity + world-state ticks | player interaction only — right-click opens the menu |
| Delivery | n/a (no dialogue) | `TextDisplay` bubble, spawned unprompted | vanilla Dialog screen, opened on request |
| State | `follower` attachment | none (stateless flavor) | `familiarity` attachment + task registry |
| Works alone | yes | yes | yes |

None of the three mods read another's state or check whether it's loaded.
Rehome and Small Talk are disjoint on subject (home condition); Hearsay and
Small Talk are disjoint on trigger/delivery (passive bubble vs. on-demand
dialog) — two different axes, so all three can run together without collision
even though Rehome and Hearsay both touch "any villager."

> **Verify before relying on this:** Rehome's actual `GiftHandler` gates on
> `follower == null` (not currently following anyone), not on
> `MemoryModuleType.HOME`. A resident who has never been gifted-to by anyone
> still has `follower == null`, so as written today Rehome will intercept a
> gift meant for Small Talk. The subject boundary in this table is the
> *intended* contract, not yet the *implemented* one — either Rehome needs an
> explicit `HOME` check, or this table needs to describe reality. Flagging
> here so it doesn't get assumed-away when Small Talk's gift verb (§6.2) gets
> built.

**A resident is a villager with `MemoryModuleType.HOME` set.** Vanilla state.
No new concept, no registry, no migration.

---

## 2. Derived identity

**Derive, don't store.** Hash the villager's UUID into a name, a personality,
and two quirks (a liked and a disliked item category). Nothing written to
disk. Every villager in the world already has a stable identity, forever,
including ones that predate the mod.

**v0.4 briefly removed the liked/disliked quirks, v0.5 restored them.**
v0.4 mistakenly gave Small Talk's gift verb an emerald-only taming mechanic
that was actually meant for Rehome (§0), and dropped the quirks because
nothing read them under that mechanic. v0.5 put the category-based gift
reaction back (§6.2), so the quirks are load-bearing again.

**Stability contract.** The hash and the name pool ordering are frozen. Pools
are **additive only** — appending is safe; reordering or removing renames
everyone in every world. `identityVersion` in the pool file guards it;
bumping it is a breaking change.

**Name tags win.** A custom name is their name; the derived one is a default.

---

## 3. Personalities

Six, reweighting dialogue rather than gating content: **Chipper**, **Gruff**,
**Dreamy**, **Brisk**, **Bashful**, **Smug**.

Personality unlocks nothing. It selects which line file is read and shifts
weighting, so the same context produces six different remarks. It will later
also bias **which requests** a resident makes (§12.4).

---

## 4. The dialogue engine

Where Neighborly failed: ~250 context-free lines, exhausted in one evening.
**Two hundred contextual lines feel deeper than two thousand random ones**,
because the player reads them as noticing rather than cycling.

### 4.1 Schema (as shipped, not as originally drafted)

**Reality check (v0.6):** the datapack-per-context schema below was the
original design; what actually shipped is
`dialogue/DialogueLinesConfig.java` reading a single flat file,
`config/smalltalk/lines.json`, structured as
`{ personality: { context: [lines...] } }`, with the same
readOrCreate contract as `SmallTalkConfig`. `ContextEvaluator` still decides
*which* context IDs pass; `DialogueLinesConfig` just supplies the lines for
whichever ID wins. This is simpler to ship and edit by hand, at the cost of
per-context metadata (weight, conditions, `offers`) living in code
(`ContextEvaluator.java`) rather than in the same file as the lines.

The original per-file schema is kept below as a **possible future
migration** if community line packs become a priority — it is not current
behavior:

```
data/<ns>/small_talk/dialogue/<personality>/<context>.json
```

```json
{
  "context": "smalltalk:player_on_fire",
  "conditions": [
    { "type": "smalltalk:player_status", "effect": "on_fire" }
  ],
  "weight": 10,
  "lines": ["smalltalk.gruff.on_fire.0", "smalltalk.gruff.on_fire.1"],

  "offers": null
}
```

`offers` (reserved in that hypothetical schema) has no equivalent yet in the
shipped config format. §12's request-offer trigger below does not depend on
it — it's decided in code, same as everything else in the current line
config.

Lines are lang keys; translation and community packs are free.

> **v0.3 note:** the `cooldown` field from v0.2 is dropped. Cooldown existed
> to stop the same ambient line repeating on every passing tick; with no
> passive trigger, a context is only evaluated once per dialog-open, so
> per-context cooldown has no job left. (Per-*villager*-per-*day* limits on
> things like the gift reaction and the solicited-talk pool, §6, are a
> separate concern and still apply.)

### 4.2 Contexts to evaluate at dialog-open

**About you** — first meeting; first meeting after an absence (3/7/30-day
buckets); on fire, drowning, poisoned, badly hurt, freezing; soaked; carrying
something remarkable; armour changed since last time; holding their liked or
disliked item; just slept, or very much hasn't; died recently.

**About the world** — rain, thunder, snow, first clear morning after a storm;
dawn, midday, dusk, deep night; full and new moon; biome flavour; a recent
raid; a villager died nearby; a zombie is close. (Hearsay evaluates the same
category of world state independently, for its own passive ambient lines —
the two mods reading the same weather flag isn't a collision, since only
Small Talk's read is player-triggered and only Hearsay's is a spawn.)

**About them** — working hours vs. idle hours; just restocked; can't reach
their workstation; heading to bed; their bed was broken.

**About you and them** — familiarity tier transitions; the gift you gave
yesterday; they remember your name.

### 4.3 Selection

At dialog-open: gather all passing contexts for this villager–player pair →
weighted random, **biased hard toward specificity**. A remark about your
burning trousers always beats one about the weather. If nothing specific
applies they say something generic, which is fine — real small talk is mostly
weather. This becomes the greeting line inside the opened Dialog (§5).

---

## 5. Delivery

**Vanilla Dialog screen** (`minecraft:notice` for a plain greeting,
`minecraft:multi_action` when a request is being offered, §12.2). Opened on
sneak-right-click (§6.1) — never spawned unprompted.

No entity is created, so there is no cleanup story: no orphan sweep, no
dimension-change/unload/death handling, no marker tag. That whole surface
(v0.2 §5) simply doesn't exist in this mod anymore — it's Hearsay's territory
now, for its own passive bubbles.

Selection (§4.3) runs exactly once, at the moment the dialog opens, against
whichever contexts are true right then. No ticking loop, no per-villager
cooldown timer, no chatterRadius, no global rate limit — none of that
machinery is needed when the player is the one initiating.

---

## 6. Interaction verbs — the Rehome contract

| Gesture | No home | Has home |
|---|---|---|
| right-click, empty hand | **vanilla trade** | **Small Talk** — dialogue menu (Gift and Trade buttons inside) |
| right-click, any item | **Rehome** — befriend, if the item is one of Rehome's configured gifts; otherwise vanilla trade | **vanilla trade** |
| sneak + right-click, empty hand | **Rehome** if following, else vanilla | **Small Talk** — fast-path straight to vanilla trade |

As of v0.5, **no gesture on a resident depends on what's in the player's
hand.** Small Talk's gift verb moved entirely inside the dialogue menu
(§6.2) — right-clicking a resident while holding any item, gift-shaped or
not, just falls through to vanilla trade exactly like an empty-hand
right-click on a stranger does. Only Rehome still reads hand items, and
only for villagers with no home.

Disjoint on the home condition, so neither mod needs to know the other
exists. Both return `PASS` when unmatched. Trade offers are never read or
written — but as of v0.3, trading is no longer *zero-clicks* for residents;
see §0.1 and §18.7.

The sneak-click fast path exists so trade-focused play isn't forced through
the menu every single time: sneak+right-click, empty hand, on a resident
skips straight to vanilla trade, same as a bare right-click always did in
v0.2. Gate it behind `sneakSkipsDialogue` (default `true`) in case playtesting
says the two gestures being this different from strangers vs. residents is
confusing rather than convenient.

> As noted in §1: Rehome's current `GiftHandler` doesn't actually check the
> home condition (it checks `follower == null`), and its sneak-right-click
> row isn't implemented at all — a code comment in `GiftHandler.java` says
> that gesture was replaced by "emerald on a following villager" as the
> stay/resume toggle. Small Talk's sneak+right-click+empty-hand is
> uncontested today, but by accident of Rehome not using that gesture, not
   because Rehome checks `HOME`. Worth reconciling before both mods ship.

**Requests reuse the talk verb** — no new gesture. A resident with something
to ask opens a `multi_action` dialog instead of the ambient-greeting `notice`
dialog on sneak-right-click (§12.2).

### 6.1 Talking

Right-click a resident, empty hand. A vanilla `multi_action` Dialog opens;
its body is the highest-weighted passing context from §4.3 (falling back to
something from the `solicited` pool — longer, more personal — if nothing
situational applies). Two buttons live inside: **Gift** (§6.2) and
**Trade**, which opens the vanilla trade screen; the dialog otherwise just
dismisses. Both run through this mod's own command tree
(`/smalltalk trade`, `/smalltalk gift`) rather than the vanilla
`custom_click_action` type SPEC.md's early drafts named — see
`interaction.DialogScreens` for why. Once per villager per day, opening this
dialog counts toward familiarity — repeatedly opening and closing it doesn't
farm familiarity past that daily cap.

Sneak+right-click, empty hand, skips the dialogue and opens vanilla trade
directly (§6, `sneakSkipsDialogue`).

### 6.2 Gifting — through the dialogue, never a hand item (v0.5)

**There is no hand-item interaction for gifting at all.** A resident with
an axe-wielding woodcutter walking past won't ever have that axe taken —
nothing reacts to what's in the player's hand on a bare right-click except
"is it empty" (§6). Gifting is entirely inside the talk-verb dialogue:

1. **Talk** to a resident (§6.1); the dialogue has a **Gift** button.
2. Clicking it opens a **gift picker** — a `DialogListDialog` listing one
   entry per distinct item currently in the player's inventory that's in a
   configured gift category (§14's `itemCategories`, restored from v0.3;
   see §0). If nothing in the player's inventory qualifies, the dialog says
   so instead of listing anything. This whole list is built and sent in one
   packet when the Gift button is clicked — no further server round trip
   happens just from browsing it.
3. Picking an entry opens a **confirmation dialog** for that specific item
   — an `ItemBody` showing the actual item and count, "Give ___ to ___?",
   **Give** / **Cancel**. Navigating from the picker into this confirmation
   is purely client-side; the whole list of possible confirmations was
   already sent in step 2.
4. **Give** runs `/smalltalk gift-confirm <villager> <item>`, which
   re-verifies everything before touching anything — the item might have
   left the player's inventory since the picker opened, the once-per-day
   cooldown might have lapsed in the meantime, or the config might have
   changed (SPEC.md section 12.2's "the payload is untrusted" discipline,
   applied here too, not just to future requests). Only then is the item
   shrunk by one and familiarity updated.

**Reaction, from the villager's derived quirks (§2):**

| Reaction | Familiarity | Feedback |
|---|---|---|
| **loved** (item matches their liked category) | +8 | hearts, `entity.villager.celebrate` |
| **neutral** (any other configured category) | +1 | `entity.villager.ambient` |
| **disliked** (item matches their disliked category) | −2 | `entity.villager.no`, smoke |

One gift per villager per day (`giftCooldownTicks`), same as v0.3. The item
is consumed on confirm regardless of reaction — you *gave* it to them.
Feedback is particles/sound only, no further dialog — §18.6's old open
question about reaction-line timing is moot with no reaction line at all.

---

## 7. Familiarity

0–100 per villager per player, stored as an attachment on the villager.

| Tier | Range | Effect |
|---|---|---|
| Stranger | 0–19 | short, formal; Bashful barely speaks |
| Acquaintance | 20–49 | uses your name; **may ask small favours** |
| Friend | 50–79 | longer lines, references past gifts; **deliveries and fetch requests** |
| Dear friend | 80–100 | nicknames you, occasional return gifts; **visits and invitations** |

**Familiarity never touches prices.** The register changing *is* the reward —
that's how Animal Crossing works, and it's why its villagers feel like people
rather than vending machines with a progress bar.

**Storage bound:** cap at the 8 most recently interacted players, evict
oldest. Uncapped maps on every villager on a 200-player server is a real
save-size problem.

**Decay:** −1 per in-game week of no interaction, floored at the bottom of
the current tier.

---

## 8. Memory

Up to three recent events per villager–player pair: a tag plus a timestamp —
`gave_gift:cake`, `saw_you_die`, `you_were_away:12d`,
`survived_raid_together`, and later `completed_request:delivery`.

Memories are context conditions like any other. A small feature doing a
disproportionate amount of the work of feeling known.

---

## 9. The acquaintance graph — derived

Two residents are **acquainted** if their beds are within
`acquaintanceRadius` (48) and pathable-ish, or share a village subchunk
cluster.

Derived on demand, never stored. Same trick as identity: no graph to
maintain, works instantly on any world, no migration, and it survives someone
bulldozing half a village.

Unused in the shippable core. It exists so deliveries (§12.3) have someone to
deliver *to*, and so a resident can mention the neighbour they don't get
along with.

---

## 10. `Errand` — reserved interface

```java
interface Errand {
    BlockPos target();
    int timeoutTicks();
    void onArrive(VillagerEntity v, ServerWorld w);
    void onAbandon(VillagerEntity v, AbandonReason r);
}
```

A temporary brain override: walk somewhere, do something, hand back to
vanilla. Structurally identical to Rehome's follow behaviour — the mods
duplicate it rather than share it, since both must stand alone, but keeping
the shape aligned means fixes port across.

Not implemented in the core release. Defined now so visits, deliveries, and
"come see this" are a filled-in slot rather than a redesign.

**Rules for any errand, once built:** never leave the resident stranded
(timeout always returns them home), never override during a raid or at
night, and never let an errand outlive a server restart — abandon on load.

---

## 11. Home awareness — read-only

A resident's **house** is the room enclosing their bed, discovered by a
bounded flood fill (≤512 blocks) from the bed.

This is the room-validation code from Rehome's early drafts, resurrected with
the crucial difference that **it gates nothing**. It only describes: how big
the room is, whether there's a window, what's on the walls, whether there's a
lamp by the door.

Unused in the core release. It's what lets a resident later ask for a lamp,
comment on your redecorating, or have a doorstep for you to knock on.

---

## 12. Requests — Fetch only, daily cadence, next up

The Animal Crossing loop this mod is ultimately for, and the piece that
actually creates a **daily-return** reason (the goal §0/header calls out).
v0.6 deliberately narrows this from "six task types, roughly in build order"
to **one task type, shipped completely, with a cadence gate** — the smallest
change that makes "come back tomorrow" true. Delivery/Quarry/Décor/Visit/
Games move to §19's backlog until Fetch has real playtest data.

### 12.1 The task registry (shipped, empty)

Already in the tree — `task/Task.java`, `task/TaskRegistry.java`,
`task/TaskState.java`. A world-level `PersistentState`. This is the one
place Small Talk keeps state that isn't attached to a villager, because a
delivery belongs to neither the sender nor the recipient.

```
Task {
  UUID     taskId
  UUID     issuer          // villager
  UUID     assignee        // player
  TaskType type
  NbtCompound payload      // item, target villager, block pos, quarry species
  long     issuedTick
  long     expiresTick
  State    state           // OFFERED | ACCEPTED | COMPLETE | EXPIRED | ABANDONED
}
```

**Introduce the empty registry in the core release** even with no task types.
It's a few dozen lines, and it means the first request type is a feature
rather than an architectural change.

Bounds: one active request per resident, `maxActiveTasks` (3) per player.
Expiry in in-game days, generous. Expired tasks cost nothing — a resident who
asked for a salmon and never got one is mildly disappointed, not offended.

### 12.2 Offering and answering

A resident with an `OFFERED` task shows it on right-click as a `multi_action`
dialog — in place of the ambient-context greeting body, never alongside it —
with what they want, why, and three buttons: **Accept**, **Not now**, and
**Trade** (so a pending request never blocks the trade fast path a bare
right-click used to guarantee). Buttons route through this mod's own
command tree, same pattern as §6.1's Gift/Trade buttons.

The payload is untrusted — re-verify on receipt that the task exists, is
`OFFERED`, belongs to this player, and hasn't expired. Same discipline as
§6.2's gift-confirm re-verify.

**Pending-request tell (resolves old open question §18.5).** A resident with
an `OFFERED` task gets a small ambient signal so the daily loop isn't
invisible: a short-lived particle effect (villager happy-villager-style, at
low frequency) while the request is outstanding. No new entity, no
`TextDisplay` — a particle packet sent when nearby players tick into range,
piggybacking on vanilla's existing villager-visible-to-client check. This is
the one place v0.6 adds anything resembling passive signaling, and it's
scoped tightly: presence-only, no text, no sound, never fires for a resident
with no offer.

### 12.3 Daily cadence gate (new in v0.6 — the actual retention mechanic)

Each resident can issue **at most one Fetch offer per in-game day**
(`requestCooldownTicks`, default one day, mirrors `giftCooldownTicks`'s
existing pattern in `FamiliarityAttachment`). A resident only *rolls* for a
new offer if:

- they have no active (`OFFERED`/`ACCEPTED`) task with this player,
- the player is under `maxActiveTasks` (3) globally,
- the per-resident cooldown has elapsed,
- `requestsEnabled` is true.

The roll happens lazily, on the same trigger as everything else in this
mod — talking to them (§6.1) — never on a tick. If they roll a request,
that's what shows on the *next* talk; if not, it's the ordinary
ambient-context greeting. This means the cadence is felt as "did they get
something to ask today?" rather than a visible countdown, which is
deliberately unguessable/unfarmable — same anti-grind stance as §12.6.

### 12.4 Fetch — the only task type in this build

"Bring me a cooked salmon." An item predicate and a count, nothing else.
No acquaintance graph, no home awareness, no `Errand`. This is the entire
task-type surface for v0.6.

**Implementation plan:**

1. `task/TaskType.java` — enum, single member `FETCH` (others added only
   when actually built, per Task.java's existing javadoc rationale for why
   `type` is a string, not an enum, today).
2. `task/FetchTaskLogic.java` — builds a `Task` with `type=FETCH` and a
   payload of `{item: Identifier, count: int}`; validates a player's
   inventory against that payload on a completion check.
3. `task/RequestOfferer.java` — implements §12.3's roll: called from
   `TalkHandler` before the ambient-context greeting is computed; owns the
   per-resident cooldown read/write via `TaskRegistry`.
4. `task/RequestDialogs.java` — builds the `multi_action` Accept/Not now/
   Trade dialog (§12.2) and the completion dialog (hand in the items,
   confirm, receive reward). Same picker→confirm→re-verify discipline as
   `GiftHandler` (§6.2) — completion re-checks the player still has the
   items at confirm time, not just at dialog-open time.
5. Wire `SmallTalkCommands` with `/smalltalk request-accept`,
   `/smalltalk request-decline`, `/smalltalk request-complete`.
6. Config: `requestsEnabled` flips from `false` to `true` only once this
   is playtested; `requestCooldownTicks` new key (§17).

**Reward on completion:** familiarity (see §12.6) plus a modest item drawn
from a profession-keyed pool — no new loot table system needed for a single
task type; a static per-profession list in `FetchTaskLogic` is enough.

### 12.5 Personality bias — deferred with the rest of the task-type variety

§0's old draft had personality bias over *which task type* a resident
prefers. With only Fetch shipping, that axis of variety doesn't exist yet.
What's cheap and worth keeping for v0.6: personality still colors the
*offer/accept/decline lines* (Gruff terse, Chipper effusive, Bashful
apologetic) by reusing the existing personality → line-pool mechanism
(§4.1) with new context IDs (`fetch_offer`, `fetch_thanks`) — no new system,
just new entries in `lines.json` per personality. Task-type-level bias
returns once a second task type (§19 backlog) exists to weight against.

### 12.6 Reward policy

**Familiarity is the primary reward.** Items are secondary, modest, and drawn
from profession-keyed loot tables. Explicitly:

- never emeralds or anything trade-adjacent
- never scaling with task difficulty in a way that rewards grinding
- never repeatable fast enough to farm

The moment a request loop out-earns a villager trade hall, this becomes an
economy mod and the whole design premise collapses.

---

## 13. Birthdays — derived, zero-storage (new in v0.6)

Reuses §2's exact trick: hash the villager's UUID into a day-of-year, once,
forever, nothing written to disk. On that day: one special greeting line
(new context ID `birthday`, always outranks other contexts per §4.3's
specificity bias) and gift reactions doubled (loved +16, neutral +2,
disliked −4 for that day only). No new UI, no reminder system, no player
calendar — the player either notices ("oh, happy birthday!") or doesn't,
same as any other context.

**Why this is worth building:** highest attachment-per-line-of-code ratio
of anything in this spec. It's the single Animal Crossing feature players
reliably remember, and it costs one derivation function plus one context.

**Implementation plan:**

1. `identity/Birthday.java` — `dayOfYear(UUID)`, same hash-derivation
   pattern as `IdentityDeriver`, capped to `[1, 365]` (leap day never
   assigned, sidesteps the once-every-four-years edge case entirely).
2. `ContextEvaluator` gets a `birthday` context: true when
   `Birthday.dayOfYear(villager.getUuid()) == currentDayOfYear(world)`.
3. `GiftHandler` reads the same check to double the §6.2 reaction table for
   that villager on that day only — no new state, just a multiplier at
   reaction-computation time.
4. `lines.json` gets a `birthday` entry per personality.

**Non-goals:** no player-visible "upcoming birthdays" list (that's the
journal-item question from old §18.3, still open, still not needed for
this); no server-wide announcement; no special item reward beyond the
existing gift-reaction table.

---

## 14. Shared-knowledge lines — new in v0.6

Villagers occasionally mention a *different* player by name, drawn from
their own memory log (§8) — "Jay brought me a cake yesterday," "I haven't
seen Alex in weeks." Directly serves the new **social proof** goal (§0
header): on a shared server, hearing a villager talk about someone else's
relationship with them is what makes the village feel like a real, shared
place rather than N independent single-player save states.

**Uses data already stored — no new persistence.** §8's memory log already
records tagged events per villager-per-player; this just reads across the
per-player map that `FamiliarityAttachment` already keys on, instead of
only reading the current player's row.

**Implementation plan:**

1. `social/SharedKnowledge.java` — given a villager and the *current*
   player, picks a candidate memory belonging to a *different* tracked
   player (from `FamiliarityAttachment.allOf`), subject to a floor
   familiarity with that other player (don't surface a stranger's name) and
   a recency window (don't reference something from months ago).
2. New context IDs, e.g. `mentions_other_player_gift`,
   `mentions_other_player_absence`, fed into the same weighted-specificity
   selection as every other context (§4.3) — competes on equal footing, not
   guaranteed to win.
3. Lines need a name-substitution placeholder (the other player's name),
   same mechanism `DialogueLinesConfig`/line-selection already needs for
   the *current* player's name (§7's "uses your name" at Acquaintance+).
4. **Privacy consideration:** only surfaces player *names*, never gift
   contents or exact numbers — "Jay was here recently," not "Jay gave me 4
   emeralds." Keep it flavor, not a leaderboard.

**Non-goals:** no cross-player notifications, no "X and Y are both friends
with Petra" social graph UI — this is a dialogue-flavor feature, not a new
system.

---

## 15. Cross-mod and world correctness — promoted to core (v0.6)

Two items were "open questions" in v0.5 (old §18.1, §18.4) that are
directly attachment-*destroying* if left alone, not merely nice-to-haves.
v0.6 promotes both to planned work.

### 15.1 Rehome's `HOME` check (was §18.4)

§1 already flags this: Rehome's `GiftHandler` gates on `follower == null`,
not on `MemoryModuleType.HOME`, so a resident who's never been gifted-to can
still be intercepted by Rehome's befriend flow instead of reaching Small
Talk's gift verb. This is a **different mod's file** to patch
(`Rehome`'s `GiftHandler.java`, not this repo) — Small Talk's own action
item is to add an explicit `home == null` guard check as a defensive
assertion in its own `GiftHandler`/`InteractionHandler` so the two mods'
behavior is verifiably disjoint from Small Talk's side even if Rehome is
never patched, plus a regression test (§18) asserting a fresh resident's
gift never reaches Rehome's command path in an integration environment
with both mods loaded.

### 15.2 Zombie-cure identity persistence (was §18.1)

A villager's UUID changes on zombie conversion and curing, so `Identity`
(derived from UUID, §2) and `FamiliarityAttachment` (keyed on the villager
entity) are both lost across a cure — a Dear Friend reverts to a total
stranger. Promoted to core because this is a direct, silent attachment
regression a player will notice and dislike.

**Implementation plan:**

1. `mixin/VillagerConversionMixin.java` — hooks the vanilla zombie-villager
   conversion completion point (the method that constructs the new
   `Villager` from the `ZombieVillager`); copies the `FAMILIARITY`
   attachment (§7) from old entity to new via
   `FamiliarityAttachment`'s existing map-based storage, and forces the new
   entity's derived `Identity` name to stay pinned by tagging it with a
   persisted "identity override" NBT key that `IdentityDeriver` checks
   before re-hashing — since the UUID changed, the *hash* would otherwise
   produce a different name/personality/quirks, breaking continuity even
   with familiarity carried over.
2. Requires wiring: `smalltalk.mixins.json` (new resource) +
   `"mixins"` entry in `fabric.mod.json` (neither exists yet — see §18's
   file hierarchy for where these land).
3. Test: name-tag a villager, raise familiarity to Friend, zombify and cure
   it, confirm both the name and the familiarity score survive.

---

## 16. Performance

v0.2 had a whole section here about forty villagers running condition checks
every tick. That failure mode doesn't exist anymore — nothing evaluates
unless a player sneak-right-clicks a resident, so there is no tick loop, no
`chatterRadius`, no `maxActiveDisplays`, no per-tick cost at all.

What's left:

- Identity derivation is pure, cached in a small LRU (repeated lookups —
  `/smalltalk whois`, dialog opens — shouldn't re-hash).
- Home awareness scans (§11, once built) are cached per bed position and
  invalidated lazily, never per-tick — this one still matters once décor
  requests exist, since a flood fill is real work.

---

## 17. Config

**Shipped today** (`SmallTalkConfig.java`, `config/smalltalk.json`):

```json
{
  "giftCooldownTicks": 24000,
  "familiarityDecayPerWeek": 1,
  "maxTrackedPlayersPerVillager": 8,
  "acquaintanceRadius": 48,
  "returnGifts": true,
  "sneakSkipsDialogue": true,
  "itemCategories": { "...": ["see SPEC.md section 2 and the code default -- eight categories, FOOD through MUSIC"] },

  "requestsEnabled": false,
  "maxActiveTasks": 3
}
```

Dropped from v0.2: `chatterRadius`, `chatterCooldownTicks`,
`globalChatterRateTicks`, `displayLifetimeTicks`, `maxActiveDisplays`,
`chatFallback` — all were ambient-delivery tuning knobs for a system that no
longer exists in this mod.

v0.4 briefly dropped `itemCategories` and added `tameChance` /
`postTameGiftAward` for the emerald-taming mechanic that turned out to be
the wrong mod's fix (§0). v0.5 reverted both: `itemCategories` is back,
`tameChance` and `postTameGiftAward` are gone. `TOOLS` is back in the
default category list too, since gifting no longer reacts to hand items at
all (§6.2) — there's nothing left for it to accidentally consume.

**New keys for v0.6, to add alongside the above when §12/§13 are built:**

```json
{
  "requestCooldownTicks": 24000,
  "birthdaysEnabled": true,
  "sharedKnowledgeEnabled": true
}
```

`requestsEnabled` (already present, currently `false`) is the master switch
for all of §12; `requestCooldownTicks` only matters once it's `true`.
`birthdaysEnabled` and `sharedKnowledgeEnabled` default on since both are
low-risk, pure-flavor additions with no economy implications — server owners
who dislike the flavor can flip them off individually rather than needing
`requestsEnabled`'s more cautious default-off treatment.

---

## 18. Package layout and file hierarchy

**Shipped** (verified against the current tree):

```
smalltalk/
├── SmallTalkMod.java
├── SmallTalkConfig.java
├── SmallTalkCommands.java
├── identity/
│   ├── Identity.java
│   ├── IdentityDeriver.java       // frozen hash -- treat as API
│   ├── ItemCategory.java
│   └── Personality.java
├── dialogue/
│   ├── ContextEvaluator.java
│   ├── DialogueContext.java
│   ├── DialogueLinesConfig.java   // config/smalltalk/lines.json -- see §4.1
│   ├── HintLinesConfig.java
│   ├── HintSelector.java
│   ├── LineSelector.java
│   └── Situation.java
├── social/
│   ├── FamiliarityAttachment.java
│   ├── FamiliarityEntry.java
│   ├── FamiliarityTier.java
│   ├── Feedback.java
│   ├── GiftCategories.java
│   ├── GiftReaction.java
│   └── MemoryEntry.java
├── interaction/
│   ├── DialogHold.java
│   ├── DialogScreens.java
│   ├── GiftHandler.java
│   ├── InteractionHandler.java
│   └── TalkHandler.java
├── task/
│   ├── Task.java
│   ├── TaskRegistry.java          // PersistentState, empty at launch
│   └── TaskState.java
└── errand/
    └── Errand.java                // interface only, for now
```

**New for v0.6 — where subsequent work belongs.** These are the files this
revision expects to be created; each one's stub should cite the SPEC
section it implements, same convention as the existing `Task.java`/
`Errand.java` javadoc:

```
smalltalk/
├── identity/
│   └── Birthday.java              // §13 -- dayOfYear(UUID) derivation
├── social/
│   └── SharedKnowledge.java       // §14 -- cross-player memory surfacing
├── task/
│   ├── TaskType.java              // §12.4 -- enum, FETCH only for now
│   ├── FetchTaskLogic.java        // §12.4 -- build/validate/complete Fetch
│   ├── RequestOfferer.java        // §12.3 -- daily cadence roll
│   └── RequestDialogs.java        // §12.2/§12.4 -- offer/accept/decline/complete dialogs
└── mixin/
    └── VillagerConversionMixin.java  // §15.2 -- zombie-cure identity/familiarity carry-over
```

**Resources — new infrastructure required for the mixin (§15.2):**

```
src/main/resources/
├── fabric.mod.json         // needs a new "mixins": ["smalltalk.mixins.json"] entry
└── smalltalk.mixins.json   // new -- standard Fabric mixin config, references mixin/VillagerConversionMixin
```

Nothing else in the resource tree changes — dialogue lines stay in
`config/smalltalk/lines.json` (§4.1), not a `data/` datapack, until/unless
that migration is separately decided.

**Do not create** (already-rejected surfaces, per §0/§15's history — a
future agent re-adding these would be reintroducing removed complexity):
`display/`, `ambient/ChatterTicker.java`, any per-tick villager scan, any
hand-item-triggered interaction path.

---

## 19. Roadmap / build order

**Core release — shipped, do not re-build:**

1. Identity derivation + `/smalltalk whois`.
2. Vanilla Dialog plumbing for the talk verb (menu + Trade button, sneak
   fast path).
3. Context engine (weather/time/greeting, then expanded).
4. `config/smalltalk/lines.json` line pool per personality/context.
5. Gift verb, familiarity, tiers.
6. Memories and return gifts.
7. Empty `TaskRegistry` and the `Errand` interface, shipped unused.

**Next — the v0.6 daily-loop plan, in dependency order:**

8. **§15.2 Zombie-cure identity persistence.** Build first, ahead of new
   player-facing features: it's a correctness fix for existing systems
   (identity, familiarity), and every feature added after it should not
   have to account for a known data-loss bug in what it's built on.
9. **§13 Birthdays.** Cheapest new feature, no dependencies on anything
   else in this list, immediately testable.
10. **§14 Shared-knowledge lines.** No dependencies; uses existing memory
    log storage.
11. **§12 Fetch requests, with the daily cadence gate and pending-request
    tell**, behind `requestsEnabled`. The one feature in this list with
    real design risk — needs its own playtest before wider release, same
    as old §16 flagged for the whole requests subsystem ("treat it as its
    own project with its own playtest").
12. **§15.1 Rehome `HOME` guard.** Defensive check on Small Talk's side;
    coordinate timing with whoever owns the Rehome repo for the upstream
    fix.

**Backlog — revisit only after step 11 has real playtest data:**

- Delivery (needs the acquaintance graph, §9).
- Décor (needs home awareness, §11).
- Quarry.
- Visit (needs `Errand`, §10).
- Games.
- Personality bias over task *type* (§12.5) — meaningless until a second
  task type exists.
- Datapack migration for dialogue lines (§4.1's original schema).
- Request journal item (old §18.3).
- Biome/profession-weighted name pools (old §18.2) — worth deciding before
  any of the above ships, since name pools are frozen/additive-only once
  live (§2).

Core steps 1–3 are the mod's soul. Step 11 (Fetch requests) is the second
mod hiding inside this one — treat it as its own project with its own
playtest, same caution the old build order flagged.

---

## 20. Test checklist

- Bare right-click on a villager **with no home** opens trades, unchanged.
- Bare right-click on a **resident** opens the dialogue menu, not trade
  directly; the menu's **Trade** button opens the normal trade screen.
- Sneak+right-click on a resident skips the menu and opens trade directly
  (with `sneakSkipsDialogue` at its default `true`).
- Same villager, same name and personality across a restart.
- Two villagers in one village have different personalities.
- Name-tagged villager uses the tag name.
- Right-clicking a resident never fires unprompted — walking past ten
  residents in a row produces zero dialogs until you actually click one.
- Set yourself on fire, then talk to a nearby resident → the dialog's
  greeting is about the fire, specifically, not generic.
- A resident with an `OFFERED` task still lets you reach Trade from the same
  dialog, without accepting or dismissing the request first.
- Right-click a resident while holding any item, gift-shaped or not (an
  axe, a sword, a stack of dirt) → nothing is consumed, nothing special
  happens; same as an empty-hand click would if it weren't for the item in
  hand blocking it from opening the dialogue (§6, §6.2's whole point).
- Open the dialogue's Gift button with nothing giftable in your inventory →
  the picker says so instead of showing an empty list.
- Gift picker lists one entry per distinct giftable item type, not one per
  stack — carrying two separate stacks of the same item shows one entry.
- Pick an item, back out with Cancel on the confirmation → nothing is
  consumed, no familiarity change.
- Confirm a gift, then immediately drop or move the item before another
  gift attempt lands on a stale confirmation → the re-verify in
  `/smalltalk gift-confirm` catches it ("you don't have that to give
  anymore"), nothing is silently consumed.
- Gift a disliked item → familiarity drops, tone shifts on next talk.
- Dear Friend → nicknames and a return gift.
- Alongside Rehome: befriending a homeless villager works; gifting a
  resident doesn't trigger Rehome. (Contingent on the §1/§6 Rehome
  `HOME`-check fix landing — currently not guaranteed.)
- Alongside Hearsay: both installed, walk through a village — Hearsay's
  ambient bubbles fire on their own triggers, Small Talk produces nothing
  until you right-click a resident. No double-greeting, no shared entity,
  no shared tag.
- Uninstall → world loads clean. (No leftover entities to check for — this
  mod never spawns any.)
- A datapack written against the v0.2/v0.3 schema still loads after requests
  ship.

**New for v0.6:**

- Zombify and cure a Dear Friend → name, personality, quirks, and
  familiarity score all survive the UUID change (§15.2).
- On a villager's derived birthday, talk to them → the greeting is the
  `birthday` context, outranking weather/time; gift reactions are doubled
  that day only, back to normal the next (§13).
- A villager mentions a different tracked player's name only when that
  player has sufficient familiarity with them and a recent-enough memory —
  never a stranger's name, never stale data (§14).
- A resident with an `OFFERED` Fetch task shows the pending-request
  particle tell before being talked to, and it disappears once the task
  leaves `OFFERED` (§12.2).
- Talking to a resident twice in the same in-game day never rolls two Fetch
  offers — the cadence gate (§12.3) is per day, not per talk.
- Completing a Fetch task re-verifies the player still has the items at
  confirm time, same as the gift-confirm discipline (§12.4).
- Alongside Rehome, with the §15.1 guard added: a resident's gift attempt
  never reaches Rehome's command path, verified with both mods loaded.

---

## 21. Open questions

Resolved in v0.6 (kept here for history, not because they're still open):

- ~~**Do zombie villagers remember you after curing?**~~ Promoted to core
  work, §15.2.
- ~~**Out-of-dialog signal for a pending request.**~~ Resolved: a particle
  tell, scoped tightly (§12.2).
- ~~**Gift reaction timing.**~~ Resolved by v0.4's rewrite (§0, §6.2):
  particles-and-sound only, no dialog involved.

Still open:

1. **Name pools and culture.** A flat English-ish pool will feel odd fast.
   Biome- or profession-weighted pools are better — decide the structure
   before any of §19's backlog ships, because frozen data (§2) can't be
   reshuffled after the fact.
2. **Do requests need a journal?** Deferred along with the rest of the
   multi-task-type backlog (§19) — with only Fetch and a 3-task cap, this
   isn't load-bearing yet, but revisit once Delivery/Décor add task variety.
3. **Rehome's home check.** §15.1 gives Small Talk a defensive-side fix;
   whether to also patch Rehome's `GiftHandler` directly is a decision for
   whoever owns that repo, not resolvable from this spec alone.
4. **Trading is no longer zero-clicks for residents.** v0.2's explicit design
   principle was "trading is never obstructed." v0.3's plain-right-click-opens-menu
   change (§0.1, §6) breaks that for residents specifically — a player who
   just wants to trade now goes through a dialogue screen unless they
   remember to sneak-click instead. `sneakSkipsDialogue` is the mitigation,
   but it means the *fast* path and the *default* path are now different
   gestures, which is exactly the kind of thing that feels obvious once you
   know it and baffling before that. Needs a real playtest: does the first
   hour of using this mod involve a moment of "wait, how do I just trade with
   this guy"? If so, worth considering flipping the default so plain
   right-click stays trade and *sneak*-right-click opens the menu instead —
   the opposite of what's specified here — trading it against how often
   players will actually want the menu versus trade.
5. **Fetch cadence tuning (new).** Is once-per-resident-per-day the right
   grain, or should it be world-wide-per-player (so three residents can't
   all offer on the same day and blow through `maxActiveTasks`)? §12.3
   specifies per-resident; needs playtest data before v0.6's numbers are
   treated as final.
