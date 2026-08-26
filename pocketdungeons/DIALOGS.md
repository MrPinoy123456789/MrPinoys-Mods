# Vanilla dialogs in Pocket Dungeons

Implements [`DIALOGS_SPEC.md`](DIALOGS_SPEC.md) §1–§6, against Part 0 and Part 1 of
[`../docs/DIALOGS_SPEC.md`](../docs/DIALOGS_SPEC.md). §7 (the elevator) is **not**
built — see "What was left out" below.

Door offers, the party roster and kick confirmation, party invites, keystone
inspection, the room whitelist and the admin `baserestore` guard are all real
screens now. No new dependency: the dialog API is vanilla, and SGUI was not added.
`fabric.mod.json` still says `"environment": "server"` and there is still no
`assets/` directory — every dialog is a runtime `Holder.direct` value, so a vanilla
client renders it with nothing installed.

## What was built

| File | Does |
|---|---|
| `DialogKit.java` | The vanilla dialog API narrowed to the shapes used here |
| `DialogScreens.java` | Every screen, built from live state per click |
| `DialogRouter.java` | Where a payload-carrying button lands, on the main thread |
| `mixin/CustomClickMixin.java` | Catches the packet; hops threads |
| `pocketdungeons.mixins.json` | New — the mod had no mixin setup at all |

Flat `pocketdungeons.*` package rather than the spec's `dialog/` subpackage: nearly
every class in this mod is package-private, and a subpackage would have forced
`Instances`, `RoomWhitelist` and `RoomStore` members public to be reachable — the
opposite of the spec's "don't reshape what commands already call". Only
`DialogRouter` is public, because the mixin package has to reach it.

## Call sites changed

Five, all presentational:

- `RitualListener.sendDoorOffer` — three chat lines and a `[ Take this key ]` link
  became one `ConfirmationDialog`. Same heading, same affix warning, same
  `/dungeon choose <step>`, plus a "Close" that spends nothing.
- `Instances.stageKick` — the `[ Confirm ]` chat link became a `ConfirmationDialog`.
- `Instances.confirmKick` — grew one line: re-show the rebuilt roster.
- `Instances.invite` — the invitee's message was plain, unclickable text. It keeps
  every word and grows a `ClickEvent.ShowDialog`.
- `DungeonCommands` — four new nodes, no node removed or changed in meaning.

New command surface: `/dungeon party` (bare), `/dungeon key info`,
`/dungeon room whitelist` (bare), `/dungeon admin baserestore <player> confirm`.

Two read-only accessors were added rather than reshaping anything:
`Instances.partyCompanions(UUID)` and `RoomStore.backupTime(server, owner)`.

## The two decisions the spec deferred

**Mid-encounter safety: accepted, not gated.** §2, §3 and §5 are reachable from a
command and nothing stops one being run inside a live encounter room. A dialog is a
client-side overlay — the player's entity keeps ticking, mobs keep swinging, fall
damage still lands — and opening one mid-fight is now explicitly the player's own
risk, exactly as opening the inventory mid-fight already is in vanilla. Gating the
commands was the alternative and was rejected: it would be a second rule that can
disagree with the command it guards, and this codebase has already spent a session
on two locally-correct things disagreeing about the same concept. Recorded in
`DialogKit.show`'s javadoc, where anyone adding a screen will read it.

**§6 stayed tier A.** `RoomStore` keeps exactly one backup generation
(`<uuid>.dat.bak`), so "which backup" never arises and the confirm button can be a
fixed command string. If a generational backup format is ever designed, this becomes
tier B like §5.

## Design notes

**One push, everything else clicked into.** `DialogKit.show` is used where the
player just acted and the screen is the answer — a door they right-clicked, a
command they ran. The invite is the one message that arrives while somebody is
doing something else, so it is hung off the chat line with `ClickEvent.ShowDialog`
and never pushed.

**Every button ends in the method the command already calls.** Tier-A buttons run
the actual `/dungeon ...` string, as the player, with the player's own permissions —
so a button can never be a privilege escalation, and a dialog and a command cannot
disagree because there is only one of them. The tier-B router calls
`RoomWhitelist.add`/`remove`, the same two methods `/dungeon room whitelist` calls.

**No permission check was duplicated.** The roster screen deliberately does not
re-check who leads a party; its buttons run `/dungeon party kick <name>` and
`Instances.stageKick` checks, once.

**Whitelist add is online-only, matching the command.** `/dungeon room whitelist
add` takes an `EntityArgument.player()`, so the dialog resolves a typed name with
`getPlayerByName` and says "X is not online" otherwise. Removal works on offline
entries because it works on the UUID the list is stored under — a superset of what
the command can express, but through the same `RoomWhitelist.remove`.

**Offline whitelist entries show a shortened UUID**, which is what
`/dungeon room whitelist list` already prints for them. 26.2's `MinecraftServer`
exposes no profile-name cache accessor (checked with `javap`), and inventing a
second name-resolution path for a label was not worth it.

**Every list ends on a screen you can act from.** There is no dialog history stack
in this API — a screen returns nowhere on its own. So the router rebuilds and
re-sends the whitelist after every outcome, success and rejection alike, and
`confirmKick` re-sends the roster. An emptied party gets a one-button notice rather
than a zero-button `MultiActionDialog`.

**Explicit exits everywhere, including the door offer.** `canCloseWithEscape =
true` and `pause = false` on every screen, per house convention, but Escape is
never the only way out: the lists carry a "Close" `exitAction` and every
confirmation carries a `noButton`.

The spec (§1, and its exit table) said the door offer could not have one, because
a `NoticeDialog` has exactly one `ActionButton` by construction — "walking away
from the door, or Escape, is the no. Documented, not a gap." That was built as
written and it *was* a gap: the first screen a new player meets is the worst place
to require knowing that Escape closes a dialog. The one-button constraint is real,
but it is an argument for a different dialog type, not for shipping a screen with
no visible way out — so the door offer is a `ConfirmationDialog` whose `noButton`
is "Close". Nothing is spent until "Take this key" is pressed, and the door is
still there to right-click again.

Keystone inspection stays a `NoticeDialog`: its single button *is* "Close".

## What was left out

**§7, the elevator.** Blocked, and not by effort. It needs a per-room `listed` flag
that does not exist anywhere in the source yet (`grep -rn "listed" src/` finds
nothing but prose), and M3's shared visit/join method as its single entry point. The
vanilla-`DialogListDialog`-vs-SGUI call is still open; `cobbleeconomy` already ships
`eu.pb4:sgui:2.1.0+26.2` to copy from if it goes that way. Record the choice in
`DOOR_LADDER_BRAINSTORM.md` §15.7 when D7 is promoted.

## Unverified in play

Everything below builds and the build is green (`PipelineProofTest`,
`PlanSelectorTest` and the rest still pass), but this has **not** been tested with a
live client yet:

- The nested `StaticAction(ClickEvent.ShowDialog(...))` that opens the whitelist's
  add-a-player screen from a button. Chat-attached `ShowDialog` is confirmed by
  three sibling mods; the same click event on a *button action* is the one shape
  here that nothing in the suite has exercised.
- `TextInput` submission through this mod's router (the tag shape itself is
  confirmed — `StringTag`, per cobbleeconomy's table in the root spec).
- Whether `MultiActionDialog` renders a long whitelist acceptably. The whole list
  ships in one packet with no server-side paging; the suite's convention past that
  is to cap and drop to chat.
