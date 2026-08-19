# Mod Discovery via Advancements — Brainstorm & Status

Reconciles a design brainstorm against what is actually built in this repo as
of 2026-08-17. Read the **Implemented** section first — it corrects several
assumptions the original brainstorm made before this was built. Everything
below **Implemented** is still just an idea.

## Core concept (confirmed workable)

Use Minecraft's advancement system to teach players a server-side mod exists,
triggered on the action closest to that mod's function. The toast is the
hook. Server-side only, datapack-defined, no client mod needed — fits every
mod in this suite, all of which are `"environment": "server"`
(see DESIGN.md §2 and §4: no mod may depend on another, in Java or
`fabric.mod.json`; a `tellraw`/`run_command` CTA is fine since it's just a
command string, not a code coupling).

## Implemented (committed in `b69fdf2` -- see item 7 below; this heading is stale)

Four mods, each with a **two-step advancement chain**, granted from code
(not vanilla criteria) via a shared pattern:

| Mod | Step 1 (passive/early) | Step 2 (existing behavior) |
|---|---|---|
| spiritwolves | `tame_wolf` — tame any wolf | `bind_wolf` — bind it with a Spirit Stone |
| kamutotems | `receive_totem` — join, get your starter totem | `bind_kamu` — bind a loose Kamu into it |
| rehome | `greet_villager` — right-click any villager | `villager_settled` — a led villager settles in |
| cobbleeconomy | `open_shop` — open the shop (NPC click or `/shop`) | `first_purchase` — complete a purchase |

Step 2 is parented under step 1 (`"parent": "<modid>:<step1>"`) so they show
as a connected pair in the vanilla advancement tree, not siblings.

**Mechanics actually used, corrected against the original brainstorm:**

- **Trigger**: every criterion is `"trigger": "minecraft:impossible"`
  (never fires on its own) named `code_triggered`, granted manually via
  `player.getAdvancements().award(advancement, "code_triggered")` at the
  point in each mod's own code where the action already happens. Nothing
  uses a real vanilla criterion (`minecraft:tame_animal`,
  `minecraft:villager_trade`, etc.) yet — see Open Questions.
- **Display text uses `{"text": "..."}`, not `{"translate": "..."}`.**
  This was a real bug hit and fixed this session: all four mods are
  server-only, so a vanilla client never receives the mod's
  `assets/<modid>/lang/en_us.json` (that's client resource-pack content, not
  something a server data pack pushes). A `translate` key rendered as the
  raw key in chat (`advancement.spiritwolves.tame_wolf.title`) instead of
  text. Fixed by switching to literal `text` components and deleting the
  now-dead lang files. **Any future advancement in this suite must use
  `text`, never `translate`, unless the mod ships client assets.**
- **Frame**: step 1 = `"task"`, step 2 = `"goal"`. Nothing uses
  `"challenge"` yet. Note for later: the purple toast/sound is a `frame`
  property; `announce_to_chat` (already `true` on all of these) is what
  controls the server-wide chat broadcast, independently of frame — the two
  are separate knobs, don't conflate them.
- **No CTA, no books, no coupon, no backfill, no hidden children** — see
  below, these are the brainstorm's ideas, still unbuilt.
- Icons are plain vanilla item ids (`bone`, `totem_of_undying`, `emerald`,
  `red_bed`, `chest`) — advancement icons take a bare item id only, no
  components/NBT. This matters for the Spirit Stone specifically: it's a
  vanilla `minecraft:echo_shard` carrying `custom_data`
  (`SpiritStone.java:40`), so there is no way to give it a visually distinct
  icon short of a resource pack (which a server-only mod can't ship). The
  brainstorm's "icon: the soulstone item itself, not a generic bone" isn't
  achievable as stated — `bone` (the taming item) is arguably already the
  more honest choice for step 1 since the stone doesn't exist as a distinct
  sprite anyway.
- **spiritwolves has no vanilla "on tame" hook and no mixin infrastructure.**
  `tame_wolf` is granted by a new 30-tick poll (`TameWatcher.java`) checking
  each online player's nearby wolves for tame + ownership, matching the
  style of the mod's existing `Tracker.java` poll. This is a candidate for
  simplification — see Open Questions.
- All four mods rebuilt clean via `./gradlew build` (existing test suites
  still pass: kamutotems 131/131, cobbleeconomy 200/200), jars synced to
  `dist/`. **Committed since, in `b69fdf2` -- see item 7.**

## Reconciling the rest of the brainstorm against reality

- **Villager hook fires on any interaction, not "first trade."** The
  brainstorm's `minecraft:villager_trade` idea doesn't match rehome's actual
  mechanic — rehome is about *gifting* a villager to lead it home, not
  vanilla trading. `greet_villager` firing on any right-click (before the
  gift-item check) is the correct earliest touch for this mod specifically.
  A `minecraft:villager_trade`-triggered advancement could still be worth
  adding as a *separate* hook if the goal is specifically catching players
  mid-trade with emeralds in hand — but it would point at a different mod
  than what exists today, since no mod currently reacts to trading.
- **Progressive disclosure (`"hidden": true` on step 2) — not used.** Both
  steps are currently visible immediately, so players can see the full pair
  before completing step 1. Worth doing if the "don't spoil the tree" effect
  matters more than letting players preview what's coming.
- **Curiosity-gap titles — not used.** Current titles/descriptions are
  literal and explanatory ("A Loyal Friend" / "Tame a wolf — a Spirit Stone
  can bind its soul to you permanently"), not the punchier
  "They sell doge life insurance now?" style the brainstorm proposed. Worth
  an iteration pass if the plain titles underperform — brainstorm's own
  guidance to cap this at 2-3 mods still applies.
- **Shop CTA — not appended anywhere.** No advancement currently ends in a
  clickable `/shop` command. This is the single highest-value gap versus the
  brainstorm's stated actual goal ("get players to run `/shop` once").
- **Wayfarers flier/coupon — entirely unbuilt.** No book, no coupon,
  no redemption command, no per-player redemption state.
- **Backfill for existing players — not addressed.** Anyone who already
  tamed a wolf, opened the shop, etc. before this datapack existed will
  never see the reveal for that action again.

## Open questions / next steps — resolved 2026-08-17 (session 2)

Every "verified" claim below was checked by extracting the mapped jar
(`spiritwolves/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/`)
and reading it with `unzip` + `javap`, not from memory of real Minecraft. Note
two renames this suite already has that matter here: the criterion package is
`net.minecraft.advancements.triggers` (real MC: `...advancements.critereon`),
and predicates live in `net.minecraft.advancements.predicates`. Criterion
**ids** on the datapack side are unchanged from real MC, so the JSON below is
literal, not a guess.

### 1. Shop CTA — build it, but config-driven, and once per player

Verified buildable: `ClickEvent` is now a sealed interface of records, and
`new ClickEvent.RunCommand("/shop")` is the shape (record component `command`,
serialized as `"command"`, not the old `"value"`).

**The blocker nobody flagged: a literal `"/shop"` in spiritwolves violates
DESIGN.md §2.** "It may not know the name of any other mod" — a hardcoded
`/shop` is exactly that knowledge, just laundered through a string. And it
fails loudly: pull cobbleeconomy off the server and four other mods now show
every player a clickable button that answers "Unknown command."

Resolution: **the CTA is a config value, not a constant.** Each mod's existing
`config/<mod_id>/` gets two optional keys, empty by default:

```json
"onboardingCtaText": "Try the server shop -> /shop",
"onboardingCtaCommand": "/shop"
```

Empty command = no CTA appended, which is the default and keeps every mod
standalone. The operator opts in. The mod knows only "a command string someone
gave me," which is genuinely §2-clean, and it generalizes to any future shop
without a rewrite.

Rejected alternative: `rewards: {"function": "cobbleeconomy:shop_cta"}` on each
advancement. `AdvancementRewards` does still carry `function` (verified —
fields are `experience`, `loot`, `recipes`, `function`), so it would work
mechanically, but it puts a hard `cobbleeconomy:` reference inside
spiritwolves' own data files. That's the same coupling with worse failure
behavior, since it breaks at datapack load rather than at click time.

**Fire the CTA on step 1 only.** Eight advancements x a CTA each is eight
identical shop nags; the discovery moment is the first toast, not the second.
And skip it in cobbleeconomy entirely — its `open_shop` grant happens *because*
the player already ran `/shop`, so the CTA there is a button telling you to do
the thing you just did.

### 2. Wayfarers coupon — descope it to wayfarers, or drop it

wayfarers is **active** (`wayfarers/` is not archived, ~29 source files,
milestone-driven build via `PLAN.md`/`HANDOFF.md`).

That's what kills the original idea. wayfarers already owns `Wallet.java`,
`Listing.java`, `Purchase.java`, and `TradeGui.java` — it is *its own economy*,
not a feeder into cobbleeconomy's. A coupon minted by wayfarers and redeemed by
`/shop redeem <code>` needs cobbleeconomy to recognize a code format wayfarers
defines. That is a shared protocol between two mods, which §2 forbids and
§4.4 does not rescue: `shop.json` `components` can list an item for sale, but
it cannot express "this item is worth 20% off your next purchase."

Two honest versions survive:

- **(a) Keep it inside wayfarers.** A flier handed out by one wayfarer, good
  for a discount at the *next* wayfarer. No coupling at all, and it fits the
  mod's existing traveling-merchant fiction better than a coupon for a
  stationary shop does. This is the recommendation.
- **(b) Make it an item, not a code.** wayfarers drops a physical item;
  cobbleeconomy's `shop.json` buys it for currency at a fixed rate. That is
  §2-legal (the arrow is an item), but it is a *voucher*, not a discount, and
  it loses the "tied to a first purchase" property the brainstorm wanted.

Note the brainstorm's implementation pointer is stale: it cites `dailyquests`'
`state.json` for the atomic per-UUID write pattern, and **dailyquests is now in
`archived/`**. Live equivalents to copy from instead:
`bounties/fabric/src/main/java/bounties/BountyState.java` (closest analogue —
per-player state, atomic replace) or `ballot/.../PollStore.java`.

### 3. TameWatcher — confirmed deletable, do this one first

Fully verified against the jar:

- `TameAnimalTrigger` exists and is registered as **`minecraft:tame_animal`**
  (id string read out of `CriteriaTriggers`, field `TAME_ANIMAL`).
- Its `TriggerInstance` is a record of `Optional<ContextAwarePredicate> player`
  and `Optional<ContextAwarePredicate> entity` — so an `entity` type predicate
  is supported.
- The firing path is real: `Wolf.tryToTame(Player)` -> `TamableAnimal.tame(Player)`,
  and `tame` loads `CriteriaTriggers.TAME_ANIMAL` and triggers it for a
  `ServerPlayer`. Confirmed in bytecode, both hops.

So `spiritwolves/src/main/resources/data/spiritwolves/advancement/tame_wolf.json`
becomes:

```json
"criteria": {
  "tamed_wolf": {
    "trigger": "minecraft:tame_animal",
    "conditions": {
      "entity": [
        { "condition": "minecraft:entity_properties",
          "predicate": { "type": "minecraft:wolf" },
          "entity": "this" }
      ]
    }
  }
}
```

and `TameWatcher.java` is deleted outright — a whole 30-tick poll over every
online player, every 30 ticks, replaced by an event vanilla already fires. This
is the highest value-per-line item on the list and it has no design questions
left; it is purely a swap.

Two things to keep straight while doing it:

- The criterion name changes from `code_triggered` to `tamed_wolf`. Nothing
  awards it from code afterward, so there is no Java call site to update —
  but do grep for the string, because a stale `award(..., "code_triggered")`
  against this advancement would silently no-op.
- This does not change the backfill story (#5). A native criterion is just as
  non-retroactive as the poll was; a player who tamed a wolf last month still
  never fires it.

**Do not extend this to the other three.** Checked each:

- `rehome`'s `greet_villager` fires from `UseEntityCallback` in
  `GiftHandler.java:47`, *before* the held-item check, so it catches an
  empty-handed right-click. The nearest vanilla criterion,
  `minecraft:player_interacted_with_entity` (`PlayerInteractTrigger`), is
  item-centric — its `matches` takes an `ItemStack` — so it would not
  reliably cover the bare-hand greet. The existing hook is already free;
  the mod is holding that event regardless.
- `kamutotems` and `cobbleeconomy` grant on mod-specific actions (receiving a
  custom totem, opening a custom menu) that no vanilla criterion describes
  at all.

`minecraft:villager_trade` (`TradeTrigger`, verified present) remains available
if a future mod ever reacts to trading — but as the brainstorm already noted,
today it would point at nothing.

### 4. `hidden: true` on step 2 — do it, with one exception

The point of this system is a *toast*, and a toast the player has already read
the text of in the tree is a weaker toast. Hiding step 2 buys a second reveal
per mod, which doubles the surface area of the whole design for one JSON field
per file.

The exception is `kamutotems`. Its step-1 description already tells you the
step-2 action ("right-click it with a loose Kamu to bind one in") — that's the
instruction that makes the mod usable, and it has to stay visible. Hiding
`bind_kamu` there hides a card whose content was already spent. Harmless, but
it buys nothing; hide it for consistency or leave it, either is fine.

### 5. Backfill — one-shot `/advancement grant`, not a reconciliation pass

`/advancement grant @a only <id>` per mod at rollout is the whole answer for
step 1s, and it is a five-line runbook rather than code. Reject the "first-tick
reconciliation" alternative: it means writing, per mod, a detector for a
condition the mod does not otherwise need to detect (does this player own a
tamed wolf anywhere in the world? have they ever opened the shop?) — which is
resurrecting `TameWatcher` right after deleting it, for a one-time event.

Caveat worth stating out loud: granting step 1 to `@a` fires the toast *and*
the chat broadcast for every online player at once, since `announce_to_chat`
is `true` on all eight. Run it once, with the server empty or nearly so, and
accept that offline players get nothing (`@a` is online players only) — or
accept that they will earn it naturally on their next wolf.

Do **not** backfill step 2s. Step 2 is an achievement, not a notification.

### 6. Titles — the prefix already did this job; leave them

The committed titles are not what the brainstorm describes. Every one now
carries an explicit mod-name prefix: "Spirit Wolves: A Loyal Friend",
"Cobble Economy: Window Shopping", "Kamu Totems: First Kamu", "Rehome: A
Familiar Face" (standardized in `b69fdf2`).

That prefix *is* the discovery mechanism, and it is a better one than a
curiosity-gap title. "They sell doge life insurance now?" is funnier but never
tells the player the words "Spirit Wolves" — and the entire point is that the
player later types or searches that name. The prefix names the mod in the toast
every single time. Recommend closing this item: keep the prefixes, do not do a
curiosity pass. If any punch-up happens it belongs in the *description* line,
which is currently doing the explaining and can absorb some voice without
costing the name.

### 7. Commit — **done.** Superseded.

All eight advancement JSONs and the four Java hook sites are committed in
`b69fdf2` ("Add CobbleEconomy dialog shopkeeper flow and standardize
i18n/advancements"). `git status` is clean apart from these two brainstorm
docs. The "Implemented (uncommitted)" heading above and the line
"Nothing in this work is committed to git yet" are both stale as of this
session.

### 8. Tree placement — DECIDED: a tab per mod, except cobbleeconomy

Previously every step 1 was `"parent": "minecraft:story/root"`, i.e. all eight
advancements lived inside vanilla's Story tab. **Decision (2026-08-17): three
mods get their own tab; cobbleeconomy moves to vanilla's Adventure tab.** Implemented — see
"State after this decision" below.

Two claims from the first pass of this item were wrong, both corrected against
the jar:

- **A background is optional, so no client assets are needed.** `DisplayInfo`
  holds `Optional<ClientAsset.ResourceTexture> background`, and
  `AdvancementTab.create` returns a tab for any root that has a `display` at
  all — background is not consulted. If absent, `AdvancementTab` falls back to
  `TextureManager.INTENTIONAL_MISSING_TEXTURE`, a deliberate blank, **not** the
  pink/black error texture. So "a server-only mod can't ship a background" was
  never a real constraint on having a tab.
- **You can still point at a vanilla background.** The JSON value is a bare
  identifier that the codec expands to `textures/<id>.png` (verified: the
  `ResourceTexture` single-arg constructor runs `id.withPath(...)` over the
  concat constant `textures/.png`). So
  `"background": "minecraft:gui/advancements/backgrounds/stone"` resolves to
  `assets/minecraft/textures/gui/advancements/backgrounds/stone.png`, which
  ships in the vanilla client. Available: `stone`, `nether`, `end`,
  `adventure`, `husbandry`.

What was **confirmed** from the first pass is the coupling objection, and it is
sharper than stated: a shared root living in one mod's namespace means the
other mods parent into it, and if that mod is absent `AdvancementTree.addAll`
drops the orphans and logs `Couldn't load advancements: {}`. The mod's
advancements vanish entirely — visible in the server log, invisible to players.
One tab per mod avoids this completely: each mod's tree is self-contained, so
every mod stays independently installable, which is the §2 property the suite
exists to protect.

**Tab capacity is not a concern.** `AdvancementTabType.getMax()` is 8 for
ABOVE, 8 for BELOW, 5 for LEFT, 5 for RIGHT — 26 slots. Vanilla occupies 5, so
three more sits at 8 and still fits the top row exactly.

**Why cobbleeconomy is the exception.** A dedicated tab is a place players go
*looking* for a mod they already know about. cobbleeconomy is the one mod that
does not need discovering — it's the shop, it's the currency sink for the whole
suite, and its own step 1 fires because the player already ran `/shop`. So it
stays inside a vanilla tab rather than claiming one.

It was moved off `minecraft:story/root` to **`minecraft:adventure/root`**
(decided 2026-08-17). Adventure is where vanilla files its own commerce —
"What a Deal!" (`adventure/trade`) and "Star Trader" both live there — so a
shop advancement sitting among them reads as belonging, whereas in Story it sat
next to "Stone Age" and "Getting an Upgrade" with nothing in common. Verified
present in the jar at `data/minecraft/advancement/adventure/root.json`.

**Root advancement shape.** Each root uses a `minecraft:tick` criterion, so it
completes silently on the player's first tick and the tab renders as reached
rather than greyed. `show_toast` and `announce_to_chat` are both `false` — the
root is plumbing, not a reveal; the toasts stay on the step 1 / step 2 pair.
This matches how vanilla's own roots behave (`show_toast: false`,
`announce_to_chat: false`).

**Titles keep their mod-name prefix** (item 6). The tab name is invisible in a
toast, and the toast is the entire point of this design — "Spirit Wolves: A
Loyal Friend" still has to name the mod on its own. The prefix reads as mildly
redundant *inside* the tab and that is the correct trade.

#### State after this decision

| Mod | Root | Tab background | Step 1 parent |
|---|---|---|---|
| spiritwolves | `spiritwolves:root` (icon `echo_shard`) | `husbandry` | `spiritwolves:root` |
| kamutotems | `kamutotems:root` (icon `totem_of_undying`) | `nether` | `kamutotems:root` |
| rehome | `rehome:root` (icon `red_bed`) | `adventure` | `rehome:root` |
| cobbleeconomy | none | — | `minecraft:adventure/root` |

`spiritwolves:root` uses `minecraft:echo_shard` rather than `bone` because the
root is the one place the Spirit Stone's actual base item makes sense as an
icon — the constraint noted in the Implemented section (no distinct sprite for
the stone) still applies, but at root level the echo shard reads as "this mod
is about the stone" without competing with `bone` on step 1.

### Suggested order of work

1. **#3, TameWatcher swap** — verified, self-contained, deletes code.
2. **#5, backfill runbook** — no code, just a documented command.
3. **#4, `hidden: true`** — one field, four files.
4. **#1, config-driven CTA** — real work, touches four configs; the only item
   with a design surface left.
5. **#2, wayfarers coupon** — descoped to wayfarers-internal; effectively a
   wayfarers milestone, not part of this thread anymore.
6. **#6 and #7** — closed, no work.
7. **#8** — decided and implemented: one tab each for spiritwolves,
   kamutotems and rehome; cobbleeconomy filed under vanilla's Adventure tab.
   All four mods rebuilt green.

## Unrelated in-progress work — also stale, now committed

The previous session flagged uncommitted work in `cobbleeconomy` (a
dialog/mixin system: `DialogRouter`, `DialogKit`, `ShopDialogs`,
`CustomClickMixin`, `cobbleeconomy.mixins.json`, `DIALOGS_SPEC.md`) and an
uncommitted move of `cobblebending` and `dailyquests` into `archived/`.

Both landed: the archive move in `c2998e1`, the dialog system in `b69fdf2`.
The working tree is now clean apart from these two brainstorm documents. Still
not part of this thread — but there is no longer anything to accidentally
revert.

One live consequence for the advancement work: `dailyquests` being archived
invalidates the `state.json` reference in item 2 above, and `CustomClickMixin`
means cobbleeconomy now carries a Mixin, so DESIGN.md §4.7 ("`ballot` has the
only one in the suite") is out of date too.
