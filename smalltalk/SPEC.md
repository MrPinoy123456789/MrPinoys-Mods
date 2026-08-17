# Small Talk — Design Spec v0.5

> **Status:** core release implemented, 2026-08-16 (identity, dialogue
> engine, familiarity, gift/talk verbs, empty task registry). Requests
> (§12) and everything after them in the build order (§16) are not.
>
> Server-side-only Fabric mod. **Minimum Minecraft 26.2** (matches the rest of
> the suite's `gradle.properties`; earlier drafts said "1.21.6," which was the
> version-numbering scheme in effect when the vanilla-dialog dependency was
> first identified — see §0). **Standalone** — no dependency on Rehome,
> Hearsay, or anything else.

*Villagers have names, personalities, something to say about today — and
things they'd like you to do.*

---

## 0. What changed from v0.1 → v0.2 → v0.3 → v0.4 → v0.5

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

### 4.1 Schema

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

**`offers` is reserved now and unused until §12.** It will name a request
template. Reserving it means community line packs written against v0.2/v0.3
stay valid when requests land — datapack schemas are public API the moment
anyone writes against them.

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

## 12. Requests — provisioned, not built

The Animal Crossing loop this mod is ultimately for. **Not in the first
release.** Everything below is design intent plus the seams that must exist
beforehand.

### 12.1 The task registry

A world-level `PersistentState`. This is the one place Small Talk keeps state
that isn't attached to a villager, because a delivery belongs to neither the
sender nor the recipient.

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
right-click used to guarantee). Buttons route through `custom_click_action`,
which needs no permissions and no client mod.

The payload is untrusted — re-verify on receipt that the task exists, is
`OFFERED`, belongs to this player, and hasn't expired. Same discipline as
Rehome v0.2's nonces.

There is no ambient hint anymore (v0.2 had chatter hint at a pending offer
via the reserved `offers` field before you talked to them) — since nothing
fires until you sneak-right-click, the `offers` field's job shrinks to
telling the talk-verb handler which template to render *when the dialog
already opened for an unrelated reason*. Whether a resident needs some
other out-of-dialog signal that they have something to ask (a particle? a
head-turn?) is open — see §18.5.

### 12.3 Task types, roughly in build order

**Fetch** — "bring me a cooked salmon." Simplest possible: an item predicate
and a count. No new systems beyond the registry. Build this one first; it
validates the whole loop.

**Delivery** — "take this to Petra." Uses the acquaintance graph (§9) for the
recipient. Hands you a marked item; delivering is a gift-verb interaction
with the named recipient. The first type that genuinely needs the
world-level registry.

**Quarry** — "I want to see a pufferfish." Fetch with a species predicate;
leans on fishing and bees, which is as close to AC's bug-catching as vanilla
gets.

**Décor** — "would you put a lantern by my door?" Uses home awareness (§11).
Completion is a block-presence check in their room. The most
Minecraft-native of the lot, because the reward for doing it is that their
house looks nicer forever.

**Visit** — "come see my place," or a resident turning up at yours. Needs
`Errand` (§10). The hardest and the best; leave it last.

**Games** — High/Low, hot-and-cold treasure hunts. Pure dialog interactions,
no world state. Cheap once dialogs exist, and good filler between the
heavier types.

### 12.4 Personality bias

Gruff residents ask for practical things and thank you badly. Chipper ones
ask constantly and celebrate. Bashful ones rarely ask and apologise when
they do. Smug ones ask for luxuries. Dreamy ones ask for something strange.
Brisk ones set the tightest deadlines.

This is nearly free — it's a weighting table over task types plus the
existing personality line files — and it's most of what makes requests feel
authored rather than generated.

### 12.5 Reward policy

**Familiarity is the primary reward.** Items are secondary, modest, and drawn
from profession-keyed loot tables. Explicitly:

- never emeralds or anything trade-adjacent
- never scaling with task difficulty in a way that rewards grinding
- never repeatable fast enough to farm

The moment a request loop out-earns a villager trade hall, this becomes an
economy mod and the whole design premise collapses.

---

## 13. Performance

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

## 14. Config

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

---

## 15. Package layout

```
com.yourname.smalltalk
├── SmallTalk.java
├── config/SmallTalkConfig.java
├── identity/
│   ├── Identity.java
│   ├── IdentityDeriver.java      // frozen hash — treat as API
│   └── Personality.java
├── dialogue/
│   ├── DialogueRegistry.java     // datapack reload listener
│   ├── DialogueEntry.java        // includes reserved `offers`
│   ├── ContextEvaluator.java
│   ├── LineSelector.java
│   └── DialogScreens.java        // builds notice/multi_action Dialog payloads
├── social/
│   ├── FamiliarityAttachment.java
│   ├── MemoryLog.java
│   ├── GiftHandler.java
│   ├── TalkHandler.java
│   └── AcquaintanceGraph.java    // derived, on demand
├── errand/Errand.java            // interface only, for now
└── task/
    ├── Task.java
    ├── TaskRegistry.java         // PersistentState, empty at launch
    └── TaskDialogs.java          // stub
```

`display/` (v0.2's `SpeechBubble.java` + `DisplaySweeper.java`) and
`ambient/ChatterTicker.java` are gone — there is no passive delivery path to
back, so there's nothing for them to do.

---

## 16. Build order

**Core release**

1. Identity derivation + `/smalltalk whois` debug command. Instant proof the
   trick works.
2. Vanilla Dialog plumbing for the talk verb: right-click a resident →
   `multi_action` dialog with a placeholder greeting and a working **Trade**
   button; sneak+right-click still opens vanilla trade directly. Proves the
   delivery mechanism and the trade fast path before any content exists.
3. Context engine with three contexts — weather, time, greeting — feeding
   that dialog's line. About a day's work, and the moment the villager stops
   being a placeholder.
4. Datapack registry and conditions. Move those three into data, add twenty
   more.
5. Gift verb, familiarity, tiers.
6. Memories and return gifts.
7. Empty `TaskRegistry` and the `Errand` interface. Ship them unused.

**Then**

8. Fetch requests end to end, behind `requestsEnabled`.
9. Home awareness → décor requests.
10. Acquaintance graph → deliveries.
11. Games.
12. `Errand` → visits.

Steps 1–3 are the mod's soul. Step 8 is the second mod hiding inside this
one — treat it as its own project with its own playtest.

---

## 17. Test checklist

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

---

## 18. Open questions

1. **Do zombie villagers remember you after curing?** UUID changes on
   conversion, so identity and familiarity are lost. Preserving it needs a
   small mixin. Lovely detail, definite scope creep.
2. **Name pools and culture.** A flat English-ish pool will feel odd fast.
   Biome- or profession-weighted pools are better — decide the structure
   before release, because frozen data can't be reshuffled.
3. **Do requests need a journal?** Three active tasks across three villagers
   in two villages is already more than anyone will remember. A
   written-book journal is the obvious answer and needs no client code, but
   it's an item, and this mod currently registers nothing.
4. **Rehome's home check.** §1/§6 flag that Rehome's `GiftHandler` doesn't
   actually gate on `MemoryModuleType.HOME`. Needs a decision: patch Rehome,
   or accept the accidental-disjointness and document it as load-bearing.
5. **Out-of-dialog signal for a pending request.** With ambient hinting
   gone (§12.2), does a resident with an `OFFERED` task need *any* passive
   tell (particle, head-turn, name color) so a player knows to go talk to
   them, or is "you'll find out when you talk to them" acceptable given the
   mod's whole premise is now "nothing happens until you initiate"?
6. ~~**Gift reaction timing.**~~ Resolved by v0.4's rewrite (§0, §6.2):
   taming is particles-and-sound only, styled on vanilla wolf taming, no
   dialog involved either way. There's no reaction *line* left to time.
7. **Trading is no longer zero-clicks for residents.** v0.2's explicit design
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
