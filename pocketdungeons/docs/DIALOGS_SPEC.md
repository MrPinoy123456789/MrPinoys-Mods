# Pocket Dungeons — vanilla dialogs spec

> Freshness note 2026-10-10: this is the original spec. What was built, and the menu as it stands now (Home menu,
> Manage Room, Inspect Compass, Manage Party, View Lobbies), is `docs/reference/DIALOGS.md`. The door offer in section 1
> was replaced by the Astrolabe Room.

> **Status: §1–§7 are all built, none in exactly the form described below.**
> See [`DIALOGS.md`](reference/DIALOGS.md) for what shipped, the decisions
> this document deferred and how they were settled, and what has not been
> tested in play yet. §7 (the elevator/room directory) shipped as the lobby
> directory: `DungeonLog.Entry.publicListed` and `VisitService.visit` both
> exist and are wired end to end, not blocked as this section originally
> assumed. §1 (a per-door notice dialog) did not ship in the form described
> below: M19 replaced the whole surface with the physical `DungeonScreen`
> text display in the player's room, and `sendDoorOffer` no longer exists
> anywhere in the tree.
>
> What follows is the original design pass, kept as written: where a dialog
> would help, what shape it takes, what it calls. Two of its assumptions were
> already out of date by the time it was built — M2's whitelist commands and
> `admin baserestore` both ship today, so §5 and §6 were retrofits onto
> existing commands rather than the command-and-dialog co-designs they describe.
>
> Nothing here is blocked on the roadmap milestones except where a section
> says so explicitly. Most of what follows retrofits UI onto commands that
> already ship today.

**Read Part 0 below first.** It is
the verified API reference (`net.minecraft.server.dialog`, the send/receive
path, the mixin shape) built by disassembling the real 26.2 jar, and it
applies unchanged to every mod in this workspace. Do not re-derive it here.
Three sibling mods have already shipped against it —
[`quizengine/DIALOGS.md`](../../quizengine/DIALOGS.md),
[`cobbleeconomy/DIALOGS.md`](../../cobbleeconomy/DIALOGS.md),
[`smalltalk/SPEC.md`](../../smalltalk/SPEC.md) §6 — and this spec leans on their
confirmed payload shapes and established patterns rather than re-guessing.

## Why this mod is a good fit

Same argument as every sibling: `Holder.direct(Dialog)` sends a runtime value
with no registry entry and no datapack JSON. A vanilla client renders it with
nothing installed — no resourcepack, no client mod, no synced registry entry.
`fabric.mod.json`'s `"environment": "server"` and the absence of an `assets/`
directory (`VISION.md` §9, non-negotiable) stay exactly as true as they are
today. This is the one UI mechanism this mod can adopt without touching either
constraint.

## The governing principle

Same rule every sibling worked under: **dialogs are presentation, not a
second set of rules.** Every dialog button in this spec ends by calling the
exact same method the equivalent chat click or command already calls —
`Instances.chooseOffer`, `Instances.confirmKick`, `Instances.join`. If a
dialog and a command could ever disagree about what happens, that is a bug in
whichever one was built second, not a matter of picking which wins.

### Hard constraints

- **Do not remove any `/dungeon ...` command node.** Every dialog here is a
  second door onto something the chat interface already does, per the exact
  discipline `quizengine/DIALOGS.md` states: *"dialogs are additive, never a
  replacement."* Console and any player who prefers typing keep working
  unchanged.
- **Do not touch `Instances`, `Keystones`, `DungeonLog`, or any other
  server-state method signature to accommodate a dialog.** If a dialog needs
  data a method doesn't return today, add a read-only overload; don't
  reshape what commands already call.
- **The three doors stay three doors, walked to individually — see §1.**
  Nothing in this spec centralizes the selector room into one menu. `VISION.md`
  §1's hook is *"your room has three doors... you spend your keystone on a
  door and walk through"* — that physical walk is the mechanic, not a UI
  limitation waiting to be fixed.

### Movement and safety while a dialog is open

The client cannot send movement input while any `Screen` has input focus —
this is inherent vanilla client behaviour for every screen (inventory, chat,
dialogs included), not something a dialog opts into. That covers "can a
player wander off mid-menu": no, their WASD is captured by the dialog until
it closes.

**It does not cover combat or fall damage, and no sibling mod has had to
think about this.** `quizengine`, `cobbleeconomy`, and `smalltalk` all set
`pause = false` — the house convention this mod should follow too — but none
of them run combat content, so nobody has verified what `pause` actually
does server-side. What's certain regardless of that flag: **the player's
entity keeps ticking.** A mob mid-swing, existing fall velocity, drowning —
none of that stops because a dialog opened. A dialog is a client-side
overlay, not a server-side pause.

⚠ **This mod is the first one in the suite where that matters.** None of §1–§7
are gated to a location that's guaranteed mob-free:

- §1 (door offer) opens only in the selector room, which carries no chest
  and no spawn points by construction — safe.
- §2 (kick), §3 (invite), §5 (whitelist), and §7 (elevator) are all reachable
  from a command, and nothing today stops a command from being run mid-run,
  inside a live encounter room with an active trial spawner.

**Decision needed, not made here:** either accept that opening one of those
four mid-encounter is the player's own risk (same as opening an inventory
screen mid-fight already is, in vanilla), or gate the triggering commands to
refuse while `Instances` reports the player inside an active, non-selector
cell. Whoever builds M2/M3/D7 should pick one and say so in that milestone's
own notes — don't let it default silently either way.

### Exit and escape — no dead ends, on every screen

Every dialog in §1–§7 sets `canCloseWithEscape = true`. This is the house
convention across the whole suite (`quizengine/DIALOGS.md`: *"Set
`canCloseWithEscape = true` and `pause = false` on every dialog"*) and there
is no reason to diverge from it here.

Escape alone is not enough — a first-time player doesn't necessarily know a
dialog can be escaped, and some expect `E` (inventory close) to work instead,
which it will not for a `Screen` that isn't a container. So, **every
`MultiActionDialog` and `DialogListDialog` in this spec sets an explicit
`exitAction`** — a visible "Cancel" or "Back" button, never relying on
Escape as the only way out:

| Menu | Dialog type | Explicit exit |
|---|---|---|
| §1 door offer | `NoticeDialog` | Can't — a `NoticeDialog` has exactly one `ActionButton` by construction. Walking away from the door, or Escape, is the "no." Documented, not a gap. |
| §2 kick confirm | `ConfirmationDialog` | `noButton`, always present by construction |
| §3 invite accept | `ConfirmationDialog` | `noButton` ("Decline") |
| §4 keystone info | `NoticeDialog` | Same as §1 — the one button is "Close," which *is* the exit |
| §5 whitelist list | `MultiActionDialog` | `exitAction` = "Close" |
| §5 add-player sub-screen | `ConfirmationDialog` + `TextInput` (not `NoticeDialog` — see below) | `noButton` ("Cancel") |
| §6 baserestore confirm | `ConfirmationDialog` | `noButton` ("Cancel") |
| §7 elevator directory | `DialogListDialog` (or SGUI page) | `exitAction` = "Close" (vanilla path) / a `BARRIER` slot (SGUI path, matching `ShopAdminMenu.openList`'s own close slot) |

### Returning to the right screen after an action

**There is no dialog history stack in this API.** A screen never
automatically "returns" anywhere — every "back to the list" the user would
expect is the mod explicitly building and sending a fresh dialog as the last
step of handling a click, not something the engine gives for free.

This is still allowed under quizengine's *"never push a dialog unprompted"*
rule — that rule is about not sending a dialog out of nowhere; re-showing a
rebuilt screen as the direct, synchronous continuation of the click the
player just made is a response, not a push. Tier A buttons do this from
inside the command handler the `RunCommand` invokes (e.g. `confirmKick` calls
`DialogKit.show(player, rebuiltRoster)` as its last line); Tier B buttons do
it from the end of the router's handler, same idea, one thread hop earlier.

Applied to each menu:

- **§1, §3, §4, §6 are terminal — no "back to a list" applies.** A door
  choice teleports the player, an accepted invite joins them, keystone info
  is read-only, and a baserestore confirm is a one-shot admin action. Each
  ends the interaction by design; don't add a return screen where there was
  never a list to return to.
- **§2 needed a real fix, not just a note — see the rewrite below.** As
  originally spec'd, kick only had a path for a target the leader already
  named on the command line; there was no roster to return to because there
  was never a roster screen. §2 is rewritten below to add one.
- **§5 (whitelist)** — after a remove or an add, re-show the whitelist list
  rebuilt from live state, not a bare success message. Already implied by
  the stale-state guard §5 specifies; now stated as the explicit UX rule.
- **§7 (elevator)** — if a click resolves to a room that's gone stale
  (delisted, or the owner's status changed after the snapshot), re-show the
  rebuilt directory with a short reason, rather than erroring into a closed
  screen with no path back in.

---

## Part 1 — Plumbing

Two tiers, and most of this spec sits in the cheap one.

### Tier A — no new plumbing at all

`sendDoorOffer` (`RitualListener.java:155`) and `stageKick`
(`Instances.java:676`) **already use the exact pattern smalltalk's whole mod
runs on**: a chat `Component` with `withClickEvent(new
ClickEvent.RunCommand(...))`. Wrapping that same click in
`ClickEvent.ShowDialog` instead of firing the command directly needs no
`CustomAll`, no `ServerboundCustomClickActionPacket`, and therefore **no
mixin, no router, no new dependency at all** — the dialog's own button simply
carries a `StaticAction(new ClickEvent.RunCommand(...))`, identical in shape
to what `smalltalk/interaction/DialogScreens.java`'s greeting screen already
does. §2, §3 and §4 below are all this tier.

The one piece of new code Tier A needs is a small `DialogKit` facade — this
mod doesn't have one yet — narrowed to `NoticeDialog` and `ConfirmationDialog`
construction only, modeled directly on
`quizengine/fabric/src/main/java/quizengine/mc/dialog/DialogKit.java`'s
shape. No mixin file, no `pocketdungeons.mixins.json` entry needed for this
tier alone.

### Tier B — needs the full plumbing

§5, §6, and §7 need a round trip: a button press has to tell the server
*which* entry was picked (which player to whitelist-remove, which room to
enter), and that can't be encoded as a fixed `/command` string the way a
fixed 1-of-3 door choice or a yes/no confirm can. This needs:

- `mixin/CustomClickMixin.java` — `@Mixin(ServerCommonPacketListenerImpl.class)`,
  `@Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)`,
  namespace-gated on `pocketdungeons:` only. Copy the shape from
  `quizengine/fabric/src/main/java/quizengine/mc/mixin/CustomClickMixin.java`
  or `cobbleeconomy/fabric/src/main/java/cobbleeconomy/mixin/CustomClickMixin.java`
  — this mod has **no mixin setup at all today** (checked: no `mixin` package,
  no `*.mixins.json`), so this also means creating
  `src/main/resources/pocketdungeons.mixins.json` and adding
  `"mixins": ["pocketdungeons.mixins.json"]` to `fabric.mod.json`.
- `dialog/DialogRouter.java` — dispatch by `Identifier` path, hopping to the
  server thread with `server.execute(...)` before touching any state (this
  hop is **load-bearing, not defensive** — the mixin fires on the netty
  thread; see `quizengine/DIALOGS.md`'s disassembly notes in root spec Part 0).
- Payload shapes are already confirmed empirically by quizengine/cobbleeconomy
  and documented in `docs/DIALOGS_SPEC.md` — `BooleanInput` returns a
  `ByteTag` not a string, `NumberRangeInput` always returns `FloatTag` even
  for whole numbers, input keys overwrite `additions` keys on collision. Don't
  re-derive these; read them off the table there.

---

## Part 2 — The menus

### §1. Door offer (retrofit)

**Today:** walking to a door and right-clicking it calls
`Instances.selectorDoorStep` then `RitualListener.sendDoorOffer`
(`RitualListener.java:155`), which sends two or three chat lines — a heading,
an optional affix warning, and a `[ Take this key ]` link running
`/dungeon choose <step>`.

**Proposed:** the same click opens a `NoticeDialog` (single offer, one
action button) instead of writing chat. Keep it **per-door** — do not build a
combined 3-door picker; see the hard constraint above. Body:

- `PlainMessage`: the heading line (`"<Affix or Oak> Door — Keystone
  [<level>]"`), styled with `offer.affix().colour` the way the chat version
  already is.
- A second `PlainMessage` line for the affix explanation, when the offer
  isn't `NONE` — same two strings `sendDoorOffer` already has.
- One `ActionButton`, label `"Take this key"`, action
  `StaticAction(new ClickEvent.RunCommand("/dungeon choose " + step))` —
  calls `Instances.chooseOffer` exactly as today, via
  `DungeonCommands.choose` (`DungeonCommands.java:221`).

No stale-state guard needed: `chooseOffer` already re-validates against the
live `DungeonLog` pending offer (`Instances.java:939`'s own javadoc notes it's
"validated purely against `DungeonLog`'s pending offer... not against being
in the selector room"), so a dialog opened against a stale offer fails the
same way a stale chat link already does today.

### §2. Party roster + kick confirmation (retrofit, plus a new entry point)

**Today:** `/dungeon party kick <target>` or `kick all` calls
`Instances.stageKick` (`Instances.java:676`), which sends `"Remove <who>
from your party?"` plus a `[ Confirm ]` chat link running
`/dungeon party kickconfirm`. There is no way to *browse* the party today —
a leader must already know and type the name they want to remove. No
existing screen this retrofits onto a list, because there was never a list.

**Proposed, two screens, still Tier A — no CustomAll needed.** The server
already knows every member's name when it builds either screen, so each
button can carry its own literal `RunCommand` string; nothing here needs the
client to send free-form data back.

1. **Roster screen** — new trigger, `/dungeon party` with no arguments (or
   a right-click target, implementer's call). `MultiActionDialog`, one
   `ActionButton` per current party member, label `"Kick <name>"`, action
   `StaticAction(RunCommand("/dungeon party kick <name>"))`. `exitAction` =
   `"Close"`. Gate this screen with **the exact same leader check
   `stageKick` already applies** — don't build a second permission check
   that could disagree with the first; see the governing principle.
2. **Confirmation screen** — unchanged from the original spec below, except
   for what happens after Yes.

- Body: `PlainMessage("Remove <who> from your party?")`.
- `yesButton`: label `"Confirm"`, `StaticAction(RunCommand("/dungeon party
  kickconfirm"))` → `Instances.confirmKick` (`Instances.java:703`), unchanged.
- `noButton`: label `"Cancel"`, no action (closes only).

**After a confirmed kick, `confirmKick`'s handler re-shows the roster
screen**, rebuilt from the party's current member list — this is what
"returning to the right page" means concretely here, and it's the fix the
top-level section above was written to require. **Edge case, decide before
building:** if the kicked member was the last one, don't construct a
zero-button `MultiActionDialog` — show a `NoticeDialog` ("Your party is now
empty.", one "Close" button) instead of either an empty roster or a silent
close, so the leader gets a clear signal the action actually happened.

### §3. Party invite — accept/decline (new UX on an existing flow)

**Today:** `Instances.invite` (`Instances.java:755`) sends the invitee a
plain, **unclickable** chat message: *"<inviter> invites you into their
dungeon. Run /dungeon join <inviter> within two minutes to go in."* Checked —
there is no clickable link here at all today, unlike the door offer and kick
flows.

**Proposed:** attach a `ConfirmationDialog` to that same message via
`ClickEvent.ShowDialog`, following quizengine's *"second front door"*
pattern — the dialog is never pushed, it only exists because the invitee
clicked into it.

- Body: `PlainMessage("<inviter> invites you into their dungeon.")`.
- `yesButton`: label `"Join"`, `StaticAction(RunCommand("/dungeon join
  <inviter>"))` → `Instances.join` (`Instances.java:792`), unchanged.
- `noButton`: label `"Decline"`, no action.

**Stale-state guard already exists for free:** `join` already checks the
invite's expiry and leader match (`Instances.java:803`) and fails cleanly if
the two-minute window passed — a dialog left open past expiry degrades the
same way a stale chat command already does. No new check needed.

### §4. Keystone inspection (new, small)

**Today:** a keystone's level and affix live in its item lore
(`Keystone.java:129`) — a player reads the tooltip. `/dungeon log`
(`DungeonCommands.java:309`) separately prints runs completed, best level,
and longest dungeon cleared, as chat text.

**Proposed, low priority:** a right-click-while-sneaking (or a
`/dungeon key info`, if a chat trigger is preferred over an item-use hook) on
a held keystone opens a `NoticeDialog` combining both — level, affix set
(once M4's stackable affixes ship), and the `log` stats — as `PlainMessage`
lines. One action button, `"Close"`. This is a display-only screen; nothing
it shows isn't already visible via lore + `/dungeon log`, so treat this as a
convenience pass, not a gap-filler.

**Out of scope, don't build:** vault keys and trial keys
(`PocketDungeonsConfig.vaultKeyItem`/a plain `minecraft:trial_key`) already
have their own vanilla UI via the trial-vault block interaction. Nothing here
should intercept that.

### §5. Room whitelist management — ties to M2 T2.2

**Not a retrofit — this proposes both a command surface and its dialog
front end together**, since M2 hasn't shipped a whitelist command yet
(`../plans/COMPLETED-MILESTONES.md` M2 T2.2 specifies the permission mask but not how an
owner edits it). If M2 lands with a chat-only `/dungeon room whitelist
add/remove <player>` pair, this section is exactly what to layer a dialog
onto next; if M2's implementer wants to build the dialog and the command
together, this is the shape to build.

- Trigger: a `/dungeon room whitelist` command with no arguments, or a
  right-click on the room's own lodestone while sneaking (needs a decision —
  not made here, the lodestone is already heavily overloaded by the ritual
  and calling-card checks in `RitualListener`, see M3 §3.2).
- Screen: `MultiActionDialog`, one button per current whitelist entry
  (label = player name, action = `CustomAll("pocketdungeons:room_unwhitelist",
  {"target": <uuid>})`) plus an `"Add..."` button opening a **second**
  screen for entry, since `MultiActionDialog` has no input slot of its own.
  That second screen is a **`ConfirmationDialog`** carrying one `TextInput`
  (player name, `maxLength` ~16) in its `CommonDialogData.inputs()` — not a
  `NoticeDialog`, which only has one `ActionButton` and so has no room for
  a "Cancel" alongside "Add." `yesButton` = `"Add"`, submitting
  `CustomAll("pocketdungeons:room_whitelist_add", {"name": <text>})`;
  `noButton` = `"Cancel"`, no action — this is what gives the add screen a
  real exit that isn't only Escape.
- **Stale-state guard required, unlike §1–§3.** A whitelist can change
  between when the dialog opened and when a remove button is clicked (the
  owner could edit it from another session, or the room itself could stop
  existing if a leadership-change purge fired mid-edit — `§7.2`). Carry the
  room's owner UUID in `additions` and have the router re-fetch the live
  whitelist rather than trusting the button's captured state, the same
  discipline quizengine's round-id check applies (`DialogRouter` rejects a
  stale round rather than acting on cached client-side state).
- Resolving a typed name to a UUID is the one piece of new server logic this
  section needs beyond what M2 already specifies — reuse whatever player-name
  resolution `EntityArgument.player()` uses elsewhere in `DungeonCommands`
  rather than inventing a second lookup path.
- **After either action, the router re-shows the whitelist list, rebuilt
  from the now-current whitelist** — not a bare success message. This is
  what makes it a manager rather than a one-shot form.
- `exitAction` = `"Close"` on the list screen. Clicking `"Cancel"` on the
  add-player screen re-shows the rebuilt list, same as a successful add
  does — both are the router's `StaticAction`/`show` call, not two
  different code paths.
- ⚠ **Escape specifically** (as opposed to the `noButton` click) is a
  separate question: `Dialog.onCancel()` (`Optional<Action>`, root API
  reference) looks like the field for "what happens on dismissal," but its
  exact trigger semantics — Escape only, any dismissal, or something
  narrower — aren't pinned down anywhere in this suite's docs or any
  sibling's implementation report. Not load-bearing here, since the
  `noButton` already gives this screen a real exit; confirm with `javap`
  and a live test only if something is meant to specifically distinguish
  "closed via Escape" from "closed via Cancel."

### §6. Admin `baserestore` confirmation — ties to M2 T2.1

`../plans/COMPLETED-MILESTONES.md` M2 now requires an `admin baserestore` command
(added after the Fable-review pass) precisely because a room blob is
player-authored content, not disposable run state. Restoring one **overwrites
whatever is there now** — a `ConfirmationDialog` is the right guard for an
operator running this by hand, same shape as §2:

- Body: `PlainMessage("Restore <owner>'s room from the backup made at
  <timestamp>? This overwrites their current room.")`.
- `yesButton`: `"Restore"` → the restore method M2 builds, unchanged.
- `noButton`: `"Cancel"`.

This is Tier A (fixed command string, `LEVEL_GAMEMASTERS`-gated) **unless**
M2 ends up supporting more than one backup generation per room, in which case
picking *which* backup needs Tier B the same way §5 and §7 do — worth
deciding when M2's backup format is actually designed, not here.

### §7. The elevator — public room/party directory, ties to D7

The big one, and the one item on this page that genuinely needs new
plumbing beyond what §1–§6 need. Filed as D7 in `DOOR_LADDER_BRAINSTORM.md` §15.7;
this section corrects and sharpens that entry's cost estimate now that the
suite's actual dialog mechanism is known — the earlier estimate ("this mod
has never built a `MenuProvider`") was measuring the wrong tool.

**Trigger:** right-click either of the elevator's two iron doors (the
identical unmarked anchor D7 already specifies — see `VISION.md` §4's silence
discipline for why they stay unlabeled).

**The real decision this section exists to force: vanilla `Dialog`, or add
`eu.pb4:sgui`?** Both are established, working patterns in this suite — they
are not a "proven vs. untested" choice, they're a **bounded vs. scalable**
one:

| | `DialogListDialog` (vanilla, no new dependency) | SGUI paginated chest (new dependency, cobbleeconomy already has it) |
|---|---|---|
| Precedent | `smalltalk`'s gift picker — a fresh `DialogListDialog` built from live server state per click | `cobbleeconomy/ShopAdminMenu.openList` (`ShopAdminMenu.java:90–154`) — arrow-button pagination, up to 54 slots/page |
| Scales to | A handful of entries. `cobbleeconomy/DIALOGS_SPEC.md` explicitly rules this construct out for *"a scrollable/searchable catalog of arbitrary size"* — the whole list ships in one packet, no server-side paging | Effectively unbounded |
| Suite's own fallback convention | Cap and drop to chat past the limit — Ballot's settings dialog caps at 8 entries with *"…and N more"* (`docs/DIALOGS_SPEC.md` A2.4) | N/A — this *is* the answer for unbounded |
| New dependency | None | `eu.pb4:sgui`, new to this mod (already in cobbleeconomy) |

**Recommendation, not a decision:** the entire reason D7 exists is §2.1's
bet on population and discovery — a directory that silently truncates past
eight rooms undercuts the one thing this feature is for. Lean toward SGUI +
pagination, copying `ShopAdminMenu.openList`'s shape directly rather than
inventing a new pagination scheme. But this is a real, single-sentence-sized
cost (one new dependency, one new file pattern) that whoever promotes D7
should decide deliberately, not by default — record the choice in
`DOOR_LADDER_BRAINSTORM.md` §15.7 when it's made, the way every other design call in
this repo is recorded rather than left implicit.

**Listing scope — restated from D7, now with a screen to hang it on:** each
slot represents a room whose owner has set `listed: true` (default `false` —
`§3.1.1`'s calling card stays the private channel; this is the second,
opt-in public one, and the two must not be conflated). Icon: the room
owner's player head (`ItemBody`, if the vanilla-Dialog path is chosen) or a
player-head item stack (if SGUI). Label: owner's name plus a live status —
`"open"`, `"run in progress"`, or `"away"` — read the same way M3's `Instances`
visit-routing already has to (owner online + in their room vs. owner online
elsewhere vs. owner offline), not a second status system.

**Clicking an entry calls the exact same method M3's calling card does.**
This is the governing principle from the top of this document, stated
concretely: the elevator and the calling card are two front doors onto one
`Instances` visit/join mechanism, never two parallel code paths that could
disagree about who's allowed in or which instance they land in. If M3 ships
first, §7's callback is a one-line call into whatever M3 built; if D7 is ever
promoted before M3, build the shared method first and have both front doors
call it, rather than letting the elevator invent its own room-entry logic.

**Stale-state guard, mandatory, same shape as §5:** the list a player is
looking at is a snapshot. A room could stop being listed, or an owner could
come home, between the dialog opening and a slot being clicked. Carry the
target owner's UUID in the click payload and re-resolve routing at click
time against live state — never trust what the snapshot said was true.

**On a stale click, re-show the rebuilt directory rather than erroring into
a closed screen.** A room that vanished from the list between snapshot and
click is exactly the case the guard above exists to catch; when it fires,
the router's response is a fresh `DialogListDialog`/page build plus a short
reason line (`"That room isn't open any more."`), not a bare failure with no
way back in. Same principle as §2 and §5 — a rejected click still ends on a
screen the player can act from, never on nothing.

**Blocked on:** M2 (rooms must persist and carry a `listed` flag) and M3 (the
visit/join method this reuses must exist). Not scheduled — see D7's own entry
for the promotion rule.

---

## What this spec deliberately does not cover

- **Affix selection UI (M4).** Affixes are seeded from the keystone
  (`MYTHIC_PLUS_RECONCILIATION.md` §4), never chosen by the player except the
  one elective slot already covered by §1's door offer. There is no second
  menu to design here.
- **Recipe/theme discovery (M7 T7.3).** The plan is explicit that this stays
  a plain list with **no hint system** — a `NoticeDialog` listing completed
  themes would be a legitimate, low-value chat-to-dialog swap for `/dungeon
  log`-style output, but building one is not worth a dedicated section here.
  If §4's keystone-inspection dialog ships, folding completed-themes into it
  is a two-line addition, not a new menu.
- **Wolves/Feral (M5), supply tables (M6).** Nothing about either benefits
  from a menu; both are entirely world-state and loot-table work.
