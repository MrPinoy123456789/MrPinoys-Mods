# M20 - Visiting rework: lobby directory replaces calling card - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives in
`pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `A:\MrPinoys Mods\CLAUDE.md`: the punctuation rule. It is enforced, and it
   applies to every line you write, including javadoc and commit messages.
2. `pocketdungeons/docs/ROOM_UX_PLAN.md`'s `## M20: Visiting rework`
   section: **the authoritative scope.** Goal, dependencies, scope, touch
   points, done-when, and verification notes all live there. This handoff
   does not repeat them.
3. `pocketdungeons/docs/D3_PROGRESSION_PLAN.md`'s "Implementation context"
   section (read once; it is not milestone-specific): toolchain, commands,
   the one-mixin budget, conventions, and the resource-directory layout.
4. `pocketdungeons/docs/DISCOVERIES.md`: verified 26.2 API findings. Do not
   rediscover these the hard way.
5. `pocketdungeons/docs/DOOR_LADDER_BRAINSTORM.md` section 8: the design
   rationale for the visiting rework.
6. `pocketdungeons/plans/COMPLETED-MILESTONES.md`: what M0-M17 actually
   built, for context on what this milestone extends and deletes.
7. `pocketdungeons/docs/DIALOGS_SPEC.md` section 7: the
   `DialogListDialog`-vs-SGUI pagination decision, which is the one open
   implementation choice for the lobby browser.

## Before you start: confirm the dependency is actually done

**Hard dependency on M18.** Check `plans/COMPLETED-MILESTONES.md` for an
M18 entry, and confirm the wall lodestone exists. The lobby browser is
invoked from a right-click on the wall lodestone (or from the menu in
M21, if M21 has landed). Without M18's wall lodestone, there is no
terminal surface to right-click.

Also confirm M3's `CallingCard.java` and `VisitService` exist, since
this milestone deletes the first and reuses the second.

## Goal, in one line

The calling card is replaced by a lobby directory: a `MultiActionDialog`
listing every online player whose room is publicly listed, with room
name and occupancy. A host-set `publicListed` boolean replaces the
hand-traded compass. Privacy is a toggle, not a token.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
   (POSIX form for Git Bash: `/c/Users/Kriss/.gradle/...`). Checking a method
   exists is not the same as checking what it does. This is trap 1 in
   `DISCOVERIES.md` and it has shipped three bugs.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   items, no custom sounds. Everything is vanilla blocks, vanilla items,
   vanilla sound events, and server-side dialogs.
3. **No em dashes and no double hyphens as punctuation**, anywhere a person
   reads: chat, dialogs, item lore, command output, log lines, markdown,
   javadoc, commit messages. Use `:`, `;`, `,`, `()`, or two sentences.
   Command-line flags and code operators (`i--`) are not punctuation and stay
   as they are. Do not mass rewrite existing violations; they stay until that
   line is edited for another reason. See `A:\MrPinoys Mods\CLAUDE.md`.
4. **`./gradlew build` green after every commit**, not just at the end.
5. **The one-mixin budget is exactly one**, `mixin/CustomClickMixin`. If this
   milestone seems to need a second mixin, say so in your report and exhaust
   the Fabric-event or datapack-recipe route first (trap 9, and traps 14/15
   in `DISCOVERIES.md` are a worked example of that search paying off).
6. **Status lives in `plans/COMPLETED-MILESTONES.md` and
   `docs/LIVE_TEST_PASS.md` now, not a `PROGRESS.md` file.** That file and the
   `handoffs/` folder were retired once M0-M9 went code-complete. Do not
   recreate either.
7. **One commit per logical change, message explains why, not just what.**
8. **`Codec` migration discipline:** a superseded `DungeonLog.Entry` field is
   marked superseded in its javadoc and its codec field kept, never deleted on
   the first pass. See "Per-player persistent state" in the plan's
   implementation-context section.

## Start here

- **Read `DIALOGS_SPEC.md` section 7 first.** The lobby browser is a
  `MultiActionDialog` with one button per public room. If the room
  count exceeds the dialog's button limit, the pagination decision
  (`DialogListDialog` vs SGUI) must be settled. The plan leaves this
  open; the spec has the analysis.
- **Study `DialogScreens.partyRoster` before writing
  `lobbyBrowser`.** The party roster is the existing
  `MultiActionDialog` pattern in this codebase: build a list of
  buttons, each carrying a player UUID in its payload, send it as a
  dialog. The lobby browser is the same shape with a different data
  source.
- **`publicListed` and `roomName` are new `DungeonLog.Entry` fields.**
  Both persisted via `optionalFieldOf` with safe defaults (`false` and
  empty string), same shape as `completedThemes`. The codec migration
  is trivial; existing saves simply get the defaults.
- **`Instances.visit` is the existing visit call.** M3 built it; this
  milestone changes how it is invoked (dialog button instead of card
  use-on-lodestone) but not what it does. `VisitService
  .createVisitInstance` is unchanged.
- **Room naming uses a `TextInput` dialog**, the same pattern as the
  existing whitelist-name dialog. The host types a name, it is saved
  to `DungeonLog.Entry.roomName`, and the lobby browser displays it.
  If M21 has not landed, room naming and the public/private toggle are
  commands (`/dungeon room name <text>`, `/dungeon room public`,
  `/dungeon room private`). If M21 has landed, they are menu options.

## Detailed implementation plan

Build in this order. Each step is one commit. Run `./gradlew build`
after each.

### Step 1: `publicListed` and `roomName` on `DungeonLog.Entry`

`DungeonLog.Entry` is a record (line 94) with `ENTRY_CODEC` (line 119)
using `RecordCodecBuilder` and `optionalFieldOf` for backwards
compatibility. Follow the same shape as `completedThemes`.

1. Add two components to the `Entry` record:
   - `boolean publicListed` (default `false`)
   - `String roomName` (default `""`)
   Add them to the record header, the `NONE` constant (line 107), and
   every call site that constructs `Entry` (the `recordCompletion`,
   `setKeystone`, etc. methods). The compiler will list every site.
2. In `ENTRY_CODEC` (line 119), add:
   - `optionalFieldOf("public_listed", false).forGetter(Entry::publicListed)`
   - `optionalFieldOf("room_name", "").forGetter(Entry::roomName)`
   Existing saves simply get the defaults; no migration needed.
3. Add accessor/mutator methods on `DungeonLog`:
   - `setPublicListed(UUID, boolean)`
   - `setRoomName(UUID, String)`
   These update the `Entry` in the live map and mark the log dirty.
   Follow the pattern of `setKeystone` / `recordTheme`.

### Step 2: `lobbyBrowser` dialog in `DialogScreens`

A `MultiActionDialog` listing every online player whose
`publicListed` is true, with room name and occupancy. Same pattern as
`DialogScreens.partyRoster` (line 133): build a list of buttons, each
carrying the owner UUID in its payload, send via `DialogKit.list`.

1. Add `lobbyBrowser(MinecraftServer server)` to `DialogScreens`:
   - Iterate `server.getPlayerList()`. For each online player, read
     `DungeonLog.get(player.getUUID())`. Skip if `!publicListed`.
   - For each listed player, build a button. The label is the room
     name (or the player's name if `roomName` is empty) plus an
     occupancy hint. The body line shows the owner's player name and
     live status (`open`, `run in progress`, `away`) read from the
     same logic `VisitService.findOwnedLiveRoom` /
     `InstanceRegistry.byMember` uses.
   - Each button's payload is a `CompoundTag` carrying
     `KEY_TARGET = ownerUuid.toString()` and `KEY_OWNER =
     clickerUuid.toString()` (the same keys the whitelist dialog uses,
     so `DialogRouter`'s owner-check works).
   - The button action id is a new constant
     `ACTION_VISIT_ROOM = "pd_visit_room"`.
   - If no rooms are listed, show a `NoticeDialog` with "No public
     rooms right now." instead of an empty list.
2. If the room count exceeds `MultiActionDialog`'s button limit, read
   `DIALOGS_SPEC.md` section 7 (lines 394 to 464) for the pagination
   decision. The spec recommends `DialogListDialog` for small lists
   or `eu.pb4:sgui` paginated chests for large ones. Start with
   `MultiActionDialog`; only switch if the count actually overflows
   in testing. If pagination is needed, add next/prev buttons
   carrying an offset in the payload.

### Step 3: `visitRoom` dispatch in `DialogRouter`

Add a case to the dispatch switch in `DialogRouter.handle` (line 58):

1. Add `ACTION_VISIT_ROOM` to the switch. Parse `KEY_TARGET` as a UUID
   from the payload. Verify `KEY_OWNER` equals the clicking player's
   UUID (the same check the whitelist actions do).
2. Call `VisitService.visit(player, targetUuid)`. This is the existing
   visit call (line 38); only the invocation path changes (dialog
   button instead of card use-on-lodestone).
3. If `visit` fails (player not found, not online, self-visit, already
   in a dungeon), `VisitService.visit` already sends a chat message.
   Re-show the lobby browser with a fresh `lobbyBrowser(server)` call
   so the player sees the updated list (a stale click on a player who
   logged out re-shows the directory with a reason line).
4. Do not add a `reshow` loop for the browser itself; it is a
   one-shot dialog. Each open is a fresh `lobbyBrowser` call.

### Step 4: Room management commands in `DungeonCommands`

Add three commands to the `/dungeon room` subtree (lines 118 to 139):

1. `/dungeon room public`: calls `DungeonLog.setPublicListed(owner,
   true)`. Sends a chat confirmation: "Your room is now listed in the
   lobby directory."
2. `/dungeon room private`: calls `DungeonLog.setPublicListed(owner,
   false)`. Sends: "Your room is no longer listed."
3. `/dungeon room name <text>`: a string argument, calls
   `DungeonLog.setRoomName(owner, text)`. Sends: "Room name set to
   <text>." Cap the length (16 chars, same as the whitelist-name
   `TextInput`).
4. These are player-only, not op-gated (same as `/dungeon room card`
   is today). Register them with `.then(Commands.literal("public")`,
   etc.
5. If M21 has landed by the time this milestone ships, the menu's
   "Manage Room" option also calls `setPublicListed` / `setRoomName`.
   The commands stay as power-user shortcuts either way.

### Step 5: Wire the lobby browser invocation

The browser needs an entry point before M21's menu lands. Add a
transient branch in `RitualListener.onUseBlock`:

1. After the card branch is removed (step 6) and before the keystone
   branch (line 183), add: if the clicked block is the wall lodestone
   (`Blocks.LODESTONE` at the wall-lodestone position for the
   player's room) and the held item is not a keystone, open the lobby
   browser: `DialogKit.show(player, DialogScreens.lobbyBrowser(server))`.
   Return `SUCCESS_SERVER`.
2. This branch is temporary. M21 replaces it with the full
   `lodestoneMenu`, whose "Browse Lobbies" option opens the same
   `lobbyBrowser` dialog. Mark the branch with a comment:
   `// M21: replace this with lodestoneMenu`.
3. The keystone branch (line 183) stays ahead of this branch so a
   keystone right-click still starts a dungeon, not the browser. Order
   in `onUseBlock`: selector door, lever, engine, keystone, then
   lodestone-browser.

### Step 6: Delete the calling card

1. Delete `CallingCard.java` entirely. Before deleting, grep for every
   reference to `CallingCard` across the mod. Known references:
   - `RitualListener` card branch (lines 173 to 181): the
     `CallingCard.ownerOf(held)` test and `VisitService.visit(...)`
     call. Remove the whole branch.
   - `DungeonCommands.roomCard` (lines 558 to 563): the
     `/dungeon room card` command and its `Payout.deliver(owner,
     CallingCard.mint(...))` delivery. Remove the command
     registration (line 120) and the method.
   - `PocketDungeonsConfig.callingCardItem()` (line 474): the config
     accessor. Remove the method and the `callingCardItem` field
     (line 70).
   - `CallingCard.CARD_ITEM` (the `ConfiguredItem`): remove its
     warm-up registration in `RitualListener.register` (line 51) if
     it is registered there.
2. If the grep finds other references (a loot table, a command
   suggestion, a doc comment), update or remove them before the
   class is deleted.
3. `VisitService.visit` (line 38) stays. `VisitService` itself stays.
   Only the card-based invocation path is removed.

### Step 7: Occupancy and live-status read

The lobby browser's body line per room needs live status. Build a
helper, e.g. `Instances.roomStatus(UUID owner)` or
`VisitService.statusOf(UUID owner)`:

1. Check `InstanceRegistry.byMember` for the owner: if the owner is in
   an active keystone run, status is "run in progress".
2. Check `VisitService.findOwnedLiveRoom(owner)`: if the owner is
   standing in their own live room, status is "open".
3. Otherwise, status is "away" (a visit creates a read-only copy via
   `createVisitInstance`).
4. Occupancy count: the number of members in the owner's
   `InstanceRecord.members` (visitors plus owner), or 0 if no live
   instance. This is the same data `VisitService.visit` uses to
   decide between a live room and a fresh visit copy.

### Verification

- `./gradlew build` green after each step.
- Headless: `publicListed` and `roomName` codec round trip (a test
  that encodes an `Entry` with each value and decodes it back, plus
  one that decodes an old save missing the fields and gets the
  defaults). Lobby browser button construction logic (pure Java test
  against a mock player list: listed players appear, unlisted do not,
  empty list shows the notice).
- Live: `/dungeon room public` then open the browser from another
  player and see the room; click the room button and teleport in;
  `/dungeon room private` and confirm the room disappears; name a
  room and see the name in the browser; confirm `/dungeon room card`
  is gone and the calling card item no longer exists. Record in
  `docs/LIVE_TEST_PASS.md`.

## What you must not do

- **Do not add a browse-all-rooms view.** The lobby directory lists
  only rooms whose owners have set `publicListed` to true. A player
  who has not opted in does not appear. This is the same privacy
  principle as the calling card's "you have to be given one," just
  with a toggle instead of a token.
- **Do not change `RoomWhitelist` or `RoomProtection`.** The permission
  mask is unchanged. A public-listed room still has the same visitor
  restrictions (cannot break, cannot open lootable containers, can use
  stations and ender chests). Public listing is visibility, not
  permission.
- **Do not delete `VisitService` or `Instances.visit`.** The visit
  mechanism is reused; only the invocation path changes (dialog button
  instead of card use-on-lodestone).

## What this milestone deletes

- **`CallingCard.java`**: the entire class. The `mint`, `isCard`,
  `warmUp`, and all helper methods. No longer referenced after the
  card branch in `RitualListener` is removed.
- **The card branch in `RitualListener.onUseBlock`**: the
  `CallingCard.isCard` positive test and its visit-invocation logic
  (lines ~132-140 in the current source). Removed entirely.
- **`PocketDungeonsConfig.callingCardItem`**: the config field for the
  calling card's item type. No longer read after the class is deleted.
- **`/dungeon room card` command**: the command that mints a calling
  card. Removed from `DungeonCommands`.
- **The `Payout.deliver(owner, CallingCard.mint(...))` delivery** in
  `DungeonCommands`: the code that gives the player a calling card.
  Removed with the command.

**What stays:**

- `Instances.visit`: the visit call, invoked from a dialog button
  instead of a card.
- `VisitService.createVisitInstance`: unchanged.
- `RoomWhitelist`: unchanged.
- `RoomProtection`: unchanged.

## Verification bar

**Done when** (from the plan):

1. A host sets their room public with `/dungeon room public`.
2. Another player opens the lobby browser and sees the host's room
   listed with name and occupancy.
3. Clicking the room button teleports the visitor into the host's room
   (or a read-only copy if the host is away).
4. The calling card no longer exists; `/dungeon room card` is gone.
5. A private room does not appear in the browser.

**Live-only:** the lobby browser dialog, the visit teleport, the
public/private toggle, the room naming dialog. Record in
`docs/LIVE_TEST_PASS.md`.

**Headless-verifiable:** `publicListed` and `roomName` codec round
trip, lobby browser button construction logic (pure Java test against
a mock player list).

## Doc updates you owe on completion

1. `plans/COMPLETED-MILESTONES.md`: add this milestone's summary in its
   place, in the same architectural-summary style as M0-M17's entries.
2. `docs/LIVE_TEST_PASS.md`: add a numbered section for whatever in this
   milestone is client-interactive and cannot be verified headless.
3. `docs/ROADMAP.md`: this milestone's entry, in order. No checkboxes; the
   roadmap carries order, not status.
4. `docs/ROOM_UX_PLAN.md`: if you diverge from the plan's scope for this
   milestone, **fix the plan first and say so.** Never silently diverge.
5. Any `DungeonLog.Entry` field this milestone supersedes gets a javadoc
   note saying so; its codec field stays until a migration confirms no
   live save carries the old data.
6. Rename this file `M20-handoff-completed.md` once the milestone is
   actually landed and its `COMPLETED-MILESTONES.md` entry exists.

## If you get stuck

- **The lobby browser has too many rooms for one dialog.** Read
  `DIALOGS_SPEC.md` section 7 for the pagination decision. If
  `MultiActionDialog` has a button limit, switch to `DialogListDialog`
  or implement simple pagination (next/prev buttons carrying an offset
  in the payload).
- **Deleting `CallingCard.java` causes compile errors in unexpected
  places.** Search for all references to `CallingCard` before deleting
  the file. The card branch in `RitualListener`, the command in
  `DungeonCommands`, and any config field are the known references.
  If something else references it, that reference must be updated or
  removed before the class is deleted.
- **`publicListed` defaults to false, so no rooms appear in the
  browser on a fresh server.** This is correct. The host must opt in.
  If you need a test, set `publicListed` to true on a test player's
  entry before opening the browser.
