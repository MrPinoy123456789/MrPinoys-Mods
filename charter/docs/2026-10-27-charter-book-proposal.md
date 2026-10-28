# Proposal: the Charter of Rights and Freedoms

Status: approved 2026-10-27 with one change. Decision 1 is option A, the book
only: the Nexus will get its own, different menu later. Decisions 2 and 3 are
approved as recommended. Recorded in DESIGN.md §23. Nothing here is built.

`/charter` opens a dialog menu that shows where a player stands right now: the
powers they hold (Rights), the powers others hold over them (Freedoms), and their
situation. A player with no faction sees how to join one or found one instead.
New players get a book, the **Charter of Rights and Freedoms**, that runs the same
command when used, so nobody has to know the command to find the menu.

It answers three problems found on 2026-10-26 and 2026-10-27:

- **New players get no direction.** On Imperial Refuge you have to join a faction
  on Discord before logging in. Charter's join handler only delivers mail.
- **The tutorial book teaches commands.** Pages 1, 2 and 5 tell the player what to
  type. A Minecraft player should not need that.
- **Governance is invisible.** Nothing shows a player what their faction lets
  them do, or what it decides for them.

## 1. The item

| | |
|---|---|
| What it is | A shortcut: using it runs `/charter`, which opens the Charter menu for whoever holds it. Once a player knows the command they never need the book (owner, 2026-10-27) |
| Look | A book and quill, named `Charter of Rights and Freedoms` |
| Base item | An item with no use of its own, given the book and quill look with the `item_model` component and a `charter: book` tag. A real book and quill cannot be used: the vanilla client opens the writing screen itself, before the server sees the click |
| Opening | `UseItemCallback`, main hand only, as Pocket Dungeons' keystone does in `Lemon.java` |
| Getting one | Handed over on any join when the player carries none; there is no risk in more books (owner, 2026-10-27). It drops on death like any item; a player who picks up someone else's book sees their own Charter |

**To check first:** that the `item_model` route shows a book and quill on a vanilla
26.2 client and that the click reaches the server. One gametest and one look in
game.

## 2. The pages

Every screen is a vanilla dialog, built from live state each time it opens (no
history stack). Pocket Dungeons' rules from the `dialog-screens` skill apply: no
list without buttons, Esc runs a harmless exit, found things first, every button
carries its owner.

**With a faction:**

```
Home: Ashfall (Lordship), you are Sheriff
  Rights            the powers you hold
  Freedoms          the powers others hold over you
  Your situation    deposits, votes waiting on you, custody
  Ashfall           members, offices, open votes, treasury, keeps
  Chronicle         opens the faction's Chronicle as a vanilla book
  Close
```

**With no faction:**

```
Home: you belong to no faction
  Join a faction    factions taking members, and what joining costs
  Found your own    the steps, ticked off as you do them
  Chronicle         the Realm Chronicle as a vanilla book
  Close
```

### Rights and Freedoms

Charter has 18 powers (`charter.grant.Power`). For each one, the page asks the
`AuthorityResolver` who holds it in the player's faction:

- **The player holds it:** a Right, with where it comes from.
- **Someone else holds it:** a Freedom given away, with who holds it.
- **Nobody holds it, so it needs a vote:** a Freedom given to the members, which the
  player shares if they can vote.

Grouped and worded for players, not by enum name. A Sheriff in a Lordship:

```
Rights
  Arrest and judge            as Sheriff
  Deposit food                as a member

Freedoms
  Spending the treasury       the Lord, up to 32 diamonds at a time
  Where land is claimed       the Lord
  Raising camps, war          the Lord
  Taking food from granaries  the Lord
  Changing the charter        the members, by vote (you can vote)
```

The same player in a Commune would see almost every line under Freedoms read
`the members, by vote (you can vote)`. Lordship and Commune become readable
without being explained.

### Your situation

Only what is true right now, and only lines that apply:

- faction, office, and how long they have been a member
- open votes they can still cast, each a button to the vote
- confined, banished or in custody, with the time left
- items the treasury owes them

## 3. Joining and founding

This is where the work is, because two pieces do not exist yet.

**Joining needs an "ask to join" action.** Today a player can only join by invite
(`InviteMember`, then `AcceptInvite`). The Join page needs a request a faction can
see and answer: either a new `Apply` action that someone with the manage members
power admits, or a notice to those players that leads to an invite. Each listed
faction shows what joining gives away (its Freedoms page, as a newcomer would
see it), so the player knows before they join.

**Founding needs a way to get a Nexus.** Today only an operator can hand one out
(`/charter admin give nexus`). Founding is also a command (`/charter found`), and a
faction stays FORMING until a second member joins.

## 4. Decisions for the owner

### Decision 1: where else the menu opens

| Option | What it is | For | Against |
|---|---|---|---|
| **A. The book only** | Everything goes through the book | One thing to learn and build | Nothing in the keep itself feels like the seat of the faction |
| **B. The book, and the Nexus** (recommended) | Right-clicking your keep's Nexus opens the same faction pages | The keep is where the faction lives, like the wall lodestone in a Pocket Dungeons room. Same screens, so little extra work | Two ways in to the same place |
| **C. B, plus a Herald at spawn** | A character at spawn greets players with no faction and opens the Join and Found pages | Something to walk up to on day one | A second thing to build and place; the book already gives direction |

**Recommendation: B.** The book replaces Lemon's summon as you asked, and the Nexus
makes the keep the place for faction business. A Herald can come later if
players still arrive lost.

**Decided: A.** The owner chose the book only. The Nexus is not a way in to these
pages; it will have a menu of its own, designed separately.

### Decision 2: how a player gets a Nexus and founds a faction

| Option | What it is | For | Against |
|---|---|---|---|
| **A. A recipe** (recommended) | Craft a Nexus from a lodestone and a banner you have named at an anvil. Placing it founds the faction under that name, with the banner's design as the faction's banner, then raises the keep | All vanilla verbs: loom, anvil, crafting table, placing a block. The banner becomes the faction's identity | A lodestone needs a netherite ingot, which may be too late in the game for founding. A cheaper recipe could replace it |
| **B. The book sells it** | The Found page takes the diamonds from your inventory and hands you a Nexus | Simple, and the price is clear | A menu doing a game action, which goes against tangible over menus |
| **C. Found first, Nexus later** | Found from the book with a name, and the faction receives a Nexus to place when it has two members | Matches FORMING as it works now | Founding is still a typed name in a menu, and nothing in the world marks the moment |

**Recommendation: A**, with the recipe cost as an open question. It also means a
faction's name and banner come from the player's own hands.

**Decided: A**, as recommended. The recipe cost is still open.

### Decision 3: the Lordship or Commune choice

With option 2A there is no menu to pick governance at founding. Recommendation:
every new faction starts as a Lordship, and the Ashfall page has a "Become a
Commune" vote for the members. That matches §6.3, which says presets only fill
things in at founding and everything can be amended.

**Decided:** as recommended.

## 5. Open questions

1. The Nexus recipe: lodestone (netherite) or something cheaper?
2. Should the book show a faction's Freedoms to a player browsing Join, or only
   to a member? Recommended: yes, it is the point of the Join page.
3. The tutorial scenario and `TUTORIAL.md` change once founding and claiming are
   tangible: rewrite them after this is built, not before.

## 6. Not in this proposal

- Claiming land by planting a banner, and the vault as a block. Same principle,
  separate proposal, so this one stays buildable.
- The guaranteed caps (leave any time, the weekly punishment cap). The owner
  decided 2026-10-27 that the book shows current state, not the rules.
- Removing commands. They stay for operators and players who like them.
