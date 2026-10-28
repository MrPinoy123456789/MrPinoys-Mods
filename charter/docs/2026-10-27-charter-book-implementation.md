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
| C2 | `bookGiven` flag on the player record | A fabric state block `book`, holding the UUIDs that were given a book | `PlayerRecord` is what the rules know; a book is not a rule. Same pattern as `Mail` and `CellBook` |
| C3 | `BecomeCommune` "removes the office" | It revokes the Lord's grants and removes the office with a new `CharterState.removeOffice` | No removal method exists; `Governance.preset` is final, so the page reads the form from the grants, never from the stored preset (B1) |

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
    public enum Form { LORDSHIP, COMMUNE }
    public enum HolderKind { YOU, OFFICE, PLAYER, ORG, MEMBERS_BY_VOTE }

    /** One reader-facing line: a group, or one power of a group whose powers differ. */
    public record Line(PowerGroup group, Power power /* null for the whole group */,
                       HolderKind kind, String name /* office, player or org name; "" otherwise */,
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

    public static Form form(CharterState state, AuthorityResolver resolver, OrgId org)
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
3. Otherwise the holder is the first such grant's recipient, by kind: `OFFICE` (the
   office name, `" (vacant)"` is not added here; pages do that), `PLAYER` (their
   last known name, `state.knownPlayer`), `ORG`. With a `SPEND` grant, the limit as
   in rule 1.

Then fold by group: if every power in a group produced an equal line (same kind,
name, vote and limit), emit one line with `power = null`; otherwise one line per
power, in the enum's order. Groups in the enum's order.

`asNewcomer` runs the same rules with a player who holds nothing and cannot vote
yet (`youCanVote` false), so it never needs a real player.

`form`: `LORDSHIP` when any office of `O` with `reserved` true is the recipient of
at least one valid grant; else `COMMUNE`. Pages show this, never
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
8. A sub-organization member (`CreateSubOrg`): lines are about the sub-organization;
   whatever its creation grants show their recipient's name.
9. `votesWaiting` lists an open motion until the player votes on it, then not.
10. Every `Power` is in exactly one `PowerGroup` (loop over `Power.values()`), so a
    new power can never be left off the page.
11. `asNewcomer` for a Lordship returns Freedoms only, all `youCanVote` false.

---

## B2: core, founding at a Nexus, and becoming a Commune

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

`core/src/main/java/charter/state/CharterState.java`: add

```java
/** Removes an office. Grants to it stop working at once (their recipient is gone). */
public void removeOffice(OfficeId id)
```

Check how `offices` is stored and remove it there; nothing else.

`core/src/main/java/charter/gov/GovEvents.java`: add
`record OfficeAbolished(long at, OfficeId office, OrgId org, String name) implements HistoryEvent`.

`core/src/main/java/charter/gov/actions/BecomeCommune.java`:

```java
public record BecomeCommune(OrgId org) implements Action
```

- `actingFor()` `org`, `power()` `AMEND`, and override `reserved(ActionContext ctx)`
  to return true (see its javadoc in `charter.engine.Action`).
- `check`: the organization's `Standing.form` is `LORDSHIP`, else a new reason
  `GovReasons.ALREADY_COMMUNE` (`"already_commune"`, `"This faction has no Lord
  to do away with."`; put it where the gov package keeps its reasons, or create
  `GovReasons` like `OrgReasons`); no open election for any reserved office of the
  organization, else `GovReasons.ELECTION_OPEN` (`"election_open"`, `"Wait for the
  election for that office to close."`).
- `apply`: for every reserved office of `org` that is the recipient of a valid
  grant: revoke each such grant (`grant.revoke(now)`, a `GrantEvents.Revoked` each),
  then `state.removeOffice(id)` and an `OfficeAbolished`.

Register it: `ACTIONS.register("gov.become_commune", BecomeCommune.class)` in
`CoreTypes`, and the line `"motion.become_commune": "do away with the Lord and
govern by vote"` in `en_us.json`. `RegistryTest` fails until both exist. Add a
Chronicle template for `GovEvents.OfficeAbolished` in `DefaultTemplates` beside
the others: `In {when}, {org} did away with the office of {name}.` (match the
placeholder style already used there).

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

`core/src/test/java/charter/gov/BecomeCommuneTest.java`, registered:

1. The Lord running it directly is refused (reserved; it needs a vote).
2. A motion passed by the members runs it: no Lord office, no valid grant to it,
   `Standing.form` is `COMMUNE`, a former Freedom line now reads
   `MEMBERS_BY_VOTE`.
3. Run on a Commune: refused with `already_commune`.

Add a round trip of `people.banner` and of a removed office to the fabric
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

`charter/mc/book/BookLedger.java`: the state block `book`, a `Set<UUID>` of players
who were given a book, saved and loaded like `Fixtures`. `CharterServer.join`
calls `BookMod.onJoin(player)` after `give(player)`: when the UUID is not in the
set and the runtime is running, add the book with the suite's give or drop pattern
(`player.getInventory().add(stack)`, and `player.drop(stack, false)` when it did not
fit), add the UUID, and send `book.given` in chat once.

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

1. A first join (call `BookMod.onJoin` with a mock player) gives one book; a second
   call gives none, even after the book is removed from the inventory.
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
(`{form}, you are {offices}`, with `book.form.lordship` / `book.form.commune`, and
`book.home.no_office` = `a member` when the player holds none); buttons: Rights,
Freedoms, Your situation, `{org}` (the Faction page), Chronicle; exit Close.

**Rights** (`rights`) and **Freedoms** (`freedoms`): one body line per
`Standing.Line`, built by one shared formatter:

| Line | Text |
|---|---|
| `YOU`, `OFFICE` name | `{group}: as {name}` |
| `YOU`, `ORG` name | `{group}: through {name}` |
| `YOU`, no name | `{group}: granted to you` |
| `OFFICE` | `{group}: the {name}` (append ` (vacant)` when that office has no holder) |
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
`Reject`. When `Standing.form` is `LORDSHIP` and the player is an eligible voter:
a button `book.faction.become_commune` = `Propose: govern without a Lord` that
calls `rt.engine.propose(me, new BecomeCommune(org))` and shows the outcome.

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
   the body text).
3. Casting Yes from the vote page: the vote's `ballots()` holds the player's Yes.
4. Admit from the Faction page (run the newest token labelled Admit): the
   applicant is a member.
5. Reject goes through the confirm screen first: the request survives until the
   confirm's yes runs.
6. The Become a Commune button opens a vote whose motion is `BecomeCommune`.
7. Chronicle sends no inventory change (the player's selected slot is the same
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

1. A fresh player joins and gets one book; using it opens their Home.
2. They follow Found your own to a placed keep without typing anything.
3. A second player asks to join from their book; the first admits them from theirs.
4. Both read Rights and Freedoms and they match who holds what.
5. The members vote to become a Commune; the Freedoms page changes.
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
