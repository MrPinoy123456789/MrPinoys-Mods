# Implementation plan: the Charter of Rights and Freedoms

Status: written 2026-10-27 for a builder (Sonnet 5.5) to take end to end. Nothing is
built. It expands `2026-10-27-charter-book-plan.md` (the milestones B0 to B7) into
files, signatures, text and tests. Where the two disagree, **this file wins**; each
difference is listed in section 1.

Read these before starting, in this order:

1. `charter/BUILDING.md` (the conventions; every rule there applies)
2. `charter/docs/2026-10-27-charter-book-proposal.md` (what the owner approved)
3. `charter/DESIGN.md` §23, the 2026-10-27 table
4. the root `CLAUDE.md` (no em dashes, no double hyphens, in code, text and commits)

## 0. Ground rules for the builder

- **Branch:** work on `feature/charter-build`. Do not create other branches. If the
  main checkout at `A:\MrPinoys Mods` is on another branch with uncommitted work
  (another session may be building there), do not switch it: use
  `git worktree add .claude/worktrees/charter-book feature/charter-build` and work
  there.
- **Commits:** one commit per milestone at least, authored as the owner:
  `git -c user.name=MrPinoy123456789 -c user.email=MrPinoy123456789@users.noreply.github.com commit ...`.
  Never `krisean`. End every message with
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Never push.
- **Build:** from `charter/`, `./gradlew build` (on Windows `gradlew.bat build`). It
  runs `coreTest`, `fabricTest` and `runGameTest`. A milestone is done only when the
  whole build is green. Report the three counts after each milestone. Starting
  counts: core 1322, fabric 362, gametests 73 (the report includes vanilla's
  `always_pass`; BUILDING.md explains).
- **New tests must be registered** or they never run:
  core tests in `core/src/test/java/charter/CoreSuite.java`, fabric plain tests in
  `fabric/src/test/java/charter/mc/FabricSuite.java`, gametest classes in
  `fabric/src/gametest/resources/fabric.mod.json`. Check that each count rises.
- **Text:** every string a player reads is a key in
  `fabric/src/main/resources/data/charter/lang/en_us.json`, read with
  `Text.get(key, "name", value, ...)`. Core never holds player text except `Reason`
  defaults, which follow the existing pattern in `OrgReasons`.
- **Dash check** before each commit, from the repo root:
  `grep -rn -e $'\u2014' -e ' -- ' --include='*.java' --include='*.md' --include='*.json' charter/`
  must print nothing you wrote.
- **Looking up a vanilla class:** the mapped 26.2 jar is
  `C:\Users\Kriss\.gradle\caches\fabric-loom\26.2\minecraft-merged.jar`. Use
  `javap -p -cp <jar> net.minecraft.world.item.crafting.CustomRecipe` for
  signatures, or decompile one class with `cfr-0.152.jar` at the repo root. Never
  guess a method name; 26.2 renamed many (BUILDING.md "Found in the M0 spike").
- **Order:** B0, then S1 and B3 (while the owner answers B0's check), then B1 and
  B2, then B4 (after the owner's answer), B5, B6, B7.
- **Pocket Dungeons is a reference, never a dependency.** Read its classes to copy
  shapes; never import them (`docs/DESIGN.md` §4 rule 3). The useful ones are in
  `pocketdungeons/src/main/java/pocketdungeons/`: `DialogKit.java` (the dialog
  API), `DiaryReading.openBook` (opening a book without touching the inventory),
  `Lemon.register` (the `UseItemCallback` for a held item).
- **When something in this plan turns out to be wrong** (a method that does not
  exist, a test that cannot be written as described), choose the smallest change
  that keeps the behaviour described here, write it down under "Builder's notes"
  at the end of this file, and carry on. Stop and ask the owner only at the two
  points marked **STOP** below.

## 1. Differences from the build plan

| # | Build plan said | This plan does | Why |
|---|---|---|---|
| C1 | Mixin 12, `CustomClickMixin`, for button presses | **No Mixin.** Every button runs `/charter act <token>` with a vanilla `run_command` click, through the existing one-shot `Tokens` | Charter already has owner-bound tokens (`Chat.act`) and gametests that click them; Pocket Dungeons uses the same route for fixed buttons (`DialogKit.command`). The suite allows a Mixin only when nothing else works (`docs/DESIGN.md` §4 rule 7) |
| C2 | Given once, with a `bookGiven` flag on the player record | **Nothing is recorded.** Every join hands a book to a player who carries none | The owner, 2026-10-27: there is no risk in more books, since a book is only a shortcut to `/charter`. Checking the inventory keeps a player from collecting a stack of them |
| C3 | `BecomeCommune` "removes the office" | **No `BecomeCommune`.** Every faction has a decision makers office with seats (S1, new): 0 seats is a Commune, 1 a Lordship, more a council that acts alone or together. Becoming a Commune is a vote to set the seats to 0 | Owner, 2026-10-27: one structure for every faction. `Governance.preset` is final, so pages read the form from the seats, never from the stored preset |
| C4 | Rights and Freedoms list one line per group of powers | Lines with the same holder are **joined into one line** ("Alice, as Lord: spending, land, war..."), so a usual Lordship reads as two or three lines, not thirteen | Fewer lines for a new player to read; the detail is the same (B5). Proposed 2026-10-27, the owner may overrule before the build starts |

## 2. Shared pieces this plan uses

Found in the code on 2026-10-27; use them as they are.

| Need | Use |
|---|---|
| Run an action as a player | `rt.engine.run(new Actor.Player(id), action)` returns `Outcome`; `Outcome.Refused(Reason r)` on refusal. `Placement.run` shows the pattern, including `rt.state.touch(...)` first |
| Refusal text | `Text.has("reason." + key) ? Text.get("reason." + key) : reason.text()` (see `Placement.run`) |
| Who may do what | `new AuthorityResolver(state, now).resolve(new Who.AsPlayer(p), org, power, ScopeNeed.NONE, false)`; `.ok()`, `.kind()`, `.via()` (the `Grant`) |
| Valid grants | `resolver.valid(grant)`; all grants: `state.grants()` |
| Voters | `rt.engine.eligibleVoters(org, now)` (members of `voteEligibilityDays` standing) |
| Votes | `state.votes()`, `Vote.open()`, `vote.electorate`, `vote.ballots()`; cast with `rt.engine.cast(voter, voteId, Vote.Ballot.YES)`; propose with `rt.engine.propose(player, action)` |
| Names | `rt.describe.org(id)`, `.player(id)`, `.office(id)`, `.holder(holder)`, `Describe.currency(c, n)` |
| Justice | `Justice.holding(state, knobs, p, now)`, `Justice.banished(state, knobs, p, land, now)`, `Justice.respawnDelayLeft(state, knobs, p, now)` |
| Owed items | `rt.mail.owedTo(id)` (read only) |
| Durations | `Chat.duration(millis)` |
| A link token | `CharterCommands.TOKENS.issue(uuid, label, System.currentTimeMillis(), Consumer<ServerPlayer>)` returns the token; `CharterCommands.tokens().newest(uuid, label)` finds it in a gametest |
| A Chronicle volume | `ChronicleBooks.faction(state, templates, false, org, volume)` and `.realm(...)`, then `Chronicles.book(volume, scope)` gives a written book `ItemStack` (see `ChronicleCommands` around line 90 for the arguments) |
| Charter items | `LandItems.nexus()`, `LandItems.kindOf(stack)`, the private `stamp` (custom data key `charter`) |
| State blocks | `Persistence.block(CoreBlocks.block("name", write, read))`; copy `Fixtures.java` around line 88 |
| Store names | `charter/mc/store/CoreTypes.java`: `ACTIONS.register("feature.verb", X.class)`; events through `registerNested` |

---

## B0: spike (serial, first)

**Goal:** prove the item, the click, one dialog with a working button, and the
recipe, with gametests, then get the owner to look on a vanilla client.

### Files

`fabric/src/main/java/charter/mc/book/CharterBook.java` (final class, kept after B0):

```java
public static final String KIND = "book";           // custom data: charter = "book"
public static ItemStack create()                     // the item
public static boolean isBook(ItemStack stack)        // LandItems.kindOf(stack) == KIND
static void register()                               // UseItemCallback, see below
```

- `create()`: `new ItemStack(Items.PAPER)`, then set
  `DataComponents.ITEM_MODEL` to `Identifier.withDefaultNamespace("writable_book")`,
  `CUSTOM_DATA` `{charter: "book"}`, `CUSTOM_NAME` `Text.get("book.item.name")` in
  gold, not italic, `LORE` one grey line `Text.get("book.item.lore")`,
  `MAX_STACK_SIZE` 1. Make `LandItems.stamp` package private or copy its four lines;
  do not change what it does.
- `register()`: copy the shape of Pocket Dungeons' `Lemon.register` keystone hook:
  `UseItemCallback.EVENT`, server side, main hand only, `isBook`; for B0 it calls
  `BookDialogs.spike(serverPlayer)` and returns `InteractionResult.SUCCESS_SERVER`.
  Also register `UseBlockCallback.EVENT` with the same test so a right-click on a
  block opens it too; return `PASS` when the block's own use would matter (a
  container, a door, a lectern): check `state.getMenuProvider(level, pos) != null`
  or the block is in `BlockTags.DOORS`, and pass those through.

`fabric/src/main/java/charter/mc/book/BookDialogs.java` (final class, kept): the
dialog kit for Charter. Copy the methods of Pocket Dungeons' `DialogKit` you need
(`show`, `common`, `text`, `closeButton`, `list`, `confirm`, `notice`) with the
same vanilla classes, and add:

```java
/** A button whose click runs a one-shot token bound to this player (C1). */
static ActionButton act(ServerPlayer player, String label, String tooltip, Consumer<ServerPlayer> run) {
    String token = CharterCommands.TOKENS.issue(player.getUUID(), label, System.currentTimeMillis(), run);
    return button(label, tooltip, new StaticAction(new ClickEvent.RunCommand("/charter act " + token)));
}
static void spike(ServerPlayer player)   // B0 only: a list with one "Hello" button whose run sends a chat line
```

`CharterCommands.TOKENS` is package private today; make it public or add a public
`static String issue(...)` wrapper in `CharterCommands`. Do not change `Tokens`.

`fabric/src/main/java/charter/mc/book/NexusRecipe.java`: a special crafting
recipe. Read `net.minecraft.world.item.crafting.BannerDuplicateRecipe` and
`CustomRecipe` in the jar first and copy their shape exactly.

- Matches a crafting grid holding exactly one `Items.LODESTONE` and exactly one
  banner (any item in `ItemTags.BANNERS`) that has a `CUSTOM_NAME`, and nothing
  else, anywhere in the grid.
- Result: `NexusItems.founding(name, bannerJson)` (below).
- Remaining items: none (the banner is used up, as the owner approved).
- Its serializer is registered in `BuiltInRegistries.RECIPE_SERIALIZER` under
  `charter:nexus`, from `CharterMod.onInitialize` through a `BookMod.register()`.
- `fabric/src/main/resources/data/charter/recipe/nexus.json`:
  `{"type": "charter:nexus", "category": "misc"}` (match the field names the
  serializer's codec actually uses).

`fabric/src/main/java/charter/mc/book/NexusItems.java`:

```java
public static final String NAME = "charter_name";      // custom data
public static final String BANNER = "charter_banner";  // custom data
public static ItemStack founding(String name, String bannerJson)
public static Optional<String> foundingName(ItemStack stack)   // empty for an admin Nexus
public static String bannerJson(ItemStack banner, HolderLookup.Provider registries)
```

- `founding`: start from `LandItems.nexus()`, then add `NAME` and `BANNER` to its
  custom data, set `CUSTOM_NAME` to the faction name in aqua, and `LORE` to
  `Text.get("book.nexus.lore", "name", name)`.
- `bannerJson`: a small JSON object `{"item": "<banner item id>", "patterns": <the
  BANNER_PATTERNS component encoded with its codec through RegistryOps over
  JsonOps>}`. An empty patterns list is fine. This string is what
  `People.banner` stores (B2); nothing reads it back yet.

`fabric/src/main/java/charter/mc/book/BookMod.java`: `register()` calls
`CharterBook.register()`, registers the recipe serializer, and later (B4) the
join hook and the state block. One call from `CharterMod.onInitialize`, as
BUILDING.md "Shared files" says.

Lang keys (add to `en_us.json`): `book.item.name` = `Charter of Rights and
Freedoms`; `book.item.lore` = `Use it to see where you stand.`; `book.nexus.lore`
= `Place it to found {name}.`

### Tests

`fabric/src/gametest/java/charter/gametest/BookGameTest.java` (new, registered):

1. `theBookLooksLikeABookAndQuill`: `CharterBook.create()` is paper, its
   `ITEM_MODEL` is `minecraft:writable_book`, `isBook` is true, stack size 1.
2. `usingTheBookSendsADialog`: a mock player holding the book in the main hand;
   call the use callback path (factor the callback body into a public static
   `CharterBook.use(ServerPlayer, InteractionHand)` that returns the result, and
   call that); it returns `SUCCESS_SERVER`. Off hand returns `PASS`. To see that a
   dialog was sent without a client, have `BookDialogs.show` record the last
   dialog per player in a package `static Map<UUID, Dialog> LAST` (cleared on
   leave), and assert it is set.
3. `theButtonRunsForItsOwnerOnly`: after `spike`, the newest token for the
   player (`CharterCommands.tokens().newest(uuid, "Hello")`) runs the button;
   running it as a second mock player does nothing (the existing token check).
4. `theRecipeNamesTheNexusAfterTheBanner`: build a `CraftingInput` of a lodestone
   and a white banner named `Ashfall`, `matches` is true and `assemble` returns a
   Nexus with `foundingName` `Ashfall`. An unnamed banner does not match; a second
   item in the grid does not match.

### Live check: **STOP** (the only hard gate)

Build, then deploy to the test server:

```bash
node pocketdungeons/tools/server/pdserver.mjs deploy --jar MrPinoys_Charter --restart
```

If `pocketdungeons/tools/server/` no longer exists, the tool has moved to
`tools/playtest/` (the Lemon spec); use the same verb there. Check
`pocketdungeons/tools/server/kinetic.env` (or its new home) names server
`714a0605` before you run it; never any other id. If players are online, ask the
owner before restarting.

Then ask the owner to check, on a **vanilla** client, and write the answers under
"Builder's notes":

1. the client connects at all (a custom recipe serializer could break registry
   sync)
2. `/charter admin give book` (add `book` to the existing `give <what>` in
   `LandCommands`, beside `nexus` and `banner`) shows a book and quill in the hotbar, in hand and in a
   chest
3. right-click in the air and on grass opens the Hello dialog; the button posts
   its line
4. a lodestone and a named banner in a crafting table show the named Nexus as the
   result, and crafting it works; the same in a crafter

**While waiting, build B1, B2 and B3** (core only, nothing in them depends on the
answer). Do not start B4 until the owner has answered.

**Fallbacks**, if the owner reports a failure:

- 1 or 4 fail: delete `NexusRecipe` and the serializer. Ship
  `data/charter/recipe/nexus.json` as a plain `crafting_shapeless` recipe (a
  lodestone and `#minecraft:banners`) whose result is the lodestone with the
  Nexus components written out in JSON (`charter: "nexus"`, the name and lore of
  `LandItems.nexus()`). The faction name then comes from **renaming the Nexus at
  an anvil**: `NexusItems.foundingName` reads the item's `CUSTOM_NAME` when the
  custom data has no `charter_name`, and the banner is not recorded. Update the
  Found page steps in B6 to: lodestone, banner, craft the Nexus, name it at an
  anvil, 64 diamonds, place it.
- 2 fails: change the base item to `Items.RECOVERY_COMPASS` (one constant) and
  keep `ITEM_MODEL`. If that also fails, drop `ITEM_MODEL` and use the compass as
  it looks.
- 3 fails for blocks only: keep the air click and drop `UseBlockCallback`.

Delete `spike` and its test after the gate; keep the rest.

---

## S1: core, seats for every office

**Owner decision, 2026-10-27:** every faction is run the same way. One office, the
**decision makers**, holds the routine powers. It has a number of **seats** and a
**way of deciding**:

| Seats | Each acts alone | Together |
|---|---|---|
| 0 | the members decide everything by vote (a Commune) | same |
| 1 | a Lordship | same as alone |
| 2 or more | co-rulers: any one of them may act | a council: a majority of the seats must agree |

Changing the form of a faction is changing those two settings. Going to or from a
Commune (0 seats) is a vote of the members; any other change of form is the office's
own to make (a lone Lord at once, a council by a majority of its seats), and a vote of
the members may still make any of them (owner, 2026-10-28; see Builder's notes).
Nothing is deleted, and the menu reads one structure for every faction.

Build S1 after B0's code (it does not wait for the owner's B0 answer; it is core
only) and before B1 and B2, which use it. B3 does not touch offices and can be
built alongside.

### Files

`core/src/main/java/charter/gov/Office.java`: one holder becomes a list of seats.

```java
public enum Deciding { ALONE, TOGETHER }
public record Seat(PlayerId player, long since) {}

private int seats;                 // how many may hold it; 0 or more
private Deciding deciding = Deciding.ALONE;
private final List<Seat> holders = new ArrayList<>();

public Office(OfficeId id, OrgId org, String name, boolean reserved, boolean vacateWhenAbsent, int seats)
public int seats()
public Deciding deciding()
public List<Seat> holders()                     // unmodifiable, in seating order
public boolean holds(PlayerId player)
public boolean vacant()                         // no holders
public boolean full()                           // holders.size() >= seats
public long heldSince(PlayerId player)          // 0 if they do not hold it
public void seat(PlayerId player, long now)     // caller has checked full() and holds()
public void unseat(PlayerId player)
public void unseatAll()
public void setSeats(int seats)                 // also unseats the newest holders beyond the count
public void setDeciding(Deciding deciding)
```

**Remove `holder()`, `heldSince()`, `fill()` and `vacate()`** so the compiler lists
every place that assumed one holder (about 60 uses in 20 files). Fix each one by
what it means: "who holds it" becomes `holders()` (Describe and pages list every
name), "is this player the holder" becomes `holds(p)`. Keep the old five argument
constructor as one that passes `seats = 1`, so offices like Sheriff are unchanged.

`core/src/main/java/charter/gov/Governance.java`: add
`public OfficeId deciders;` (null for an organization saved before S1; Repair fixes
it, below).

`core/src/main/java/charter/gov/Preset.java`: both presets create the decision
makers office, named `Preset.LORD` ("Lord"), reserved, `vacateWhenAbsent` true,
with the same root grants for `LORD_POWERS` that the Lordship has today (the spend
limit included), and store its id in `governance.deciders`.

- `LORDSHIP`: one seat, the founder seated. Today's behaviour, unchanged.
- `COMMUNE`: **zero seats**, nobody seated. The grants exist but reach nobody, so
  every power stays with the members' vote, as today.

`core/src/main/java/charter/gov/actions/Appoint.java`: seats the player.

- Already holds it: refused `already_seated` ("That player already holds this
  office.").
- Office has 0 seats: refused `no_seats` ("This office has no seats.").
- Full with one seat: the holder is replaced, as today (`OfficeVacated` "replaced",
  then `OfficeFilled`). Full with more seats: refused `office_full` ("Every seat is
  taken. Remove someone first.").

`core/src/main/java/charter/gov/actions/Unseat.java` (new):
`record Unseat(OrgId org, OfficeId office, PlayerId player)`, power `APPOINT`,
reserved when the office is (copy `Vacate.reserved`). Refused when the player does
not hold it. `OfficeVacated` "removed".

`Vacate` keeps its fields and now empties **every** seat (one `OfficeVacated` per
holder), so old saved votes and scenario files that name it still mean what they
meant. Refused when nobody holds it, as today.

`core/src/main/java/charter/gov/actions/SetSeats.java` (new):
`record SetSeats(OrgId org, OfficeId office, int seats, Office.Deciding deciding)`,
power `APPOINT` (changed from `AMEND` on 2026-10-28), `reserved` only when the old or new
seat count is 0; otherwise the office's deciders run it, acting through that very office
(`not_decider` for anyone else) and, for a council of two or more, by a majority of its
seats (`Action.needsSeatMajority`). Refused when
`seats` is below 0 or above the knob `maxSeats` (new, default 7), or when nothing
would change (`no_change`, "That is how it already is."). Apply: `setSeats`, one
`OfficeVacated` "seat removed" for each holder it unseats (the most recently
seated go first), `setDeciding`, and a new
`GovEvents.SeatsChanged(at, office, org, seats, deciding)`.

`core/src/main/java/charter/engine/ActionEngine.java`:

- **Elections** fill one seat: `openElection` refuses when the office has 0 seats,
  or is full and has more than one seat (`office_full`). A one seat office that is
  full can still hold an election, which replaces the holder, as today.
- **Absent holders:** in `tick`, for each office with `vacateWhenAbsent`, unseat
  every holder not seen for `absentLordDays` (one `OfficeVacated` "absent" each),
  then open one election if any seat is now empty (`openElection` already refuses
  a second one while one is open; the next tick opens the next).
- **Deciding together.** In `authorize`, after `resolve` for a player: when the
  authority came through a grant whose recipient is `Holder.OfOffice(id)`, and that
  office decides `TOGETHER` and has two or more holders, refuse with a new
  `Reason.NEEDS_SEATS` (key `needs_seats`, "The council decides this together.
  Propose it to them."). Add the case to `refusalFor` the same way `NEEDS_VOTE` is
  chosen.
- `public Outcome proposeToSeats(PlayerId proposer, OfficeId office, Action action)`:
  the proposer must hold the office and the action must be one the office's grants
  cover (resolve with a new `Who.AsSeats(office)`); reserved actions are refused
  (`Reason.RESERVED`). Opens a vote whose electorate is exactly the office's
  holders, every one counted active, closing after the knob `seatVoteHours` (new,
  default 24). The proposer's Yes is cast at once.
- `Vote` gains `public final OfficeId seatsOf` (null for every vote of the members).
  For such a vote, after each ballot: it **passes as soon as Yes ballots are more
  than half the electorate**, and **fails as soon as that can no longer happen**.
  When time runs out, the same test decides. Quorum does not apply.
- `authorize` for `Actor.Vote` whose vote has `seatsOf`: `who` is
  `new Who.AsSeats(seatsOf)`, never `AsOrg`, so a council vote can never do what
  only the members may (reserved actions stay `NONE`).

`core/src/main/java/charter/grant/Who.java`: add `record AsSeats(OfficeId office)`.
`AuthorityResolver.reaches`: `Holder.OfOffice(var id)` is reached by
`Who.AsSeats(var s)` when `id.equals(s)`; `AsSeats` reaches nothing else.

`fabric/src/main/java/charter/mc/store/CoreBlocks.java` (offices, around line 317):
write `seats`, `deciding` and `holders` (a list of `{player, since}`). Read the old
form too: a saved `holder` and `heldSince` become one seat, and `seats` defaults to
1 when missing. Votes: write and read `seatsOf`, null when missing.
`Governance.deciders`: write and read with the organization.

`Repair`: an organization whose `governance.deciders` is null gets one. If it has a
reserved office named `Lord`, that is it. Otherwise (a Commune saved before S1),
create the office with 0 seats and the Lord grants, exactly as `Preset.COMMUNE`
now does, recorded as a `StoreEvents.Repaired`.

Store names and text: `gov.unseat` and `gov.set_seats` in `CoreTypes`;
`motion.unseat` (`remove {player} from {office}`) and `motion.set_seats` (`give
{office} {seats} seats, deciding {deciding}`) in `en_us.json`, with whatever
placeholders `RegistryTest` asks for. A Chronicle template for `SeatsChanged`:
`In {when}, {org} changed how it is ruled: {seats} seats, deciding {deciding}.`
`Describe.office` lists the holders' names. `Pages` and `Notices`: every line that
printed the holder prints every holder.

### Tests

`core/src/test/java/charter/gov/SeatsTest.java`, registered in `CoreSuite`:

1. A Lordship founds with one seat, the founder seated, deciding alone; the
   founder spends as today.
2. A Commune founds with the decision office at 0 seats; a member's spend is
   refused with the needs a vote reason; a vote of the members spends.
3. `SetSeats` run by the Lord directly is refused (reserved); passed by the
   members, a Lordship becomes 0 seats and the former Lord's spend now needs a
   vote.
4. A Commune voting to 3 seats, alone: three elections seat three members; each of
   them spends on their own.
5. The same council set to together: one holder's spend is refused with
   `needs_seats`; `proposeToSeats` opens a vote of the three; the second Yes passes
   it at once and the spend happens; a vote with two No fails at once.
6. A council vote cannot run `SetSeats` or any `AMEND` action (refused reserved).
7. Lowering 3 seats to 1 unseats the two most recently seated.
8. An absent holder of three is unseated after `absentLordDays` and one election
   opens; the other two keep their seats.
9. `Appoint` into a full three seat office is refused `office_full`; into a full
   one seat office it replaces, as today.
10. `Vacate` empties every seat.

Fabric `StoreTest`: an office with three holders, deciding together, and a seat
vote in progress survive a save and load; a saved state from before S1 (written by
hand in the test, with `holder` and `heldSince`) loads as one seat; a pre S1
Commune gets its decision office from `Repair`.

**Every existing test must still pass.** `GovernanceTest`, `JusticeTest`,
`InvariantTest` and the gametests that appoint or vacate offices change only where
they named the removed methods.

---

## B1: core, the standing model

**Goal:** what one player holds and gives away, computed from grants at the
moment of reading. Never stored.

### Files

`core/src/main/java/charter/gov/PowerGroup.java`:

```java
public enum PowerGroup {
    MEMBERS(Power.MANAGE_MEMBERS),
    OFFICES(Power.APPOINT),
    TREASURY(Power.SPEND),
    LAND(Power.PLACE_CORE, Power.CLAIM),
    GRANARY_IN(Power.GRANARY_DEPOSIT),
    GRANARY_OUT(Power.GRANARY_WITHDRAW),
    GARRISON(Power.GARRISON),
    JUSTICE(Power.ARREST, Power.JUDGE),
    PARDON(Power.PARDON),
    WAR(Power.RAISE_CAMP, Power.DECLARE_WAR, Power.CHOOSE_FALL),
    DIPLOMACY(Power.NEGOTIATE),
    SUBORGS(Power.CREATE_SUBORG),
    CHARTER(Power.AMEND, Power.DISSOLVE);

    public final List<Power> powers;
    PowerGroup(Power... powers) { this.powers = List.of(powers); }
    public static PowerGroup of(Power power)   // throws if none
}
```

That covers all 18 powers once each; the test proves it.

`core/src/main/java/charter/gov/Standing.java`:

```java
public final class Standing {
    public enum Form { COMMUNE, LORDSHIP, COUNCIL }
    public enum HolderKind { YOU, OFFICE, PLAYER, ORG, MEMBERS_BY_VOTE }

    /** One reader-facing line: a group, or one power of a group whose powers differ. */
    public record Line(PowerGroup group, Power power /* null for the whole group */,
                       HolderKind kind, String name /* office, player or org name; "" otherwise */,
                       List<String> holders /* for OFFICE: the seated players' names */,
                       boolean together /* for OFFICE: the office decides together */,
                       boolean youCanVote, long spendLimitDiamonds /* -1 when none */) {
        public boolean right() { return kind == HolderKind.YOU; }
    }

    public record View(OrgId org, String orgName, Form form, List<String> yourOffices,
                       long memberSince, List<Line> lines, List<VoteId> votesWaiting) {
        public List<Line> rights()    // lines with right()
        public List<Line> freedoms()  // the rest
    }

    /** Empty when the player belongs to no organization. */
    public static Optional<View> of(CharterState state, ActionEngine engine, PlayerId player, long now)

    /** The same view as a member of org would read it on joining today (for the Join page). */
    public static View asNewcomer(CharterState state, ActionEngine engine, OrgId org, long now)

    public static Form form(CharterState state, OrgId org)
}
```

Rules, in order, for each power `P` of the player's organization `O`:

1. If `resolve(AsPlayer(me), O, P, NONE, false).ok()` the holder is `YOU`. The line's
   `name` says through what: the grant's recipient. `OfOffice` gives the office
   name, `OfOrg` the organization's name, `OfPlayer` the empty string. When the
   grant is for `SPEND`, `spendLimitDiamonds` is the grant scope's
   `limit(Currency.DIAMOND)`, or -1 when it is `Long.MAX_VALUE`.
2. Otherwise take the valid grants over `O` for `P` (`resolver.valid`). If there are
   none, the holder is `MEMBERS_BY_VOTE`, with `youCanVote` =
   `engine.eligibleVoters(O, now).contains(me)`.
3. Drop the grants whose recipient is an office with no holders (they reach
   nobody). If none are left, the holder is `MEMBERS_BY_VOTE` as in rule 2.
4. Otherwise the holder is the first such grant's recipient, by kind: `OFFICE` (the
   office name, its holders' last known names in seating order, and `together`
   when it decides `TOGETHER` with two or more holders), `PLAYER` (their last
   known name, `state.knownPlayer`), `ORG`. With a `SPEND` grant, the limit as in
   rule 1.

In rule 1, a player who holds a seat in an office that decides together (with two
or more holders) does **not** count as `YOU` for powers that reach them only
through that office: the line is `OFFICE` with `together` true, because they
cannot act alone.

Then fold by group: if every power in a group produced an equal line (same kind,
name, vote and limit), emit one line with `power = null`; otherwise one line per
power, in the enum's order. Groups in the enum's order.

`asNewcomer` runs the same rules with a player who holds nothing and cannot vote
yet (`youCanVote` false), so it never needs a real player.

`form`: from the decision makers office (`governance.deciders`): 0 seats
`COMMUNE`, 1 seat `LORDSHIP`, more `COUNCIL`. Pages show this, never
`governance.preset` (C3).

`yourOffices`: names of offices of `O` held by the player. `memberSince`:
`membership.joinedAt()`. `votesWaiting`: open votes of `O` whose `electorate`
contains the player and whose `ballots()` has no entry for them, oldest first.

### Tests

`core/src/test/java/charter/gov/StandingTest.java`, registered in `CoreSuite`.
Use `Fixture` (`f.found(...)`, `f.as(...)`, `f.passDays(...)`, `f.motion(...)`).

1. A Lordship founder: every group except `CHARTER` is a Right whose name is
   `Lord`; `CHARTER` is `MEMBERS_BY_VOTE`.
2. The treasury line of the founder has `spendLimitDiamonds` 32 (the knob).
3. A second member of 4 days: those groups are Freedoms held by `OFFICE` `Lord`;
   `CHARTER` reads `MEMBERS_BY_VOTE` with `youCanVote` true.
4. A member of 2 days: `CHARTER` has `youCanVote` false.
5. A Sheriff: create an office through the real actions (`CreateOffice`,
   `Appoint`, `GrantPower` for `ARREST` and `JUDGE` to the office, as the Lord);
   the Sheriff's `JUSTICE` line is a Right named `Sheriff`. After `Vacate`, it is a
   Freedom again.
6. A mixed group: grant only `ARREST` to the Sheriff; `JUSTICE` splits into two
   lines, `ARREST` a Right and `JUDGE` held by the Lord.
7. A Commune member: every line is `MEMBERS_BY_VOTE`; `form` is `COMMUNE`.
8. A council of three deciding together: a holder's lines are `OFFICE` with three
   names and `together` true, never `YOU`; deciding alone, they are `YOU`; `form`
   is `COUNCIL` either way.
8. A sub-organization member (`CreateSubOrg`): lines are about the sub-organization;
   whatever its creation grants show their recipient's name.
9. `votesWaiting` lists an open motion until the player votes on it, then not.
10. Every `Power` is in exactly one `PowerGroup` (loop over `Power.values()`), so a
    new power can never be left off the page.
11. `asNewcomer` for a Lordship returns Freedoms only, all `youCanVote` false.

---

## B2: core, founding at a Nexus

### Files

`core/src/main/java/charter/land/LandGraph.java`: add

```java
/** Keep checks for a faction that does not exist yet: every existing keep is foreign. */
public Optional<Reason> keepProblemForNew(ChunkKey center)
```

Same as `keepProblem` without the `faction(...)` comparison.

`core/src/main/java/charter/org/actions/FoundKeep.java`:

```java
public record FoundKeep(PlayerId founder, String name, String banner, ChunkKey center, long paidDiamonds) implements Action
```

- `actingFor()` null, `power()` empty (it is the player's own act, as `Found`).
- `check`: in this order, returning the first refusal: not the founder and not
  admin (`OrgReasons.NOT_YOU`); already a member (`ALREADY_MEMBER`);
  `Found.checkName` (make it public); the founding cooldown exactly as `Found.check`
  does (factor that into a package static `Found.cooldownProblem(ctx, founder)` and
  call it from both); `keepProblemForNew(center)`; `paidDiamonds <
  ctx.knobs.keepPriceDiamonds` gives `LandReasons.NO_FUNDS.with("A keep costs " +
  price + " diamonds and you carry fewer.")`.
- `apply`: run `new Found(founder, name, Preset.LORDSHIP).apply(ctx)` and keep its
  events; set the new organization's `people.banner = banner` and `people.name =
  name.strip()`; `ledger.add(Currency.DIAMOND, paidDiamonds)` with a
  `TreasuryEvents.Deposited(now, org, DIAMOND, paidDiamonds, founder)`; then
  `ledger.take(DIAMOND, keepPriceDiamonds)` and
  `new LandGraph(state, knobs).placeKeep(org, center, now)`. Return every event in
  that order. Any change left over (paid more than the price) stays in the
  treasury.

Because `check` runs before `apply` and nothing else mutates in between, a refusal
leaves nothing behind. Do not call `ActionEngine.run` from inside `apply`.

Becoming a Commune needs no action of its own any more: it is `SetSeats` to 0
(S1).

### Tests

`core/src/test/java/charter/org/FoundKeepTest.java`, registered in `CoreSuite`:

1. Founding with 64 paid: one FORMING organization named as given, the founder its
   Lord, one keep of nine claims at `center`, treasury diamonds 0, `people.banner`
   as given.
2. Paid 70: treasury 6.
3. Each refusal changes nothing (count orgs, claims, cores and the player's
   membership before and after): a taken name, a two letter name, already a member,
   founded 2 days ago, a keep within `keepGapChunks` of another, a footprint chunk
   already claimed, paid 63.
4. The events are `Founded`, the Lordship's office and grant events,
   `Deposited`, `NexusPlaced`, in that order.

Add a round trip of `people.banner` to the fabric
`StoreTest` (find it under `fabric/src/test`), so a save and load keeps both.

---

## B3: core, asking to join

### Files

`core/src/main/java/charter/org/JoinRequest.java`:
`public record JoinRequest(OrgId org, PlayerId player, long at)`.

`CharterState`: a `List<JoinRequest> joinRequests` block of its own (copy the
invites methods: `joinRequests()`, `addJoinRequest`, `joinRequest(org, player)`,
`joinRequestOf(player)` returning the player's one open request,
`removeJoinRequest(org, player)`). In `leave` and wherever an organization is
dissolved, drop requests that point at it the way invites are dropped (line ~203).

`Knobs`: `public int joinRequestDays = 7;` with a comment.

`core/src/main/java/charter/org/actions/Apply.java`:
`record Apply(PlayerId player, OrgId org)`, the player's own act (`actingFor` null).
Refusals: not you; no such org (`Reason.NO_SUCH_ORG`); not top level or not
`exists()`; already a member of any organization (`ALREADY_MEMBER`); an open
request anywhere (`OrgReasons.ALREADY_ASKING`, `"already_asking"`, `"You have
already asked to join a faction. Wait for an answer first."`). Apply adds the
request and returns `OrgEvents.Asked(at, org, player)`.

`Admit.java`: `record Admit(OrgId org, PlayerId player)`, `actingFor` org, power
`MANAGE_MEMBERS`. Refused without a request (`OrgReasons.NO_REQUEST`,
`"no_request"`, `"That player has not asked to join."`) or if the player has since
joined somewhere (`ALREADY_MEMBER`). Apply: the same steps as `AcceptInvite.apply`
(leave, join, FORMING to ACTIVE at two members), remove the request and any invite
from this org, events `OrgEvents.Admitted(at, org, player)` then the usual
`Joined` and maybe `Activated`. Factor the shared joining steps out of
`AcceptInvite.apply` into a package static helper and call it from both, so they
can never drift.

`Reject.java`: `record Reject(OrgId org, PlayerId player)`, `MANAGE_MEMBERS`;
removes the request; `OrgEvents.Rejected(at, org, player)`.

Expiry: in `ActionEngine.tick()`, after votes, drop requests older than
`joinRequestDays` with `OrgEvents.RequestLapsed(at, org, player)` recorded as
`Actor.System`, the way `OfficeVacated` is recorded there.

Persistence: store names `org.apply`, `org.admit`, `org.reject` in `CoreTypes`,
`motion.apply`, `motion.admit`, `motion.reject` lines in `en_us.json`
(`"admit {player}"`, ...; check the placeholders `RegistryTest` wants), the new
`OrgEvents` records are picked up by the existing `registerNested`. Save the
requests in a block `join_requests` registered with
`Persistence.block(CoreBlocks.block("join_requests", write, read))` from
`CoreTypes` or a new `JoinTypes.register()` called from `Persistence.register()`;
copy `ContractTypes` (line ~47). Chronicle templates for `Admitted` (`In {when},
{org} took in {player}.`). `Asked`, `Rejected` and `RequestLapsed` are not
Chronicle news: add them to the events `ChronicleRenderer.routine` skips, so they
never fall back to the generic line.

### Tests

`core/src/test/java/charter/org/JoinRequestTest.java`, registered:

1. Apply then Admit by the Lord: a member; the request is gone; a FORMING faction
   becomes ACTIVE.
2. Reject: no member, no request.
3. After `joinRequestDays` plus a minute and `engine.tick()`: the request is gone,
   one `RequestLapsed`.
4. A member without `MANAGE_MEMBERS` cannot Admit.
5. A second Apply while one is open, to any faction: refused `already_asking`.
6. An invite and a request for the same player: accepting the invite removes the
   request too (add that to the shared joining helper), and a later Admit is
   refused `no_request`.
7. A request survives a save and load (fabric `StoreTest`).

---

## B4: fabric, the menu command and the book in play

Start only after the owner answered the B0 check.

### Files

`charter/mc/cmd/CharterCommands.java`:

- `/charter` with no arguments: `.executes(ctx -> menu(ctx))`, which calls
  `CharterMenu.open(player)`.
- `/charter page`: the old behaviour, `page(ctx, Pages::faction)`.
- Search the code, the scenario files and every doc under `charter/` for a bare
  `/charter` that meant the chat page and change it to `/charter page`
  (`Pages`' own links included).

`charter/mc/book/CharterMenu.java`: `public static void open(ServerPlayer player)`.
If the runtime is not running, a chat line `book.not_running`. If the player has a
faction, `MemberPages.home(player)`; else `NewcomerPages.home(player)` (B6; for B4 a
stub page with a Close button and the line `book.newcomer.soon`).

`CharterBook.use`: runs `/charter` as the player through the dispatcher:
`server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "charter")`.
This is the whole of the book: no owner, nothing stored on the item.

`CharterServer.join` calls `BookMod.onJoin(player)` after `give(player)`. When the
runtime is running and no slot of the player's inventory holds a book
(`CharterBook.isBook`, main inventory, off hand and armour slots all checked), it
hands one over with the suite's give or drop pattern
(`player.getInventory().add(stack)`, and `player.drop(stack, false)` when it did not
fit) and sends `book.given`. Nothing is saved: a player who drops, loses or throws
away their book gets a new one the next time they join (owner, 2026-10-27).

`BookDialogs` (from B0) gains the house rules from the Pocket Dungeons
`dialog-screens` skill (`.claude/skills/dialog-screens/SKILL.md`; read it):

- **Never a dialog without buttons.** `list(...)` with an empty action list adds one
  line `book.nothing_here` and still has its exit button. Add an assertion in
  `list` that throws in gametests if `actions` and `exit` are both missing.
- **Esc runs the exit.** `common(...)` uses `DialogAction.CLOSE` and every screen
  has a visible exit; screens opened from another screen use a Back button
  (`act(...)` that reopens the parent) as their exit, not Close.
- **Every button carries its owner.** Only `act(...)` makes buttons that do
  something; the token is bound to the player the screen was built for.
- **No history stack.** Back rebuilds the parent from live state.
- **Every press shows a screen afterwards**: the result of the action (refusal text
  in red at the top, or the success line) above the rebuilt page.

`BookRouter.java`: one helper used by every page button:

```java
static void run(ServerPlayer player, Action action, Consumer<String> thenShow)
```

It touches the player, runs the action through the engine, turns a refusal into
its text (as `Placement.run`), and calls `thenShow` with `null` on success or the
refusal text. Pages pass a lambda that reopens themselves with that line.

Lang keys: `book.not_running` (`Charter is not running.`), `book.given` (`You
have been given the Charter of Rights and Freedoms. Use it, or type /charter, to
see where you stand.`), `book.nothing_here` (`Nothing here yet.`),
`book.newcomer.soon`, `book.button.close` (`Close`), `book.button.back` (`Back`).

### Tests

Add to `BookGameTest`:

1. A join (call `BookMod.onJoin` with a mock player) gives one book; a second call
   while they still carry it gives none; after the book is removed from the
   inventory, the next call gives one again.
2. A full inventory gets the book dropped at the feet (an `ItemEntity` near the
   player).
3. `/charter` (run with `performPrefixedCommand`) and `CharterBook.use` both leave
   the same dialog in `BookDialogs.LAST` (compare titles and button labels).
4. A player using a book created for nobody in particular sees their own Home:
   two mock players in different factions, the same book stack moved from one to
   the other, each sees their own faction's name in the title.
5. A token issued for player A run by player B does nothing.
6. Every page builder called with an empty state (no factions at all) produces a
   dialog with at least one button (call each page method directly).
7. `/charter page` still prints the chat page (a chat line arrives).

---

## B5: fabric, the pages for a member

### Files

`charter/mc/book/MemberPages.java`, all `static void x(ServerPlayer player, String
notice)`, `notice` the line from the last press or null. Each builds from live
state through `CharterServer.runtime()` and `Standing.of(...)`.

**Home** (`home`): title `book.home.title` = `{org}`; body lines: `book.home.form`
(`{form}, you are {offices}`, with `book.form.commune`, `book.form.lordship` and
`book.form.council` (`a council of {count}, any one may act` or `a council of
{count}, deciding together`), and
`book.home.no_office` = `a member` when the player holds none); buttons: Rights,
Freedoms, Your situation, `{org}` (the Faction page), Chronicle; exit Close.

**Rights** (`rights`) and **Freedoms** (`freedoms`): lines with the same holder
(same kind, names, together and vote) are **joined** (C4): one body line per
holder, the holder text first and then the groups, in the enum's order, with the
spend limit after the treasury group. So a Lordship member reads two lines:
`Alice, as Lord: taking in members, appointing officers, spending the treasury (up
to 32 diamonds at a time), ...` and `The members, by vote (you can vote):
changing or ending the charter`. Group labels are lower case in this joined form.
The table below is the holder text for one group; the joined line uses the same
holder text in front of the list:

| Line | Text |
|---|---|
| `YOU`, `OFFICE` name | `{group}: as {name}` |
| `YOU`, `ORG` name | `{group}: through {name}` |
| `YOU`, no name | `{group}: granted to you` |
| `OFFICE`, one holder | `{group}: {holder}, as {name}` |
| `OFFICE`, several | `{group}: {holders}, any one of them`, or with `together`, `{group}: {holders}, together` |
| `PLAYER` | `{group}: {name}` |
| `ORG` | `{group}: {name}, by its own decision` |
| `MEMBERS_BY_VOTE` | `{group}: the members, by vote (you can vote)` or `(you cannot vote yet)` |

with `, up to {n} diamonds at a time` appended when `spendLimitDiamonds >= 0`.
`{group}` is `book.group.<enum name lower case>` (for a split line,
`book.power.<power lower case>`). Group labels:

| Key | Text |
|---|---|
| `members` | Taking in and removing members |
| `offices` | Appointing officers |
| `treasury` | Spending the treasury |
| `land` | Where land is held |
| `granary_in` | Putting food in granaries |
| `granary_out` | Taking food from granaries |
| `garrison` | Garrisoning keeps |
| `justice` | Arrest and judgement |
| `pardon` | Pardons |
| `war` | Raising camps and war |
| `diplomacy` | Treaties and contracts |
| `suborgs` | Founding sub-factions |
| `charter` | Changing or ending the charter |

and one `book.power.*` per power for split lines (write a plain label for each of
the 18). An empty Rights page shows `book.rights.none` (`You hold no powers of
your own here. Your say is your vote.`); Freedoms likewise
`book.freedoms.none`. Exit: Back to Home.

**Your situation** (`situation`), only the lines that apply:

- `book.situation.member` = `Member of {org} for {time}` (always)
- one button per waiting vote: label from `rt.notices.about(vote)` (shortened to
  fit, 40 characters), opening `vote(player, voteId)`
- held: `Justice.holding(...)` present gives `book.situation.held` with the time
  left; compute it the way the justice code already shows a prisoner their time
  (search `charter/mc/justice` for where `Justice.until` is called and reuse that)
- banished from land: for each top-level faction where `Justice.banished` is true,
  `book.situation.banished` = `Banished from {org}'s land`
- respawn wait: `respawnDelayLeft > 0` gives `book.situation.respawn` with the time
- owed items: `rt.mail.owedTo(id)` non empty gives `book.situation.owed` = `The
  treasury owes you {list}; it arrives when you have room.`

**Vote** (`vote(player, voteId)`): the motion's text (`rt.notices.about(vote)`), time
left, and three buttons Yes, No, Abstain running `rt.engine.cast(...)` through a
small wrapper that reports the `Outcome` like `BookRouter.run`. After casting,
reopen Your situation with `book.vote.cast`.

**Faction** (`faction`): members with their offices (`Pages.faction` shows how),
vacant offices, treasury balances, keeps with granary time left (find how
`Pages` or `/charter admin supply show` computes it and reuse that method; do not
compute it again), open votes as buttons to `vote`. When the player passes
`resolve(..., MANAGE_MEMBERS, ...)`: a line per join request with two buttons,
Admit (`BookRouter.run(new Admit(org, p))`) and Reject, which first opens a
`confirm` dialog (`book.reject.confirm` = `Turn {player} away?`) whose yes runs
`Reject`. For an eligible voter, two more buttons:

- `book.faction.who_decides` = `Propose a change to who decides`, opening
  **Who decides** (`whoDecides`): one button per choice, each proposing
  `SetSeats(org, deciders, seats, deciding)` with `rt.engine.propose`: `Everyone,
  by vote` (0 seats), `One ruler` (1), and for n from 2 to 5 `A council of {n},
  any one may act` and `A council of {n}, deciding together`. The current choice
  is shown as a line, not a button. Back to Faction.
- When the decision makers office has an empty seat and no election is open:
  `book.faction.elect` = `Hold an election for a seat` (`rt.engine.openElection`).

A player who holds a seat in an office that decides together is refused
`needs_seats` for its powers. `BookRouter.run` handles that once for every page: on
a `needs_seats` refusal it calls `rt.engine.proposeToSeats(me, office, action)` and
shows `book.seats.proposed` = `Put to the {office}. It passes when most of them
agree.`

**Vote** pages for an **election** show one button per member of the faction
(at most 20), each casting `Vote.Ballot.candidate(p)`, instead of Yes and No.

**Chronicle** (`chronicle`): build the faction's newest volume with
`ChronicleBooks.faction(...)`, turn it into a written book with
`Chronicles.book(...)`, and open it with a copy of Pocket Dungeons'
`DiaryReading.openBook` (slot packet, open book packet, slot restored; the
server inventory is never touched). Put that copy in
`charter/mc/book/BookReading.java` with the same javadoc reasoning.

### Tests

`fabric/src/gametest/java/charter/gametest/MemberPagesGameTest.java`, registered:

1. Each page, in its fullest state (a faction with an office, a vote, a request,
   a keep) and its emptiest (a lone founder), leaves a dialog in
   `BookDialogs.LAST` with at least one button and no exception.
2. A Lordship member and a Commune member get different Freedoms bodies (compare
   the body text); a Lordship member's Freedoms page has at most three lines.
3. Casting Yes from the vote page: the vote's `ballots()` holds the player's Yes.
4. Admit from the Faction page (run the newest token labelled Admit): the
   applicant is a member.
5. Reject goes through the confirm screen first: the request survives until the
   confirm's yes runs.
6. The Who decides page's `Everyone, by vote` button opens a vote whose motion is
   `SetSeats` with 0 seats; an election page button casts a candidate ballot.
7. A council member deciding together presses Admit: a seat vote opens instead,
   and the second council Yes admits the applicant.
8. Chronicle sends no inventory change (the player's selected slot is the same
   item before and after).

---

## B6: fabric, founding and joining in the world

### Files

`charter/mc/land/Placement.java`, `placeNexus`: before the `org.isEmpty()` line:

```java
if (org.isEmpty()) {
    Optional<String> name = NexusItems.foundingName(stack);   // pass the placed stack in
    if (name.isEmpty()) return Text.get("book.nexus.unnamed");
    return FoundingPlacement.found(player, level, pos, name.get(), NexusItems.bannerOf(stack));
}
```

`placeNexus` needs the placed stack: pass `single` from `place(...)` through.
A member placing any Nexus runs `PlaceNexus` exactly as today, so admin Nexuses and
the scenarios keep working.

`charter/mc/book/FoundingPlacement.java`:

```java
/** Null on success, else the sentence that says why not. */
static String found(ServerPlayer player, Level level, BlockPos pos, String name, String banner)
```

1. Count diamonds in the player's inventory. Fewer than `keepPriceDiamonds`: return
   `book.found.need_diamonds` (`A keep costs {price} diamonds. You carry {have}.`)
   and take nothing.
2. Run `FoundKeep(id, name, banner, chunk, price)` through the engine (the same
   path as `Placement.run`). Refused: return its text, take nothing.
3. Succeeded: remove exactly `price` diamonds from the inventory (stack by stack),
   add the Nexus fixture exactly as `placeNexus` does for a member, send
   `book.found.done` (`{name} is founded. Its keep stands here. It needs a second
   member before it is more than a name.`), and return null.

Diamonds are only taken after the action succeeded, and the engine call is on the
server thread, so nothing can take them in between.

Lang: `book.nexus.unnamed` = `Name a banner at an anvil and craft it with a
lodestone: its name becomes your faction's.` (with the B0 anvil fallback: `Name
this Nexus at an anvil first: its name becomes your faction's.`).

`charter/mc/book/NewcomerPages.java`:

**Home** (`home`), for a player with no faction: title `book.newcomer.title` = `You
belong to no faction`; a line with a pending request if any (`Waiting for {org} to
answer`); buttons Join a faction, Found your own, Realm Chronicle; exit Close.

**Join a faction** (`join`): every top-level organization that `exists()`, sorted by
member count, highest first, at most 20 (then a line `book.join.more`); each a
button `{org} ({members} members, {form})` to `faction(player, org)`. With none:
`book.join.none` = `No factions yet. Found your own.` and the Found button.

**One faction** (`faction(player, org)`): its form, members, keeps; then "What
joining gives away": the `Standing.asNewcomer` lines through the same formatter as
Freedoms; a button Ask to join (`BookRouter.run(new Apply(me, org))`), or the line
`book.join.waiting` when this is the faction already asked. Back to Join.

**Found your own** (`found`): steps worked out on every open, never stored:

| Step | Done when |
|---|---|
| Have a banner | any banner in the inventory |
| Name it at an anvil | a banner with a custom name, or a founding Nexus already crafted |
| Have a lodestone | a lodestone, or a founding Nexus |
| Craft the Nexus | a founding Nexus (`NexusItems.foundingName` present) in the inventory |
| Carry 64 diamonds | the count, with the number held |
| Place it where your keep should stand | never done here; it is the last step |

Done steps in green with a tick (`✔`), the first not done in yellow, the rest
grey. One line under the list: `book.found.cooldown` when the founding cooldown
applies, with the time left. Exit Back.

**Realm Chronicle**: `ChronicleBooks.realm(...)` through `BookReading`.

Notices: when an `OrgEvents.Asked` is recorded, tell every member of that org who
passes `MANAGE_MEMBERS`, online by chat with a `Chat.act` link that opens the
Faction page, offline by `Mail.post`. Add the case to `Notices.onEntry` beside the
others, with `notice.asked` = `{player} asks to join {org}.`. Tell the applicant
when they are admitted or rejected the same way (`notice.admitted`,
`notice.rejected`).

### Tests

`fabric/src/gametest/java/charter/gametest/FoundingGameTest.java`, registered,
each in its own block of chunks (BUILDING.md: state persists between tests; pick
coordinates no other test uses, as `TutorialGameTest` does). Founding makes a
faction a scenario clear may take down, so run it in its own environment:
`@GameTest(environment = "charter-gametest:founding_alone")`, declared by copying
`fabric/src/gametest/resources/data/charter-gametest/test_environment/tutorial_alone.json`
to `founding_alone.json`.

1. Crafting through `NexusRecipe` (or the fallback) gives a Nexus named after the
   banner.
2. A factionless mock player with 64 diamonds places it (through
   `Placement.place`, as `LandPlacementGameTest` places a Nexus): a faction of
   that name exists, the player is its Lord, the keep's nine chunks are claimed,
   the player holds 0 diamonds, and the faction's treasury is 0.
3. With 63 diamonds: refused, the block is gone, the Nexus is back in hand, 63
   diamonds held, no faction.
4. An unnamed (admin) Nexus placed by a factionless player: refused with the
   unnamed line.
5. A member placing an admin Nexus still runs `PlaceNexus` (treasury charged).
6. The Found page's steps follow the inventory: give the items one by one and
   check the first yellow step moves on.
7. Ask to join from the newcomer page, then Admit from the Lord's Faction page:
   a member, and the Lord got the `notice.asked` chat line.
8. The Join page with no factions has a button (the Found one).

---

## B7: the tutorial, docs and live checks (serial, last)

1. `fabric/src/main/resources/charter/scenarios/tutorial.json`: rewrite the book so
   no page tells the player to type a command. Founding is: make a banner, name it
   at an anvil, craft it with a lodestone, carry 64 diamonds, place it on the
   emerald pillar. "Your faction" is "use your Charter book". Claiming land stays
   `/charter map` (its own proposal later), so that one page may keep it; note it
   as the one exception in the test below. The scenario must still give whatever
   the new steps need (a banner, a lodestone, 64 diamonds, an anvil beside the
   pad) instead of founding by command. Keep the role names and pillars so
   `TutorialGameTest` needs the least change; update that test to found by
   placement.
2. `charter/TUTORIAL.md`: the same story in long form.
3. `ScenarioFilesTest`: a check that no shipped scenario book page contains `/charter`
   except the claiming page (find it by a marker word you choose and document).
4. `charter/README.md`: the book under "Land in the world"; `/charter` opens the
   menu, `/charter page` is the chat page; no new Mixin (C1).
5. `charter/BUILDING.md`: a section `## The Charter book (charter.mc.book)` with
   what B0 found (paste the owner's answers), C1, C2, and the dialog house rules.
6. `charter/DESIGN.md` §19: the Charter book in the table of what players touch;
   §23 2026-10-27 row: built.
7. `charter/docs/2026-10-27-charter-book-plan.md`: status line `Built, see the
   implementation plan's Builder's notes`.

**Live checks: STOP** again. Deploy as in B0, then ask the owner to play these on
the test server with a vanilla client and write their answers under "Builder's
notes":

1. A fresh player joins and gets one book; using it opens their Home. They throw it
   away, rejoin, and have one again.
2. They follow Found your own to a placed keep without typing anything.
3. A second player asks to join from their book; the first admits them from theirs.
4. Both read Rights and Freedoms and they match who holds what.
5. The members vote to become a council of two deciding together and elect a
   second member; the Freedoms page says `together`; one of them admits a new
   member through a seat vote. Then they vote to become a Commune and the Freedoms
   page reads `the members, by vote`.
6. The tutorial scenario (`/charter admin scenario run tutorial`) reads with no
   commands except the claiming page.

## Done

- `gradlew build` green; counts reported, each above 1322 core, 362 fabric and 73
  gametests.
- Both STOP checks answered by the owner and written below.
- No new Mixin in `charter.mixins.json`.
- The dash check prints nothing.
- Every milestone committed on `feature/charter-build`, authored
  `MrPinoy123456789`.

## Builder's notes

(The builder writes here: B0's live answers, anything in this plan that was wrong
and what was done instead, and the final counts.)

### Builder's notes (2026-10-27 to 2026-10-28)

Counts after each step (core / fabric plain / gametests including vanilla's
`always_pass`). The starting counts in section 0 (fabric 362, gametests 73) were
stale: the first full run, after B0, already showed fabric 418 and 81 gametests (core
was 1322 as stated), so the B0 row is the first true baseline.

| Step | Core | Fabric | Gametests |
|---|---|---|---|
| B0 | 1322 | 418 | 81 |
| S1 | 1411 | 425 | 81 |
| B3 | 1468 | 426 | 81 |
| B1 | 1635 | 426 | 81 |
| B2 | 1679 | 426 | 81 |
| B4 | 1679 | 426 | 86 |
| B5 | 1679 | 426 | 93 |
| B6 | 1679 | 426 | 101 |
| B7 | 1679 | 446 | 101 |

**B0 live answers (owner, vanilla client, 2026-10-27):** the client connects; the book
looks right (book and quill) and opens the dialog; its button works; the named Nexus
crafts in a table and in a crafter. No fallback was needed. The spike dialog was
deleted after the gate.

**Things in this plan that were wrong, and what was done instead**

- `LandItems.stamp` is private to `charter.mc.land`; "package private" would not reach
  `charter.mc.book`, so `CharterBook.create` copies its four lines.
- `CharterCommands.TOKENS` stays package private; a public `issue(player, label, run)`
  wrapper was added.
- `BookDialogs.LAST` is public (the gametests are in another package).
- `NexusRecipe` also refuses a lodestone or banner that already carries custom data, so
  a Nexus or a War Banner is never consumed by it.
- `Commands`: the plan's lang keys `motion.unseat` and `motion.set_seats` are
  `motion.gov.unseat` and `motion.gov.set_seats`, following the existing `gov.` store names.
- `Vote` gained a second constructor with `seatsOf`; the old one still passes null.
- A council vote fails as soon as the Yes ballots can no longer be a majority; when time
  runs out the same test decides, with undecided seats counting as not Yes.
- Existing tests changed only where they named removed methods, plus `StoreTest` (the
  Commune now has a Lord office whose root grants also name SPEND, so the test picks the
  grant that has a child) and `FullState` (a council with a seat vote in progress).
- The Chronicle coverage test wants a default template and a golden line for every
  event, routine or not, so `Asked`, `Rejected` and `RequestLapsed` have templates and
  a golden of "(hidden)"; `ChronicleRenderer.routine` hides them.
- `FoundKeep` is in `charter.org.actions` (beside `Found`), so `Found.checkName` did not
  need to become public; `cooldownProblem` was factored out.
- `BookDialogs.list` returns a notice when it has no buttons (a list with none cannot be
  encoded); a screen with text but no buttons does not say "Nothing here yet".
- The Found page counts a crafted founding Nexus toward steps 1 to 3, because crafting
  uses the banner and lodestone up.
- `NexusItems.bannerJson` takes null registries (a crafting recipe has no level); it then
  writes an empty pattern list. A banner with patterns is stored with them only when the
  server is running.
- **Plan gap:** the tutorial paid the treasury with `/charter vault deposit`, which the
  new book may not name. The Faction page now has a "Pay in N currency" button for each
  currency the player carries (`CharterCommands.depositAll`). The scenario also gained a
  `levels` field on `give` (an anvil asks for a level to name a banner) and an anvil
  marker beside the emerald pillar. The tutorial book is 15 pages.
- The claiming page is the one tutorial page that still names a command
  (`/charter map`); `ScenarioFilesTest` finds it by the title "Claim land".
- The "Join page with no factions" gametest cannot start from an empty world (state
  persists between tests), so it checks that the page always has a button.
- The notice for a join request to an online member goes through a new
  `Notices.Messenger.askedToJoin`, which the live server answers with a chat line and an
  "Open" link; an offline member gets it by mail (tested).
- `LoadGameTest` ("twenty players around a siege cost little") failed once on a slow
  machine (5.6 ms per sim step); it passed on the rerun with no change.
- No new Mixin: `charter.mixins.json` is unchanged.

**What only a live server can confirm (B7 checks, with the owner):** see the list in
section B7. Nothing from B1 to B7 has been seen on a vanilla client yet.

**After the B6 live check (owner, 2026-10-28):** the Join page heading "What joining gives
away" was wrong, since a player with no faction never held those powers. It now reads
"Who decides here:" and lists only what a member lives under (taking in and removing
members, the treasury, war, treaties, the charter), with the charter vote worded "you
could vote once you are a member"; a closing line says the same hands run the keeps and
the rest. The founding cooldown (`foundSecedeCooldownDays` 7) blocked the owner's second
founding attempt on the test server, which is correct; only the tutorial scenario sets it
to 0. Rights and Freedoms for members are unchanged.

**Second live finding (owner, 2026-10-28):** a fresh founder saw no "who decides" or
election buttons and no reason, because the faction page shows them only to eligible
voters. Two changes. (1) The faction page now says "You can vote on changes in {time}."
to any member who cannot vote yet. (2) `ActionEngine.eligibleVoters` returns the only
member of a faction at once, since the 3 day wait guards against newcomers swaying a
faction and a faction of one has none. This touches no reserved or war rule: the
electorate is fixed when a vote opens, the Lord's powers are unchanged, and a vote of
one still passes only what votes could already pass. The moment a second member joins,
the wait applies to both again. Core test `aSoleMemberVotesAtOnce`; the full suite,
including the invariant simulation, is unchanged.

**Third live finding (owner, 2026-10-28): shrinking a council kept the founder.** `SetSeats`
used to unseat the newest holders, so Lordship to council and back left the founder as
Lord. Now, when the seats drop below two or more current holders (and stay at 1 or more),
every holder steps down ("seat removed") and an election opens at once among those
former holders. Electorate: `ActionEngine.openElection` gained an overload taking the
electorate, and `Vote` gained a `restricted` flag (saved) so the election and the ones
that follow know they are the former council's; the electorate itself was already a
`Vote` field. A council shrinking to 2 or more seats holds one election per seat, each
opened when the last one is carried out, among the same people; a seated winner cannot be
named again (`already_seated`, now also refused at casting). A lone holder, or seats that
do not drop below the holders, or a shrink to 0, behave as before (no election). If a
restricted election fails (a tie, no quorum) the office stays vacant and any eligible
member may open a normal election from the faction page. While the office is empty
nothing else breaks: the standing rules read the faction as members by vote, the
absent-holder tick skips vacant offices, reserved actions never needed the holder, and
the faction page tells members "An election ... is open among the council that stepped
down". Tests: three core scenarios in `SeatsTest` (3 to 1, 3 to 2, lone holder and to 0),
a store round trip of the flag, and a faction page gametest.

**Fourth change (owner, 2026-10-28): who may run SetSeats.** Any faction can be a lordship, a
council where any one may act, or a council deciding together. The office's own deciders
change its form between 1 seat and 2 or more without a vote of the members: a lone Lord
at once (he stays seated, the new seats empty until filled by election or appointment), a
council by a majority of its seats through `proposeToSeats` (even a council whose holders
may act alone, so one holder cannot reshape it: `Action.needsSeatMajority`). Council to
lordship uses the shrink election already built. To or from a Commune (0 seats) stays a
vote of the members, and the members' vote may still change any form. `SetSeats` moved from
`AMEND` to `APPOINT` for this, with `reserved` true only when either count is 0, and it
refuses a grant that does not reach the actor through this very office (`not_decider`), so
a Sheriff given APPOINT cannot reshape the Lord's office. A council still cannot run any
other reserved action (the `Who.AsSeats` limit is unchanged; its test now tries a Commune
and a Rename). Book: "Who decides" offers a holder of the office "Become a council, any one
may act", "Become a council, deciding together" (each then asks for 2 to 5 seats), "Become
a lordship" for a council, and "Put a change to the members"; a member who does not hold the
office goes straight to the members' list. Tests: core scenarios 8a to 8d in `SeatsTest`
(lord alone, council vote passing and failing, a council becoming a lordship, Commune
transitions, only the office itself) and a book page gametest.

### NPC strongholds (`2026-10-28-npc-strongholds-brief.md`) and `admin dissolve`

**Admin dissolve** (`860ffb43`). `DissolveOrg` (operators only) dissolves a faction and its whole
subtree: members freed, keeps and land removed, open votes closed as failed, join requests and invites
dropped, grants revoked and live contracts ended with the reason "organization dissolved", one
`Dissolved` Chronicle line each (shown only where operator events are shown, like other admin lines). The
organization stays as `DISSOLVED` so `orgIncludingDissolved` still names it. The command first breaks the
Nexus blocks, unlinks granaries and drops fixtures, then runs the action, then repairs; it prints what it did and
is written to the siege log.

**N1, N3** (`9dab6654`). `Organization.npc` (saved; an older save reads false). Refused for a faction of the raiders:
Apply and InviteMember (`not_joinable`), PlantCamp (`npc_no_camps`), occupy (`raze_only`). Treaties and war do
not exist as player actions in this build, so there was nothing to refuse beyond camps. The absent ruler tick
skips them; `dormancyDays` is a knob with no code behind it, so nothing to exempt. A fallen NPC keep is razed at
once inside `SiegeSim.fall` (no pending choice, no choice links offered), so the plunder and the Nexus break
follow from the usual `Razed` and `Plundered` events. The Join page never lists them. The `protectedFaction`
rule skips NPC factions: found in the first stronghold gametest, which never saw its keep fall because a fresh
NPC faction counted as a protected new faction for three days.

**N5** (`9bf208a3`, committed together with the first world code by a background build). `NpcPlacement.choose` is
a pure function of a seeded `Random` (twenty candidates; never inside a claim; `keepGapChunks` from every keep; at
least `npcPlayerClearChunks` from any player claim and online player; the terrain test is the caller's).
`NpcScheduler` holds the caps and the respawn delay (`npcMaxStrongholds` 4, `npcMaxOutposts` 4,
`npcRespawnDays` 3); `NpcBook` is a saved state block (`npc`) with the sites and the timers.

**N2, N4, N7.** `NpcWorld` (fabric) builds a stronghold (a stone floor, a cobblestone wall ring with a gate, the
Nexus, up to the granary chests linked to the keep and filled to the tier's share) or an outpost (a raised camp,
banner, signal fire, a store of bread), stocks the treasury by tier, and spawns vanilla pillagers, vindicators
and (from the middle tier, one in four) ravagers tagged `charter.npc`, kept near home with `setHomeTo`. Tiers
follow the chunk distance from the world spawn (`npcNearChunks` 40, `npcMiddleChunks` 120). The defenders are
removed when the scheduler sees the core gone. An outpost ends the way a player camp does (its banner broken once
it is exposed); the rules spill nothing for a camp, so the scheduler leaves a small chest of its store where the
banner stood. Commands under `/charter admin npc`: `spawn <stronghold|outpost>` (the tier follows the position),
`list`, `clear <id>`, `respawn`. Chronicle: `NpcEvents.Raised` and `Replaced`; the fall and the razing use the
existing siege lines. The names are twelve lang keys (`npc.name.N`).

**Choices to know about.** The scheduler runs every ten seconds and builds at most one site per pass. A site
needs a generated, dry, flat enough surface, and a candidate is only tried inside the rings 16 to 250 chunks
from the spawn. The raiders never raid players (N6): the comment is on `NpcWorld` and on
`Organization.npc`. Not done: a real hostile-mob presence rule so defenders never count as attackers (they are
mobs, not members, so the siege counts do not see them) was relied on rather than tested; and the outpost
gametest ends the outpost with `LoseCore` rather than by breaking its banner while exposed.

## Builder's notes: protected keeps, step 1 (raider waves, drain, crumble, restore, failsafe)

Brief: `2026-10-28-protected-keeps-brief.md`, rule N. Built first because it fixes the ruins.

**What a site is now.** `NpcBook.Live` (kept apart from the immutable `Site`, keyed by site id, saved in the `npc`
block as `live`) holds the supply, the pool, the next wave time, when it was last fought, the UUIDs of its raiders,
its home and a snapshot of the blocks it replaced. Supply is counted in raiders (one each, a kill is not refunded);
the owner approved the amounts as knobs: `npcSupplyStronghold` 24/48/96, `npcSupplyOutpost` 6/9/12, pools
`npcPoolByTier` 3/5/8 and `npcOutpostPool` 1/2/3. The granary chests are decoration only. `npcRespawnDays` and
`npcDefenders` are gone.

**Waves and falling** (pure rules in `NpcScheduler.step`, world work in `NpcWorld.tend`). Every `npcWaveMinutes`
(5) a wave tops the living raiders up to the pool, each costing one supply. A site falls when the supply is zero and
none of its raiders lives. A site nobody fought for `npcExpireDays` (10) is cleaned up the same way, and either way a
replacement is awaited for `npcWaveMinutes`. "Fought" means a raider of the site died since the last pass, or a
player is within 48 blocks of its home. Raiders are tracked by UUID, not by searching a box; a site whose chunk is
not entity ticking is not counted (only the failsafe is judged there). An old save's untracked raiders are adopted
by their tag the first time the site is tended.

**Crumble and restore.** A fallen site is dismantled in `NpcWorld.dismantle`: raiders removed at the start, then the
cores, fixtures, granary links, faction and claim, then a `Crumble` is saved in the book. Every
`npcCrumbleTicksPerLayer` (4) server ticks the top layer of the site's own blocks (only positions its snapshot
recorded) comes down with block break particles and sounds, containers emptied first; when nothing is left the
snapshot is put back and the fall loot spills. The crumble is persisted (`crumbles`), so a restart carries on where
it was. No new site is built while a crumble is in progress. The snapshot is taken as a before/after diff of a box
around the site, so it also covers the campfire and the granary's air then chest steps. Block entity data of the
original ground is not kept (natural terrain has none). A site with no snapshot (raised before this) gets a
synthetic one: its cobblestone, stone bricks, Nexus, chests, campfire and banner within reach come down and stone
bricks become grass. An admin `npc clear` is instant, without loot.

**Defaults I took (told to the owner).** Supply amounts as above. `npcFallLoot` 4: a quarter of the treasury's
diamonds, then of its cobblestone, as at most four stacks, spilled where the ground is back. The brief also says
each raider drops "a small share" of the treasury; only the vanilla table drops from raiders, the share is paid at the
fall. The old siege route (`SiegeSim` razing an NPC keep, `collectFallen`) now goes through the same dismantle, so no
walls are left; the old plunder it spills is removed in step 2 with the rest of the siege code.

**`/charter admin npc clearruins <x> <z>`.** Op only; refuses if any chunk within 10 blocks is claimed; clears the
raiders' palette blocks (cobblestone, stone bricks to grass, Nexus, chests, campfire, banner) in a 21 by 21 footprint
and prints the count. It does not remove other blocks. `admin npc list` also shows supply and pool.

**Tests.** Core `NpcWaveTest` (supply by tier, waves and cost, fall, expiry, retire), fabric `StoreTest`
(site state, snapshot and crumble round trip, an old npc block), gametests in `NpcGameTest`: drain and exact restore,
the failsafe, the outpost via the siege route, the crumble order and the marker block outside the footprint, and
clearruins. Traps: the automatic NPC pass is switched off while any test site is open (a counter, because tests run
side by side; a test that closed early re-enabled the real scheduler and it built sites in the test world, which ran
the server out of memory); entities a pass spawns appear on the next tick, so stages wait; a failed run leaves forced
chunks saved in `fabric/build/run/gameTest/world`, delete it when a run dies of OutOfMemory (and a run that prints no
"GAME TESTS COMPLETE" line is not a pass even if Gradle says BUILD SUCCESSFUL).

## Builder's notes: protected keeps, step 2 (K1, K2, and the removals)

Brief: `2026-10-28-protected-keeps-brief.md`. Built as one commit for removals and K2 (the removed tests and the new
upkeep tests depend on each other); K1 needed almost no code.

**Removed.** Keep sieges (`countingCamps`, perimeter, starvation, presence, assault, notoriety multiplier), raze and
occupy (`Falls`, `FallChoice`, `ChooseFall`, `PendingFall`, `Occupation`, `contract.Occupations`), plunder, raids and holds
on outlying claims, notoriety, new-faction protection, `SiegeInputs`, `JusticeInputs`, `WorldInputs`, the
`CHOOSE_FALL` power, the admin `perimeter` command, four siege scenarios (raid, partial-siege, encirclement,
sally), the manual `loadCheck` task. Events removed: SiegeBegan, Encircled, RingBroken, SiegeLifted, Starving, KeepFell,
Razed, Occupied, OccupationEnded, Plundered, CampExposed, HoldEmptied, TruceBegan, ProtectionWaived. The old history
lines for them are skipped when `history.jsonl` is read (the file keeps them), so the Chronicle no longer tells old
falls. Knobs removed: plunderShare, fallChoiceMinutes, notoriety*, newFactionProtectionDays, outlying hold and
refill, camp sally, presence and assault multipliers, starvationThreshold, imposedNotorietyPerSeverity.
`occupationCapDays` stays as the treaty cap on `Term.Access` (`BasisCaps`); `Basis.OCCUPATION` stays so old contracts
read, and a live one ends on load. Left inert on purpose for step 4 (outposts): the food allowance bookkeeping in
`Supply` (nothing is ever under siege, so nothing is ever needed), `Crossings`, the camp store burn state, `Truce`.

**Old saves.** The fixture `fabric/src/test/resources/charter/old-protected-keeps` is a real save written by the
old code (pending fall, occupation and its contract, notoriety, waived protection, a hold, a besieged keep site,
CHOOSE_FALL grants) with a history file of fall events; `StoreTest.aSaveFromBeforeProtectedKeepsLoads` proves it loads.
It found two real faults, now fixed: a save naming the removed power failed to load at all (grants and votes of an
unknown power are now dropped with a repair note), and a live occupation contract survived the load (now ended).

**K2.** `SiegeSim.upkeepPass`: the granary of every keep that is not a raiders' keep drains
`storageLimit / (keepUpkeepDays * 24)` an hour on the siege clock and nothing else changes the drain (members,
prisoners, camps, presence). Warnings at `keepWarnDays` (7, 3, 1) are the `SiegeEvents.KeepRunningLow` event, once per
threshold, the smallest announced is saved (`KeepSite.warned`); feeding above a threshold re-arms it. They reach every
member through the notices (so also on login) and the Chronicle, and the faction page shows the days left. At zero
the keep lapses through `loseCore`: `KeepLapsed`, prisoners freed, claims released (a farm claim goes contested and
lapses after the usual orphan grace), blocks and the Nexus block stand, members, treasury and sub-factions stay, and
a new Nexus may be placed for the price again. Day counts are siege-clock days (`timeScale` is 1 by default).

**Decision for the owner: grace.** A keep placed with an empty granary (every new keep) would lapse on its first
pass. I gave each keep one upkeep period (`keepUpkeepDays`) of grace from its first pass (`KeepSite.graceUntilSiege`),
so an unstocked keep warns at 7, 3 and 1 days and lapses at 14; old saves get the same grace on load. Not in the brief.

**K1.** `SiegeRules.protectedClaim` is true for every keep claim at all times (a camp's only once raised); the
Nexus was already unbreakable by everyone. The break, use, container, entity, explosion, piston, fire, lava,
water, dispenser and farmland cases already had gametests in `LandProtectionGameTest` and `LandHazardsGameTest` and
needed no new Mixin. `PlaceNexus` refuses a sub-faction (`LandReasons.SUB_NO_KEEP`) unless it is breaking away;
existing sub-faction keeps in saves are left alone.

**Deleted tests, by name, and what replaces them.**
Gametests: `SiegeWorldGameTest` (aFullSiegeEndsRazed, anOccupiedKeepIsHeldByTheOccupierUntilTheCap,
presenceCountsPlayersOfStandingWhereTheyStand); `SupplyGameTest` (aRunnerWalksFoodInThroughAnOpenSide,
crossingTheCampTeleportsAndPearlsCarryNothing, anEnderChestCannotBeOpenedInABesiegedKeep, aCartLineFeedsABesiegedGranary,
anEncircledKeepTakesNoFoodFromAnySide); `JusticeSiegeGameTest` (aPrisonerIsAMouthWhileHeldAndWalksFreeWhenTheKeepFalls,
starvingOutAnOccupationLeavesNoBreachMark); `LoadGameTest` (twentyPlayersAroundASiegeCostLittle); `ScenarioGameTest`
(theRaidPutsACampBesideTheFarthestFarm, thePartialSiegeLeavesSidesOpenAndARailLine, theEncirclementClosesEverySide,
theSallyIsTheRingWithFoodBeyondTheEastCamp, anExposedCampsBannerBreaksAndEndsTheCamp); `GranaryGameTest`
(afterTheKeepFallsAnOutsiderCanOpenIt, depositsGoThroughTheCarryInRule, aHopperCannotFillABesiegedGranary).
`LandPlacementGameTest` lost the perimeter command from its admin test. Replaced by `KeepUpkeepGameTest`
(aKeepRunsDryLapsesAndCanBeClaimedAgain: closed to a stranger and its chest, the 7 day warning once, the lapse, blocks
and Nexus block standing, open ground after, treasury untouched, a new Nexus for the price). Prisoners freed on
lapse use the same `freeKeep` path and are not gametested.
Core: in `SiegeTest` testJEndurance (3 and 30), testJPresenceFloor, timeScaleAgrees, testKCarryIn, cartPool,
testLEncirclement, testMStarvationGate, testNCampSupply, testORaid, testQNewFaction, testRTruce, theFall, testXSally,
notorietyRisesAndDecays; in `ContractTest` testNotoriety, testVPrisonersEat, testWOccupation, testD7Deposit and the
occupation tail of testTResistance; `NpcTest.aFallenStrongholdIsRazedAtOnceAndPlundered`; `ChronicleStoryTest`
aSiegeReadsAsAStory and the goldens of the removed events; `LoadCheck`. Replaced by the K2 tests in `SiegeTest`
(upkeepDrainsAFullGranaryOverFourteenDays, upkeepWarnsOncePerThresholdAndRearms, aNewKeepHasOnePeriodOfGrace,
aLapseReleasesTheLandAndKeepsTheNation, theRaidersKeepsAreNotKeptUp, onlyANationHoldsAKeep) and goldens for
KeepRunningLow and KeepLapsed. The `BreakawayTest` and `DissolveOrgTest` sub-faction keeps are now placed through
`LandGraph.placeKeep` (an old save may hold one).

**Still to do (steps 3 and 4).** The tutorial scenario and `TUTORIAL.md` still teach besieging a rival keep; the
`TutorialGameTest` now stops once the camp is raised. The Join, Freedoms and Who decides pages, `DESIGN.md` 10.8,
16 and 23, and the word "camp" for players are for step 4.

## Builder's notes: protected keeps, step 3 (K3: one of each, teleports, the deposit)

**One of each.** `PlaceNexus` refuses a second keep for a nation (`LandReasons.ONE_KEEP`, checked after the
placement problems so their reasons still win; a sub-faction breaking away is exempt). `PlantCamp` refuses a second
outpost for an organization (`ONE_OUTPOST`) and an outpost for a sub-faction of fewer than `outpostMinMembers` (2)
members (`OUTPOST_TOO_SMALL`). Existing saves with several keeps or camps per organization are left as they are; tests
that need them place them through `LandGraph` (`legacyKeep`, `legacyCamp` in `LandTest`).

**The deposit.** The Nth outpost a nation holds in all, nation and sub-factions together, costs `outpostDeposit` (8)
plus `outpostDepositStep` (4) for each already held, paid in diamonds from the placing organization's treasury. It
replaces the 256 cobblestone war banner price (`warBannerPriceCobblestone` is gone). The deposit is held on the
`Core` (`deposit`, saved when above zero), not in the treasury; anyone who uses the banner is told whose outpost it is
and what it holds. An outpost lost any way but capture (a raising banner pulled down, an admin `LoseCore`) gives its
deposit back to the owner in `LandGraph.loseCore`. Step 4's capture must take the deposit before it calls `loseCore`.
`/charter admin faction spawn` now funds the keep price plus one deposit in diamonds.

**Teleports.** `charter.mc.cmd.Teleports`: `/home`, `/keep`, `/outpost`, and `/charter home|keep|outpost` for a
server where another mod owns a name. Warmup `teleportWarmupSeconds` (5), cancelled by moving (more than 0.2 blocks)
or by the health dropping (so it does not depend on a damage event); cooldown `teleportCooldownSeconds` (30) from the
arrival; same dimension only. `/home` reads the vanilla spawn point and needs the bed block to still be there (Charter
owns no beds). `/keep` goes to the Nexus of the member's nation (a sub-faction member too). `/outpost` goes to the banner of
the member's own organization's outpost, only once raised, costs `outpostTeleportFood` (1) from the outpost's store
(least nourishing first, taken through the granary so a barrel follows) and is refused when the store is empty. Not done:
a bed that is not clear to stand on is not looked for (the player lands on the bed block); no teleport from or to the
nether.

**Tutorial.** The banner now needs diamonds, so the tutorial gift is 72 diamonds (64 for the keep, 8 for the deposit),
the cobblestone is gone, page 4 pays in diamond; `TUTORIAL.md` follows. The tutorial still teaches the siege from page 8
on and is rewritten with the outposts (step 4).

**Tests.** Core (`SiegeTest`): oneKeepAndOneOutpostEach, anOutpostCostsADepositThatGrowsWithTheNation. Gametest
`TeleportGameTest`: keep trip and cooldown, outpost cost and refusals, moving or being hurt cancels. Changed:
`LandTest` second keep and camp cases, `LandProtectionGameTest`/`LandPlacementGameTest` banner funding,
`TutorialGameTest`, `ScenarioFilesTest`.

## Builder's notes: protected keeps, step 4 (outposts as war prizes, the War page, the clean up) and the two value sets

**War model** (approved by the owner's session, with three additions). `DeclareWar(nation, outpost)`, power
`DECLARE_WAR`: a nation only (not a sub-faction), another nation's raised outpost that is not the raiders', not already
named, no truce between the two nations, at most `warGoalsPerWar` (2) open goals, and the declarer must hold a raised
outpost of its own. State is `state.wars` (`WarBook`: goals, truces, windows), saved as a `wars` block; an old save has
none. Times are siege time. Capture cannot begin until `warGoalNoticeHours` (12) after the declaration; a goal lapses
after `warGoalHours` (48). Both nations are told at the declaration and when capture opens (`WarDeclared`, `WarOpened`,
notices and Chronicle lines). From the opening, the outpost's claims are not protected (`SiegeRules.protectedClaim`).
Capture: `WarInputs` (fabric `WarPresence`, counted before each sim step) says whether members of the attacking nation
stand in the outpost's chunk and whether any member of the owning organization (or beneath it) does; with no defender,
attackers add to the hold, a defender resets it; at `outpostCaptureMinutes` (10) the outpost is captured
(`OutpostCaptured`): its deposit goes to the attackers' treasury, every claim of it is released, the banner and signal
fire come down (`SiegeWorld.takeDownOutpost`), a truce of `truceDays` (3) starts, and for
`outpostWinnerWindowMinutes` (30) only the captors may plant an outpost on that chunk (`LandReasons.WINDOW_HELD`). A goal
that lapses also starts the truce (`WarLapsed`). A goal whose outpost is lost another way is dropped quietly (and the
deposit is returned, `LandGraph.loseCore`). A player counts as present when online, alive, not a spectator, active
within `presenceAfkMinutes` (on Minecraft's clock, not the wall clock) and a member for `presenceMinMemberSeconds`.

**Book.** A War button on the Charter home opens the War page: the wars against the nation (with the time capture
opens, in plain words), the wars it declared, the truces, and the other nations' raised outposts with a button each;
the confirm page says why the declarer may (it holds a raised outpost of its own), the notice, the hold, and the cooldown
after a lapse. The Join, Freedoms and Who decides pages held no siege wording. The tutorial book is 14 pages around an
outpost and a war goal (Brinewatch has an outpost), `TUTORIAL.md` follows, and the tutorial gametest plays the loop to a
capture.

**Clean up.** The carry-in rule is gone entirely: `Allowance`, the cart pool, crossings, `SupplyLines`, the hopper gate,
the camp burn state (`CampSupply`, `CampSite`), the camp cut-off and empty-store events, ender chest rules. Dead lang
keys are removed. In player text a camp is an outpost (internal ids keep `camp`). `DESIGN.md` section 10 is marked
superseded, 10.8 and 10.9, 16.1 and 16.2 are corrected and section 23 records the decisions. The new keep grace is a
knob, `keepNewGraceDays` (14).

**Two sets of values.** `Knobs.shipped()` is the balance set and the default; `Knobs.test()` is the shipped set with the raiders'
timers (a wave a minute, a one day expiry, a layer a tick) and the slow governance clocks (join requests, absent Lords,
breaking away, the founding cooldown) made short; by the owner's word siege time, a trip's wait, votes and presence stay
as shipped. The config
file names a `profile`; the loader starts from that set and any other key wins over it (`CharterConfig`,
`ConfigTest.profilesGiveShippedAndTestValues`). `presenceMinMemberSeconds` is 3 days (259200) in both sets. The
gametests still run on the shipped set and set the few values they need by hand.

**Tests added in step 4.** Core `WarTest` (declaring, the notice, the hold, a defender resetting it, a lapse and its
cooldown, the window), goldens for the four war events; gametest `WarGameTest` (the book flow and a real capture by a
player in the chunk); the tutorial gametest now declares and captures. Deleted in 4a: the allowance and cart tests went
with step 2's `SupplyGameTest`; nothing else.
