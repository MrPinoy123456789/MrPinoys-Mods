# Charter: design

**Status:** design, agreed in conversation 2026-10-24, nothing built. The
implementation plan is `charter/PLAN.md`; its decisions are folded in here (§23).
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
| The first big faction snowballs and owns the map | Claims capped by active members (§9.1); more members means more mouths to feed (§10.11); food limits how many sieges a faction can feed (§10.10); empires strain and their parts break away (§14) |
| Offline raids: you log in to find your base gone | A full keep takes 3 to 4 days to starve (§10); the camp must be raised in the open first (§10.2); assault only once the keep is starving (§10.7) |
| The king quits and the kingdom becomes a ghost town | Organizations hold power, not players; an absent Lord loses the office automatically (§6.4); dormant land returns to the map (§16.4) |
| The politics happens on Discord | Treaties are contracts with mechanical terms (§15); everything is written into an in-world Chronicle (§17) |
| Jails and executions used to remove people for days or weeks | Sentences are imposed contracts with hard caps (1 day at most), a weekly limit per player, food costs for the jailer, and the judge's name in the Chronicle (§15.5) |
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
  claims per member seen in the last 14 days (default 6), on top of its keeps. Camp
  claims count toward this cap; the banner chunk itself does not. Two players with
  a keep cannot own half the map.
- **Overworld only** at first (a knob lists the dimensions where claims may be
  made).
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

Farmland in a claim cannot be trampled by outsiders.

A claim stays fully protected until it runs out, at which point it is no longer a
claim. There is no partial protection. What runs out depends on the claim:

| Claim | Runs out when |
|---|---|
| Keep chunks | The granary is empty (§10.8) |
| Outlying claims | The hold is empty (§10.3) |
| Camp claims | The **sally hold** is empty (§10.6). An empty camp store stops the camp besieging but leaves it protected |

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

- The granary drains at the keep's **base rate,** which depends on how many mouths
  it feeds (§10.11). A full granary of preserved food lasts about 84 hours, about
  3.5 days, with nothing coming in, whatever the faction's size, at zero
  notoriety. (Even preserved food spoils slowly, so the real figure is a few hours
  under 84; tests allow 7%.)
- Each besieging camp eats from its own store (default 30 nourishment an hour). A
  camp eats only while besieging or raiding, not while it is raising. A camp with
  an empty store stops counting as besieging.
- **Siege time pauses while the server is down.** Every siege, spoilage, raising
  and grace clock stops with the server and resumes with it.
- **Several factions can besiege the same keep at once.** All their camps count
  toward encirclement, the drain is the keep's (not per camp), and the truce after
  a lifted siege applies to each attacker separately.

An outlying claim with an enemy camp next to it is **raided:** its hold (measured in
hours, not food) counts down and stops refilling. At 6 hours of hold, a farm falls
in an afternoon, and once its protection lifts, its crops and animals are open to the
raiders. That is the pirate's living.

### 10.4 Getting food in

This is the supply line, and it is physical.

- The keep's **perimeter** is the twelve chunks that share an edge with its ring.
- A perimeter chunk is **open** unless it is claimed by a faction that has an
  active camp touching this keep's ring. A neutral neighbour's farm stays open:
  only the besiegers close sides. Defenders' own outlying claims there are open, so a keep ringed by its own farms cannot be
  surrounded until those farms are raided away. Cutting a supply line starts with
  stripping the land that feeds it.
- **While besieged, food can only go into the granary if it came in through an
  open side.** When a player walks or rides into a keep chunk from an open
  perimeter chunk, the mod notes the food they are carrying (shulker boxes
  included). For the next 10 minutes they may deposit up to that much.
- **Carts follow the same rule.** A chest minecart or hopper minecart that rolls
  into a keep chunk from an open perimeter chunk has its food noted, and up to that
  much can be unloaded into the granary, by hand or by hopper. Rails are the supply
  line made physical (§10.12). **Chest boats** count as carts, so a river can feed
  a coastal keep. Pack animals with chests (donkeys, mules, llamas) carry nothing
  that counts in the first build.
- A player or cart that crosses more than 16 blocks between two checks is treated
  as having teleported, so a fast elytra flight or minecart cannot skip the check.
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

**Sallies.** Each camp claim has a **sally hold** of 1 hour. It drains only while
defenders stand in their own claim next to it: each defender present adds one unit
of drain, each attacker present in the camp takes one away. It refills when nobody
is contesting it. When it empties, the camp's claims lose protection and the
banner can be broken. An hour of defenders standing at the camp's edge, unopposed,
breaks it.

**Alts.** Presence counts only members of at least 3 days' standing, so a faction
cannot slow a siege by logging in fresh accounts. Logged-out players are never
present.

### 10.7 Starvation and assault

Once a keep's granary falls below 25% **of its storage limit** (a fixed 21 hours of
drain), the garrison is **starving** and assault is possible: attackers standing
**inside** a keep chunk drain it four times faster. The assault factor multiplies
on top of the presence bound, so the combined rate can reach 8 times base; the
42 hour floor in §10.6 applies to presence from outside the keep, not to assault.
Protection still stops them breaking or placing there, so they have to get over or
through the walls: a siege tower built in their own chunk, ender pearls, elytra, or
a gate left open. That is when the walls and the people on them matter most.

Above 25% there is no assault. A healthy keep cannot be stormed overnight.

### 10.8 The fall

When the granary is empty, the keep falls. The besieger chooses:

- **Raze:** the nexus breaks, the nine keep chunks are unclaimed, the plunder share
  of the treasury spills in the core chunk, and its outlying claims are reassigned
  or orphaned (§11).
- **Occupy:** an imposed contract on the occupation basis (§15.4). The keep stays
  recorded as the defender's, but the occupier controls it: access, the granary,
  tribute while it lasts. Plunder still spills. The occupation ends when its cap
  runs out (default 7 days), when a treaty settles it, or when the defenders starve
  the occupiers out by the same siege rules with the sides swapped. Then control
  returns to the owner unless a treaty ceded the keep. Recognized claim and
  physical control, separated, exactly as spec rule 6 wants.

### 10.9 After a siege

A keep whose siege is lifted (every camp gone or starved) gets a **truce** from the
same attacker for 3 days. No chain sieges against one target. An occupation that runs
out ends the same way: the occupier leaves under a 3 day truce, or a keep handed back
with an empty granary would fall at once to the camp still beside it (found in M11).

### 10.10 Supply lines everywhere

The same idea applies to all land:

- An outlying claim **cut off** from every core of its faction does not refill its
  hold and cannot be resupplied. It enters the orphan grace (§11.2).
- **Camps have supply lines too, and raids end by being cut off.** A camp's
  perimeter is every chunk sharing an edge with its claims. Its store can only be
  refilled with food carried in through an open side of that perimeter, by the
  same rule as a keep. A side of a camp is closed when it is claimed by the faction
  the camp is besieging or raiding, or by any faction with an active camp against
  the camp's owner; neutral claims stay open. When every side is closed, the camp
  is **cut off:** nothing can be carried in,
  and its store burns twice as fast. Defenders cut a raid
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
steak is worth more than a carrot. Filling a granary is a farming project, which
is why a settled kingdom looks like farmland and not like an obsidian box.

**Mouths.** A keep feeds its share of the faction: its owning organization's
active members divided by the number of keeps it holds, never fewer than 3. Each
mouth eats 10 nourishment an hour while besieged. A six-member faction with one
keep drains 60 an hour; a twenty-member kingdom drains 200. A bigger faction needs
bigger farms and suffers more when its supply is cut, which is one more brake on
the snowball, as it was for real empires.

**Storage.** A granary holds 84 hours of its keep's drain without trouble: about
5,000 nourishment for six mouths (roughly ten stacks of steak), about 17,000 for
twenty. It grows and shrinks as active membership changes. Prisoners add mouths
but do not raise the storage limit, so they make a keep fall sooner.

A large faction's granary does not fit in one chest, so a granary is **linked
chests:** up to 8 single chests (a knob) placed together in the core chunk act as
one granary.

**Spoilage** takes the place of a hard cap, as damp and rats did in real
storerooms:

- Food in a granary slowly spoils, in peace and in war, at a rate that depends on
  the food. Preserved food (bread, dried kelp, baked potatoes) keeps for weeks;
  cooked meat for days; raw meat and fresh crops go off quickly.
- **Past the storage limit, everything spoils fast.** An overpacked storeroom rots.
  Stockpiling beyond 84 hours wastes itself, so siege length stays at 3 to 4 days
  for a well-run keep.
- What to store becomes a real decision: preserved food holds out longest under
  siege, and a granary full of fresh meat falls short of 84 hours.
- Keeping a granary full takes a steady supply from the farms, not one big haul.

Only granaries and camp stores spoil. Ordinary chests are untouched, but their
food cannot reach a besieged granary (§10.4), so it only helps before the siege
begins.

**The granary is a real chest.**

- Its provisions are its actual contents, valued by nourishment. The time left
  shown on the granary and nexus is read from what is inside.
- **The garrison eats real items.** While besieged, food is removed hour by hour,
  least nourishing first, so bread added in an emergency does not burn through the
  steak.
- **There is no hard cap.** Food past the storage limit spoils fast instead, so
  spare food belongs in ordinary chests.
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

## 15. Contracts, treaties, justice and sellswords

### 15.1 Contracts

- Structured terms only, from one shared vocabulary (§15.4): payments, deliveries,
  grants, access, escrow, and the restrictive terms used by imposed contracts.
  Free text may explain a contract but enforces nothing.
- Lifecycle: draft, offered, accepted, active, fulfilled, breached, terminated,
  expired. Breach is detected only for observable terms and goes into the
  Chronicle.
- A contract never changes membership or hierarchy. Vassalage is a contract, not a
  tree link.

### 15.2 Peace treaties

A war ends with a treaty, a contract between the two sides. A treaty is **signed**
by both (§15.4): a defeated faction that accepts terms gets a better deal than one
that has terms imposed on it.

**War goals and war exhaustion** (decided 2026-10-24, replacing war score; the
model follows Stellaris):

- **A war goal is declared up front.** When a faction first raises a camp against
  another, it declares at most two goals, chosen from the term vocabulary (§15.4)
  within server caps: cede named claims, tribute for a set time, vassalage,
  release prisoners. The defender knows from day one what is at stake.
- **Declaring costs something,** in diamonds and notoriety, more for harsher goals.
- **Both sides build war exhaustion** from what the war costs them: camps lost,
  claims lost, keeps fallen, food burned in sieges, prisoners taken, and time at
  war. Exhaustion measures the thing Charter is about, and it cannot be farmed off
  a fake enemy without really spending camps and food.
- **At 100% exhaustion the war ends.** If the defender is exhausted, the attacker
  may enforce its declared goals as an imposed contract on the war goal basis. If
  the attacker is exhausted, the war ends in a **status quo peace:** occupations
  end and nothing else changes hands.
- **Before that, either side may sign any treaty** they agree on, which is always
  cheaper than an imposed one.
- The first playtest build records every war event per pair of factions but does
  not yet run treaties, so both the old war score idea and this model can be
  checked against real play before treaties are built.
- **Conditional surrender** is a term: "these chunks pass to the besieger on
  Saturday unless the siege is lifted." A defender can give up part of its land on
  terms instead of losing a starving keep.
- After a treaty the two sides cannot fight each other for a cooldown period, so a
  strong faction cannot loot the same weak one again and again.

### 15.3 The contract board

A board at spawn (and one in any keep that wants one) where factions post jobs
with diamonds held in escrow: hold this chunk, break this camp, carry 500
nourishment into a besieged keep, escort a cart shipment, cut a rail line, break a
prisoner out. Players with no faction become **sellswords.** Running food through
the lines is the best paid job in the game, and the Chronicle records each
sellsword's finished and broken contracts as their reputation.

### 15.4 Signed and imposed contracts

Every exercise of power over another government's or player's rights is a
contract: a treaty, a vassalage, an occupation, a prison sentence, an execution.
What differs is **consent.**

| | Signed | Imposed |
|---|---|---|
| How it starts | Both parties accept | One party imposes it, on a **basis** (below) |
| Breaking it | **Breach:** a mark on the faction page and in the Chronicle | **Resistance:** escaping, rebelling or fighting back carries no breach mark |
| Notoriety | None | Costs the imposer notoriety, more for harsher terms |
| Limits | Whatever both agree, within the server's caps | Hard caps per basis on duration and severity |
| Chronicle | Recorded | Recorded under the name of whoever imposed it |

Imposing is always possible but never free, and signing is always cheaper. That
pushes players toward negotiation, which is the politics the mod is for.

**A basis** is the leverage that lets one party impose terms on another. Each basis
allows certain terms, up to a cap:

| Basis | Comes from | Allows |
|---|---|---|
| **Custody** | Holding a player you arrested on your own land (§15.5) | Confinement, bond, banishment, a capped fine |
| **War goal** | A declared goal, once the enemy's war exhaustion reaches 100% (§15.2) | Only the goals that were declared: tribute, ceding claims, relation locks, claim limits, vassalage, release |
| **Occupation** | Starving a keep and choosing to occupy it (§10.8) | Control of the keep without owning it, tribute while it lasts |

**The term vocabulary** is shared by every contract, signed or imposed, and each
term is something the mod can actually enforce:

| Term | Enforced as |
|---|---|
| Payment, tribute, fine | Scheduled transfers from a treasury or a player's deposit |
| Delivery | Items into a named chest by a deadline |
| Grant | Authority added through the action engine (§18) |
| Access | Who may build or use what in named claims |
| Confinement | The player is held in a named cell; teleports, pearls and commands are blocked; the walls do the rest |
| Tether | The player is held to named land (pushed back when they try to leave) instead of a cell; used by a bond or an indenture |
| Banishment | The player is pushed out of named land on entry |
| Respawn delay | The player waits before respawning |
| Relation lock | A faction cannot raise camps against named factions |
| Claim limit | A lower cap on a faction's claims |
| Cede | Named claims change owner |
| Release | Prisoners freed, vassals released, occupations ended |

Adding a new kind of power over others means adding a term, not a system, and the
caps, notoriety and Chronicle come with it for free.

### 15.5 Justice

Jails and executions on kingdom servers fail because punishment is unbounded,
free for the punisher and decided by one person, so it becomes a tool for
removing people. Here they are imposed contracts on the custody basis, with the
same caps for everyone.

- **Due process is governance.** Arresting and sentencing need a grant: a Judge
  or Sheriff office, or a vote. A Lordship can let the Lord judge alone; a Commune
  can require a trial vote.
- **Arrest is physical.** The office allows a sentence; the officer still has to
  catch the player, on their own faction's land, by using a lead on them.
- **Prison is a place, and prisoners eat.** A cell is an enclosed space in a keep,
  marked as a cell by the faction, and every prisoner is an extra mouth on its
  granary. Long sentences cost real food; a
  besieged keep cannot afford many prisoners; when a keep falls, its prisoners
  walk free. A jailbreak is something players can do, by force or by hiring
  sellswords.
- **Execution is banishment, not a death ban.** A short respawn delay, then
  banishment from that faction's land. The executed player keeps their inventory:
  the drama is the wait and the banishment, not loot. No one is locked out of the
  server.
- **Hard limits:** confinement 1 hour to 1 day; respawn delay at most 1 hour;
  banishment at most 1 day; a weekly cap on total punishment per player across all
  factions, so nobody can be chain-jailed; a cooldown before the same faction can
  sentence the same player again. Confinement, bonds and respawn delays count
  toward the weekly cap; banishment does not, because it restricts land, not play.
- **Sentences run in real time,** capped at 1 day. The point is the roleplay
  moment, not making someone sit out.
- **A custody fine** is allowed, capped at 16 diamonds, paid from the prisoner's
  own vault deposit and never from their faction's treasury. A player with no
  deposit pays nothing.
- **Jailing an outsider is an act of war.** Imprisoning a member of another
  faction counts toward the jailers' war exhaustion in any war between them, and
  is grounds for that faction to declare war goals against the jailers. Jailing an
  unaligned player costs notoriety, as raiding does.
- **Bond** (a knob, off by default in the first build): instead of a cell, a
  prisoner can be tethered to the jailer's land with build and use access so they
  can work. A bond is capped and counted exactly like confinement, the bonded
  player is a mouth on the granary, and walking out by force or being freed is
  resistance, not breach. What a bonded player gathers is theirs; only a delivery
  quota is enforced.
- **Indenture** (with signed contracts, after the first build): a player may
  *sign* a contract to work for an organization for longer than any sentence, up
  to 7 days, tethered to named land with a delivery quota and a wage paid from
  escrow. Leaving early is breach on their record, or either side can end it early
  for a buyout set in the contract. **No indenture may be offered to a player in
  custody or sentenced in the last 24 hours,** so a sentence can never be turned
  into long signed servitude. The Chronicle names both parties of every bond and
  indenture.
- **Pardons:** a higher organization in the tree can pardon, and a treaty term can
  release prisoners, so prisoners become bargaining chips in peace deals.
- Every arrest, sentence, escape and pardon is in the Chronicle under the judge's
  name.

## 16. Political transitions

### 16.1 Secession

1. A new top-level organization is created.
2. The members and subordinates that leave move with it.
3. **Cores owned by the seceding organization go with it,** with their claims and
   granaries. This is the strongest consequence in the mod and is intended.
4. People is forked if they want.
5. Open contracts and grants with the old parent are ended and recorded.

As built (2026-10-28): a sub-faction secedes as a whole by a vote of its members, whatever its
form, passing when more than half of ALL members eligible to vote say yes (not voting and
abstaining count as no, no activity filter). With no keep of its own it has `breakawayHours`
(24) to place a Nexus at its own cost or nothing changes. A group of two or more can leave a
faction that has no sub-factions with no vote: one starts, the others press the same button, and
the starter places a named Nexus. Members of a Lordship or a council have no other vote on the
ruler; breaking away is the way out (`docs/2026-10-28-government-forms-brief.md`).

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
| **Charter book** | Paper shown as a book and quill, handed to every joiner. Using it opens the `/charter` menu: Rights, Freedoms, your situation, your faction; Join or Found for a player with no faction |
| **Named Nexus** | Crafted from a lodestone and a named banner. Placed with no faction, it founds the faction under that name and raises its keep, paid in diamonds from the inventory |
| **War banner** | Raises a camp. Pick up, replant, capture, hang as a trophy |
| **Camp store** | At the banner. Feed the camp |
| **Contract board** | Post and take jobs |
| **Realm Chronicle** | At spawn. The server's history |
| **Borders** | Particles along chunk edges while holding a war banner or a compass |

Commands kept to a handful: `/charter` (the Charter menu), `/charter page` (the chat faction page), `/charter found`,
`/charter map`, `/charter vote`.

## 20. Persistence

- Stable ids everywhere. Hierarchy, contracts, grants and claims stored separately.
- **Recommended:** world saved data for current state, plus an append-only history
  file per world (the Chronicle is rendered from it). All writes happen on the
  server thread, so a multi-record action is applied in memory and saved as one
  snapshot. SQLite stays an option if history grows past what a flat file handles.
- **Decided:** a JSON snapshot in the world folder plus the append-only history,
  not vanilla saved data and not SQLite (PLAN.md §8 explains why).
- Running sieges, camp raising timers, carry-in allowances and grace timers are
  saved, so a restart does not lift a siege or reset a granary. Siege clocks pause
  while the server is down.
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

**Also in the first playtest build** (moved up from v1, PLAN.md §1)

- Occupation, the contract and term model with bases and caps, justice (bond
  behind a knob, off by default), and notoriety in its minimal form

**v1**

- Signed contracts with escrow; war goals, war exhaustion and peace treaties;
  conditional surrender; the contract board; indenture
- Secession with a People fork; settling; posture labels; trophies; dormancy

**Later**

- Unrest and distance costs (needs tribute contracts)
- Monarchy, Council and Company presets
- A one way Discord bridge for the Chronicle (§23, 2026-10-27)

## 22. Acceptance tests

A to H are from the original spec; I onward cover land and supply. Most are pure
`core` tests: the land graph, the supply maths and the grant chain are plain Java.

| Test | Scenario |
|---|---|
| **A Commune** | Four players found a commune, vote through a spend and appoint an officer. Nobody acts beyond what the rules grant |
| **B Lordship** | The four name a Lord with a spending limit. Members keep reserved powers and remove the Lord by the agreed procedure |
| **C Delegated command** | A kingdom grants its guard the power to raise camps. The guard cannot withdraw unlimited funds or appoint the monarch |
| **D Sellswords** | A company contracts with two kingdoms and stays its own top-level organization with its own treasury. Needs signed contracts, so it waits for v1; the first build tests only the deposit grant half (D7) |
| **E Identity fork** | A group secedes, forks People, edits it. Later edits stay separate |
| **F Contested authority** | A kingdom revokes its guard's grants. The guard loses permissions at once, keeps the fort it owns, and can besiege the kingdom |
| **G Grant limits** | A kingdom tries to grant power over another kingdom's treasury. Rejected |
| **H Persistence** | After a restart: structure, People, balances, contracts, grants, claims, granaries, running sieges and carry-in allowances are restored |
| **I Raising** | A camp is breakable by anyone while raising and protected once active; the alert fires only when it becomes active |
| **J Endurance** | A granary at its storage limit with preserved food lasts 84 hours (within 7%, for spoilage) besieged with no deposits and nobody present, at zero notoriety, for 3 mouths and for 30, and never less than 42 hours whoever is present outside the keep |
| **K Carry-in** | During a siege, food carried in from an open perimeter chunk can be deposited; food from a chest inside, an ender chest, a pearl or across an enemy chunk cannot |
| **L Encirclement** | With all twelve perimeter chunks enemy-held, no deposit is possible; breaking one camp reopens that side |
| **M Starvation gate** | Assault drain is impossible above 25% of the storage limit and four times base below it, multiplying on top of presence up to 8 times in total |
| **N Camp supply** | A camp with an empty store stops besieging. A camp whose every perimeter chunk is claimed by hostile factions is cut off: it cannot be refilled and burns twice as fast |
| **K2 Carts** | During a siege, a cart rolling in from an open perimeter chunk can unload its food into the granary; a cart rolling in across an enemy chunk, or loaded inside the keep, cannot. In peace any hopper or cart can fill it |
| **K3 Granary** | The garrison eats real items, least nourishing first; food spoils at its type's rate, and fast past the storage limit; storage and drain follow active members per keep; contents are loot when the keep falls |
| **K4 Rail protection** | Outsiders cannot break rails or carts inside a claim; on neutral land they can |
| **O Raid** | A camp next to a farm drains its hold in 6 hours; the hold refills only while connected and unraided |
| **P Keep loss** | A faction with two linked keeps loses one; connected claims move to the other within capacity, the rest orphan with grace |
| **Q New faction** | A faction under 3 days old cannot be besieged; the grace ends if it raises a camp |
| **R Truce** | A lifted siege bars the same attacker from that keep for 3 days |
| **S Imposed caps** | An imposed contract cannot exceed its basis's caps; a sentence of 3 days is cut to 1; a term the basis does not allow is rejected |
| **T Resistance** | Escaping a cell or ending an occupation by force leaves no breach mark; breaking a signed treaty does |
| **U Weekly cap** | A player jailed by two factions in one week cannot be held past the weekly total |
| **V Prisoners eat** | Each prisoner adds a mouth to the keep's drain; prisoners are released when the keep falls |
| **W Occupation** | An occupied keep stays recorded to its owner and returns to them when the cap runs out or the occupiers starve |
| **X Sally** | Defenders unopposed beside a camp break it in 1 hour; each attacker present in the camp cancels one defender; the sally hold refills when uncontested |
| **Y Hostile sides** | A neutral faction's claim on a keep's perimeter stays open; only factions with an active camp against the keep close sides |
| **Z Server down** | Siege, spoilage, raising and grace clocks do not advance while the server is stopped |

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
| Granary | A real chest; the garrison eats real items, least nourishing first |
| Hoarding | No hard cap: food spoils by type, and fast past a storage limit of 84 hours of drain |
| Mouths | Drain and storage scale with active members per keep, minimum 3 |
| Garrison presence | Defenders present slow the drain. Realistically more people eat faster; rewarding players for showing up was chosen over realism on purpose |
| Rails | Peacetime automation is free; during a siege only carts entering through an open side count; rails and carts are protected in claims, fair game on neutral land |
| Camp store | A real chest, same rules as a granary |
| Power over others | One system: signed and imposed contracts. Imposed ones need a basis (custody, war goal, occupation), are capped, cost notoriety, and resisting them is not breach |
| Justice | Sentences are imposed contracts: confinement 1 hour to 1 day, execution as a short respawn delay plus banishment of at most 1 day, a weekly cap per player; prisoners eat from the granary |
| After a keep falls | The besieger chooses to raze or occupy |
| Pillars in | Supplies, empire strain, the Chronicle, sellswords |

### Accepted from PLAN.md, 2026-10-24

The owner accepted every recommendation and assumption in PLAN.md §14 and §15.
They are folded into the sections above; this table is the index.

| # | Decision | Outcome |
|---|---|---|
| D1 | Several besiegers at once | Allowed; all camps count toward encirclement; the drain is the keep's; truce per attacker (§10.3) |
| D2 | Food values | Nourishment from the vanilla food component; shelf class from `food.json`; no variety bonus yet |
| D3 | Unrest formula | Deferred until tribute contracts exist |
| D4 | Plunder | 25% of the treasury, spilled in the core chunk |
| D5 | Persistence | JSON snapshot in the world folder plus append-only history (§20) |
| D6 | Ships | A camp replanted at a dock; chest boats count as carts (§10.4) |
| D7 | Outsiders carrying food | Allowed with a deposit grant from the keep's owner; the grant can include counting as garrison |
| D8 | Mod name | Charter for the first jar; decide before the first public server, since renaming changes the mod id and config folder |
| D9 | Sentence clock | Real time, 1 day cap (§15.5) |
| D10 | Custody fine | 16 diamonds at most, from the prisoner's own deposit (§15.5) |
| D11 | War score or war goals | **Declared war goals with war exhaustion** (§15.2); the first build records war events for both models |
| D12 | Labour terms | Bond in the justice milestone behind a knob, off by default; indenture with signed contracts, 7 day cap, never offered to someone in custody or sentenced in the last 24 hours (§15.5) |
| D13 | What the time scale covers | Siege, spoilage, raising, grace and carry-in timers; not governance; sentences only if a separate knob says so |
| D14 | Server down | Siege clocks pause (§10.3, §20) |
| D15 | Deployment | Extend the Pocket Dungeons deploy script with a jar option so Charter can share the test server 714a0605, overworld only; approved |
| D16 | Granary size | Linked chests, up to 8 single chests (§10.11) |
| D17 | Who closes a side | Only factions with an active camp against the keep (or camp); neutral claims stay open (§10.4, §10.10) |
| D18 | Execution and items | The executed player keeps their inventory (§15.5) |
| D19 | Farmland | Outsiders cannot trample it in a claim (§9.5) |
| D20 | Raising camps | Do not eat until active (§10.3) |
| Gaps | The eighteen gaps in PLAN.md §15 | Resolved as PLAN.md assumes: test J within 7% for spoilage; assault multiplies on top of presence to 8 times; starvation is 25% of the storage limit; camps use a sally hold; camp claims count toward the member cap; overworld only; pack animals ignored; banishment does not count toward the weekly cap; prisoners add mouths, not storage; arrest by lead, cells marked by the faction |

### Settled in conversation, 2026-10-27

Built on `feature/charter-build`: every row below, the Charter book, the named Nexus,
seats for every office and asking to join (S1 and B0 to B7 of
`docs/2026-10-27-charter-book-implementation.md`). The B7 live checks are with the owner.

From a look at Imperial Refuge, a kingdom server run mostly through Discord:
staff host wars, rule on reports backed by video clips, and kingdom leaders post
their own currency counts by hand.

| Decision | Outcome |
|---|---|
| Discord | **The game owns the state; Discord carries the voice.** Anything with a true answer (who holds a chunk, what a treasury holds, who is at war, who leads) lives in Charter and is enforced there. Negotiation, banter and roleplay stay wherever players like to talk. Nothing in Discord is ever the only record of what happened |
| The test | If a process needs a staff member to say what happened, it belongs in the mod. If it is only people talking, it does not |
| Ceremony | Game actions should read as proclamations, not log lines, so that declaring war in the world feels like the public declarations players enjoy on Discord. The Chronicle voice (§17) carries this |
| Discord bridge | Later: Chronicle entries posted one way to a Discord channel by webhook, a URL in the config. It brings the pings and the public spectacle that call people back, and nothing comes back in, so there is no input to secure |
| Acting from Discord | Not planned. Voting or declaring from Discord waits until players ask for it |
| The Charter book | `/charter` opens a dialog menu of the player's current state. The **Charter of Rights and Freedoms**, handed to any player who joins without one, is only a shortcut to it: a book and quill look on an item with no use of its own, and using it runs `/charter` for whoever holds it. It has no owner and drops on death like any item. The menu shows Rights (powers they hold), Freedoms (powers others hold over them, or that need a vote) and their situation. A player with no faction sees Join and Found instead. No guaranteed caps on it; current state only. Full proposal: `docs/2026-10-27-charter-book-proposal.md` |
| Ways in | The book only. The Nexus is not a way in to these pages; it gets its own menu later, designed separately |
| Founding | Craft a Nexus from a lodestone and a banner named at an anvil. Placing it founds the faction under that name, with the banner's design as its banner, then raises the keep. The recipe cost is open |
| Governance at founding | Every new faction starts as a Lordship. Its Lord may make his own office a council or a Commune; members of a Lordship cannot vote the ruler out (see Who decides and Breaking away) |
| Who decides | Every faction has one office of decision makers holding the routine powers, with a number of seats and a way of deciding: 0 seats is a Commune (the members vote on everything), 1 a Lordship, 2 or more a council whose holders either each act alone or decide together by a majority of the seats. **Only the deciders change their own form:** a Commune by a vote of its members, a Lord alone (he may make himself a council or abdicate into a Commune), a council by a majority of its seats (dropping to one seat opens an election among the former holders). Members of a Lordship or council have no vote on form, and no other members vote can remove the Lord (Unseat, Vacate, Appoint, Expel, root grant revocation and Rename on the decision office follow the same rule). Decided 2026-10-28; `docs/2026-10-28-government-forms-brief.md` |
| Breaking away | The only way out of a ruler you do not control. A sub-faction secedes as a whole by a vote of its members: more than half of ALL eligible members must vote Yes, and not voting or abstaining counts as No. It takes any keeps it owns; with none it places a Nexus outside every keep's protection within 24 hours and pays the diamonds from its own treasury, or the break lapses. A faction with no sub-factions lets a group of at least 2 leave together with no vote. A player belongs to one organization, so a member who disagrees can only Leave. Then the new faction may go to war with the old one like any rival; there is no separate coup. Sieges do not change: a castle changes hands only by occupation and a treaty that cedes it. Dead accounts are a separate problem. Decided 2026-10-28 |

### Considered and rejected

| Idea | Why not |
|---|---|
| Watch windows (fixed daily vulnerability hours, as in EVE Online) | Long sieges and a visible raising phase protect offline players without anyone promising to be online at a set time |
| Watchtower detection block | Recon should be people patrolling, not a notification |
| Breaking blocks to take land | Leads to the obsidian box and the flying machine (KingdomsX's own wiki names this problem) |
| Defence and war paid in cobblestone and diamonds | Would imply a cobblestone to diamond rate; food is better and makes farming matter |
| Mechanical People traits | Min-maxing and fork exploits; identity is enough |
| Server-wide war events | Not needed once sieges take days |
| War score (points earned, spent at the peace table) | Rewards taking claims, the action the design most wants slow; replaced by declared war goals and exhaustion (D11) |

### Still open

| # | Question | Status |
|---|---|---|
| D3 | Unrest formula | Waits for tribute contracts |
| D8 | Final mod name | Before the first public server |
| D21 | The Nexus recipe: a lodestone (needs netherite) or something cheaper | With the Charter book build |

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
| Drain per mouth | 10 nourishment an hour |
| Mouths per keep | Active members divided by keeps, minimum 3 |
| Granary storage limit | 84 hours of the keep's drain |
| Granary linked chests | Up to 8 single chests |
| Starvation threshold | 25% of the storage limit |
| Camp sally hold | 1 hour, one unit of drain per uncontested defender |
| Presence counts members of at least | 3 days |
| Teleport detection | More than 16 blocks moved between checks |
| Claim dimensions | Overworld only |
| Time scale | 1 (compresses siege, spoilage, raising, grace and carry-in timers; not governance) |
| Scale sentences with time scale | Off |
| Bond (labour term) | Off |
| War goals per war | At most 2 |
| Indenture cap | 7 days |
| Spoilage, by food | Preserved about 2% a day, cooked meat about 10%, raw and fresh about 30% |
| Spoilage past the storage limit | 50% a day on the excess |
| Camp store burn | 30 nourishment an hour; 60 when cut off |
| Outlying hold | 6 hours, refills over 24 hours |
| Camp protection against a sally | 1 hour |
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
| Confinement | 1 hour to 1 day |
| Execution respawn delay | At most 1 hour |
| Banishment | At most 1 day |
| Weekly punishment cap per player | 1 day in total, across all factions |
| Same faction, same player cooldown | 3 days |
| Custody fine cap | 16 diamonds |
| Occupation cap | 7 days |
| Imposed vassalage cap | 14 days |
| Imposed contract notoriety | Scales with the harshness of the terms |

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
