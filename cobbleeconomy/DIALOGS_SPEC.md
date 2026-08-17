# Cobble Economy — vanilla dialogs spec

> **Status: implemented and building.** See [DIALOGS.md](DIALOGS.md) for what was built,
> the confirmed payload shapes, and the manual test checklist. Parts 1 and 2 are done
> except 2.6, which was deliberately deferred. `DialogTest` was kept rather than deleted
> — see the report for why.

Adopt Minecraft 26.2's native dialog system in Cobble Economy, alongside the existing
`eu.pb4:sgui` chest menus rather than instead of them.

**Read [../DIALOGS_SPEC.md](../DIALOGS_SPEC.md) Part 0 first.** It is the verified API
reference (`net.minecraft.server.dialog`, the send/receive path, the mixin shape) built
by disassembling the real 26.2 jar, and it applies to every mod in this workspace
unchanged. Do not re-derive it here. QuizEngine has already shipped against it
(`quizengine/DIALOGS.md`) and confirmed the text-input payload shape; that is the one
piece of prior art this spec leans on.

## Why this mod is a good fit

`CobbleEconomyMod.java`'s own class doc states the design constraint dialogs are built
for: "vanilla clients need nothing installed... nothing is added to a synced registry."
`Holder.direct(Dialog)` sends a runtime value with no registry entry and no datapack
JSON, same as the sgui chest windows already in this mod — a vanilla client renders both
with nothing installed. `fabric.mod.json`'s `"environment": "server"` stays accurate.

## The governing principle

Same rule Ballot and QuizEngine worked under: **dialogs are presentation, not a second
set of rules.** Every dialog action in this spec ends by calling the exact same method
the equivalent sgui click or chat command already calls —
`ShopAdminMenu.save`/`catalog`/`config.save`, or `ShopPurchase.attempt`. If a dialog and
an sgui click could ever disagree about what happens, that is a bug in this spec, not a
matter of picking which one wins.

### Hard constraints

- **Do not touch `cobbleeconomy/core/`.** No Minecraft on its classpath; `EconomyTest`
  must still pass unchanged (`./gradlew :core:test`).
- **Do not remove SGUI, `ShopMenu`, or `ShopAdminMenu`.** This is additive. Every screen
  that works today keeps working, reachable exactly as it is now.
- **Do not remove any `/cobbleeconomy` or `/buy`/`/shop` command node.** Dialog buttons
  are a second door, not a replacement for the chat interface console still needs.
- Keep `ShopAdminMenu.askText`/`askNumber`'s method signatures unchanged where
  practical, so call sites already correct today do not need touching.

## Part 1 — Plumbing (own copy)

Cobble Economy is its own Gradle module with no dependency on `ballot` or `quizengine`,
so — same as QuizEngine did — it needs its own copy of the small amount of glue code,
not a shared one:

- `fabric/src/main/java/cobbleeconomy/dialog/DialogKit.java` — the builder facade from
  root spec Part 1.1, narrowed to this mod's shapes. Model it directly on
  `quizengine/fabric/src/main/java/quizengine/mc/dialog/DialogKit.java`; it is already
  proven against this MC version.
- `fabric/src/main/java/cobbleeconomy/mixin/CustomClickMixin.java` — root spec Part 1.2.
  Cobble Economy has **no mixin setup at all** (checked: no `mixin` package, no
  `*.mixins.json` anywhere under `fabric/`), so this also means creating
  `fabric/src/main/resources/cobbleeconomy.mixins.json` (copy QuizEngine's shape —
  `"compatibilityLevel": "JAVA_25"`, package `cobbleeconomy.mixin`) and adding
  `"mixins": ["cobbleeconomy.mixins.json"]` to `fabric.mod.json`. No
  `build.gradle.kts` change needed; Loom reads it from `fabric.mod.json`.
- Namespace-gate on `cobbleeconomy:` only in the mixin, same as the other two mods —
  anything else falls through untouched.
- Hop to the server thread before touching `catalog`/`economy`/`log`
  (`server.execute(...)`), per root spec Part 1.3. `ShopAdminMenu` and `ShopPurchase`
  currently assume they are already on the main thread; a dialog submission arrives on
  the netty thread and must not skip this.

## Part 2 — Where to use one

### 2.1 Text and number entry (do this first)

[`ShopAdminMenu.askText`](fabric/src/main/java/cobbleeconomy/ShopAdminMenu.java#L481-L499)
fakes a text field with an anvil rename screen — its own comment calls this "the same
trick `ballot` uses," and Ballot's spec (root, A2.1) already retired it there. Do the
same swap here: a `NoticeDialog` with one `TextInput` and a `CustomAll` submit, behind
the same `askText(prompt, initial, onDone)` signature so its four call sites — rename
(`ShopAdminMenu.java:199`), category (`:224`), quantity (`:210`, via `askNumber`), price
(`:330`, via `askNumber`) — need no changes beyond the method body.

- Rename: `maxLength` ~48 (a `/buy` key is one word).
- Category: `maxLength` ~48.
- `askNumber` becomes its own dialog rather than a thin wrapper around `askText`: a
  `NumberRangeInput`, read back with `getFloatOr` and rounded to `long`. See Part 3 —
  this input's payload shape is now confirmed, so the earlier plan to keep numeric entry
  on `TextInput` no longer applies. Reject a result that rounds to zero or below with
  the same message `askNumber`'s catch block gives today.
- Delete the `AnvilInputGui` import and path once the dialog version works.

### 2.2 Purchase confirmation (highest value — player-facing)

[`ShopMenu.openConfirm`](fabric/src/main/java/cobbleeconomy/ShopMenu.java#L229-L284)
builds a whole `GENERIC_9x3` chest window with a yes item, a no item, and the goods
between them, specifically because — per its own doc comment — "the two buttons cannot
be hit by the same twitch." That is precisely the shape `ConfirmationDialog` is: a
`yesButton`/`noButton` pair that cannot occupy the same click. Replace it with one,
keeping the same body content (cost, current balance, balance left after) as
`PlainMessage` lines, and the same two outcomes calling `bulkBuy`/`ShopPurchase.attempt`
then reopening the category screen — identical to today's `Yes`/`No` callbacks
(`ShopMenu.java:263-273`, `:276-281`), just triggered from a dialog action instead of a
slot click. `afterAction = CLOSE`; `canCloseWithEscape = true`, since escaping a "buy?"
prompt with nothing spent is exactly what "No" already does.

Reached the same way it is today — `PurchaseConfirm.required(...)` decides whether to
show it at all (`ShopMenu.java:200-205`), unchanged.

### 2.3 Admin destructive confirmations — leave most alone

[`ShopAdminMenu`](fabric/src/main/java/cobbleeconomy/ShopAdminMenu.java) already gates
delete (`:273-288`) and enable/disable (`:155-163`, `:266-270`) behind shift-click.
Per root spec A2.3's own framing, dialogs are for the *irreversible* one-way doors, and
a shift-click that is fast for an admin doing a dozen edits should not grow an extra
screen for free. Recommendation: convert **delete only** to a `ConfirmationDialog`
naming the entry key and its price in the body (an admin dropping the wrong listing
loses no data — `shop.json` is one `git`/backup away — but a clear "delete
`iron_ingot`? players buying it will get 'not for sale'" body line is worth the one
extra click). Leave enable/disable as shift-click; it is reversible by clicking again.

### 2.4 Price editing — mostly stays as-is

[`ShopAdminMenu.drawPrices`](fabric/src/main/java/cobbleeconomy/ShopAdminMenu.java#L298-L340)'s
left/right/shift-click nudge buttons are a faster interaction than any dialog could be
for the common case (±1, ±100) — do not touch them. Only the "middle-click to type an
amount" path (`:330`) goes through `askNumber`, which 2.1 already converts. No separate
work item here.

### 2.5 Explicitly out of scope

`ShopMenu`'s category grids and `ShopAdminMenu.openList`'s paginated entry list stay
SGUI chest menus, full stop — this was the direct answer to "can SGUI reuse the creative
search screen" earlier in this conversation, and it is also root spec A2.5's own
position on `DialogListDialog`: fine for a handful of author-defined buttons, not built
for a scrollable/searchable catalog of arbitrary size. Nothing in this mod's item
browsing changes.

### 2.6 New: typed item-add (optional, nice-to-have)

[`ShopAdminMenu.addFromHand`](fabric/src/main/java/cobbleeconomy/ShopAdminMenu.java#L384-L400)
requires holding the item to sell. Once `askText` runs on dialogs (2.1), a companion
`[ Add by ID ]` button feeding a typed item identifier through `ItemBank.resolve` costs
almost nothing extra and covers items an admin cannot easily obtain a copy of (e.g. an
item disabled elsewhere). Lower priority than 2.1/2.2 — build only if time remains.

## Part 3 — Resolved: the input shapes are now confirmed

Root spec Part 0 originally flagged `BooleanInput`/`NumberRangeInput`'s submitted-tag
shape as unconfirmed. `fabric/src/main/java/cobbleeconomy/dialog/DialogTest.java` (a
throwaway diagnostic dialog reachable via `/cobbleeconomy dialogtest`) settled it in
play:

| Input | Submitted tag | Read with |
|---|---|---|
| `TextInput` | `StringTag` | `getStringOr` |
| `SingleOptionInput` | `StringTag` (the entry `id()`) | `getStringOr` |
| `NumberRangeInput` | `FloatTag`, always -- even for whole numbers | `getFloatOr` |
| `BooleanInput` | `ByteTag` (`1b`/`0b`) -- **not** `onTrue`/`onFalse`, which was the root spec's own wrong guess, now corrected there too | `getBooleanOr` |

This changes the earlier recommendation: numeric entry no longer needs to stay on
`TextInput` + `Long.parseLong` to avoid gambling on an unverified encoding. Price and
quantity fields (2.1, 2.4) may use `NumberRangeInput` directly, reading the result with
`getFloatOr` and rounding to `long` -- a shop price is always a whole number, so round
rather than truncate, and reject anything that rounds to zero or below the entry's
minimum the same way `askNumber`'s catch block rejects unparseable text today. Keep
`TextInput` for the rename/category fields (2.1), which are genuinely text, not numbers
wearing a slider.

Once Part 2 is actually implemented, delete `DialogTest.java` and the `dialog_test` case
in `DialogRouter` -- they exist only to have produced the table above.

## Part 4 — Acceptance

1. `./gradlew build` passes from `cobbleeconomy/`. JDK 25.
2. `EconomyTest` (`core`) passes unchanged — if the count of tests moved, something in
   `core/` was touched that should not have been.
3. No new dependency in either `build.gradle.kts` — the dialog API is vanilla, same as
   the other two mods.
4. Every pre-existing command, sgui screen, and shopkeeper-villager interaction
   (`ShopkeeperInteraction`, `ShopkeeperCommands`) still compiles and is still reachable.
5. `/cobbleeconomy shop edit` still opens fine from console-unfriendly contexts exactly
   as before (`AdminCommands.java:252-261`) — dialogs never change that a player is
   required for the screen.

## Report

On implementation, write `DIALOGS.md` in `cobbleeconomy/` covering: what was built, the
real `CompoundTag` shape observed for the text-input payload in this mod's own testing,
anything left out and why, and a numbered manual test checklist — same shape as
`quizengine/DIALOGS.md`. Do not commit; leave the work in the working tree for review.
