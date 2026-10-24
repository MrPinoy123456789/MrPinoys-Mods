# Charter: design

**Status:** design, agreed in conversation 2026-10-24, nothing built.
**Working title:** Charter (mod id `charter`). Rename before the first jar if a better one turns up.
**Platform:** Fabric 26.2, JDK 25, server-side only. Vanilla clients install nothing.
**Audience:** 10 to 20 players to start; the goal is 100 or more. Every number that
depends on population is a config knob (§24).

An alternative to "Kingdom" servers. Players found organizations, govern them by
rules they choose, hold land through cores, and take land from each other by
cutting supply lines and starving keeps rather than by griefing walls. Kingdoms,
communes, mercenary companies and pirate bands all come out of one small set of
rules; none of them is a hard-coded type.

---

## 1. The pitch

Most kingdom plugins make the king a rank that owns everything. Here an
**organization** holds power and its rules decide who may use it, so a lord can be
voted out, a guard can rebel, and a group can split off and take its fort with it.

Three ideas set the mod apart:

1. **Formal authority is not physical control.** The system records who is
   *allowed* to act. Whether they can still hold the castle is decided on the map.
2. **Settled and roving are a spectrum.** A faction's character comes from how it
   spends its land: heavy keeps that never move, or cheap camps that move and raid.
   Nobody picks "kingdom" or "pirates" from a list.
3. **Castles fall to supply, not to walls.** As in real medieval sieges, a keep
   that is completely surrounded falls in the end, because nobody can carry food
   in. Walls buy time; supply lines decide wars.

## 2. Why kingdom servers die, and what answers each

| Failure | Answer in Charter |
|---|---|
| The first big faction snowballs and owns the map | Claims capped by active members (§9.1); food limits how many sieges a faction can feed (§10.6); empires strain and their parts break away (§14) |
| Offline raids: you log in to find your base gone | A full keep takes 3 to 4 days to starve (§10); the camp must be raised in the open first (§10.2); assault only once the keep is starving (§10.7) |
| The king quits and the kingdom becomes a ghost town | Organizations hold power, not players; an absent Lord loses the office automatically (§6.4); dormant land returns to the map (§16.4) |
| The politics happens on Discord | Treaties are contracts with mechanical terms (§15); everything is written into an in-world Chronicle (§17) |
| Nothing to do between wars, or for players with no faction | Farming feeds the keep; patrols watch the walls; sellswords take contracts to run food through the lines (§15.3) |

## 3. Principles

1. **Organizations hold power.** A leader exercises authority granted by an
   organization; they do not own it.
2. **Governance is configuration.** A commune and a lordship are the same entity
   with different rules.
3. **People is an identity the top-level organization owns,** and it can be forked
   when a new people splits off.
4. **The hierarchy is a tree.** Each organization has at most one parent.
5. **Contracts are graph edges,** separate from the tree. They connect anything to
   anything and never change the hierarchy.
6. **Formal authority does not guarantee physical control.**
7. **Posture is behaviour, not a type.** Settled or roving is read off a faction's
   cores and notoriety; it is never chosen.
8. **Land changes hands through supply.** A claimed chunk is protected. The way to
   take it is to cut it off and wait, not to break it.
9. **Passive is valid, never immune.** A faction that farms, builds and never
   attacks anyone is a real way to play. It can still be besieged.

A small consistent rules engine, not a simulation of every institution.

## 4. Two axes

Every faction sits somewhere on two independent axes.

| Axis | Question | Set by |
|---|---|---|
| **Governance** | Who decides? | The organization's governance config (§6) |
| **Posture** | How does it hold land? | Its cores, claims and notoriety (§9 to §13) |

A pirate commune, a raiding monarchy and a peaceful mercenary council are all
valid. The posture label the mod shows (Settled, Frontier, Roving) is derived and
cosmetic (§13.3).

## 5. Fit with the suite

Follows the contract in `docs/DESIGN.md` §4:

- **Currency only.** Prices are paid in plain cobblestone and diamond items. No
  dependency on `cobbleeconomy`; treasuries hold their own ledger of deposited
  items. Food in a granary is provisions, not a payment, so it does not set any
  rate between currencies.
- **No other mod named in code.** Voting is built in, not borrowed from `ballot`,
  because governance needs votes as first-class actions.
- **Layout:** `core/` is the pure-JDK rules model (organizations, grants, votes,
  land graph, supply and siege maths, chronicle templates) with `main()` tests;
  `fabric/` is persistence, events, commands, displays. The build keeps Minecraft
  off `core`'s classpath.
- **Config** under `config/charter/`, written on first boot, never overwritten
  after a parse failure.
- **Mixins:** Fabric API covers player break, place and use. Explosions, pistons,
  fire spread and fluids crossing a border have no event, so claim protection
  needs a few small Mixins there. That is the "genuinely no event" case the suite
  allows; list them in the README.
- **Sounds** use the quiet `Chime` pattern.

---

## 6. Organizations, membership and governance

### 6.1 Organization

| Field | Notes |
|---|---|
| `id` | Permanent UUID. Everything references ids, never names |
| `name`, `description` | Editable through governance |
| `parent_id` | Null for a top-level organization |
| `governance` | §6.3 |
| `people` | Top-level only (§7) |
| `treasury` | §8 |
| `status` | active, dormant, dissolved |
| `founded_at`, `lineage` | Lineage records splits and merges, for history only |

Each organization may contain player members and subordinate organizations. A
subordinate is a full organization with its own members, treasury, rules and
cores. The parent delegates authority to it; the parent does not own its treasury
or its land. No cycles, one parent, checked on every hierarchy change.

### 6.2 Membership

- A player is a direct member of **at most one organization per tree**, and in v0
  of **at most one tree**. Other relationships (a sellsword serving a kingdom) are
  contracts, not memberships.
- Operations: invite, accept, apply, admit, reject, leave, expel, suspend,
  reinstate, transfer within the tree. Which need a vote is governance.
- **Roles** (citizen, soldier, treasurer) are labels and grant nothing on their own.

> Membership is not ownership, and membership is not authority.

### 6.3 Governance

Each organization defines decision rules (for each action category: who proposes,
who approves, how), offices with grants attached, amendment rules, succession and
dissolution.

**Presets** fill this in at founding. They are not types; everything can be amended.

| Preset | Shape |
|---|---|
| **Commune** (v0) | Every eligible member votes on what matters. Officers by vote |
| **Lordship** (v0) | One Lord office with routine powers and a spending limit. Members keep the reserved powers: amending the charter, removing the Lord, dissolving |
| Monarchy, Council, Company | Later |

The player sees a preset and two or three settings. Full configuration lives in
data files for admins. The interesting politics comes from land and supply
pressure, not from voting thresholds.

**Votes** set electorate, method (majority, supermajority, unanimous, executive
approval), quorum, duration and ties. Small-server defaults:

- **Eligibility:** a member for at least 3 days. Stops alt-account flooding.
- **Quorum counts active members only** (seen in the last 14 days).
- **Votes close on a timer** (default 48 hours). Not voting is abstaining.
- **Ties fail.**
- **The electorate is fixed when the vote opens.** Expelling voters cannot change
  a vote already running.

A passed vote authorizes an action, which is re-validated when it runs (§18).

### 6.4 The Lord, and automatic succession

A Lord is a player holding an office. The office decides what they can do and how
they are removed. Removal through a valid procedure strips the permissions at
once; the former Lord can still stand in the throne room, which is a physical
problem for the members to solve.

**A Lord unseen for 7 days loses the office** and a succession vote opens. A
kingdom whose king quit keeps going.

## 7. People

A top-level organization's identity: name, origin story, description, traditions,
beliefs, banner pattern and colours, membership rules, revision, `derived_from`.

- **Identity only.** No mechanical traits, for at least the first year. Identity is
  the point; bonuses only invite min-maxing and fork exploits.
- **Forking** on secession copies the identity and sets `derived_from`. Edits never
  propagate either way. Lineage grants no authority.
- The People's banner pattern is the faction's war banner (§9.3) and its trophy
  (§13.4).

## 8. Treasury

- A ledger of cobblestone and diamonds deposited as physical items, owned by the
  organization, never by a player.
- **Deposits and withdrawals happen at the vault** in a keep's core chunk. With no
  keep, a faction can hold a balance but can only move it by contract or transfer.
- Spending needs a grant with a limit, or a vote. Every movement is in history.
- **Plunder:** when a keep falls, a share of the treasury (default 25%) spills as
  items in the core chunk for whoever holds it.

---

## 9. Land: cores and claims

### 9.1 Claims

- **The unit is the chunk,** the full column from bedrock to sky. Easy to compute
  adjacency on and easy to read on a map.
- Every claim belongs to exactly one **core** and must share an edge (four
  neighbours, not diagonals) with another claim of the same faction. Land grows
  outward from cores one chunk at a time.
- A claim belongs to the core it reaches in the fewest hops; ties go to the older
  core. It takes that core's kind.
- **Range:** at most N hops from its core (keep 8, camp 2). No one-chunk fences
  across the map.
- **Capacity:** each core supports a limited number of claims.
- **Active members cap total land:** a faction holds at most a set number of
  claims per member seen in the last 14 days (default 6), on top of its keeps. Two
  players with a keep cannot own half the map.
- The owner of a core is the organization that placed it, at any level of the
  tree. The Royal Guard can own the eastern fort.

### 9.2 Keep: the rooted core

- Placing a **nexus** block claims a **3x3 keep:** the core chunk and the eight ring
  chunks around it. Needs a free 3x3 area and a minimum gap from other factions'
  keeps (default 4 chunks).
- Expensive (diamonds). The price covers all nine chunks.
- Large capacity (default 40 outlying claims) and range 8.
- The core chunk holds the **granary**, the **vault** and the **charter lectern**.
- Fixed in place. A keep cannot be packed up and moved.

### 9.3 Camp: the mobile core

- Planting the faction's **war banner** raises a camp. Cheap (cobblestone).
- Small capacity (default 4 claims), range 2. No vault.
- **Raising takes time** (default 2 hours, §10.2) and is visible from afar.
- Has its own small **camp store** of food that it eats while it is besieging.
- **Pick up and replant** with a cooldown (default 30 minutes); its claims fold
  back in when it moves.
- May be planted next to foreign claims, never inside one.

### 9.4 Outlying claims

Farms, roads, mines: every claim that is not part of a keep or a camp. Each has a
small **hold** of its own, measured in hours (default 6), which refills by itself
over a day while the claim is connected to its core and not under raid.

### 9.5 Protection

Inside a claimed chunk, anyone without permission from the owning faction cannot
break, place, open containers, or use doors and buttons. Explosions, pistons, fire
and fluids do not cross into it. Players can always **walk** through; protection
stops building, not movement. Members' permissions inside their own land come from
grants; default: members build anywhere except the core chunk.

A claim stays fully protected until its granary, store or hold is empty, at which
point it is no longer a claim. There is no partial protection.

## 10. Siege: supply lines

### 10.1 The idea

Nobody breaks a keep. A keep under siege **eats**: its garrison consumes the
granary, hour by hour. Whether it survives depends on one question: **can food
still get in?** Leave a side open and runners can carry food through the lines.
Surround it completely and nothing gets in, so it falls when the granary runs dry.
Walls buy time; supply decides the war.

### 10.2 Raising the camp

Planting a war banner next to foreign land starts the camp **raising** (default 2
hours). During raising:

- The camp is **unprotected.** Anyone can break the banner.
- It sends up a tall **column of campfire smoke** visible from far away. An army's
  campfires give it away.
- There is no warning message. Seeing the smoke is the job of whoever **patrols
  the walls.** There is no watchtower block; recon is people looking.

When raising finishes, the camp becomes active, its claims become protected, and
the defending faction gets an **alert** (and a message waiting for anyone
offline). From that alert there are still days left, so it is a warning, not a
loss.

### 10.3 War footing

A keep is **besieged** while any active enemy camp claim touches one of its ring
chunks. While besieged:

- The granary drains at the **base rate:** a full granary lasts 84 hours, about
  3.5 days, with nothing coming in.
- Each besieging camp eats from its own store at half that rate. A camp with an
  empty store stops counting as besieging.

An outlying claim with an enemy camp next to it is **raided:** its hold drains at
the same base rate and stops refilling. At 6 hours of hold, a farm falls in an
afternoon, and once its protection lifts, its crops and animals are open to the
raiders. That is the pirate's living.

### 10.4 Getting food in

This is the supply line, and it is physical.

- The keep's **perimeter** is the twelve chunks that share an edge with its ring.
- A perimeter chunk is **open** unless an enemy faction claims it. Defenders' own
  outlying claims there are open, so a keep ringed by its own farms cannot be
  surrounded until those farms are raided away. Cutting a supply line starts with
  stripping the land that feeds it.
- **While besieged, food can only go into the granary if it came in through an
  open side.** When a player walks or rides into a keep chunk from an open
  perimeter chunk, the mod notes the food they are carrying (shulker boxes
  included). For the next 10 minutes they may deposit up to that much.
- **Carts follow the same rule.** A chest minecart or hopper minecart that rolls
  into a keep chunk from an open perimeter chunk has its food noted, and up to that
  much can be unloaded into the granary, by hand or by hopper. Rails are the supply
  line made physical (§10.12).
- Food that arrives any other way buys nothing: from a chest already inside, an
  ender chest, an ender pearl, a teleport, or crossing in from an enemy chunk on
  foot or by cart (over the camp, or tunnelling under it, since a claim is the
  whole column). A stockpile inside the walls only helps if someone carries it out
  and back in through an open side, which is exactly what smuggling is.
- Hoppers fed from inside the keep stop filling the granary while besieged; only
  noted food from carts gets through.
- When no siege is running, anything can feed the granary: hand, hopper, or cart.

### 10.5 Encirclement

| State | Perimeter | What it means |
|---|---|---|
| **Supplied** | No enemy camp touches the ring | No siege. The granary is not drawn on |
| **Besieged** | Some sides open | The granary drains. Runners can carry food in through the open sides; attackers have to catch them |
| **Encircled** | Every perimeter chunk held by the enemy | Nothing gets in. The keep falls when the granary is empty, unless the ring is broken |

Encircling a keep takes all twelve perimeter chunks. At 4 claims plus the banner
chunk per camp, that is about three camps, each with its own store to feed. A full
encirclement is a real commitment, usually by a coalition or a large faction, and
every camp in the ring is a target.

**Breaking the ring:** destroy one camp, or take back one perimeter chunk, and that
side is open again.

Coastal keeps are naturally hard to encircle: attackers need a platform to plant a
banner on water. Pirates are the faction best placed to blockade a port.

### 10.6 Presence

The base rate runs with nobody online. Players present change it:

- **Attackers standing in a besieging camp** speed the drain: each adds 25%.
- **Defenders standing in the keep** (the garrison on the walls) slow it: each
  removes 25%.
- The combined multiplier stays between 0.5 and 2. However hard the attackers
  play, a full keep lasts at least 42 hours; with the usual few hours of attendance
  a day it is about 3 days.

The same rule runs the other way. Defenders standing in their own claim next to a
camp drain that camp's protection (a camp holds out about an hour against a
sally), and attackers present slow it. Every drain in the game follows this one
rule.

### 10.7 Starvation and assault

Once a keep's granary falls below 25%, the garrison is **starving** and assault is
possible: attackers standing **inside** a keep chunk drain it four times faster.
Protection still stops them breaking or placing there, so they have to get over or
through the walls: a siege tower built in their own chunk, ender pearls, elytra, or
a gate left open. That is when the walls and the people on them matter most.

Above 25% there is no assault. A healthy keep cannot be stormed overnight.

### 10.8 The fall

When the granary is empty, the keep falls: the nexus breaks, the nine keep chunks
are unclaimed, the plunder share of the treasury spills in the core chunk, and its
outlying claims are reassigned or orphaned (§11).

### 10.9 After a siege

A keep whose siege is lifted (every camp gone or starved) gets a **truce** from the
same attacker for 3 days. No chain sieges against one target.

### 10.10 Supply lines everywhere

The same idea applies to all land:

- An outlying claim **cut off** from every core of its faction does not refill its
  hold and cannot be resupplied. It enters the orphan grace (§11.2).
- **Camps have supply lines too, and raids end by being cut off.** A camp's
  perimeter is every chunk sharing an edge with its claims. Its store can only be
  refilled with food carried in through an open side of that perimeter, by the
  same rule as a keep. When every side is closed (claimed by the defenders, or by
  anyone hostile to the camp), the camp is **cut off:** nothing can be carried in,
  and its store burns at the full base rate instead of half. Defenders cut a raid
  off by claiming the chunks around the camp, or by raising camps of their own
  beside it. A raid pushed deep into enemy land is surrounded by enemy claims
  already, so the deeper the raid, the easier it is to cut off. A raiding party
  that does not keep a road home open starves and goes home.
- **The camp store is a real chest** at the banner and works exactly like a
  granary: fed by hand or by cart, through an open side, eaten item by item.
- **Wars are limited by food.** A faction can only keep as many camps fed as its
  farms (or its raids) can supply.

### 10.11 Food and the granary

Provisions are measured in **nourishment,** the hunger points a food restores: a
steak is worth more than a carrot. A full granary is 84 hours at the base rate
(default 60 nourishment an hour, about 5,000 in all: roughly ten stacks of
steak). Filling it is a farming project, which is why a settled kingdom looks like
farmland and not like an obsidian box.

**The granary is a real chest.**

- Its provisions are its actual contents, valued by nourishment. The time left
  shown on the granary and nexus is read from what is inside.
- **The garrison eats real items.** While besieged, food is removed hour by hour,
  least nourishing first, so bread added in an emergency does not burn through the
  steak.
- **It refuses food past the cap,** from a player or a hopper, so a siege lasts 3
  to 4 days however big the farm. Spare food lives in ordinary chests.
- Members open it according to their grants; outsiders cannot open it while the
  keep stands.
- **When the keep falls, the granary is loot.** Whatever is left inside belongs to
  whoever holds the core.

### 10.12 Rails

Settled factions can automate supply: farms feeding hoppers, minecart lines
carrying food from distant fields into the granary. In peace this is a building
project players will show off. In war it is the supply line, and it can be cut.

- **Rails through neutral land** can be broken by anyone. Cheap to lay, easy to
  cut.
- **Rails through a faction's own claims** are protected until that claim is
  raided. Securing a supply road means spending claims on it, and the member cap
  (§9.1) makes that a real choice: guard the road, or expand the farms.
- **Attackers' first move** is often to raid the corridor chunks and cut the line
  before raising camps around the keep.
- **Attackers' camps can be fed by rail too,** and cutting the raiders' line is how
  a raid gets cut off (§10.10).
- **Theft:** a cart on neutral land is fair game. Players can break it and take the
  cargo, as vanilla already allows. Highway robbery on unclaimed stretches is a
  role for players with no faction, and escorting shipments is a sellsword job
  (§15.3).
- **Carts inside claims are protected** like blocks: outsiders cannot break them or
  open them. Fabric API's attack-entity and use-entity events cover this without a
  Mixin.

## 11. When a keep falls

### 11.1 Reassignment

Each outlying claim of the fallen keep that still connects to another core of the
same faction moves to that core. Everything else is orphaned. If the receiving
core has no room, the owner picks which claims to keep during the grace period;
otherwise the farthest claims drop first.

### 11.2 Orphan grace

Orphaned and cut off claims go **contested** for a grace period (default 30
minutes). They stay protected but cannot be refilled. Planting a core that links
to them saves them; a camp counts. Otherwise they are unclaimed when the timer
ends. Without the grace, one lost keep erases a kingdom in an instant.

### 11.3 What does not fall

Losing every keep does not dissolve the organization. Members, governance,
treasury balance (minus plunder), contracts and People survive. A kingdom with no
land is a government in exile; it can raise a camp and start over as a roving band.

## 12. Protection for new factions

A faction founded in the last 3 days cannot be besieged or raided, and the grace
ends early if it raises a camp against anyone. New players get time to build a
granary before the world can starve them.

## 13. The spectrum in motion

### 13.1 Settling

A camp held unmoved for long enough (default 3 days), with a free 3x3 and the keep
gap, can be **upgraded to a keep** by paying the difference. A pirate band that
finds a good cove and stays becomes a settlement.

### 13.2 Notoriety

- Rises with every camp a faction raises against someone and every claim it takes.
  Decays daily.
- High notoriety makes the faction's own keeps eat faster when besieged (up to 1.5
  times the base rate, inside the same 0.5 to 2 bound).
- **Pirates with no keep** barely care. **A raiding kingdom** puts its castle at
  risk every time it raids. **A trading city** keeps it near zero and lasts longest
  under siege. This is how a kingdom pays for raiding with its castle.

### 13.3 Posture label

Cosmetic, on the faction page and map, derived from keep land, camp land and
notoriety:

| Label | Roughly |
|---|---|
| Settled | Mostly keep land, low notoriety |
| Frontier | A mix of keeps and camps |
| Roving | Camps only |
| Raiders, Warlords | The above with high notoriety |

### 13.4 Trophies

A destroyed camp drops its war banner. Hung in a keep, it becomes a trophy, and the
Chronicle remembers whose it was.

## 14. Empires strain

A big faction is not stopped by nerfing it; it is stopped by giving its own parts
a reason to leave. Empires historically fell to overextension and revolt.

- **Distance costs.** Outlying claims far from their core hold less and refill more
  slowly.
- **Unrest** builds in a subordinate organization when its parent takes a lot from
  it: tribute contracts, few voting rights in the parent, distance from the
  parent's keeps.
- **High unrest makes secession cheap:** it skips the cooldown, and the
  subordinate's cores and land go with it (§16.1). The Royal Guard that owns the
  eastern fort will leave if the crown squeezes it.

The winning move for a large faction becomes governing well, not just fighting
well. Unrest needs tribute to exist, so it ships after contracts.

## 15. Contracts, treaties and sellswords

### 15.1 Contracts

- Structured terms only: recurring payments, a delivery by a deadline, attached
  grants, access to named claims, escrow. Free text may explain a contract but
  enforces nothing.
- Lifecycle: draft, offered, accepted, active, fulfilled, breached, terminated,
  expired. Breach is detected only for observable terms and goes into the
  Chronicle.
- A contract never changes membership or hierarchy. Vassalage is a contract, not a
  tree link.

### 15.2 Peace treaties

A war ends with a treaty, a contract between the two sides.

- **War score** is earned per pair of enemies (claims taken, camps destroyed, keeps
  starved, sieges broken), so nobody farms points off a fake enemy.
- War score buys **terms:** reparations in diamonds, ceding named claims, returning
  captured claims, releasing a vassal, vassalage (tribute and limits for a set
  time).
- **Conditional surrender** is a term: "these chunks pass to the besieger on
  Saturday unless the siege is lifted." A defender can give up part of its land on
  terms instead of losing a starving keep.
- After a treaty the two sides cannot fight each other for a cooldown period, so a
  strong faction cannot loot the same weak one again and again.

### 15.3 The contract board

A board at spawn (and one in any keep that wants one) where factions post jobs
with diamonds held in escrow: hold this chunk, break this camp, carry 500
nourishment into a besieged keep, escort a cart shipment, cut a rail line. Players with no faction become **sellswords.**
Running food through the lines is the best paid job in the game, and the Chronicle
records each sellsword's finished and broken contracts as their reputation.

## 16. Political transitions

### 16.1 Secession

1. A new top-level organization is created.
2. The members and subordinates that leave move with it.
3. **Cores owned by the seceding organization go with it,** with their claims and
   granaries. This is the strongest consequence in the mod and is intended.
4. People is forked if they want.
5. Open contracts and grants with the old parent are ended and recorded.

Limits: a top-level organization needs at least 2 founders, and a player cannot
found or secede more than once every 7 days (unless unrest waives it, §14).

### 16.2 Rebellion and defection

All allowed: leaving, leadership changes, a subordinate breaking away, breaching a
contract, besieging your former parent. Holding command never moves a treasury or
members on its own.

### 16.3 Merger and dissolution

Merger needs explicit choices for identity, governance, members, cores, treasury
and contracts. Dissolution ends the organization's authority, refunds nothing,
releases its cores (claims orphan as in §11.2) and leaves subordinates as new
top-level organizations.

### 16.4 Dormancy

A faction with no member seen for 30 days goes dormant: its granaries and holds
drain slowly until its land returns to the map. Abandoned kingdoms do not lock up
the world forever.

## 17. The Chronicle

Every event the history records (votes, successions, camps raised, keeps starved,
treaties, secessions, breached contracts) is also written as **prose**, from
templates filled with names, places and numbers. For example:

> *In the second week of Frostfall, the Eastern Guard rose against the Crown of
> Ashfall. They held the river fort for six days. Ashfall's granary ran dry before
> any relief arrived, and the fort is theirs.*

- Each keep's charter lectern holds that faction's chronicle as a written book.
- A **Realm Chronicle** lectern at spawn holds the server's.
- Templates live in data files so servers can rewrite the voice.

This puts the politics back into the game. A betrayal arranged off-server still
ends up in the Chronicle under the names of whoever voted for it.

## 18. Authority, grants and the action engine

### 18.1 Grants

A grant records grantor, recipient (player, office or organization), actions,
scope (spending limit, targets, region, duration), whether it may be re-delegated,
revocation conditions and its source. Example powers: manage members, appoint,
spend, deposit to and withdraw from the granary, raise camps, claim, place cores,
negotiate contracts, amend governance. A grant can only pass on authority its
grantor actually has.

### 18.2 Validation

Every consequential operation goes through one action engine: resolve actor and
target; walk the authority chain to its source and check every grant is still
valid and in scope; resolve any required vote; check preconditions and costs;
apply; record in history and the Chronicle.

Permissions are never copied onto a recipient; they are traced at execution time,
so revoking a grant ends everything derived from it at once. Only descendant sets
are cached. A proposal that passes is re-validated when it runs. Failed actions
return a plain reason. Admin overrides go through the engine too, flagged as
admin.

### 18.3 Concurrency

The engine runs on the server thread, so actions are already serialised. Anything
that waits (a vote, a siege tick, a camp raising) is re-checked when it resumes.

## 19. What players touch

Choices are objects in the world wherever possible; clickable chat pages (in the
style of `ballot`) for reading and voting.

| Object | Does |
|---|---|
| **Nexus block** | The keep's heart. Shows faction name and time left in the granary |
| **Granary** | A chest in the core chunk. Food in by hand, hopper or cart. Shows "Provisions: 3 days, 6 hours" |
| **Rails** | Minecart lines carry food to granaries and camp stores; cut them to cut supply |
| **Vault** | In the core chunk. Deposit and withdraw currency |
| **Charter lectern** | In the core chunk. Faction page, open votes, the faction's Chronicle |
| **War banner** | Raises a camp. Pick up, replant, capture, hang as a trophy |
| **Camp store** | At the banner. Feed the camp |
| **Contract board** | Post and take jobs |
| **Realm Chronicle** | At spawn. The server's history |
| **Borders** | Particles along chunk edges while holding a war banner or a compass |

Commands kept to a handful: `/charter` (your faction page), `/charter found`,
`/charter map`, `/charter vote`.

## 20. Persistence

- Stable ids everywhere. Hierarchy, contracts, grants and claims stored separately.
- **Recommended:** world saved data for current state, plus an append-only history
  file per world (the Chronicle is rendered from it). All writes happen on the
  server thread, so a multi-record action is applied in memory and saved as one
  snapshot. SQLite stays an option if history grows past what a flat file handles.
- Running sieges, camp raising timers, carry-in allowances and grace timers are
  saved, so a restart does not lift a siege or reset a granary.
- On load, repair rather than crash: drop grants whose source is gone, re-parent
  orphaned organizations to top level, orphan claims whose core is missing.

## 21. Scope

**v0, the first playable slice**

- Organizations, the tree, membership; Commune and Lordship; offices, grants,
  timed votes, automatic succession
- Treasury and vault
- Keeps, camps, outlying claims, adjacency, capacity, range, member cap, protection
- The full supply siege: raising, war footing, carry-in, encirclement, presence,
  starvation and assault, the fall, truce
- Core loss, orphan grace, new-faction protection
- Action engine, history and the Chronicle

**v1**

- Contracts with escrow; peace treaties and conditional surrender; the contract
  board
- Secession with a People fork; settling; notoriety and posture labels; trophies;
  dormancy

**Later**

- Unrest and distance costs (needs tribute contracts)
- Monarchy, Council and Company presets

## 22. Acceptance tests

A to H are from the original spec; I onward cover land and supply. Most are pure
`core` tests: the land graph, the supply maths and the grant chain are plain Java.

| Test | Scenario |
|---|---|
| **A Commune** | Four players found a commune, vote through a spend and appoint an officer. Nobody acts beyond what the rules grant |
| **B Lordship** | The four name a Lord with a spending limit. Members keep reserved powers and remove the Lord by the agreed procedure |
| **C Delegated command** | A kingdom grants its guard the power to raise camps. The guard cannot withdraw unlimited funds or appoint the monarch |
| **D Sellswords** | A company contracts with two kingdoms and stays its own top-level organization with its own treasury |
| **E Identity fork** | A group secedes, forks People, edits it. Later edits stay separate |
| **F Contested authority** | A kingdom revokes its guard's grants. The guard loses permissions at once, keeps the fort it owns, and can besiege the kingdom |
| **G Grant limits** | A kingdom tries to grant power over another kingdom's treasury. Rejected |
| **H Persistence** | After a restart: structure, People, balances, contracts, grants, claims, granaries, running sieges and carry-in allowances are restored |
| **I Raising** | A camp is breakable by anyone while raising and protected once active; the alert fires only when it becomes active |
| **J Endurance** | A full granary lasts 84 hours besieged with no deposits and nobody present, and never less than 42 hours whoever is present |
| **K Carry-in** | During a siege, food carried in from an open perimeter chunk can be deposited; food from a chest inside, an ender chest, a pearl or across an enemy chunk cannot |
| **L Encirclement** | With all twelve perimeter chunks enemy-held, no deposit is possible; breaking one camp reopens that side |
| **M Starvation gate** | Assault drain is impossible above 25% and four times base below it |
| **N Camp supply** | A camp with an empty store stops besieging. A camp whose every perimeter chunk is claimed by hostile factions is cut off: it cannot be refilled and burns at the full base rate |
| **K2 Carts** | During a siege, a cart rolling in from an open perimeter chunk can unload its food into the granary; a cart rolling in across an enemy chunk, or loaded inside the keep, cannot. In peace any hopper or cart can fill it |
| **K3 Granary** | The garrison eats real items, least nourishing first; the granary refuses food past 84 hours of base drain; its contents are loot when the keep falls |
| **K4 Rail protection** | Outsiders cannot break rails or carts inside a claim; on neutral land they can |
| **O Raid** | A camp next to a farm drains its hold in 6 hours; the hold refills only while connected and unraided |
| **P Keep loss** | A faction with two linked keeps loses one; connected claims move to the other within capacity, the rest orphan with grace |
| **Q New faction** | A faction under 3 days old cannot be besieged; the grace ends if it raises a camp |
| **R Truce** | A lifted siege bars the same attacker from that keep for 3 days |

## 23. Decisions

### Settled in conversation, 2026-10-24

| Decision | Outcome |
|---|---|
| Spectrum | Settled and roving come from how a faction holds land, never from a type |
| Cores | Keeps (3x3, expensive, fixed) and camps (cheap, mobile); every claim chains back to a core |
| How land is taken | Supply: besieged keeps eat their granary; food gets in only through open sides; encircled keeps fall |
| Siege length | A full granary lasts 3 to 4 days with no resupply |
| Presence | Speeds or slows the drain within 0.5 to 2; never required |
| Warning | The camp raises in the open with a smoke column; no watchtower mechanic, patrols watch the walls |
| Land layers | The keep shares the granary; outlying claims have small holds of hours |
| Garrison | Defenders present slow the drain |
| Passive factions | Valid, never immune |
| Raids | End when the camp is cut off from home |
| Granary | A real chest; the garrison eats real items, least nourishing first; capped at 84 hours |
| Rails | Peacetime automation is free; during a siege only carts entering through an open side count; rails and carts are protected in claims, fair game on neutral land |
| Camp store | A real chest, same rules as a granary |
| Pillars in | Supplies, empire strain, the Chronicle, sellswords |

### Considered and rejected

| Idea | Why not |
|---|---|
| Watch windows (fixed daily vulnerability hours, as in EVE Online) | Long sieges and a visible raising phase protect offline players without anyone promising to be online at a set time |
| Watchtower detection block | Recon should be people patrolling, not a notification |
| Breaking blocks to take land | Leads to the obsidian box and the flying machine (KingdomsX's own wiki names this problem) |
| Defence and war paid in cobblestone and diamonds | Would imply a cobblestone to diamond rate; food is better and makes farming matter |
| Mechanical People traits | Min-maxing and fork exploits; identity is enough |
| Server-wide war events | Not needed once sieges take days |

### Still open

| # | Question | Recommendation |
|---|---|---|
| D1 | Can several factions besiege the same keep at once? | Yes; their camps all count toward encirclement, but the drain rate is the keep's, not per camp |
| D2 | Food values | Nourishment (hunger points) per item, from a data file. Maybe a bonus for variety later |
| D3 | Unrest formula | Decide when tribute contracts exist |
| D4 | Plunder share | 25% of the treasury when a keep falls |
| D5 | Persistence engine | World saved data plus an append-only history file |
| D6 | Ships | A camp replanted at a dock. Claims that move with a vehicle are out of scope |
| D7 | Can a player carry food in for a faction they do not belong to? | Yes, if the keep's owner grants them deposit rights. That is how sellswords and allies run supplies |
| D8 | Mod name | Charter, until something better |

## 24. Tuning knobs

All in `config/charter/charter.json`. Starting values, to be tuned in play.

| Knob | Default |
|---|---|
| Keep price | 64 diamonds |
| War banner price | 256 cobblestone |
| Keep capacity, range | 40 outlying claims, 8 hops |
| Camp capacity, range | 4 claims, 2 hops |
| Claims per active member | 6, plus keeps |
| Keep gap between factions | 4 chunks |
| Base drain | 60 nourishment an hour |
| Granary capacity | 84 hours of base drain |
| Camp store burn | Half the base drain |
| Outlying hold | 6 hours, refills over 24 hours |
| Camp protection against a sally | 1 hour of base drain |
| Presence effect | 25% per player, total between 0.5 and 2 |
| Notoriety effect | Up to 1.5 times, inside the same bound |
| Starvation threshold, assault rate | 25%, 4 times |
| Camp raising time | 2 hours |
| Carry-in allowance window (players and carts) | 10 minutes |
| Truce after a lifted siege | 3 days |
| New-faction protection | 3 days |
| Banner move cooldown | 30 minutes |
| Orphan grace | 30 minutes |
| Plunder share | 25% |
| Settle time | 3 days |
| Absent Lord loses office | 7 days |
| Vote eligibility, duration, active window | 3 days, 48 hours, 14 days |
| Found or secede cooldown | 7 days |
| Dormancy | 30 days |

## 25. Prior art

- **Medieval sieges.** Most castles fell to blockade, starvation, treachery or
  negotiated surrender, not to breached walls. Mining and assault came late,
  against a weakened garrison. Walls bought time for relief. Every part of §10 is
  built on this.
- **KingdomsX** (Spigot plugin, wiki pages cloned to `A:\tmp\kingdomsx-ref`). Its
  Powercell protecting the 3x3 around it is close to our keep ring; invasions end
  if the attacker dies or logs out; its peace treaty addon (war points per enemy,
  terms, vassalage, treaty cooldown) shaped §15.2. Its champion mobs, item to
  points converter and heavy menus were not taken.
