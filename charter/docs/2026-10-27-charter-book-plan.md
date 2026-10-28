# Build plan: the Charter of Rights and Freedoms

Status: written 2026-10-27, not started. The file to build from is
`docs/2026-10-27-charter-book-implementation.md`, which details every milestone
and wins where the two differ. Builds what
`docs/2026-10-27-charter-book-proposal.md` approved (DESIGN.md §23, 2026-10-27).
Conventions are `BUILDING.md`; branch off `feature/charter-build`.

Eight milestones, B0 to B7, plus S1 (seats for every office, added 2026-10-27; `BecomeCommune` below is replaced by it, see the implementation plan). **B0 is a spike and gates everything:** if the book
cannot look and click right on a vanilla client, the rest waits for a new plan.

## What the book is

**The book is a shortcut, not a possession.** Using it does exactly what typing
`/charter` does: it opens the Charter menu for whoever is holding it. `/charter`
with no arguments becomes the dialog menu (today it prints the faction page in
chat). Once a player knows the command, they never need the book.

So the book carries no owner and nothing of its own: it drops on death like any
item, and a player who picks up someone else's book simply sees their own
Charter. A player who joins without one is handed a book; there is no risk in more
books (owner, 2026-10-27).

## One choice this plan makes (the owner can overrule)

| # | Question | This plan assumes | Why |
|---|---|---|---|
| P1 | A new faction's first keep costs 64 diamonds from its treasury (`keepPriceDiamonds`), but a faction founded by placing a Nexus has an empty treasury | Placing a founding Nexus takes the 64 diamonds **from the founder's inventory**, refused with a plain line if they carry fewer | Paying with what you hold is the most vanilla answer, and the price stays where it is |

## Milestones

| # | What | Depends on | Size |
|---|---|---|---|
| **B0** | Spike: the book item, one dialog, one click, the crafting route | nothing | small, **serial, first** |
| **B1** | Core: the standing model (Rights and Freedoms) | B0 passed | medium |
| **B2** | Core: founding at a Nexus, and becoming a Commune | B0 passed | medium |
| **B3** | Core: asking to join | B0 passed | small |
| **B4** | Fabric: the book in play, the dialog kit, the click Mixin | B0 | medium |
| **B5** | Fabric: the pages for a member | B1, B4 | medium |
| **B6** | Fabric: the Nexus recipe, founding by placement, the pages for a player with no faction | B2, B3, B4 | large |
| **B7** | The tutorial, docs and live checks | B5, B6 | small, **serial, last** |

B1, B2 and B3 are core only and touch different packages, so they can run in
parallel after B0. B5 and B6 both add pages; they share `CharterPages` and the
lang file, so run them one after the other or give each its own page class.

---

## B0: spike (do this first)

**Goal:** prove four things on a real vanilla 26.2 client before building on them.

1. **The look.** An item based on `minecraft:paper` with `item_model` set to
   `minecraft:writable_book`, a custom name, lore and a `charter: book` custom
   data tag shows as a book and quill in the hotbar, in
   the hand and in a chest. Paper is the base because it has no use of its own:
   the client opens no screen and predicts nothing. Fallback if paper renders
   wrong: `minecraft:recovery_compass`, which Pocket Dungeons' keystone already
   uses this way.
2. **The click.** Right-clicking it in the air and against a block (grass,
   stone; not a chest) reaches the server through `UseItemCallback`, main hand
   only, as `Lemon.java` does for the keystone.
3. **A dialog round trip.** The click shows a vanilla `MultiActionDialog` with one
   button whose click is a custom action in the `charter` namespace, and the press
   arrives back on the server thread with the right player.
4. **The crafting route.** A crafting recipe whose result is computed from its
   inputs (the banner's name and patterns copied onto the Nexus). Try a special
   recipe first: a `CustomRecipe` subclass with its own serializer, as vanilla does
   for banner duplication. Recipe serializers are not sent to clients in 26.2 as
   far as I know, so a vanilla client should still connect and craft; check that,
   and check the crafter block too. Fallback: a static JSON recipe plus a Mixin on
   the crafting result.

**Files:** a throwaway `fabric/src/main/java/charter/mc/book/Spike.java` and one
gametest, deleted or folded into B4 when the spike passes.

**Tests:** a gametest that builds the item and asserts its components; a gametest
that runs the use callback with a mock player and sees the dialog packet sent;
the recipe assembling a named Nexus from a named banner in a test crafting grid.

**Live check (the gate):** deploy to `714a0605` and look, on a vanilla client:
the item in hand, a right-click in air and on a block, the dialog, the button,
crafting a Nexus at a table and in a crafter. Write the result into this file
under B0 before starting B1.

---

## B1: core, the standing model

**Goal:** for one player, the Rights and Freedoms lists, computed from grants at
the moment of reading. Never stored.

**Files:**

- `core/src/main/java/charter/gov/Standing.java`: `Standing.of(state, knobs,
  player, now)` returns the player's organization and office, and for each power
  group one line: who holds it (`YOU`, `OFFICE` with its name, `PLAYER` with a
  name, `MEMBERS_BY_VOTE` with whether this player may vote). Uses
  `AuthorityResolver.resolve`, nothing of its own.
- `core/src/main/java/charter/gov/PowerGroup.java`: the 18 powers folded into
  the lines a player reads, for example `SPEND` alone as "Spending the treasury",
  `ARREST` and `JUDGE` as "Arrest and judge", `PLACE_CORE`, `CLAIM` as "Where
  land is held". Every power in exactly one group.
- Open votes the player can still cast: `Standing.votesWaiting`.

**Tests** (`StandingTest`, in `CoreSuite`):

- a Lordship founder: every group but amend and dissolve is a Right "as Lord"
- a Lordship member: those groups are Freedoms held by the Lord; amend and
  dissolve read "the members, by vote (you can vote)"
- a member of 2 days (under the 3 day eligibility): "the members, by vote (you
  cannot vote yet)"
- a Sheriff with arrest and judge granted to the office: that group is a Right
  "as Sheriff"; after `Vacate`, it is a Freedom again
- a Commune member: every group reads "the members, by vote"
- a member of a sub-organization: grants from the parent show with the parent's name
- the spend limit appears on the spending line
- a scan that fails when a `Power` is in no `PowerGroup`, so a new power can never
  be missing from the page

---

## B2: core, founding at a Nexus, and becoming a Commune

**Goal:** one action that founds a faction and raises its first keep together, so
a refusal leaves nothing half done. And the vote that turns a Lordship into a
Commune.

**Files:**

- `core/src/main/java/charter/org/actions/FoundKeep.java`: `FoundKeep(founder,
  name, banner, center)`. `check` runs `Found.checkName`, membership, the founding
  cooldown, and the keep checks (`LandGraph.keepProblem`, treating every existing
  keep as foreign, since the faction does not exist yet). `apply` founds as a
  Lordship, credits the 64 diamonds the fabric side took (P1), places the keep and
  pays for it. One `Founded`, one `Deposited`, the keep's events.
- The banner goes into `People.banner`, which already exists as a stored component
  string the fabric side reads (§7). Core stays free of Minecraft types.
- `core/src/main/java/charter/gov/actions/BecomeCommune.java`: reserved, needs
  `AMEND`, so only a vote of the members runs it. It revokes the Lord office's
  grants and removes the office. A store name in `Persistence.ACTIONS` and a
  `motion.become_commune` line, as `RegistryTest` demands.

**Tests** (`FoundKeepTest`, in `CoreSuite`):

- founding by placement makes a FORMING Lordship with the founder as Lord, a keep
  and a treasury of 0 after the price
- each refusal leaves no organization, no claim and no diamonds moved: a name in
  use, a name too short, already a member, the cooldown, a keep too close to
  another, a chunk already claimed
- the banner is saved and read back through the store (`StoreTest`)
- `BecomeCommune` cannot run without a vote; after a passed vote the Lord has no
  grants, the office is gone, and `Standing` reads "the members, by vote"

---

## B3: core, asking to join

**Goal:** the apply, admit and reject operations DESIGN.md §6.2 already lists.
Today only an invite works.

**Files:**

- `core/src/main/java/charter/org/actions/Apply.java`: a player asks to join a
  faction. Refused if already a member or already asking.
- `Admit.java` and `Reject.java`: need `MANAGE_MEMBERS`. `Admit` joins the player
  through the same path as `AcceptInvite`.
- Requests are records in `CharterState` with their own `StateBlock`, as
  BUILDING.md describes, and expire after a knob (`joinRequestDays`, default 7).
- History events, so the Chronicle can say who joined whom.

**Tests** (`JoinRequestTest`): apply then admit makes a member; reject and expiry
leave none; someone without the power cannot admit; applying to a second faction
while one request is open is refused; an invite and a request for the same player
both resolve to one membership.

---

## B4: fabric, the menu command and the book

**Goal:** `/charter` opens the Charter menu, the book runs `/charter`, and buttons
come back.

**Files** (new package `charter.mc.book`):

- `/charter` with no arguments opens the Home dialog (`CharterCommands`). The
  chat faction page stays reachable as `/charter page`, so nothing is lost.
- `CharterBook.java`: builds the item (B0's components, named "Charter of Rights
  and Freedoms"), and on use runs `/charter` as the player through the command
  dispatcher, so the book and the command can never behave differently. No owner,
  no special death handling. Handed over from `CharterServer.join` whenever the
  player carries none; nothing is recorded.
- `BookDialogs.java`: Charter's dialog kit, written fresh for Charter (no
  Pocket Dungeons code is imported, DESIGN.md §5), following the rules in the
  `dialog-screens` skill: never a dialog without buttons, Esc runs a harmless
  exit, every button carries its owner, Back rebuilds from live state.
- `BookRouter.java`: handles presses; checks the owner, runs actions through the
  engine, then always shows a screen.
- `fabric/src/main/java/charter/mc/mixin/CustomClickMixin.java`: **Mixin 12**, the
  same hook as Pocket Dungeons' (`ServerCommonPacketListenerImpl.handleCustomClickAction`),
  taking only the `charter` namespace and passing everything else through. Listed
  in `charter.mixins.json` and in the README's Mixin table with its reason.
- Text keys in `en_us.json`, prefix `book.`.

**Tests:** gametests for: a join gets one book, a join while carrying one gets no
second, a join after throwing it away gets a new one; `/charter` and the book send the
same dialog; a player using someone else's book sees their own Home; a press
from the wrong player does nothing; a press on a screen built for an empty list
never sends a dialog without buttons.

---

## B5: fabric, the pages for a member

**Goal:** the Home, Rights, Freedoms, Your situation, faction and Chronicle pages.

**Files:** `charter/mc/book/MemberPages.java`, reading `Standing` (B1) and the
runtime:

- **Home:** faction, preset, office; buttons to the pages below.
- **Rights** and **Freedoms:** one line per power group, from `Standing`.
- **Your situation:** only lines that apply: membership length; votes waiting,
  each a button that opens the vote; custody, confinement, banishment or a respawn
  wait with time left (`Justice.holding`, `banished`, `respawnDelayLeft`); items
  owed (`Mail.owedTo`, which only reads).
- **Faction:** members and offices, open votes, treasury balance, keeps with their
  granary time left. Join requests for anyone with `MANAGE_MEMBERS` (B3), each
  with Admit and Reject (Reject asks first, as the skill's rule 6 says).
- **Chronicle:** opens the faction's Chronicle as a vanilla book, the way Pocket
  Dungeons' `DiaryReading` opens a diary (`ClientboundOpenBookPacket`, the
  inventory untouched).

**Tests:** a gametest per page in its fullest and emptiest state, so every dialog
is actually encoded; a Lordship member and a Commune member see different
Freedoms; a vote cast from the page counts; Admit from the page makes a member.

---

## B6: fabric, founding and joining in the world

**Goal:** a player with no faction can found or join one without typing.

**Files:**

- The Nexus recipe (B0's route): a lodestone and any banner give a Nexus carrying
  the banner's name and patterns. In `data/charter/recipe/`, so an operator can
  change it while D21 (the recipe cost) is open.
- `Placement.placeNexus`: a player with no faction placing a named Nexus runs
  `FoundKeep`, after taking 64 diamonds from their inventory (P1); refused, the
  block and item come back as now and the diamonds too. A member placing a Nexus
  runs `PlaceNexus` as today, so admin Nexuses still work. An unnamed Nexus placed
  by a player with no faction is refused with "Name a banner at an anvil and craft
  it into the Nexus: its name is your faction's."
- `charter/mc/book/NewcomerPages.java`:
  - **Home** for a player with no faction: Join a faction, Found your own,
    Realm Chronicle.
  - **Join a faction:** factions taking members, each opening a page with what
    joining gives away (their `Standing` as a new member would read it) and an
    Ask to join button (`Apply`). A pending request shows as such.
  - **Found your own:** the steps, worked out fresh each time it opens, never
    stored: a banner in your inventory; named at an anvil; a lodestone; the Nexus
    crafted; 64 diamonds; place it. Done steps green, the next one yellow.
- The `Become a Commune` vote as a button on a Lordship member's Faction page.
- Notices: members with `MANAGE_MEMBERS` hear about a new request (online now,
  else by mail, as `Notices` already does).

**Tests:** gametests: crafting a Nexus copies the banner's name; placing it with
64 diamonds founds a faction named after the banner, raises the keep and leaves
the founder 64 diamonds lighter; with 63 it is refused and nothing changes; a
member placing a Nexus still runs `PlaceNexus`; the Found page's steps follow the
inventory; Ask to join, then Admit from the officer's page, makes a member; the
Join page never sends an empty dialog when no faction is taking members.

---

## B7: the tutorial, docs and live checks

- Rewrite `tutorial.json`'s book and `TUTORIAL.md`: no commands on any page.
  Founding is crafting and placing; the faction page is the Charter. Claiming
  stays `/charter map` until its own proposal (not in this plan).
- `ScenarioFilesTest` gains a check that no shipped scenario book contains a `/`
  command.
- README: Mixin 12 in the table; the book under "Land in the world".
- BUILDING.md: a section for `charter.mc.book` with what B0 found.
- DESIGN.md §19: the Charter book in the table of what players touch.
- Live checks, played on `714a0605` with a vanilla client: a fresh player joins,
  reads their book, follows Found your own to a keep; a second player asks to
  join from their book and is admitted from the first player's; both read Rights
  and Freedoms; the members vote to become a Commune and the Freedoms page changes.

## What done looks like

`gradlew build` green with the counts reported (core, fabric, gametests, each
higher than 1322, 362 and 73), the B0 and B7 live checks written down, and a new
player able to go from first join to a raised keep without typing a command.
