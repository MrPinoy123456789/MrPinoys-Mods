# Charter

A server-side Fabric mod for Minecraft 26.2: organizations govern themselves, hold
land through keeps and camps, and take land by supply-line sieges instead of
breaking blocks. Vanilla clients install nothing. The design is `DESIGN.md`, the
plan `PLAN.md`, how to build and test `BUILDING.md`, the rules that must never
break `INVARIANTS.md`.

## Mixins, and why no Fabric API event covers each

The suite rule: every Mixin is listed here with the reason an event would not do.
All live in `fabric/src/main/java/charter/mc/mixin` and are listed in
`charter.mixins.json`. Each is a few lines; the logic is in `charter.mc.land`
(`Protection`, `Hazards`, `Placement`, `HopperGate`), and every check goes through
`ClaimIndex` and core's `SiegeRules.protectedClaim`.

| # | Mixin | Target | Why no event covers it | What it does |
|---|---|---|---|---|
| 1 | `BlockItemMixin` | `BlockItem.place` (wrap) | Fabric API has no server side block place event. `UseBlockCallback` would mean guessing the final position from the click face, sneaking and replaceable blocks | Refuses placement into protected land; turns a nexus item into a keep and a war banner into a camp, taking the block back if the engine refuses |
| 2 | `ServerExplosionMixin` | `ServerExplosion.calculateExplodedPositions` (return) | Explosions fire no break event | Removes protected blocks and the nexus from the list of blocks a blast destroys, unless a permitted player lit it. Entity damage is untouched |
| 3 | `PistonBaseBlockMixin` | `PistonBaseBlock.moveBlocks` (head) | Piston pushes and pulls fire no event | Refuses the move if any block pushed, destroyed or pushed into lies in another owner's protected land, or is the nexus |
| 4 | `FireBlockMixin` | `FireBlock.tick` (spread) and `checkBurnOut` | Fire ticking has no event | Fire cannot spread into, or burn blocks in, protected land of a different owner than the land it stands in |
| 5 | `LavaFluidMixin` | `LavaFluid.randomTick` (ignition) and `spreadTo` | Fluid ticking has no event | Lava cannot set fires in, or turn water to stone in, protected land of another owner |
| 6 | `FlowingFluidMixin` | `FlowingFluid.spreadTo` (was `canSpreadTo` before 26.2) | Fluid flow has no event | Water and lava do not flow across a border into protected land of another owner |
| 7 | `DispenserBlockMixin` | `DispenserBlock.dispenseFrom` | Dispensers fire no event, and a dispenser outside a border can place lava or fire into a claim | Refuses to dispense into protected land the dispenser's own chunk owner does not share |
| 8 | `HopperBlockEntityMixin` | `HopperBlockEntity.addItem(Container, Container, ItemStack, Direction)` (static) | Container transfers fire no event | The gate only: every insert is offered to `HopperGate`. M8 registers nothing; the granary and supply lines register their interceptors |
| 9 | `VehicleEntityMixin` | `VehicleEntity.hurtServer` | `AttackEntityCallback` covers melee only; arrows, tridents and fire charges hurt a cart through `hurtServer` | In protected land only a permitted player can hurt a minecart or boat (test K4) |
| 10 | `FarmlandBlockMixin` | `FarmlandBlock.fallOn` (the `turnToDirt` call; the class was `FarmBlock` before 26.2) | Trampling fires no event | Outsiders and mobs cannot trample farmland in protected land; fall damage still works |

Protection that does use a Fabric API event (`Guard`): `PlayerBlockBreakEvents.BEFORE`,
`AttackBlockCallback`, `UseBlockCallback`, `UseItemCallback` (buckets, flint and
steel, fire charges, spawn eggs), `AttackEntityCallback`, `UseEntityCallback`.

Not protected on purpose: mob griefing (endermen, ravagers) follows the vanilla
`mobGriefing` rule, and withers breaking blocks are a server rule problem.

## Land in the world

- A **nexus** is a lodestone item tagged `charter: nexus`; placing it runs
  `PlaceNexus`. A **war banner** is a red banner tagged `charter: war_banner`;
  placing it runs `PlantCamp` and lights a signal campfire on a hay bale beside it.
  Operators hand them out with `/charter admin give nexus|banner [player]`.
- `/charter map` draws 11 by 11 chunks in chat; the `+` cells and your own farms are
  links that claim and give up land through `ClaimLand` and `Unclaim`.
- Holding a war banner or a compass draws the chunk edges around you in dust: green
  yours, red theirs, yellow the open sides of a besieged keep.
- Admin: `/charter admin faction spawn <name> <player>`, `camp raise-now`,
  `perimeter show`, `give`.
