# Pocket Dungeons — Roadmap

> **What this is:** the ordered path from what ships today (U8) to everything
> `VISION.md` and `MYTHIC_PLUS_RECONCILIATION.md` describe.
>
> **What this is not:** a design document. Every *why* lives in `VISION.md`; every
> *how* lives in `../plans/M<n>-*.md`. This file is the order and the reasoning for
> the order, nothing else.
>
> **Live verification lives in `LIVE_TEST_PASS.md`.** Do not record status here.
>
> **Menu/dialog design lives in `DIALOGS_SPEC.md`.** Where a milestone below
> would benefit from a vanilla-dialog menu instead of chat text, that file has
> the spec; this file and the per-milestone plans only cross-reference it.

---

## The shape of it

```
M0  Entry fee          ── unblocks everyone, including us
     │
M1  Themes foundation  ── unblocks all content work
     │
M2  The room ─────────────┐  the big one
     │                    │
M3  The calling card ─────┘  the thesis (§2.1). Gated on M2 and nothing else
     │
M4  Affixes            ── the ladder finally has texture
     │
M5  Wolves / Feral     ── the first kiss/curse proven end to end
     │
M6  Supply             ── §3.7 becomes true instead of aspirational
     │
M7  Recipes            ── composition space, folklore
     │
M8  Deferred           ── outdoor dimension, one rule-breaking dungeon
```

M0 and M1 are days. M2 is the milestone the whole design has been waiting on.
M3 is short but only because M2 did the work.

---

## M0 — Entry fee and safety

**Why first:** every item is an afternoon, and two of them are what a pack author
hits on day one. Nothing here is blocked on anything.

| Item | Size |
|---|---|
| `RoomManifest` driven by `/reload` | small |
| `LICENSE` at repo root (`fabric.mod.json` already claims MIT) | trivial |
| `INTEGRATION.md` + published `dungeon_room` schema | small |
| Owner check on selector doors — tidy-up, **not** the bug it was filed as | small |

> **Corrected:** an earlier handoff recorded this as "a party guest can click the
> host's doors and spend their offer." **The code does not do that.**
> `Instances.selectorDoorStep` (`Instances.java:905`) resolves through
> `byMember.get(player.getUUID())`, and the click lands in
> `RitualListener.onUseBlock` (`RitualListener.java:80`) which only calls
> `sendDoorOffer` — a prompt. The spend happens in `chooseOffer`
> (`Instances.java:931`), which reads and clears **the clicking player's own**
> `DungeonLog` offer and is *deliberately* not room-gated, per its javadoc, so a
> player who disconnects mid-prompt can still accept from anywhere.
>
> The real defect is smaller: a guest standing in a host's selector room gets a
> prompt from doors that are not theirs. Confusing, not exploitable. Worth an
> owner check for clarity; not worth calling a security hole.

**Done when:** a datapack author can iterate with `/reload`, the repo states its
own licence, and a third party has a schema to write against.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M1 — Themes foundation

**Why second:** `VISION.md` §5.2 calls wiring `processors` the highest-leverage
unwired thing in the codebase, and it is the difference between "140 hand-authored
`.nbt` files" and "14 templates plus N processor lists." Every content milestone
after this one is cheaper because of it.

| Item | Size |
|---|---|
| Wire `processors` through `TemplateStamper` | small — **draft already in tree, uncommitted** |
| `theme` field on `DungeonRoomMeta`, symmetric with `roles` | small |
| Filter on theme in `RoomSelector` | small |
| Three proof themes as processor lists (deepslate, prismarine, blackstone) | content |

**Done when:** one `.nbt` and three JSON files produce three visibly different
dungeons, and none of it required a recompile.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M2 — The room

**Why here:** it is the centre of the design (§3.1), it replaces the selector
room, and M3 cannot start without it. It is also the only milestone with real
architectural risk, which is an argument for doing it while the surrounding code
is still fresh rather than after four content milestones have grown around the
current shape.

| Item | Size |
|---|---|
| Persist the room as a `StructureTemplate` blob keyed by owner | medium |
| Owner + whitelist permission mask (break, containers; stations open) | medium |
| Bedrock envelope — sub-floor, over-ceiling, outer ring on unadjacent faces | small |
| The closed loop — **capture → persist → clear → stamp**, order non-negotiable | medium, delicate |
| Door-as-entrance; the finished dungeon lingers as a quarry | small |
| **Purge on unexpected leadership change** rather than transferring ownership (§7.2) | small |

**Done when:** a player completes a run, opens the last door, and walks into the
room they left — with their decorations in it — and the entrance cell is empty
when they walk back.

**Protect:** the mod says nothing about any of this (§4). No message, no sound.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M3 — The calling card

**Why here and not later:** §2.1. Skyblock was a challenge map for a year and a
mode the moment it went multiplayer; Hypixel's version won by putting a public
layer next to the private one. A decoratable room nobody can visit is the private
half on its own. This is the highest-value milestone in the document and it is
*small* — but only after M2.

| Item | Size |
|---|---|
| Mint a calling card (plain compass + `CUSTOM_DATA` owner UUID) | trivial |
| Use-on-lodestone opens a way in that is not one of the three doors | small |
| One shared visit instance per owner, refcounted; route home if the owner is in | medium |
| Visitor = "not on the whitelist"; reuses M2's mask wholesale | trivial |

**Done when:** two players holding cards to the same room stand in it together,
neither can break anything, and both can use the stations.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M4 — Affixes

**Why after the room:** affixes make the ladder textured, but the ladder already
functions. The room does not.

| Item | Size |
|---|---|
| `Keystone.Affix` enum → stackable **set**; comma-join into the existing field | small — no codec migration |
| Three sites branch on affix today; refactor to `contains(...)` | trivial |
| Level thresholds **5 / 11 / 17** decide *how many*; the key seeds *which* | small |
| Naming: `<intensifier> <affix> Keystone [<level>]`, set in enum order | small |
| Four new affixes, **each owing a kiss** — Swarming, Overclocked, Molten, Silenced | medium |

**The rule:** every affix hands you something (`MYTHIC_PLUS_RECONCILIATION.md`
§5.0), and all five kisses are decided — Molten is the game's **only lava
faucet**, Silenced **deafens the mobs** it silences you against. Depletion takes
the `max` across the set, capped at 2×, never the product (§7.1).

**Done when:** a level-16 key reads `Menace Cooked Keystone [16] [Swarming]` and
the player can name what each word bought them.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M5 — Wolves and Feral

**Why its own milestone:** it is the first kiss/curse proven end to end and it
crosses into `spiritwolves`. Bundling it into M4 hides a cross-mod surface inside
a tuning milestone.

| Item | Size |
|---|---|
| Untamed wolves at stamp time, pinned with `setHomeTo` | small |
| 9 coats gated on `DifficultyProfile.lootTier()` via `WOLF_VARIANT` | small |
| Bones as the catch resource — vanilla roll **is** the catch rate | trivial |
| Run-scoped by default; optional permanence via Spirit Stone | small |

**Verified in the 26.2 bytecode** (§7.4), so none of this needs tuning by guess:
`tryToTame` is `random.nextInt(3) == 0` — **1 in 3 per bone**; `isAngry()`
**refuses the bone outright**; a successful tame calls `setOrderedToSit(true)`.

Three forced consequences: wolves spawn **neutral, never angered** (an angry wolf
is an untameable wolf); the readable rule is **"don't hit it, feed it"**; and a
caught wolf **sits and stays sat**, so it is collected on the way out rather than
trailing the party through a timed run.

**Done when:** a Feral run spawns coat-appropriate wolves, a player tames one with
bones from the same run, and binding it to a Spirit Stone preserves the coat.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M6 — Supply

**Why here:** §3.7 is a *constraint*, and it is currently aspirational — wood is
structurally missing and consumables are weighted rather than floored. Until this
lands, "could a player progress without ever leaving?" has the answer *no*.

| Item | Size |
|---|---|
| **Guaranteed floors for consumables, weighted rolls for treasure** | medium |
| **Bones on a guaranteed floor** — 1-in-3 taming odds against a weighted drop fails silently | small |
| Tiered building blocks in the tables — as the point, not as filler | content |
| Grove / garden room type — one template, one JSON, no Java | content |
| Seeds and dirt guaranteed — the Skyblock bootstrap | small |
| Nether / End *products* not ingredients (ender chest, brewing stand) | content |
| Plumbing: `/dungeon` as a first-class route; exit no-ops with nowhere to go; join and stray fallback prefer the room | small |

**Done when:** a server with an emptied overworld is fully playable and nobody had
to tune anything twice.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M7 — Recipes

**Why last of the built work:** it needs themes (M1) to exist and the dungeon log
to have been recording them for a while.

| Item | Size |
|---|---|
| Store the last N run themes in `DungeonLog` (`optionalFieldOf` + default) | small |
| Recipe table as datapack JSON: `[theme, theme, theme] → dungeon id` | small |
| **Order matters** — the same three themes in a different sequence differ | — |
| Surface completed themes so ingredients are visible while combinations are not | small |

**Done when:** someone comes back with *New Dungeon Discovered* and their friends
have to ask what they did.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M9: Refactor and cleanup

Landed between M7 and the D3 progression plan below. See
`../plans/COMPLETED-MILESTONES.md` for what it built; this roadmap otherwise
stopped tracking individual milestones once `D3_PROGRESSION_PLAN.md` became
the sequenced source for everything after M9.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M10: Ladder reframe

First of the D3 progression plan's milestones (M10-M17), full scope and
sequencing in `D3_PROGRESSION_PLAN.md`. Raises the keystone cap to 100, scales
mob strength with level, spreads affix thresholds and intensifier bands across
the wider range, retires `Affix.Kind.ELECTIVE`, and gates run completion on
clearing a fraction of the run's trial spawners.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M11: Adventures

Second of the D3 progression plan's milestones. Replaces the recipe system's
backward-looking tail match with a forward-looking descent graph
(`AdventureGraph`): a theme's transitions decide what doors offer next, and a
boss-kind theme ends the adventure with a fight instead of a walk to the pad.
Full scope in `D3_PROGRESSION_PLAN.md`; the boss room shipped as a landed
scope divergence (reusing the terminal cell rather than a hand-authored
structure, recorded there and in `COMPLETED-MILESTONES.md`) since no live
client was available in the implementing session to author or capture one.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M12: Two-tier doors and fuel

Third of the D3 progression plan's milestones. Door 1 becomes the free,
non-depleting, fuel-paying tier; doors 2 and 3 become the fuel-costed,
level-gated Greater tier, making M10's level gate concrete: a low key cannot
take a door-2/3 offer a high key can. Fuel is echo shards, paid out only by
door 1 (a deliberate landed decision against a loot-table entry, recorded in
`D3_PROGRESSION_PLAN.md`'s M12 section, to close off any self-funding risk
rather than merely mitigate it). Full scope in `D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M13: Gear loot pool

Content only, and the long pole for every gear-touching sink: M14, M16 and
M17's extraction source all had nothing to operate on until this landed, since
the existing chest tables carried zero wearable or wieldable items. Adds 15
slot-keyed `gear/<slot>_<tier>` tables for the gamble to draw from, and folds
a gear pool into all nine chest tables so runs themselves drop gear. Every
piece carries a `custom_data` tier marker the cost curves downstream key off.
Enchanting uses `enchant_with_levels` rather than the plan's assumed
`set_enchantments`, a landed correction recorded in `D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M14: Gear reroll station

Fourth of the D3 progression plan's milestones to land (depends on M13's gear
loot pool). A room station rerolls one enchantment on a piece of gear, the
player's choice of which, at a lapis cost that scales with the item's tier
(read off the `pocketdungeons.tier` custom_data tag M13's loot writes).
The gear-scale sink, paired with M12's fuel as the ladder-scale sink: fuel is
why level 100 is worth reaching, lapis is why the gear it drops is worth
using. Full scope in `D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M15: Armor trims

Fifth of the D3 progression plan's milestones to land (depends on M13's gear
loot pool for a place to put templates and materials; is itself the code
dependency M17 needs, since the Herobrine Cube's equipped-power check reuses
this milestone's equip-time attribute plumbing). Trim templates and materials
drop as dungeon loot; applying one at a smithing table still works exactly
like vanilla, but the template-duplication crafting recipe is disabled
globally (verified: `Ingredient` matches only by item/tag, with no way to
require specific item components, so a dungeon-found template cannot be
told apart from an ordinary one at the recipe level) and the trim material
grants the worn piece a real attribute bonus, not just a colour. Full scope
in `D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M16: Gear gamble station

Sixth of the D3 progression plan's milestones to land (depends on M13's gear
loot pool). A room station spends emeralds for one random piece of gear in a
chosen slot and tier, with no guarantee of quality within the slot: the
volume-over-certainty sink that sits next to M14's targeted, guaranteed
reroll. Draws directly off M13's slot-keyed `gear/<slot>_<tier>` tables (a
`LootTable` roll, not a chest) so a lucky draw cannot out-produce opening the
run's own chests. Which tiers a player can gamble at is read off the same
level-to-tier mapping (`KeystoneMath.lootTier`) a run's own loot already
uses, rather than a separate unlock threshold. Full scope in
`D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M17: The Herobrine Cube

Seventh and last of the D3 progression plan's milestones to land (depends on
M15's equip-time attribute plumbing and M11's adventure-graph rare-node
rewards). A crafting-table ritual in the original plan; landed as a block-use
ritual station instead once the jar confirmed `Ingredient` cannot match by
component value, which rules out a datapack recipe expressing "any weapon or
armour piece, plus whichever of an open-ended power library the player
chooses." Extract consumes a rare adventure-node drop and permanently unlocks
its one power; imbue applies a previously extracted power to an ordinary
piece of tiered gear for a small material cost. An equip cap (three active
powers, counted by distinct id) keeps the choice live no matter how large the
unlocked library grows. Full scope in `D3_PROGRESSION_PLAN.md`.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M18: Room shell pass

First of the Room UX pass (`docs/reference/ROOM_UX_PLAN.md`), and the milestone M24's
room skins wait on. The room's shell (floor, walls, ceiling, lamps) becomes
immutable to everyone, the owner included, via a pure coordinate test on
`RoomProtection` with no block-state lookup, so a future skin swap changes
block types without touching the protection. The lodestone moves from the
floor to the north wall at eye height, ready to become the right-click
navigation terminal in M21; the selector opening gets physical double doors
(two oak doors plus a wall lintel) that block mobs and open by hand once a
run is underway; and the interior ceiling becomes top slabs with
stair-framed lanterns, gaining half a block of headroom. All four are
geometry changes to the same code paths (`RoomBuilder.buildShell`, the room
templates, and the protection guards), so they landed together.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M19: Physical door selection

Second of the Room UX pass. The dialog-based door offer is replaced by a
physical UI on the selector wall: three copper bulbs above the doors signal
the selection, a black concrete screen with a server-side `text_display`
entity above them shows the offer or the run context, and a lever beside the
third door commits the choice through `RunLifecycle.chooseOffer`. A separate
engine terminal, a respawn anchor on the wall to the left, shows fuel and
accepts echo shards, keeping "which fight" and "can I afford it" on two
surfaces. The screen entities are summoned at every room stamp and never
captured with the room; `isFurniture` protects all of it from the player.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M20: Visiting rework: lobby directory, no calling card

Third of the Room UX pass. The hand-traded calling card is replaced by a
lobby directory opened from the room's wall lodestone: a `MultiActionDialog`
listing every online player whose room is publicly listed, one button per
room carrying the owner UUID, with the room name (or owner name) and
occupancy on the label and the owner's live status (`open`, `run in
progress`, `away`) in the body line. Privacy is a host-set `publicListed`
toggle, not a token: `DungeonLog.Entry` gains `publicListed` and `roomName`
as optional codec fields with safe defaults, `/dungeon room
public|private|name` manage them, and clicking a listed room calls the
existing `VisitService.visit` from a dialog button instead of card
use-on-lodestone. `CallingCard.java`, its config field, and `/dungeon room
card` are deleted; `RoomWhitelist` and `RoomProtection` are unchanged
(public listing is visibility, not permission).

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M21: UX consolidation: one lodestone, one menu

Fourth of the Room UX pass. Five lodestone interactions collapse into one
right-click menu on the wall lodestone: the menu's buttons depend on context
(overworld, i.e. not in the dungeon dimension: Start Dungeon, Browse
Lobbies, Manage Room, Inspect Keystone; in dungeon: Leave, Manage Room for
the room's own owner, Inspect Keystone). The keystone is checked when Start
Dungeon is clicked rather than when the menu opens; the `hasInstance` guard
inverts to show the in-dungeon menu; and the stand-on leave-pad
(`Instances.isOnRoomLeavePad`) is deleted, leaving the terminal pad at the
dungeon's end as the only stand-on lodestone interaction. Manage Room gains
the room name and public/private toggle next to the whitelist; `/dungeon`
commands stay as shortcuts to the same methods.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M22: Sound pass

Fifth and final milestone of the Room UX pass. A `Chime.java` class, the
suite's house pattern, sends one per-player `ClientboundSoundPacket` per
interaction: door selection, run lifecycle (complete, timeout, keystone up
and depleted, spawner cleared), room and menu (menu open, room listed and
unlisted, visitor arrives, room relocated), and lobby visiting (browser
open, visit start and end). All vanilla `SoundEvents`, no custom assets,
server-side only. The one legacy room-wide broadcast (the run-start cue in
`DialogRouter.startDungeon`) becomes a per-player packet. Landed divergence:
the spawner-cleared cue is a small watcher on `Instances.onTick` with one
in-memory `clearedCells` field, since no per-cell clear event exists to
hook.

→→ `../plans/COMPLETED-MILESTONES.md`

---

## M8 — Deferred

Held deliberately. Each is a milestone wearing a feature's clothes.

| Item | Why it is held |
|---|---|
| **Outdoor themes** | Needs a **second dimension**. Sky is per-dimension (`has_skylight: false`, `effects: the_end`), and `Instances` is built around one level with one slot grid |
| **One rule-breaking dungeon** | Pick **the Endless Mine** — "do not place a terminal, keep extending" bends `LayoutGraphGenerator`'s invariants least. Hold the rest until it ships |
| **Data-driven affixes** | Do not attempt until 5–6 exist in Java and the varying knobs are known |
| **Lava as a second faucet** | Molten is the only source (§5.5). If that proves too narrow, widen it *after* M6 shows whether it actually pinches |
| **Multi-cell footprints** | `LayoutGraphGenerator` is 1×1 only today |
| **Room size as progression**, station unlocks | Downstream of M2 and M6 |
| **The elevator** — public opt-in room/party directory | Needs M2, M3, and a menu system this mod has never built — reconcile with the calling card's deliberate no-browse rule first |

→ `DOOR_LADDER_BRAINSTORM.md` §15.7

---

## Standing rules for every milestone

1. **Verify against the 26.2 jar, not memory.** `javap -cp` and `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Anything unverified
   gets a `⚠ UNVERIFIED` comment in the source.
2. **No client mod, ever.** No assets, no custom blocks, no custom items, no
   custom registry entries. `"environment": "server"` is load-bearing for §6.
3. **Mods stay strangers.** Cross-mod work follows `kamutotems/INTEGRATION.md` —
   no compile-time coupling, no shared vocabulary.
4. **Self-sufficiency is a constraint, not a mode.** Every change is checked
   against *could a player progress without ever leaving?*
5. **The mod stays quiet about the trick** (§4), and everything it does say is
   slang. Silent-vs-slang, not clinical-vs-slang.
6. **Superseded designs are marked superseded, not deleted.**
