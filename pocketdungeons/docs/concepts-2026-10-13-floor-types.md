# Floor-type brainstorm (ChatGPT, 2026-10-13)

Prompt: "hunting trial spawners gets old if it's every floor", brainstorming a second
floor type for Pocket Dungeons. Status: raw brainstorm, nothing agreed yet.

---

The key design shift I'd make is to stop treating every floor as a combat encounter with a different coat of paint. Your existing rooms, marker blocks, trial-spawner infrastructure, affixes, and Ordeals are enough to support several distinct objective systems without needing to generate fundamentally different dungeons.
I'd also avoid making every new floor type a bespoke puzzle. The best ones should be built from a small number of reusable mechanics that interact differently with room layouts and themes.
For Pocket Dungeons, I'd aim for a mix of:
- Objective floors: Accomplish something other than killing everything.
- Pressure floors: The environment forces you to move or manage a worsening situation.
- Expedition floors: Choose how much time or durability to spend for a better haul.
- Combat hybrids: Fighting still happens, but it's a means to an end rather than the objective itself.
1. Twelve floor-type ideas
1. The Excavation
Objective: Mine a quota of valuable blocks scattered throughout the floor, using soft-breakable walls and hidden ore deposits. Each deposit has a randomized yield, so players must decide whether to dig into promising side rooms or stick to the obvious veins.
Pressure: Pickaxe durability is a finite resource, and some deposits are buried behind tougher materials. Combat can interrupt excavation, but killing every enemy isn't required.
Best themes: Mineshaft, Deepslate, Copper Works, Endless Mines.
2. The Hunt
Objective: Track down a handful of marked creatures that flee between connected rooms, leaving clues such as disturbed blocks, particles, or sounds. Players must corner and kill or otherwise capture the targets to unlock the exit.
Pressure: Targets can relocate when approached, forcing players to explore and coordinate rather than stand in a room farming spawners.
Best themes: Infestation, Frostworks, Rootworks, Lush Caves.
3. The Haul
Objective: Recover several heavy relics, ore carts, or supply crates and bring them to a designated extraction point. Carrying an objective item might prevent sprinting, disable shields, or make the carrier more vulnerable.
Pressure: Players must decide who carries the cargo, which routes to take, and whether to clear dangerous rooms or attempt a risky shortcut.
Best themes: Mineshaft, Ancient City, Copper Works, Ossuary.
4. The Purge
Objective: Destroy a quota of infestation nodes, cursed growths, or corrupted structures scattered across the floor. Each destroyed node contributes to a shared progress counter and might trigger a defensive wave.
Pressure: Destroying nodes makes progress but causes the remaining infestation to become more aggressive. Players can choose between a fast, dangerous route and a slower, safer one.
Best themes: Infestation, Rootworks, Lush Caves, Deepslate.
5. The Blackout
Objective: Restore power to a specified number of generators distributed across the floor. Each generator requires a short interaction, a resource, or a nearby switch activated by a marker.
Pressure: Lights progressively fail, hostile creatures gain advantages in darkness, and restoring a generator illuminates part of the floor or weakens a hazard.
Best themes: Copper Works, Deepslate, Ancient City, Mineshaft.
6. The Descent
Objective: Reach a series of marked checkpoints as the floor's route descends vertically through shafts, scaffolds, ladders, and broken walkways. Activating the final checkpoint opens the exit.
Pressure: Players must manage fall risks, limited climbing routes, and enemies positioned to disrupt movement. The challenge is navigating the environment, not hunting every spawner.
Best themes: Mineshaft, Deepslate, Frostworks, The End.
7. The Holdout
Objective: Activate a central mechanism and survive a fixed number of assault waves. Unlike conventional spawner clearing, the objective is tied to time or wave completion, not to finding and exhausting every spawner.
Pressure: The mechanism might need periodic interaction, or its surrounding defenses could deteriorate during the fight. Players must balance fighting, maintaining the objective, and reviving a failing defense.
Best themes: Ancient City, Prismarine, Copper Works, Ossuary.
8. The Offering
Objective: Deposit a specified value of items into an altar, furnace, or ancient receptacle to unlock the exit. Players can sacrifice dungeon loot, crafted items, or theme-specific resources.
Pressure: Every item deposited is permanently lost from the run's potential haul. Players must decide whether the fastest route is worth giving up valuable resources.
Best themes: Ossuary, The End, Ancient City, Frostworks.
9. The Locksmith
Objective: Find a small set of keys or components and use them to open a designated exit mechanism. Components are placed in reachable, guaranteed locations, with optional caches offering shortcuts or bonuses.
Pressure: Keys can be hidden behind destructible obstacles, guarded by enemies, or placed along alternate routes. Players must decide how much durability and health they're willing to spend.
Best themes: Mineshaft, Deepslate, Ancient City, Copper Works.
10. The Flood
Objective: Reach an extraction mechanism before rising water, spreading lava, or another advancing hazard blocks the route. Players activate a checkpoint or two along the way to establish progress and open the final exit.
Pressure: The environment progressively removes safe routes, encouraging decisive movement and cooperation rather than room-by-room clearing.
Best themes: Prismarine, Frostworks, Mineshaft, Copper Works.
11. The Scavenger
Objective: Collect a quota of scattered salvage, such as scrap-bearing components, abandoned tools, or rare dungeon materials. The floor ends once the quota is deposited at the exit mechanism, even if optional caches remain unexplored.
Pressure: Players can extract as soon as they have enough, or search dangerous side rooms for additional rewards. The best loot is placed off the most direct route.
Best themes: Mineshaft, Endless Mines, Deepslate, Copper Works.
12. The Seal
Objective: Close a set of unstable rifts, haunted graves, or dimensional tears by interacting with marked blocks in several rooms. Each sealed point contributes to a global counter and may remove a hazard from the floor.
Pressure: Unsealed points periodically generate enemies or environmental effects. The longer players wait, the harder the floor becomes, but progress is always retained.
Best themes: Ossuary, The End, Infestation, Ancient City.
2. The three I'd build first
My picks are The Excavation, The Haul, and The Purge.
They offer three mechanically distinct experiences:
- Excavation turns block-breaking and durability into an objective.
- Haul turns movement and route selection into an objective.
- Purge turns interacting with the environment under pressure into an objective.
Crucially, each can be implemented using your existing cell-stamping architecture. None requires abandoning the exit-pad clear condition or generating an entirely new kind of dungeon.
A. The Excavation
The floor is a mine that happens to contain monsters, not a combat arena that happens to contain ore.
Exact clear condition
At floor generation, select a target excavation quota, such as 6 ore units.
Each eligible ore marker represents a deposit with a predetermined yield. For example:
Deposit type
Yield
Typical difficulty
Exposed seam
1 unit
Easy
Buried vein
2 units
Requires breaking soft blocks
Deep deposit
3 units
Requires a longer detour or tougher obstruction
These values are illustrative starting points, not final balance.
When a player breaks the designated deposit block, the floor records the deposit as harvested and adds its yield to a shared progress counter.
The exit opens when the quota is reached. Players don't have to find every deposit or clear every trial spawner.
I'd distinguish between the actual block being broken and the reward being collected. Once a deposit is harvested, its contribution is recorded immediately, so throwing the drops into lava or disconnecting doesn't invalidate progress.
How it stamps into procedural rooms
Add a small set of marker types that can be placed in authored `.nbt` rooms:
- `ORE_EXPOSED`: Place a visible, easily accessible deposit.
- `ORE_BURIED`: Place a deposit behind a designated soft-breakable block.
- `ORE_DEEP`: Place a higher-yield deposit behind a tougher obstacle.
- `EXCAVATION_HINT`: Optionally place a clue pointing toward a nearby deposit.
During floor assembly:
The floor generator selects the Excavation objective and quota.
It collects all eligible excavation markers from the stamped cells.
It assigns deposit types and yields according to the floor's difficulty and theme.
It verifies that the available deposits can satisfy the quota.
It opens the exit when enough ore units have been harvested.
The important part is that deposit identity and yield are generated once, rather than recalculated whenever a player enters a room.
For a first implementation, I'd use existing soft-breakable blocks as the obstruction system rather than inventing a new mining mechanic.
Failure cases and edge conditions
Not enough deposits: Reject the floor configuration and regenerate its marker assignments. If that still fails, lower the quota to a value the available deposits can satisfy.
A deposit is accidentally destroyed: If its designated block is broken by an explosion or another environmental effect, either count it as harvested or replace it with a guaranteed accessible fallback. Never allow a required deposit to become permanently inaccessible.
Players mine every deposit immediately: That's fine. The floor should reward planning and efficient resource use, not force players to wait for a timer.
Players run out of pickaxe durability: The generator must guarantee a route to the required quota using the tools available to the party. If the player can enter a floor without a suitable tool, provide an accessible fallback deposit or a floor-specific basic tool.
Players leave a room and return: The harvested state must persist for the duration of the floor.
Loot and scrap
Excavation provides a natural reason to make the loot barrel and scrap system feel different.
- Standard clear reward: The usual themed loot barrel and 1–3 scrap.
- Optional deposits: Extra deposits beyond the quota can yield additional ordinary loot, but shouldn't endlessly generate scrap.
- Rare deposits: A small chance of producing a valuable material or a bonus loot roll.
- Affix scaling: `Loot 250%` increases the rewards from eligible deposits or the final loot barrel, according to your existing affix semantics.
I'd avoid making every deposit produce scrap. Otherwise, the floor becomes a scrap-mining exploit instead of a distinct objective.
Party play
All players contribute to the same excavation quota.
This naturally divides work:
- One player clears a route.
- Another excavates.
- Another looks for side deposits.
- A fourth protects the miners or carries recovered resources.
Solo players still have a complete objective, while groups can cover more ground.
One useful rule: don't scale the quota linearly with player count. A four-player party already has a substantial advantage from parallel exploration. Increase the quota modestly, or scale it through a combination of extra deposits and more dangerous obstructions.
Best themes
Primary: Mineshaft, Endless Mines, Deepslate, Copper Works.
Secondary: Frostworks, Ancient City.
In Frostworks, excavation could focus on deposits encased in ice. In Ancient City, players might extract buried relics rather than conventional ores.
One nasty affix interaction: Blackout
A Blackout affix disables most ambient light while leaving the excavation markers' exact positions unknown until players approach them.
This creates a nasty combination of limited visibility, soft-breakable obstacles, and potential enemies.
I'd make the markers discoverable through environmental hints, rather than requiring pixel-perfect visual searches in darkness.
Main risk: This objective can become trivial farming if optional deposits generate unlimited scrap. Give every deposit a persistent identity, restrict bonus rewards, and keep the clear quota independent of the total number of deposits.
B. The Haul
The party has found something valuable. Now it has to get it out.
Of the three, this is the one I'd be most excited to playtest because it changes the significance of the rooms you've already built. A route that is easy to traverse when empty might become dangerous when you're carrying a valuable objective.
Exact clear condition
The floor generates a quota of recoverable cargo, with one or more extraction points.
For example, a floor might require the party to recover three supply crates.
Each crate has three states:
Undiscovered: The crate exists in a stamped room but hasn't been recovered.
Carried: A player has picked it up.
Delivered: The crate has been brought to an extraction point.
The exit opens when the required number of crates has been delivered.
The cargo doesn't have to be a literal block. It could be represented by a custom server-side objective item, a vanilla item with a persistent identifier, or an interaction with a marker block that updates the carrier's objective state.
I'd lean toward a lightweight objective item with a persistent cargo ID, so it can be dropped, transferred, and recovered.
While carrying cargo, a player might:
- Lose sprinting.
- Be unable to use a shield.
- Take a small movement penalty.
- Become more visible to enemies.
I'd start with no sprinting and no shield use, then test whether the penalty makes carrying interesting without turning it into a chore.
How it stamps into procedural rooms
Use three marker types:
- `CARGO_SPAWN`: A recoverable crate or relic.
- `EXTRACTION_POINT`: A designated cargo-delivery location.
- `CARGO_ROUTE_HINT`: An optional marker that makes a side route or shortcut discoverable.
At assembly time:
Select the number of required crates.
Assign cargo markers to eligible rooms.
Ensure at least one extraction point is reachable from every required cargo location.
Assign cargo IDs and initialize their states.
Open the exit after enough crates have been delivered.
You can reuse your existing locked iron doors, latch doors, soft-breakable blocks, and climbable-path constraints to make routes meaningfully different.
For instance, a crate might be in a room accessible through a narrow passage. A player can take the longer normal route or break through a soft wall to create a shortcut.
The key is to keep the cargo route solvable without requiring a specific tool that might already have broken.
Failure cases and edge conditions
A crate is dropped into lava or the void: Automatically respawn it at its original location or a nearby valid recovery point.
A player disconnects while carrying cargo: Return the crate to its original spawn or transfer it to the shared objective state. Never let a disconnect destroy mandatory progress.
A crate gets stuck in an inaccessible position: Give cargo a recovery interaction or automatically return it after a delay.
The extraction point is behind a locked door: Validate that the door can be opened using the floor's guaranteed resources. If the route depends on a key, the generator must guarantee the key's accessibility.
Players carry crates directly to the exit: That's intended. Don't add arbitrary delivery stages just to prolong the floor.
Players deliver one crate, then lose another: Delivered cargo should remain delivered. Only cargo that hasn't been delivered should be recoverable.
Loot and scrap
This floor type should reward players for taking risks without making them afraid to finish the objective.
- Standard clear reward: The usual loot barrel and 1–3 scrap.
- Optional cargo: Extra crates can contain bonus loot or a themed material.
- Fragile cargo: An optional crate might grant a bonus if delivered without being dropped, taking damage, or being left behind.
- Risk/reward routes: Dangerous cargo locations can have better bonus rewards without being mandatory.
I'd keep the standard clear reward tied to the required cargo quota, not the number of optional crates recovered.
Otherwise, groups will naturally optimize around carrying every crate rather than making interesting choices.
Party play
This floor naturally supports cooperation.
One player carries the crate, another clears enemies, another scouts the next room, and a fourth protects the route back.
For balance, cargo should be shared across the party's objective state. Don't require each player to carry an individual crate.
If a player is downed or dies, the crate should drop or return to a safe recovery state. It shouldn't disappear.
For four-player scaling, increase the number of optional crates and the number of dangerous routes before significantly increasing the mandatory quota.
Best themes
Primary: Mineshaft, Ancient City, Copper Works, Endless Mines.
Secondary: Prismarine, The End, Ossuary.
The cargo can change with the theme:
- Mineshaft: Ore carts and supply crates.
- Ancient City: Ancient relics.
- Copper Works: Machinery components.
- Prismarine: Sealed artifacts recovered from flooded chambers.
- Ossuary: Sarcophagi or cursed remains.
This is an easy way to make the same objective feel thematically different without building an entirely new system for every dungeon.
One nasty affix interaction: Loot 250%
The affix increases the value of optional cargo rewards, but the most valuable cargo is placed along a dangerous route.
Players must choose whether to extract the mandatory quota quickly or carry additional cargo through more dangerous rooms.
I'd keep the clear condition unchanged and apply the affix to the bonus rewards. Otherwise, a loot affix could accidentally turn into a mandatory cargo grind.
Main risk: Cargo retrieval can become frustrating if players lose items through glitches or if carrying penalties are excessive. Cargo needs reliable recovery, and mandatory progress must never be lost.
C. The Purge
The dungeon is alive, and the party has to destroy it before it overwhelms them.
This is probably the easiest of the three to implement with your current systems because it can reuse marker blocks, mob spawning, and your existing global floor-state tracking.
Unlike conventional spawner clearing, the party isn't trying to eliminate every enemy. It's trying to eliminate the source of the problem.
Exact clear condition
The floor generates a set of infestation nodes, corrupted growths, or cursed structures.
Each node has a persistent state:
- Active.
- Being purged, if the interaction takes time.
- Destroyed.
The exit opens when all mandatory nodes are destroyed.
For a typical floor, I'd start with three mandatory nodes.
Nodes can be destroyed by breaking a designated block, interacting with it for a short duration, or surviving a small encounter near it.
The objective should be simple enough to understand immediately: find the growths, destroy them, and get out.
The twist is that active nodes periodically create pressure. Depending on the theme, that could mean:
- Spawning a small group of hostile mobs.
- Applying a brief environmental effect.
- Reinforcing nearby enemies.
- Blocking a route with spreading growth.
I'd avoid having every node continuously spawn mobs. That can create a runaway situation where players become trapped in an ever-growing combat encounter.
Instead, use bounded events: each node can trigger a limited number of reinforcements or activate a small number of times per floor.
How it stamps into procedural rooms
Use three marker types:
- `PURGE_NODE`: A mandatory objective.
- `PURGE_SPAWN`: An optional location for a defensive encounter or reinforcement.
- `PURGE_REWARD`: An optional cache or bonus objective associated with a node.
During floor generation:
Select a node count appropriate to the floor's size.
Assign mandatory nodes to reachable rooms.
Initialize a global count of active nodes.
Register any reinforcement points associated with each node.
Open the exit when the active-node count reaches zero.
You can use the same marker system for multiple themes.
For example, a Rootworks floor might contain aggressive growths, an Infestation floor might contain egg clusters, and an Ossuary floor might contain cursed graves.
The objective logic remains the same. Only the visuals, associated hazards, and rewards change.
Failure cases and edge conditions
A node is destroyed by an explosion: Count it as destroyed, provided its persistent state can be reliably updated. If the marker itself disappears before registration, recover its state through a stored objective ID rather than scanning the room continuously.
A node is inaccessible: The generator must guarantee a reachable interaction point. Don't require the player to mine through an unbreakable wall.
Players destroy every node immediately: That's a valid fast clear. Don't artificially force a minimum floor duration.
A node's reinforcement event never triggers: The floor should still be completable because reinforcement is pressure, not a mandatory source of progress.
A player leaves and re-enters a room: Destroyed nodes stay destroyed, and their associated reinforcements remain disabled.
A node is accidentally duplicated: Every node needs a unique objective ID so the same node can't increment the completion counter more than once.
Loot and scrap
The basic clear reward remains unchanged.
Additional rewards can be tied to the way players handle the infestation:
- Destroying an optional node grants a bonus cache.
- Destroying a node before it produces reinforcements grants a small bonus.
- Destroying all optional nodes awards a themed item or additional loot roll.
I'd be cautious about rewarding players for destroying nodes quickly if that encourages rushing through rooms without engaging with their hazards. A bonus for preventing a node from reaching a certain state is better than a strict timer that punishes cautious parties.
Scrap should remain tied primarily to floor completion, with only occasional bonus scrap from optional objectives.
Party play
The objective scales naturally because multiple players can split up and attack different nodes.
For a solo player, nodes can be distributed along a reasonably direct route. For a party, additional optional nodes can encourage exploration without making them mandatory.
One important detail: if a node requires an interaction lasting several seconds, decide whether multiple players can speed up the process. I'd let cooperation shorten the interaction modestly, but not make it instantaneous.
That gives the party a reason to protect the player interacting with the node.
Best themes
Primary: Infestation, Rootworks, Lush Caves, Ossuary.
Secondary: Ancient City, The End, Deepslate.
The same system could support:
- Infestation: Egg clusters.
- Rootworks: Corrupted roots.
- Lush Caves: Parasitic growths.
- Ossuary: Cursed burial sites.
- The End: Unstable rifts.
One nasty affix interaction: Ominous
The ominous variant causes active nodes to trigger more dangerous defensive events, but destroying a node permanently removes its associated pressure source.
This creates an interesting decision: players can rush the most dangerous nodes to make the rest of the floor safer, or clear easier nodes first to gather resources and prepare.
I'd keep the escalation bounded. Ominous should make each active node more threatening, not cause an exponential increase in spawns that makes the floor impossible to finish.
Main risk: If destroying nodes is too fast, Purge can become a sequence of trivial interactions. If destroying them takes too long, it becomes a repetitive holdout. Keep the interaction short and put the challenge into reaching, approaching, and surviving the node.
3. How I'd fit these into your existing architecture
I wouldn't create twelve completely separate floor implementations.
Instead, I'd establish a small shared objective framework.
A floor type defines:
- Which marker types it requires.
- How its objective state is initialized.
- What events advance progress.
- How the exit condition is evaluated.
- Which optional rewards can be generated.
The floor instance tracks:
- A unique ID for every objective.
- Objective states.
- The mandatory completion quota.
- The current progress.
- Any optional reward conditions.
The existing exit-pad system continues to check a single condition: is the floor complete?
This means the exit-pad code doesn't need to know whether players excavated ore, delivered crates, or destroyed infestation nodes.
The procedural generator also doesn't need to understand the full objective logic. It only needs to place and validate the required markers.
One important architectural rule
Never make a floor's mandatory completion depend on an event that has no guaranteed, recoverable path to completion.
For example:
- A required ore deposit can't be permanently destroyed without credit.
- A mandatory crate can't be irretrievably lost.
- A mandatory purge node can't become inaccessible.
- A required interaction can't depend on an optional drop.
- A spawner can be optional even if it contains valuable loot.
If the generator can't satisfy a floor's requirements, it should reject that configuration before the player enters.
I'd also give each floor a generic recovery path. If a required objective becomes invalid during play, the floor can restore it, credit it if the intended action already happened, or fall back to a simpler completion requirement. It should never require a player to abandon an otherwise successful run because of a generation or state-tracking bug.
4. Two additional ideas I'd keep in reserve
These are a little more ambitious or depend on relaxing the current exit-pad rule.
Constraints-lite: The Escape
The floor starts collapsing, flooding, freezing, or filling with darkness. Players must reach the exit before a timer expires.
The exit is available from the start, but reaching it safely requires navigating obstacles and potentially activating checkpoints.
Why it works: It creates urgency without requiring combat or a separate objective counter.
Risk: If the floor is too large or movement is obstructed by an unlucky procedural layout, the timer can feel unfair. It needs a guaranteed traversable route and a timer scaled to route length.
Constraints-lite: The Gamble
Players find a central mechanism that offers progressively better rewards in exchange for increasing danger. They can activate it repeatedly, but each activation triggers a new hazard or combat wave.
The party can leave at any point, banking the floor's guaranteed reward, or push for additional loot.
Why it works: It gives the party a meaningful decision between safety and greed.
Risk: If rewards scale faster than danger, players will farm the mechanism. If danger scales too quickly, the optimal strategy becomes ignoring it.
5. My recommended rollout
If I were implementing this in Pocket Dungeons, I'd start with the following sequence:
Priority
Floor type
Why
1
The Purge
Reuses marker blocks and existing enemy systems. Lowest implementation risk.
2
The Excavation
Makes durability, soft-breakable blocks, and hidden ore central to progression.
3
The Haul
Introduces a genuinely different movement and teamwork challenge.
4
The Blackout
Builds on the existing affix and environmental-effect systems.
5
The Offering
Adds a straightforward resource-management objective.
6
The Escape
Creates a distinct pacing option once exit-pad timing rules support it.
I'd keep conventional trial-spawner floors in the rotation, too. The objective isn't to eliminate combat; it's to make combat one of several different ways a floor can challenge the party.
The bigger design principle is that each floor type should ask players a different question.
- Excavation: How much durability are we willing to spend?
- Haul: How much danger are we willing to carry back?
- Purge: Which threat should we eliminate first?
- Blackout: How do we move when information and visibility are limited?
- Offering: What are we willing to sacrifice?
- Escape: What can we accomplish before time runs out?
If two floor types ask the same question and differ only in which blocks or mobs appear, they're probably one floor type with two themes, not two distinct gameplay experiences.

---

## Follow-up: The Purge (detailed design, ChatGPT round 2)

Prompt: pick The Purge and write the exact state machine, edge cases, and infrastructure
reuse. Status: raw follow-up, nothing agreed yet.

I'd pick The Purge.
It changes the objective from find and kill every trial-spawner wave to locate and destroy specific targets while their continued existence makes the floor more dangerous. It also reuses the most existing infrastructure: stamped `.nbt` rooms, marker blocks, mob spawning, floor-level Ordeals, affixes, and the exit-pad completion check.
The Excavation is a close second, but it depends more heavily on reliable tool and block-break handling. The Haul introduces a new cargo lifecycle, including carrying, dropping, recovery, and delivery.
The Purge can be built around a small, persistent objective record without scanning the world.
The Purge: exact state machine
1. Floor generation
Each Purge floor receives three mandatory infestation nodes by default.
During floor assembly:
Stamp the static `.nbt` room templates.
Collect the `PURGE_NODE` marker positions from the templates being placed.
Assign each mandatory node a unique ID and associate it with its world position and room.
Initialize each node's state as `ACTIVE`.
Verify that all mandatory nodes have valid, reachable interaction positions.
Store the floor's objective record.
Allow entry only after validation succeeds.
The number of mandatory nodes can scale with floor difficulty, but I would start with three for all party sizes and adjust based on playtesting.
Important: Marker blocks are generation instructions, not the authoritative objective state. Once the floor is initialized, the per-floor record becomes authoritative.
A node might be represented in the record like this:
FloorObjectiveRecord
  floorId
  objectiveType = PURGE
  status = ACTIVE
  requiredNodes = 3
  destroyedNodes = 0

  nodes:
    - id
      position
      state = ACTIVE
      reinforcementEventsUsed = 0
This is illustrative pseudostructure, not a requirement to use these exact Java classes or field names.
Each node also has a persistent identity so that destroying it twice can never count twice.
2. Node state machine
Each mandatory node has three states:
- `ACTIVE`: The node exists and contributes to the floor's remaining objective count.
- `DESTROYED`: The node has been successfully purged.
- `RECOVERY_REQUIRED`: The objective's physical representation is missing or invalid, but it has not been credited as destroyed.
`RECOVERY_REQUIRED` is a recovery state, not a gameplay challenge. The player should never need to discover or interact with it manually.
State transitions
Current state
Trigger
Next state
Action
`ACTIVE`
Player breaks the designated node block
`DESTROYED`
Credit the node exactly once; disable its pressure events.
`ACTIVE`
Explosion destroys the mandatory node block
`RECOVERY_REQUIRED`
Record that the physical node is missing; do not silently lose the objective.
`ACTIVE`
Unexpected block replacement or invalidation is detected through a known event
`RECOVERY_REQUIRED`
Preserve the objective identity and initiate recovery.
`RECOVERY_REQUIRED`
The recovery handler restores the node at its original position or a validated fallback position
`ACTIVE`
Restore a usable node without changing the objective quota.
`DESTROYED`
Any subsequent break, explosion, or interaction event
`DESTROYED`
Ignore the duplicate event.
I would not automatically credit an explosion as a successful purge. That creates a potential exploit where players can destroy objectives remotely or bypass intended interactions. Instead, recover the node.
There is one caveat: if the explosion was deliberately intended to destroy the infestation, counting it as purged would be reasonable. For the initial implementation, however, restoring the node is simpler and keeps the clear condition consistent.
What makes a node dangerous?
An active node can trigger a bounded number of reinforcement events.
For example:
- A nearby player enters its trigger radius.
- The node emits a reinforcement wave.
- The event counter increments.
- The node cannot trigger another wave until its cooldown expires, and it stops triggering once its configured event budget is exhausted.
These events are pressure mechanics, not prerequisites for completion. A node that never spawns a single enemy can still be destroyed and credited.
This distinction prevents a failed mob spawn, an unloaded room, or a broken reinforcement system from soft-locking the floor.
I would trigger these events from existing entity, interaction, or scheduled-event hooks rather than scanning every node every tick.
3. Floor state machine
The floor itself has four states:
- `PREPARING`: Templates are stamped, markers are collected, and the objective is validated.
- `IN_PROGRESS`: Players can interact with the objective.
- `RECOVERING`: A mandatory objective needs physical restoration.
- `CLEARED`: The clear condition has been satisfied, and the exit is unlocked.
Floor transitions
Current state
Trigger
Next state
Action
`PREPARING`
All mandatory nodes are registered and validated
`IN_PROGRESS`
Permit dungeon entry.
`PREPARING`
Validation fails
`PREPARING` with regeneration or corrected configuration
Do not expose an invalid floor to players.
`IN_PROGRESS`
A mandatory node becomes invalid
`RECOVERING`
Suspend completion checks until recovery is resolved.
`RECOVERING`
All invalid nodes are restored
`IN_PROGRESS`
Resume normal objective processing.
`IN_PROGRESS`
Every mandatory node is `DESTROYED`
`CLEARED`
Award the clear reward and unlock the exit pad.
`CLEARED`
Any later event
`CLEARED`
Ignore further objective events and prevent duplicate rewards.
A floor record might include:
FloorObjectiveRecord
  floorId
  objectiveType = PURGE
  status
  requiredNodes
  destroyedNodes
  rewardClaimed
  nodes[]
The `destroyedNodes` count is a cached value. The authoritative condition is that all mandatory node IDs have state `DESTROYED`.
That distinction matters because a duplicate event or a recovery operation must never accidentally increment the counter twice.
The clear transition should be atomic: once all nodes are destroyed, mark the floor `CLEARED`, mark its reward as claimed, and unlock the exit through an idempotent operation. This prevents reconnects or duplicate events from paying out multiple times.
4. The three required edge cases
Case A: A player disconnects mid-objective
Expected behavior: Nothing about the objective changes.
The Purge objective belongs to the floor, not to an individual player.
If a player disconnects:
- All node states remain unchanged.
- Destroyed nodes remain destroyed.
- Active nodes continue to exist.
- The mandatory quota does not change.
- The exit remains locked unless the floor has already been cleared.
If the disconnect happens during a node interaction, the interaction is cancelled. The node remains `ACTIVE` unless the node's destruction event has already been committed.
If the player disconnects immediately after breaking a node, the node's state transition must still be persisted. When the player reconnects, the objective record determines whether that node was destroyed.
Implementation detail: Treat a node's destruction as one authoritative server-side transaction: validate its ID and current state, change it to `DESTROYED`, update the progress counter, and evaluate the floor's clear condition. Never depend on a client-side acknowledgement to commit progress.
If the entire party disconnects, the floor can remain suspended or unloaded according to your existing dungeon lifecycle. When they return, resume from the saved record.
Case B: An explosion destroys a mandatory node block
Expected behavior: The node is restored, and the floor remains completable.
The explosion event identifies the affected block position. If it matches a registered mandatory node whose state is `ACTIVE`:
Mark the node `RECOVERY_REQUIRED`.
Mark the floor `RECOVERING`.
Prevent the floor from clearing while the node is invalid.
Restore the node at its original position, or at a validated fallback position if the original location is no longer usable.
Return the node to `ACTIVE`.
Resume the floor.
Do not search the world for replacement markers. The floor record already tells you the node's identity and location.
If the explosion destroys the node's physical block but the node has already been credited as `DESTROYED`, do nothing. The logical objective is complete; there is no reason to recreate it.
Why not simply credit the explosion?
Because block destruction and objective completion should be separate concepts.
An explosion might come from a player, a mob, a chain reaction, or another environmental event. Automatically crediting every such destruction can turn the objective into an unintended shortcut.
If you eventually want explosives to be a legitimate way to purge nodes, make that an explicit design decision. The initial version should prioritize correctness and recoverability.
Important Fabric implementation consideration
Don't rely solely on periodically checking whether the node block still exists. That would violate your no-world-scanning requirement and introduce unnecessary work.
Instead, use the relevant server-side block-destruction and explosion hooks available in your target Minecraft version to identify affected registered objective positions.
If a particular destruction path bypasses the hook you rely on, ensure that path is either handled by another appropriate event or that the node can be recovered through a separate, bounded validation mechanism.
Case C: A solo player enters with no pickaxe
Expected behavior: The floor remains completable without requiring a specific tool.
I would make Purge nodes interactable rather than mineable objectives.
The player right-clicks a node and holds the interaction for a short duration. On completion, the server verifies that:
- The floor is `IN_PROGRESS`.
- The node belongs to that floor.
- The node is still `ACTIVE`.
- The player is eligible to interact with it.
The server then transitions the node to `DESTROYED`.
No pickaxe is required. No special inventory item is required.
The interaction can be interrupted by leaving the interaction range, taking damage, or releasing the interaction, depending on the interaction system you implement.
For a solo player, use a relatively short interaction duration and a bounded reinforcement budget so the objective doesn't become an endless defensive encounter.
I would not make Purge completion depend on breaking blocks at all. That avoids unnecessary coupling between mandatory progression and your deliberately constrained durability economy.
5. How it reuses existing infrastructure
Existing system
Purge usage
Static `.nbt` room templates
Supply node locations and environmental dressing.
Marker blocks
Identify mandatory and optional nodes at generation time.
Per-floor state record
Store node states, progress, and completion.
Mob spawning
Generate bounded reinforcement events around active nodes.
Ordeals
Add additional pressure or modify node behavior.
Door affixes
Change visibility, reinforcement strength, or interaction difficulty.
Ominous floors
Increase the danger associated with active nodes.
Exit-pad system
Unlock when the floor transitions to `CLEARED`.
Loot barrel
Award the existing themed completion reward.
Scrap currency
Keep the existing 1–3 scrap clear reward.
The main new system is a generic objective-node lifecycle. Once that exists, other objectives can reuse it: generators to activate, relics to recover, keys to find, or mechanisms to disable.
One final design decision
I'd make destroying mandatory nodes the only required objective and treat all combat as optional pressure.
That gives the floor a fundamentally different rhythm:
Enter a room.
Identify the objective.
Decide whether to engage nearby enemies or push toward the node.
Destroy the node.
Remove one source of future pressure.
Choose the next target.
The player is no longer clearing rooms because every room contains a spawner. They're clearing rooms because the dungeon has a problem, and every decision they make changes how dangerous the rest of the floor will be.

---

## Follow-up: The Haul (detailed design, ChatGPT round 3)

Prompt: pick The Haul and write the exact state machine, edge cases, and infrastructure
reuse. Status: raw follow-up, nothing agreed yet.

The Haul this time.
It changes player behavior more fundamentally than The Purge: players aren't simply finding and destroying targets. They must travel into the dungeon, recover cargo, and get it to an extraction point. That makes room connectivity, route selection, teamwork, and existing movement constraints matter in ways the spawner-clearing loop doesn't.
It also reuses your existing `.nbt` room templates, marker blocks, locked doors, soft-breakable blocks, floor records, loot system, and exit pad. The main new mechanic is persistent cargo state.
The Haul: implementation specification
1. Objective and clear condition
Each floor generates a set of mandatory cargo objectives and one or more extraction points.
The floor clears when every mandatory cargo item has been delivered to an extraction point.
For the initial implementation:
- Generate 3 mandatory cargo items.
- Generate 1 extraction point.
- Place cargo in 3 distinct rooms when possible.
- Allow cargo to be carried by any player in the party.
- Preserve delivery progress if a player disconnects, dies, or drops cargo.
- Allow optional cargo to provide bonus rewards without affecting the clear condition.
Cargo could be represented by a vanilla item with a persistent cargo ID, an interaction with a marker block, or a server-side objective item. I'd use a lightweight objective item with a unique persistent ID because it supports dropping, transferring, and recovering cargo.
The objective should not require players to clear every trial spawner. Enemies are obstacles along the route, not the floor's completion condition.
2. State machine
There are two related state machines: one for each cargo item and one for the floor.
Cargo state machine
Each mandatory cargo item has one of four states:
- `STORED`: The cargo is at its generated spawn location.
- `CARRIED`: A player is carrying the cargo.
- `RECOVERY_REQUIRED`: The cargo has been lost or its physical representation is invalid.
- `DELIVERED`: The cargo has been deposited at an extraction point.
`DELIVERED` is terminal. Once cargo is delivered, it cannot become undelivered.
Transitions
Current state
Trigger
Next state
Server action
`STORED`
Player interacts with the cargo
`CARRIED`
Assign cargo ID to the player and apply carrying restrictions.
`CARRIED`
Player drops the cargo
`STORED`
Remove the carrier assignment and place the cargo at a valid drop position.
`CARRIED`
Player delivers cargo to an extraction point
`DELIVERED`
Clear the carrier assignment and increment delivered progress exactly once.
`CARRIED`
Carrier disconnects
`STORED`
Return cargo to its original spawn location or a valid recovery location.
`CARRIED`
Carrier dies
`STORED`
Return cargo to its original spawn location or a valid recovery location.
`STORED`
Cargo entity or block is destroyed unexpectedly
`RECOVERY_REQUIRED`
Preserve its identity and initiate restoration.
`RECOVERY_REQUIRED`
Cargo is restored at a valid location
`STORED`
Restore the physical cargo without changing progress.
`DELIVERED`
Any later interaction or destruction event
`DELIVERED`
Ignore the event.
For the first implementation, I'd return cargo to its original spawn after a disconnect or death rather than trying to preserve its exact dropped position. That makes recovery predictable and avoids edge cases involving unloaded rooms, lava, the void, or inaccessible terrain.
If cargo is represented by an interaction with a marker block rather than an item entity, the same state machine still applies; only the physical representation changes.
Important rule: state is authoritative
The per-floor record determines whether cargo is stored, carried, awaiting recovery, or delivered.
An item entity existing in the world does not prove that cargo is available. An absent item entity does not prove that cargo has been delivered.
The cargo ID links the physical representation to its authoritative record.
This prevents duplicated items, repeated delivery events, and accidental loss of mandatory progress.
Floor state machine
The floor has four states:
- `PREPARING`
- `IN_PROGRESS`
- `RECOVERING`
- `CLEARED`
Transitions
Current state
Trigger
Next state
Action
`PREPARING`
Cargo and extraction markers are validated
`IN_PROGRESS`
Permit entry and activate the objective.
`PREPARING`
Validation fails
`PREPARING`
Correct the configuration or regenerate the floor before entry.
`IN_PROGRESS`
Mandatory cargo becomes invalid
`RECOVERING`
Suspend objective completion until cargo is restored.
`RECOVERING`
All invalid cargo is restored
`IN_PROGRESS`
Resume normal gameplay.
`IN_PROGRESS`
All mandatory cargo is `DELIVERED`
`CLEARED`
Award rewards and unlock the exit pad.
`CLEARED`
Any later objective event
`CLEARED`
Ignore further progression events and prevent duplicate rewards.
The floor record might contain:
FloorObjectiveRecord
  floorId
  objectiveType = HAUL
  status
  requiredDeliveries = 3
  deliveredCount = 0
  rewardClaimed = false

  cargo:
    - id
      originalPosition
      state = STORED
      carrierId = null

    - id
      originalPosition
      state = STORED
      carrierId = null

    - id
      originalPosition
      state = STORED
      carrierId = null

  extractionPoints:
    - id
      position
This is illustrative pseudostructure, not a prescription for your actual Java classes.
`deliveredCount` is a cached counter. The authoritative clear condition is that every mandatory cargo ID is in state `DELIVERED`.
The transition to `CLEARED` and reward claim must be idempotent: duplicate events must never unlock the exit twice or award the clear reward multiple times.
3. How it stamps into static `.nbt` rooms
No runtime dungeon generation changes are required beyond selecting and validating marker locations.
Required markers
`CARGO_SPAWN`
Defines a location where a mandatory or optional cargo item can appear.
Associated generation data:
- Cargo ID.
- Mandatory or optional classification.
- Original spawn position.
- Cargo type or visual variant.
`EXTRACTION_POINT`
Defines a valid delivery location.
A floor may contain multiple extraction points, but the initial implementation can use one shared extraction point per floor.
`CARGO_ROUTE_HINT` (optional)
Marks an optional shortcut, alternate route, or bonus cargo location.
This marker doesn't participate in the clear condition. It exists to support interesting route choices and bonus rewards.
Generation procedure
Stamp the room templates.
Collect the cargo and extraction markers from the stamped rooms.
Select three eligible cargo locations.
Assign unique cargo IDs.
Verify that the selected cargo can reach at least one extraction point through the floor's intended traversal rules.
Initialize the floor record.
Spawn or activate the physical cargo representations.
Allow the party to enter.
If validation fails, choose different cargo locations or reject the floor configuration.
Don't require a particular key, pickaxe, or other expendable item to reach mandatory cargo or the extraction point unless the generator guarantees access to it.
Since rooms are static after stamping, store their absolute positions in the floor record. There's no need to scan the world to rediscover cargo locations when players return to a room.
4. The three required edge cases
A. A player disconnects mid-objective
Behavior: The cargo returns to its original spawn, and delivery progress is preserved.
Suppose a player picks up cargo `C-02` and then disconnects.
The server:
Identifies `C-02` as carried by the departing player.
Changes its state from `CARRIED` to `STORED`.
Clears its carrier assignment.
Restores the physical cargo at its original position, or marks it for recovery if that position cannot immediately be loaded.
Leaves `deliveredCount` unchanged.
When the party returns, the cargo is available to pick up again.
If the player disconnects after delivering the cargo, the cargo remains `DELIVERED`. A disconnect must never undo committed objective progress.
If the entire party disconnects, the floor's lifecycle can follow your existing persistence rules. The cargo record remains sufficient to resume the objective without reconstructing progress from world entities.
Implementation requirement: Update cargo state on the server and persist it through the same mechanism as the floor's other objective state. Never make delivery dependent on a client acknowledgement.
B. An explosion destroys a mandatory cargo block or entity
Behavior: The cargo enters `RECOVERY_REQUIRED` and is restored.
When the server receives a relevant block-destruction or entity-removal event:
Identify whether the affected position or entity belongs to a registered mandatory cargo ID.
Check that the cargo isn't already `DELIVERED`.
If it is stored and its physical representation was destroyed, transition it to `RECOVERY_REQUIRED`.
If necessary, transition the floor to `RECOVERING`.
Restore the cargo at its original position or a validated fallback position.
Return its state to `STORED`.
Resume the floor if no other mandatory cargo needs recovery.
If the cargo is already `DELIVERED`, ignore the event. The objective is complete regardless of what happens to the original spawn location.
I would not automatically treat an explosion as delivering or recovering cargo. Explosions can destroy a representation, but they don't satisfy the delivery condition.
This requires event-driven tracking of registered objective blocks and entities. Don't periodically scan every room for missing cargo.
C. A solo player enters with no pickaxe
Behavior: The floor is fully completable without a pickaxe.
Cargo pickup and delivery use interactions, not block mining.
The player interacts with the cargo to pick it up and interacts with the extraction point to deliver it. Neither operation requires a tool.
This also fits your limited-durability economy: mandatory floor progression doesn't consume pickaxe durability or assume the player brought a specific tool.
If a route contains a soft-breakable obstruction, the generator must still guarantee a traversable route to mandatory cargo and extraction. Optional shortcuts can require tools, but mandatory progression cannot depend on a tool the player might lack.
5. What makes carrying cargo interesting?
The objective needs to change how players traverse the floor, but it shouldn't make carrying cargo miserable.
I'd start with two restrictions:
- The carrier cannot sprint.
- The carrier cannot use a shield while carrying cargo.
I'd avoid preventing all combat or making the carrier take huge amounts of extra damage. Those restrictions could turn the cargo carrier into a helpless escort mission.
Cargo should be transferable between party members through an explicit interaction. The team can decide who carries it based on health, equipment, and route conditions.
The challenge comes from moving cargo through dangerous rooms, not from fighting the controls.
Party behavior
A party can split roles naturally:
- One player retrieves cargo.
- Another clears the route.
- Another scouts for shortcuts.
- Another protects the carrier on the return trip.
For solo players, place mandatory cargo along reasonably direct routes and keep the carrying restrictions modest.
I would not scale mandatory cargo linearly with party size at first. More players already make it easier to scout, protect carriers, and explore multiple routes simultaneously. Tune the number of required deliveries through playtesting rather than assuming four players should carry four times as much.
6. Loot and scrap
Keep the standard floor-clear reward unchanged:
- The themed loot barrel.
- The usual 1-3 scrap.
- Any existing rewards associated with the floor's affixes.
Then use optional cargo to introduce risk/reward choices.
Cargo type
Reward
Mandatory cargo
Required for floor completion; no separate guaranteed scrap payout.
Optional cargo
Bonus loot roll or themed materials.
Dangerous-route cargo
Better optional reward for taking a riskier route.
Fragile cargo
Optional bonus for delivering without dropping it.
The standard clear reward should depend only on delivering the mandatory cargo.
That way, players aren't punished for skipping optional cargo, and cargo collection doesn't become a way to farm scrap indefinitely.
A `Loot 250%` affix could increase optional cargo rewards without increasing the mandatory delivery quota.
An ominous variant could introduce more dangerous enemies along cargo routes or increase the risks of carrying cargo. Neither should invalidate the guaranteed route to completion.
7. Why I'd build this first
The Haul introduces a new player behavior loop without requiring a completely new dungeon architecture.
It reuses:
- Static `.nbt` rooms.
- Marker-based placement.
- Per-floor persistent state.
- Existing room connectivity and traversal constraints.
- Soft-breakable blocks and locked doors.
- Existing loot, scrap, affix, and exit-pad systems.
The main new pieces are a cargo lifecycle, pickup and delivery interactions, and recovery handling.
Once those work reliably, the same framework could support relic retrieval, resource transport, rescue objectives, or other floors where players must move something through the dungeon.
The essential design rule is that cargo can be temporarily lost, but mandatory progress can never be permanently lost. That lets you make the floor dangerous without making it fragile to disconnects, explosions, or procedural edge cases.

---

## Follow-up: six filtered ideas (ChatGPT round 4)

Prompt: harder filter applied (no overlap, no dynamic terrain, no mob AI that doesn't
exist, no soft-locks, no "find N things" reskins). Status: raw follow-up, nothing
agreed yet.

The strongest opportunities are the mechanics that make players reinterpret the rooms they already have, rather than search those rooms for a different set of objects.
I'd focus on six ideas. Each has a different central decision: whether to commit to a route, how to manage enemy activation, when to spend durability, how to exploit one-way doors, whether to accept a dangerous shortcut, or when to stop fighting and escape to the exit.
1. The Gauntlet
Can you commit to a route without knowing whether you can handle what comes next?
Core mechanic: Pressure plates activate trial spawners in the next room, but the party chooses which route to take through a branching floor graph. Activating a plate commits the party to a combat encounter; alternate routes can offer easier encounters, better loot, or a more direct path to the exit.
This isn't simply spawner clearing with a new counter. The objective is to reach the exit through a sequence of choices about which encounters to accept.
The decision every 30 seconds
Do we take the dangerous short route, or the longer route with better opportunities to recover?
A party might see two doors:
- One leads to a room with a powerful spawner and a guaranteed bonus chest.
- The other leads through two ordinary rooms with a soft-breakable shortcut.
The first route saves time but risks health and durability. The second costs resources or time but may be safer.
Why existing rooms become interesting
Your room graph becomes a route-planning problem rather than a checklist.
Latch doors create one-way commitments. Soft-breakable walls provide shortcuts. Locked doors and key blocks can make certain branches more attractive, provided the required key is guaranteed accessible.
Creeping reveal makes the choice more consequential because the party cannot see every room in advance.
Clear-condition state machine
Four states:
- `READY`: The floor is generated; the exit is locked.
- `ROUTING`: Players can traverse rooms and choose which encounters to activate.
- `COMPLETE`: Players reach the designated final encounter and finish its wave.
- `CLEARED`: The exit pad is unlocked.
Transitions:
State
Trigger
Next state
`READY`
Party enters the floor
`ROUTING`
`ROUTING`
Players reach the designated final encounter
`COMPLETE`
`COMPLETE`
Final encounter's wave is defeated
`CLEARED`
The final encounter is mandatory; intermediate encounters are optional. The generator must guarantee a traversable route to it.
What could go wrong?
The party bypasses every intermediate encounter and reaches the finale undergeared.
That's a balance problem, not a soft-lock. The finale should be independently tuned for the party's available equipment, and optional encounters should offer advantages rather than mandatory resources.
Existing system and one new system
- Leans hardest on: Room connectivity, latch doors, trial spawners, and creeping reveal.
- One new system: A route graph objective that designates a mandatory finale and classifies other branches as optional.
Verdict: Strong if you want players to make meaningful choices about where to fight. Weaker if you want floors to feel fundamentally different from combat encounters.
2. The Restless Floor
How much danger are you willing to leave behind while you push deeper?
Core mechanic: Cleared rooms can reactivate under a floor-specific rule. Players can rush toward the exit, backtrack for loot, or deliberately clear an optional room again to earn a reward.
This takes your existing Restless Ordeal and turns it into a complete floor objective rather than an occasional modifier.
The key distinction is that the objective is reaching and securing the exit while managing the consequences of your previous choices, not clearing a set of spawners.
The decision every 30 seconds
Do we revisit that room for the loot, knowing we may have to fight there again on the way out?
A party might pass a cleared room containing a bonus cache. Collecting it is worthwhile, but the floor's rules mean that entering the room can reactivate its spawner.
Alternatively, they can leave the cache behind and continue toward the exit.
Why existing rooms become interesting
Room topology now determines how expensive backtracking becomes.
A latch door might provide a one-way escape from a dangerous room. A soft-breakable shortcut might bypass a previously cleared encounter. A dead-end room with good loot becomes a meaningful risk rather than a mandatory stop.
Creeping reveal reinforces the tension: revisiting rooms is a conscious choice, not an automatic consequence of exploring everything.
Clear-condition state machine
Four states:
- `SEALED`: The exit is locked.
- `ACTIVE`: Players can explore and progress toward the exit.
- `EXIT_READY`: Players have reached the designated exit approach.
- `CLEARED`: The exit pad is unlocked.
Transitions:
State
Trigger
Next state
`SEALED`
Party enters the floor
`ACTIVE`
`ACTIVE`
Party reaches the designated exit approach
`EXIT_READY`
`EXIT_READY`
Any hostile wave currently active in the exit room is defeated
`CLEARED`
The floor does not require every room to be cleared. The exit approach is guaranteed to exist and remain reachable.
What could go wrong?
A spawner fails to produce its wave, leaving the exit encounter permanently active.
Use the existing spawner's authoritative wave-completion event. If the spawner enters an invalid state or its wave cannot be completed, a bounded recovery handler can deactivate that encounter and satisfy its completion condition.
Don't use an arbitrary timer to declare a functioning encounter complete.
Existing system and one new system
- Leans hardest on: Restless Ordeal, trial-spawner lifecycle, latch doors, and room-state tracking.
- One new system: Reactivation policy that specifies when cleared encounters can become active again.
Verdict: Extremely cheap to implement, although it risks feeling like a spawner floor with more backtracking. I'd use it as a special floor variant rather than one of the core objective types.
3. The Toll
Which resources are worth spending to make the rest of the floor easier?
Core mechanic: Certain routes contain toll mechanisms that let players exchange resources for access to shortcuts, safer paths, or bonus rooms. The party can pay with materials from its dungeon inventory, sacrifice optional loot, or refuse and take the normal route.
Unlike Excavation, the objective isn't to gather resources. It's to decide when spending resources is more valuable than preserving them.
The decision every 30 seconds
Should we spend our remaining iron to bypass this dangerous encounter, or save it for a better opportunity later?
A party might reach a locked shortcut that accepts a specified resource. Paying opens a latch door into a safer route; refusing forces the party through a longer route with an active spawner.
The choice depends on what the party has collected, its remaining durability, and how confident it feels about the next encounter.
Why existing rooms become interesting
Locked doors, key blocks, soft-breakable walls, and branching room layouts become competing solutions to the same problem.
A door might offer:
- A paid shortcut.
- A longer route with a spawner.
- A destructible obstruction that consumes tool durability.
The room layout creates a resource decision without needing dynamic terrain or new enemy behavior.
Clear-condition state machine
Three states:
- `LOCKED`: The exit is closed.
- `ROUTING`: Players choose between available paths.
- `CLEARED`: Players reach the designated terminal room and activate its exit pad.
Transitions:
State
Trigger
Next state
`LOCKED`
Party enters the floor
`ROUTING`
`ROUTING`
Party reaches the terminal room
`CLEARED`
The tolls are optional. The generator must ensure the terminal room is reachable without paying any toll.
What could go wrong?
A party spends its last required resource on a toll and cannot open a mandatory door.
The generator must guarantee that every mandatory door has an independent, accessible solution. Toll payments must use a separate resource budget or only offer optional benefits.
Existing system and one new system
- Leans hardest on: Locked iron doors, key blocks, soft-breakable blocks, and the dungeon inventory.
- One new system: Resource toll interactions that atomically exchange an accepted item quantity for a predetermined route or reward.
Verdict: Very compatible with your durability economy. Its weakness is that it can become a series of simple purchase prompts unless tolls offer genuinely different routes.
4. The Switchback
When is the best time to commit to a shortcut you cannot return through?
Core mechanic: The floor is designed around one-way latch doors. Players can take risky shortcuts, bypass difficult rooms, or drop into sections from which the previous route is inaccessible.
The exit objective is simple: reach the terminal room and activate the exit pad. The challenge is choosing a route through a graph that progressively removes earlier options.
This is different from The Gauntlet because the core mechanic is irreversible navigation, not choosing which encounters to fight.
The decision every 30 seconds
Do we take this one-way door now, or explore the branch behind us before we lose access to it?
A party might discover a latch door leading toward the exit, but an unexplored branch contains a bonus chest and a potentially valuable salvage bench.
Taking the door saves time and reduces exposure to combat, but permanently abandons that branch.
Why existing rooms become interesting
Latch doors become strategic commitments instead of decorative shortcuts.
Room layout matters because a door's value depends on what it bypasses and what the party leaves behind. Soft-breakable walls can provide alternate routes, while locked doors can restrict the party's options.
Creeping reveal makes this especially effective: players cannot assume that a one-way door leads to a known quantity.
Clear-condition state machine
Three states:
- `SEALED`: The floor is generated, and the exit is locked.
- `TRAVERSING`: The party navigates the room graph.
- `CLEARED`: The party reaches the designated terminal room and activates the exit pad.
Transitions:
State
Trigger
Next state
`SEALED`
Party enters the floor
`TRAVERSING`
`TRAVERSING`
Party reaches the terminal room and activates its pad
`CLEARED`
The terminal room is reachable through at least one valid route, regardless of optional branches taken.
What could go wrong?
A generated one-way door traps the party in a branch with no route to the terminal room.
Before entry, validate reachability in the directed room graph, treating latch doors as one-way edges. Reject or repair any graph in which the terminal room is unreachable from a valid starting state.
No world scan is necessary; this is a graph validation problem during generation.
Existing system and one new system
- Leans hardest on: Latch doors, creeping reveal, room connectivity, and soft-breakable shortcuts.
- One new system: Directed-route validation that treats one-way doors as directed graph edges and verifies terminal reachability.
Verdict: One of the cheapest options and a good way to make authored rooms feel different without adding mobs or new blocks.
5. The Bounty
Which enemy is worth fighting, and which should you avoid?
Core mechanic: One encounter in the floor is designated as a bounty target. Defeating it is mandatory, but players choose when and how to reach it. Other spawners remain optional, offering supplies or loot that might make the bounty easier.
Unlike The Purge, players aren't destroying environmental objects. Unlike the standard floor, they don't need to exhaust every spawner. The objective is to prepare for and defeat one specific encounter.
The decision every 30 seconds
Do we fight the optional spawner for a better reward, or preserve our resources for the bounty?
The party might find a bounty room early, but its spawner is more difficult than a normal encounter. They can attempt it immediately or explore optional branches to find better equipment and loot.
A four-player party can't simply split up and trivialize the floor if the bounty itself scales properly to party size and the group must reunite to activate it.
Why existing rooms become interesting
Room order becomes a preparation decision.
Optional branches are no longer just additional rooms to clear. They represent opportunities to improve the party's chances before a mandatory encounter.
Soft-breakable walls and latch doors provide shortcuts that let experienced groups attempt the bounty early. Hidden ore and loot caches create incentives to take detours.
Clear-condition state machine
Four states:
- `UNLOCATED`: The bounty encounter hasn't been reached.
- `AVAILABLE`: The party has reached the bounty room.
- `ACTIVE`: The bounty spawner's wave is active.
- `CLEARED`: The bounty wave has been defeated, unlocking the exit.
Transitions:
State
Trigger
Next state
`UNLOCATED`
Party activates the designated bounty room
`AVAILABLE`
`AVAILABLE`
Bounty spawner activates
`ACTIVE`
`ACTIVE`
Its wave is defeated
`CLEARED`
Optional spawners don't affect the clear condition.
What could go wrong?
The bounty spawner fails to activate, or its wave becomes stuck.
Use the existing trial-spawner lifecycle as the authoritative encounter state. If the encounter becomes invalid, use a bounded recovery path to reset or restore the encounter rather than requiring the party to abandon the run.
Existing system and one new system
- Leans hardest on: Trial spawners, mob modifiers, Guardians, and room graph generation.
- One new system: Mandatory encounter designation that selects one spawner as the floor's required bounty.
Verdict: Easy to implement, but it remains combat-centric. I'd use it to break up ordinary floors rather than make it a flagship objective type.
6. The Evacuation
How much more are you willing to risk before you leave?
Core mechanic: The exit pad starts unlocked, but activating it ends the floor immediately. Players can leave with the standard reward or voluntarily activate an optional challenge mechanism that locks the exit again until a designated encounter is completed. Completing that encounter restores the exit and improves the reward.
This relaxes the normal progression assumption without removing the exit pad as the only way out.
Unlike The Haul, players aren't transporting objects. Unlike The Toll, they aren't paying for access. They're deciding whether to secure a modest reward now or risk it for a better one.
The decision every 30 seconds
Do we take the reward we've earned, or deliberately make the floor more dangerous for a chance at more?
A party might reach the exit after a short route and discover an optional challenge mechanism nearby. Activating it starts an additional encounter and locks the exit until that encounter is resolved.
The group can leave immediately or gamble on the bonus.
Why existing rooms become interesting
The room graph determines whether optional challenges are worth visiting. A challenge mechanism at the end of a dangerous branch is a meaningful decision; one next to the exit is a tempting last-minute gamble.
Soft-breakable shortcuts and latch doors can reduce the cost of returning to the exit after completing the challenge.
Clear-condition state machine
Four states:
- `EXIT_OPEN`: The exit is usable.
- `CHALLENGE_ACTIVE`: The party voluntarily accepts the challenge; the exit is temporarily locked.
- `EXIT_REOPENING`: The required challenge encounter is complete, and the exit is being restored.
- `CLEARED`: A player activates the exit pad while it is open.
Transitions:
State
Trigger
Next state
`EXIT_OPEN`
Party activates the optional challenge
`CHALLENGE_ACTIVE`
`CHALLENGE_ACTIVE`
The designated wave is defeated
`EXIT_REOPENING`
`EXIT_REOPENING`
Exit-pad unlock is committed
`EXIT_OPEN`
`EXIT_OPEN`
A player activates the exit pad
`CLEARED`
The optional challenge must never be activated automatically. If the party ignores it, they can leave with the standard reward.
What could go wrong?
The party activates the challenge, but the encounter fails to spawn.
The encounter uses the existing trial-spawner recovery mechanism. If it cannot be restored, cancel the challenge, reopen the exit, and award only the standard reward.
Existing system and one new system
- Leans hardest on: Exit pad, trial spawners, door affixes, and loot barrel rewards.
- One new system: Voluntary reward escalation that temporarily locks the exit after an accepted optional challenge and restores it when complete.
Verdict: Strong replayability, but it requires careful reward tuning to avoid making the optional challenge an obvious mandatory choice for optimal players.
My recommendation
I'd prototype The Switchback first, followed by The Toll.
Switchback
Toll
Changes player behavior
Route commitment and irreversible decisions
Resource conservation and spending
Reuses existing systems
Extremely high
Very high
New runtime mechanics
Almost none
One resource exchange interaction
Main technical risk
Invalid directed graph
Resource soft-lock
Main design risk
Routes feel arbitrary
Choices become obvious
Distinct from spawner clearing
High
High
The Switchback is the cleanest way to make the same authored rooms produce a different experience. It requires no new mob behavior, no dynamic terrain, and almost no new runtime state. Most of its complexity belongs in procedural graph validation.
The Toll is the strongest choice if you want to make durability and inventory management matter more.
One final filter: I'd keep The Restless Floor and The Bounty as modifiers or encounter variants, not standalone floor types. They still lean heavily on the existing spawner-clearing loop. The Switchback and The Toll change what players are thinking about while they move through the dungeon, which is the more valuable kind of variety for Pocket Dungeons.

---

## Outcome (2026-10-13)

Three variants were built from this brainstorm, under names of their own: the
Heist (`sunken_treasury`, act 3), the Haunt (`lighthouse_below`, act 3, the
Keeper's HUNT behaviour) and the Claim (`gilded_seam`, act 4). The shared
framework of section 3 landed as `FloorObjectives` plus `ObjectiveState`, and
the generic recovery path became the fallback-to-spawners rule. The rules,
numbers and knobs are recorded in
`docs/decision-2026-10-13-act34-floor-types.md`; this document stays as
provenance.
