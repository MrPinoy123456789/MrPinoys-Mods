# Pocket Dungeons — vanilla dialogs spec

> **Status: spec only. Nothing here is built.** No code, no `DialogKit`, no
> mixin. This is a menu design pass — where a dialog would help, what shape it
> takes, what it calls — written so the choice of *when* to build any of it
> stays separate from *what* it should look like when someone does.
>
> Nothing here is blocked on the roadmap milestones except where a section
> says so explicitly. Most of what follows retrofits UI onto commands that
> already ship today.

**Read [`docs/DIALOGS_SPEC.md`](../docs/DIALOGS_SPEC.md) Part 0 first.** It is
the verified API reference (`net.minecraft.server.dialog`, the send/receive
path, the mixin shape) built by disassembling the real 26.2 jar, and it
applies unchanged to every mod in this workspace. Do not re-derive it here.
Three sibling mods have already shipped against it —
[`quizengine/DIALOGS.md`](../quizengine/DIALOGS.md),
[`cobbleeconomy/DIALOGS.md`](../cobbleeconomy/DIALOGS.md),
[`smalltalk/SPEC.md`](../smalltalk/SPEC.md) §6 — and this spec leans on their
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

### §2. Party kick confirmation (retrofit)

**Today:** `/dungeon party kick <target>` or `kick all` calls
`Instances.stageKick` (`Instances.java:676`), which sends `"Remove <who>
from your party?"` plus a `[ Confirm ]` chat link running
`/dungeon party kickconfirm`.

**Proposed:** replace the two chat lines with one `ConfirmationDialog` —
this is precisely the shape cobbleeconomy adopted `ConfirmationDialog` for,
quoting its own reasoning: *"the two buttons cannot be hit by the same
twitch."* A two-command chat flow has no such protection; a dialog does.

- Body: `PlainMessage("Remove <who> from your party?")`.
- `yesButton`: label `"Confirm"`, `StaticAction(RunCommand("/dungeon party
  kickconfirm"))` → `Instances.confirmKick` (`Instances.java:703`), unchanged.
- `noButton`: label `"Cancel"`, no action (closes only).

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
(`plans/M2-the-room.md` T2.2 specifies the permission mask but not how an
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
  {"target": <uuid>})`) plus one `TextInput`-bearing entry point — since
  `MultiActionDialog` has no input slot of its own, the "add a player" case
  needs a **second** screen: a `NoticeDialog` with one `TextInput` (player
  name, `maxLength` ~16) reached via an `"Add..."` button on the first
  screen, submitting `CustomAll("pocketdungeons:room_whitelist_add",
  {"name": <text>})`.
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

### §6. Admin `baserestore` confirmation — ties to M2 T2.1

`plans/M2-the-room.md` now requires an `admin baserestore` command
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
plumbing beyond what §1–§6 need. Filed as D7 in `plans/M8-deferred.md`;
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
`plans/M8-deferred.md` when it's made, the way every other design call in
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
