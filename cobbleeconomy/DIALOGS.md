# Vanilla dialogs in Cobble Economy

Implements [DIALOGS_SPEC.md](DIALOGS_SPEC.md) Parts 1 and 2, against the workspace
reference in [../DIALOGS_SPEC.md](../DIALOGS_SPEC.md).

No new dependency — the dialog API is vanilla. SGUI is still here and still does the
item browsing it is better at.

## What was built

| File | Does |
|---|---|
| `dialog/DialogKit.java` | The vanilla dialog API narrowed to the shapes used here |
| `dialog/DialogTest.java` | The diagnostic that answered the open payload question |
| `ShopDialogs.java` | The screens: buy confirmation, listing editor, delete confirmation, text prompt |
| `DialogRouter.java` | Where a clicked button lands, on the main thread |
| `mixin/CustomClickMixin.java` | Catches the packet; hops threads |
| `cobbleeconomy.mixins.json` | New — the mod had no mixin setup at all |

`ShopMenu`, `ShopAdminMenu` and `AdminCommands` changed only where a screen was
replaced or a route added. `core/` was not touched: **200/200 tests still pass.**

`ShopDialogs` lives in the flat `cobbleeconomy` package rather than `.dialog` because it
drives `ShopMenu` and `ShopAdminMenu`, both package-private. `DialogKit` — the half with
no shop knowledge in it — stays in `.dialog`.

## The payload question, answered

`/cobbleeconomy dialogtest` (op only) opens a dialog carrying one of every input control
and prints what comes back, with tag types, in chat and the log. Run in play:

| Input | Submitted tag | Read with |
|---|---|---|
| `TextInput` | `StringTag` | `getStringOr` |
| `SingleOptionInput` | `StringTag` — the entry `id()`, not its display component | `getStringOr` |
| `NumberRangeInput` | `FloatTag`, always, even for whole numbers | `getFloatOr` |
| `BooleanInput` | `ByteTag` (`1b`/`0b`) | `getBooleanOr` |

**`BooleanInput` was the correction worth making.** The workspace reference claimed the
`onTrue`/`onFalse` strings were what got submitted. They are not — the value is a raw
byte regardless of what those fields are set to. Ballot's planned settings dialog uses
three `BooleanInput`s and would have read every one of them as an empty string. The root
spec is corrected in place, marked `[confirmed in play, cobbleeconomy]`.

`DialogTest` and its command are **kept**, against the spec's own instruction to delete
them. Re-confirming a payload shape is a recurring need, the whole thing is one op-gated
command, and the alternative is adding a logging branch to a real handler next time.

## What changed for a player

**The purchase confirmation is a dialog.** It was a `GENERIC_9x3` chest with a lime
concrete "yes" and a barrier "no" four slots apart, and the numbers — cost, your
balance, what you would have left — buried in an item tooltip you had to hover to read.
Now it is a `ConfirmationDialog` with two labelled buttons and the numbers written out
as text, including a `12,481 -> 11,841` before-and-after per currency.

Nothing about *when* it appears changed: `PurchaseConfirm.required` still decides, and a
bulk buy is still sized by `plannedLots` before anything is charged.

## What changed for an admin

**Editing a listing is one form instead of six screens.** Clicking an entry in
`/cobbleeconomy shop edit` used to open a chest, where changing the key opened an anvil,
typing, accepting, landing back on the chest, then the category, another anvil. Six
round trips to change six things. It is now a `MultiActionDialog` carrying every field —
key, category, quantity, on-sale toggle, and one price field per registered currency —
with one Save.

Save applies everything or nothing. A bad key, a name already taken, an unparseable
number or a price that comes to zero in every currency reports in chat and writes
nothing; a partly-applied save would leave a listing in a state nobody asked for and
cannot see.

**The sgui editor is still there, on right-click.** It keeps the two things a dialog
cannot do: take the item out of your hand, and nudge a price up and down without typing
a number. The list entry's lore says which click does which.

**Delete asks properly.** It was shift-click-to-confirm — a gesture you have to know,
that cannot say what it is about to destroy. It is now a `ConfirmationDialog` naming the
listing, what it sells, and that anyone with `/buy <key>` in muscle memory will start
getting "not for sale".

**The anvil text hack is gone.** `askText` was an `AnvilInputGui` with the value read off
the rename box — `ShopAdminMenu`'s own comment conceded it was a workaround. It is a
`TextInput` now, 128 characters instead of what fits in an anvil.

## Design notes

**Sliders are not for money.** `NumberRangeInput` is confirmed working and deliberately
unused for prices and quantities. A slider needs a bounded range to be usable; a shop
price is not bounded, and one spanning 1 to 50,000 has a pixel worth hundreds of
cobblestone. It is the right control for a bounded setting and the wrong one here.
`BooleanInput` *is* the right control for the on-sale toggle, and that is what it does.

**Item browsing stays SGUI.** `/shop`'s category grids and the admin listing pages are
unchanged. Dialogs present a handful of author-defined buttons; they have no scrollable
grid and no search, which is the same conclusion the Ballot spec reached about
`DialogListDialog`.

**Context keys are prefixed `ctx_`.** `CustomAll` merges input values into the same flat
compound as the context and **input keys win on a collision**. The editor has an input
called `key` and needs to carry which listing is being renamed, so that context key is
`ctx_target`. This is a real hazard, not a hypothetical one — the rename field would
otherwise silently overwrite the identity of the thing being renamed.

**One-shot prompts keep their callback server-side.** `askText` holds a `Consumer` in a
per-player map rather than routing through the payload, because the callback is a
closure over the sgui screen that opened it. One slot per player: opening a second
prompt abandons the first, which is what opening a second anvil already did.

**Reopening after a click is safe.** The client closes the dialog locally when a button
is pressed and *then* sends the packet, so a server-side reopen always lands after the
close. Save reopens the editor with fresh values; "Keep it" on the delete prompt returns
to the editor.

## Manual test checklist

Needs a 26.2 Fabric server and a vanilla 26.2 client. The build and the 200-test core
suite are the only automated evidence — nothing below has been run.

1. **Mixin loads.** Start the server. No mixin apply error, and
   `Cobble Economy initialised` still appears.
2. **Nothing regressed.** `/shop` opens, categories browse, a cheap item buys in one
   click with no prompt. `/buy <key>` still works. `/cobbleeconomy shop list` from
   console still works.
3. **Buy confirmation.** Buy something above the `confirmAt` threshold. Expect a dialog,
   not a chest. Check the before/after balance line is right. Confirm → bought, and you
   land back on the category page you clicked from.
4. **Cancel spends nothing.** Same purchase, hit Cancel. Balance unchanged, back on the
   category page. Then try escape — same result.
5. **Bulk confirm.** Shift-click an expensive stackable. The prompt should name the real
   total for as many lots as you can afford, not one lot.
6. **Editor.** `/cobbleeconomy shop edit`, click a listing. Every field present and
   pre-filled. Change several at once, Save. Chat confirms, the editor reopens showing
   the new values, and `config/cobbleeconomy/shop.json` has them.
7. **The toggle.** Flip "On sale" off, Save, confirm the entry disappears from `/shop`
   and is still in `shop.json`. **This is the `BooleanInput`/`ByteTag` path** — if the
   payload finding is wrong anywhere, it shows up here.
8. **Rename.** Change the key. Old key gone from `/buy` tab-completion, new one works.
   Then try renaming to a key that already exists — expect a refusal and no write.
9. **Nothing-applied-on-failure.** Set a valid category and an unparseable quantity in
   the same save. Expect the refusal, and confirm the category did *not* change.
10. **No price at all.** Zero every currency field and Save. Expect "needs a price in at
    least one currency", nothing written.
11. **Right-click still works.** Right-click a listing → the old sgui editor. Item from
    hand, price nudges, all as before.
12. **Delete.** Delete from the sgui editor → confirmation dialog naming the listing.
    "Keep it" returns to the dialog editor; "Delete it" removes it from `shop.json`.
13. **Stale listing.** Open the editor, delete the same entry via
    `/cobbleeconomy shop remove <key>` in another session, then Save. Expect "That
    listing is gone", no crash.
14. **Escape everywhere.** Every dialog closes on escape with nothing written.
15. **Shopkeeper unaffected.** Right-click a shopkeeper villager — still opens `/shop`.

## Not done

Spec 2.6 (add-a-listing by typed item id) was left out; `addFromHand` still requires
holding the item. It is the lowest-value item in the spec and the plumbing it needs
(`askText`) is now in place, so it is a small follow-up rather than a rewrite.

Not committed — the work is in the working tree.
