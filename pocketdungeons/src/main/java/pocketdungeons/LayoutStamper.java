package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Walks a resolved {@link DungeonPlan} and writes it into the world.
 *
 * <p>Cells are placed in critical-path order first, then branches, so the route
 * a player will actually walk exists earliest (spec section 8.3). With the
 * current 5-8 cell profile the whole layout still lands in one tick; the ordering
 * matters for when placement becomes tick-budgeted.
 */
final class LayoutStamper {

    /** Jigsaw name marking a mob spawn point inside a room template. */
    static final Identifier SPAWN_NAME = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "spawn");

    private LayoutStamper() {}

    /**
     * Stamps every cell of the plan and returns the layout the instance will
     * carry. Throws if any template is missing, which {@code Instances} treats as
     * a stamp failure and aborts on -- releasing the slot and the force-load
     * tickets rather than leaving a half-built dungeon allocated.
     *
     * @param keystoneLevel the level of the keystone spent to open the run, or
     *                      {@code 0} for a run nobody paid for. It drives the loot
     *                      tier
     * @param ominous       whether the run is ominous -- U8 Stage 6: this is now
     *                      the single source, set by the caller from the spent
     *                      keystone's own affix, with no other route into it
     */
    static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                int keystoneLevel, Set<String> affixes) {
        return stamp(level, origin, plan, keystoneLevel, affixes, null);
    }

    /**
     * As the 5-argument {@link #stamp}, with {@code owner}'s persisted room (M2)
     * overlaid onto the entrance cell if one exists. {@code owner} is
     * {@code null} for a run with no room concept at all
     * ({@code /dungeon admin build}/{@code untimed}).
     */
    static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                int keystoneLevel, Set<String> affixes, UUID owner) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, false);
    }

    /**
     * As the 6-argument {@link #stamp}, but for M2/M3's lobby-first entry: the
     * entrance cell already physically exists (the lobby, stamped and standing
     * before any plan was generated -- see {@code Instances.stampLobby}), so it
     * is skipped entirely here rather than re-stamped on top of. Everything
     * else -- every other cell, the bedrock envelope, the returned layout's
     * bookkeeping is identical.
     *
     * <p>A room's own processors always win over the run theme. A grove remains a
     * strange chamber inside a deepslate run instead of quietly recolouring itself
     * to match the walls around it.
     *
     * <p>{@code standingCells} are cells outside the plan that already stand
     * beside it (the staging room). The envelope treats them as neighbours, so
     * no ring lands in their wall column.
     */
    static InstanceLayout stampBehindLobby(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                           int keystoneLevel, Set<String> affixes, UUID owner,
                                           String theme, Set<BlockPos> standingCells,
                                           DungeonDef.LootBand lootBand) {
        return stampBehindLobby(level, origin, plan, keystoneLevel, affixes, owner, theme,
                standingCells, lootBand, NodeStamper.Context.NONE);
    }

    /**
     * As the 9-argument {@link #stampBehindLobby}, with the floor's resource node
     * palette and darkness ({@link NodeStamper.Context}). The registered node
     * positions and soft gate positions ride on the returned layout.
     */
    static InstanceLayout stampBehindLobby(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                           int keystoneLevel, Set<String> affixes, UUID owner,
                                           String theme, Set<BlockPos> standingCells,
                                           DungeonDef.LootBand lootBand, NodeStamper.Context nodeCtx) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, true, theme, standingCells,
                lootBand, nodeCtx);
    }

    /**
     * (M56) Stamps only the entrance cell of the plan, for the door preview.
     * The entrance cell's template and room content are placed, but no
     * connectors, no bedrock envelope, and no owner room overlay. The caller
     * places a window in the staging room's door slot so the party can see
     * in. On commit, {@link #stampBehindLobby} stamps the rest of the cells
     * (skipping the entrance, since it is already stamped) and applies
     * connectors and bedrock.
     *
     * @return the {@link PlanGeometry} for the plan, so the caller knows the
     *         entrance cell's world origin and the full geometry for later
     *         force-loading
     */
    static PlanGeometry stampEntranceOnly(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                          int keystoneLevel, Set<String> affixes, String theme,
                                          DungeonDef.LootBand lootBand) {
        return stampEntranceOnly(level, origin, plan, keystoneLevel, affixes, theme, lootBand,
                NodeStamper.Context.NONE, new HashSet<>());
    }

    /**
     * As the 7-argument {@link #stampEntranceOnly}, applying the floor's light and
     * node rules to the entrance cell and adding its node positions to
     * {@code nodesOut} (the caller keeps them for the commit).
     */
    static PlanGeometry stampEntranceOnly(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                          int keystoneLevel, Set<String> affixes, String theme,
                                          DungeonDef.LootBand lootBand, NodeStamper.Context nodeCtx,
                                          Set<BlockPos> nodesOut) {
        PlanGeometry geometry = PlanGeometry.of(origin, plan.cells());
        StructureTemplateManager manager = level.getStructureManager();
        RoomManifest manifest = RoomManifest.current();
        DifficultyProfile profile = DifficultyProfile.of(plan.criticalPath().size(), keystoneLevel, lootBand);
        PlanCell entranceCell = plan.entrance();

        Set<PlanCell> voidedCells = computeVoidedCells(plan, affixes);

        DungeonPlan.PlacedRoom placed = plan.rooms().get(entranceCell);
        if (placed == null) {
            throw new IllegalStateException("plan has no room for entrance cell " + entranceCell);
        }
        RoomManifest.Entry entry = manifest.byName(placed.name());
        if (entry == null) {
            throw new IllegalStateException("manifest has no room named " + placed.name());
        }

        BlockPos cellOrigin = geometry.cellOrigin(entranceCell);
        ThemeManifest.Entry runTheme = ThemeManifest.current().byId(theme);
        String processors = entry.meta.processors != null ? entry.meta.processors
                : (runTheme == null) ? null : runTheme.meta().processors;
        List<BlockPos> spawns = TemplateStamper.place(
                level, manager, cellOrigin, Identifier.parse(entry.meta.template),
                placed.rotation(), plan.seed() ^ cellOrigin.asLong(),
                processors == null ? null : Identifier.parse(processors));

        int depth = plan.depths().getOrDefault(entranceCell, 0);
        String lootSuffix = runTheme == null ? null : runTheme.meta().lootSuffix;
        String lootTableOverride = runTheme == null ? null : runTheme.meta().lootTable;
        RoomContent.apply(level, cellOrigin, plan.roles().get(entranceCell),
                depth, profile, spawns, plan.seed(), affixes, lootSuffix, lootTableOverride, theme,
                voidedCells.contains(entranceCell), false, entry.meta.content);
            PressureSources.arm(level, cellOrigin, entry.meta, theme, affixes.contains(AffixIds.OMINOUS));
        NodeStamper.applyCell(level, cellOrigin, placed.rotation(), entry.meta, nodeCtx,
                plan.seed() ^ cellOrigin.asLong(), nodesOut);

        // Remove stray selector door blocks that may be baked into older
        // versions of entrance_hall.nbt. Selector doors belong only in the
        // staging room; the dungeon's entrance cell should not carry them.
        clearStraySelectorDoors(level, cellOrigin);

        return geometry;
    }

    /**
     * Removes any blocks that are the selector-door furniture from the cell.
     * Older entrance_hall.nbt templates included the three selector doors from
     * the staging room; the dungeon's entrance cell should never have them.
     */
    private static void clearStraySelectorDoors(ServerLevel level, BlockPos cellOrigin) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT + 1; y++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    cursor.set(cellOrigin.getX() + x, cellOrigin.getY() + y, cellOrigin.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                        RoomBuilder.set(level, cursor, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                        int keystoneLevel, Set<String> affixes, UUID owner,
                                        boolean entranceAlreadyStamped) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, entranceAlreadyStamped, null, Set.of(), null,
                NodeStamper.Context.NONE);
    }

    private static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                        int keystoneLevel, Set<String> affixes, UUID owner,
                                        boolean entranceAlreadyStamped, String theme,
                                        Set<BlockPos> standingCells, DungeonDef.LootBand lootBand,
                                        NodeStamper.Context nodeCtx) {
        StructureTemplateManager manager = level.getStructureManager();
        RoomManifest manifest = RoomManifest.current();
        // M61: the deepest any room in this layout reaches below its own cell
        // floor, so PlanGeometry.bounds can extend down to cover lower stories
        // for teardown, entity sweeps and protection.
        Map<PlanCell, Integer> spanYByCell = cellSpanY(plan, manifest);
        int storyFloorOffset = maxStoryOffset(spanYByCell);
        PlanGeometry geometry = PlanGeometry.of(origin, plan.cells(), storyFloorOffset);
        DifficultyProfile profile = DifficultyProfile.of(plan.criticalPath().size(), keystoneLevel, lootBand);
        PlanCell entranceCell = plan.entrance();
        // M10: every trial spawner this stamp places, for the spawner-clear
        // completion gate. Collected here rather than passed an InstanceRecord,
        // since one does not exist yet this early; it travels on the returned
        // layout instead, the way everything else about this run's shape does.
        Set<BlockPos> trialSpawners = new LinkedHashSet<>();
        // Dungeon structure W4: the resource node positions every cell registers, and the
        // soft mechanic gates the directional gate pass places.
        Set<BlockPos> nodePositions = new LinkedHashSet<>();
        Set<BlockPos> softGates = new LinkedHashSet<>();
        // PD-160: the flooded hall's containment doors (latches) and the connector
        // iron doors' levers and buttons, each mapped to the door it opens.
        Set<BlockPos> latchDoors = new LinkedHashSet<>();
        Map<BlockPos, Set<BlockPos>> doorOpeners = new HashMap<>();
        // M25: the rare door to a Pocket2 child, if this run rolled one. Placed
        // in the first cleared encounter cell; carried on the layout so the
        // right-click handler can find it without scanning the world.
        // Rolled once per run off the plan seed, and gated on the adventure
        // graph: a theme that has no node in it (an unthemed run, or a datapack
        // that never declared one) never hosts a pocket door.
        // J7: Pocket2 is hidden from players for now; no playtest has met it.
        // The machinery stays, the roll is pinned off until it does.
        boolean pocket2Rolled = Pocket2.DOORS_LIVE
                && theme != null
                && AdventureGraphs.current().graph().node(theme) != null
                && RandomSource.create(plan.seed()).nextDouble()
                < PocketDungeonsConfig.pocket2DoorChance();
        BlockPos pocket2Door = null;

        // Voided affix: select which cells get voided floor before stamping.
        // Entrance and terminal cells always excluded: lobby door and
        // lodestone pad need solid floor. Seeded from plan seed for
        // reproducibility.
        Set<PlanCell> voidedCells = computeVoidedCells(plan, affixes);

        for (PlanCell cell : stampOrder(plan)) {
            if (entranceAlreadyStamped && cell.equals(entranceCell)) {
                continue;
            }
            DungeonPlan.PlacedRoom placed = plan.rooms().get(cell);
            if (placed == null) {
                throw new IllegalStateException("plan has no room for cell " + cell);
            }
            RoomManifest.Entry entry = entryAt(manifest, plan, cell);
            if (entry == null) {
                throw new IllegalStateException("manifest has no room named " + placed.name());
            }

            BlockPos cellOrigin = geometry.cellOrigin(cell);
            // M35: the anomaly cell keeps whatever processors and content its own
            // room carries and never picks up the run theme's -- that is the whole
            // point of the swap. A room whose own processors field is null simply
            // stamps untinted rather than falling back to the theme around it.
            boolean isAnomalyCell = cell.equals(plan.anomalyCell());
            ThemeManifest.Entry runTheme = ThemeManifest.current().byId(theme);
            String processors = entry.meta.processors != null ? entry.meta.processors
                    : (isAnomalyCell || runTheme == null) ? null : runTheme.meta().processors;
            List<BlockPos> spawns = TemplateStamper.place(
                    level, manager, cellOrigin, Identifier.parse(entry.meta.template),
                    placed.rotation(), plan.seed() ^ cellOrigin.asLong(),
                    processors == null ? null : Identifier.parse(processors));

            // M61 (spec 13.4): a multi-story room must have a climbable route
            // from its lowest story back to the upper floor. The lower story
            // has no doorways, so a room without a return path traps the player.
            // The check is structural: it reads the blocks the template just
            // placed, not the metadata. A failure refuses the stamp.
            if (entry.meta.spanY > 1
                    && !ReturnPathValidator.validate(level, cellOrigin, entry.meta.spanY)) {
                throw new IllegalStateException("room " + placed.name()
                        + " has spanY " + entry.meta.spanY
                        + " but no climbable return path from its lower story"
                        + " to the upper floor (spec 13.4)");
            }

            int depth = plan.depths().getOrDefault(cell, 0);
            String lootSuffix = (isAnomalyCell || runTheme == null) ? null : runTheme.meta().lootSuffix;
            String lootTableOverride = (isAnomalyCell || runTheme == null) ? null : runTheme.meta().lootTable;
            List<BlockPos> spawnerAnchors = RoomContent.apply(level, cellOrigin, plan.roles().get(cell),
                    depth, profile, spawns, plan.seed(), affixes, lootSuffix, lootTableOverride,
                    isAnomalyCell && !"store".equals(entry.meta.content) ? null : theme,
                    voidedCells.contains(cell), isAnomalyCell, entry.meta.content);
            PressureSources.arm(level, cellOrigin, entry.meta, theme, affixes.contains(AffixIds.OMINOUS));
            trialSpawners.addAll(spawnerAnchors);
            if ("flooded_hall".equals(entry.meta.content)) {
                latchDoors.addAll(TraversalSpecs.latchDoorsAt(level, cellOrigin));
            }
            // A dead end that holds no fight (a vault, a chest, a wall) may hold a fountain.
            if (!isAnomalyCell && spawnerAnchors.isEmpty() && !cell.equals(plan.entrance())
                    && !cell.equals(plan.terminal())
                    && plan.doors().stream().filter(edge -> edge.touches(cell)).count() == 1) {
                Fountain.maybePlace(level, cellOrigin, plan.seed());
            }
            // Dungeon structure W4: darkness, then the room's resource nodes (D17, D19).
            NodeStamper.applyCell(level, cellOrigin, placed.rotation(), entry.meta, nodeCtx,
                    plan.seed() ^ cellOrigin.asLong(), nodePositions);
            // A sealed two-story room: rubble over its way down, after the
            // return-path check above has read the room as built. The seal is
            // its own Ordeal, keyed under the cell so the room's stays armed.
            if (plan.sealedCells().contains(cell)) {
                RubbleOrdeal.sealFloor(level, cellOrigin);
                Ordeals.armAt(RubbleOrdeal.FLOOR, level, cellOrigin, cellOrigin.below());
            }

            // M25: the pocket door lives in the run's first cleared encounter
            // cell. A cell with no sealed wall cannot host one (every wall
            // leads to a neighbour), so the roll falls through to the next
            // encounter cell rather than failing the run. M70: the check
            // reads the role's operation, so a third-party role with
            // operation TRIAL_ENCOUNTER also hosts a pocket door.
            if (pocket2Rolled && pocket2Door == null
                    && isEncounterOperation(plan.roles().get(cell))) {
                pocket2Door = Pocket2.placeDoor(level, cellOrigin, geometry);
            }

            // M2 T2.1/T2.4: the entrance cell is the room. Overlaying after the
            // ordinary content pass rather than skipping it keeps RoomSelector's
            // mask/role resolution completely unchanged -- entrance_hall is still
            // exactly what validates and gets picked, and this just re-skins it
            // with whatever the owner captured last time.
            if (cell.equals(entranceCell) && owner != null) {
                MinecraftServer server = level.getServer();
                boolean placedRoom = server != null && RoomStore.place(level, server, owner,
                        cellOrigin, placed.rotation(), RandomSource.create(plan.seed()));
                if (placedRoom) {
                    PocketDungeonsMod.LOG.debug("Placed {}'s saved room at the entrance cell", owner);
                }
            }
        }

        Set<BlockPos> ironDoorFarSideSlots = new LinkedHashSet<>();
        applyConnectors(level, geometry, plan, entranceCell, manifest, ironDoorFarSideSlots, doorOpeners);

        applyDirectionalGates(level, geometry, plan, manifest, softGates);

        BedrockEnvelope.apply(level, geometry, voidedCells, spanYByCell, standingCells);

        return new InstanceLayout(
                origin,
                geometry,
                geometry.cellCentre(entranceCell),
                InstanceLayout.yawFor(entranceDoor(plan, entranceCell)),
                geometry.cellCentre(plan.terminal()).below(),
                geometry.bounds(),
                plan.seed(),
                plan.criticalPath().size(),
                plan.cells().size(),
                profile.lootTier(),
                true,
                affixes,
                keystoneLevel,
                geometry.cellOrigin(plan.terminal()),
                plan.rooms().get(entranceCell).rotation(),
                plan.rooms().get(plan.terminal()).rotation(),
                Set.copyOf(trialSpawners),
                pocket2Door,
                Set.copyOf(ironDoorFarSideSlots),
                Set.copyOf(nodePositions),
                Set.copyOf(softGates),
                Set.copyOf(latchDoors),
                Map.copyOf(doorOpeners));
    }

    /**
     * M61: the vertical span of the room stamped in each cell, for
     * {@link BedrockEnvelope} so it can lower the sub-floor and extend the wall
     * rings of any multi-story room. Cells whose room declares no
     * {@code spanY} default to 1 and are unaffected.
     */
    private static Map<PlanCell, Integer> cellSpanY(DungeonPlan plan, RoomManifest manifest) {
        Map<PlanCell, Integer> out = new HashMap<>();
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> e : plan.rooms().entrySet()) {
            RoomManifest.Entry entry = entryAt(manifest, plan, e.getKey());
            if (entry != null && entry.meta.spanY > 1) {
                out.put(e.getKey(), entry.meta.spanY);
            }
        }
        return out;
    }

    /** M61: the deepest any cell in {@code spanYByCell} reaches below its floor. */
    private static int maxStoryOffset(Map<PlanCell, Integer> spanYByCell) {
        int max = 0;
        for (int spanY : spanYByCell.values()) {
            max = Math.max(max, RoomGeometry.storyOffset(spanY));
        }
        return max;
    }

    /**
     * Places directional doorway gates at runtime on the side facing away from
     * the entrance (higher depth). Three rooms bake their gate into the
     * template at the EXIT (east) doorway: infested_wall, elders_chamber, and
     * dont_look. The layout can rotate a room 180 degrees, which swaps
     * ENTRANCE and EXIT and puts the gate on the player's entrance side,
     * trapping them outside their own room. Placing the gate here, after the
     * plan's door edges are known, ensures the gate always faces the right way
     * regardless of rotation.
     *
     * <p>The gate goes one block inside the wall (not in the doorway plane
     * itself), the same convention the template decor used, so the doorway's
     * jigsaw blocks survive and the room keeps its door in the manifest mask.
     */
    private static void applyDirectionalGates(ServerLevel level, PlanGeometry geometry,
                                              DungeonPlan plan, RoomManifest manifest,
                                              Set<BlockPos> softGates) {
        for (var entry : plan.rooms().entrySet()) {
            PlanCell cell = entry.getKey();
            String roomName = entry.getValue().name();
            DoorMask.Direction gateSide = exitSide(plan, cell);
            if (gateSide == null) {
                // PD-147: a bridge in a cell with no deeper neighbour was never
                // armed, so it kept the template's retracted state: planks gone.
                // It stands, without a lever, like any other bridge.
                if ("pocketdungeons:collapsing_bridge".equals(JsonPackSupport.qualify(roomName))) {
                    Ordeals.arm(CollapsingBridgeOrdeal.INSTANCE, level, geometry.cellOrigin(cell));
                }
                continue;
            }
            BlockPos origin = geometry.cellOrigin(cell);
            // PD-66: plan room names are namespaced ("pocketdungeons:hold_the_plate"),
            // so the match qualifies the name first; a bare-name switch never fired.
            switch (JsonPackSupport.qualify(roomName)) {
                case "pocketdungeons:infested_wall" -> placeInfestedGate(level, origin, gateSide, softGates);
                case "pocketdungeons:elders_chamber" -> placeSealedGate(level, origin, gateSide,
                        Blocks.GRAVEL.defaultBlockState(), softGates);
                case "pocketdungeons:dont_look" -> placeDoorwayTopGate(level, origin, gateSide);
                case "pocketdungeons:hold_the_plate" -> placeIronDoorGate(level, origin, gateSide);
                case "pocketdungeons:rising_lava" -> armExitLeverOrdeal(level, origin, gateSide,
                        RisingLavaOrdeal.INSTANCE);
                case "pocketdungeons:collapsing_bridge" -> armExitLeverOrdeal(level, origin, gateSide,
                        CollapsingBridgeOrdeal.INSTANCE);
                default -> { }
            }
        }
    }

    /**
     * An Ordeal whose lever waits on the far side (Rising Lava, Collapsing
     * Bridge): the lever goes beside the exit doorway, one block inside the
     * wall at along {@code DOOR_MIN - 1}, facing into the room with its lamp
     * above, and only then is the Ordeal armed, since arming finds the lever.
     * Here rather than baked for the same reason as the gates: a room turned
     * round would carry a baked lever to its entrance.
     */
    private static void armExitLeverOrdeal(ServerLevel level, BlockPos origin, DoorMask.Direction exit,
                                           Ordeal<?> ordeal) {
        BlockPos lever = interiorDoorPos(origin, exit, RoomGeometry.DOOR_MIN - 1, 1);
        Ordeals.placeWallLever(level, lever, Instances.mcDirection(CellGeometry.opposite(exit)));
        Ordeals.arm(ordeal, level, origin);
    }

    /**
     * Finds the side of {@code cell} that faces away from the entrance (the
     * exit side), by looking for the door edge whose other cell has a higher
     * depth. Returns {@code null} if the cell has no door edge to a
     * higher-depth neighbour (e.g. the terminal cell or a dead-end branch).
     */
    private static DoorMask.Direction exitSide(DungeonPlan plan, PlanCell cell) {
        int myDepth = plan.depths().getOrDefault(cell, 0);
        DoorMask.Direction best = null;
        int bestDepth = myDepth;
        for (PlanEdge edge : plan.doors()) {
            if (!edge.touches(cell)) {
                continue;
            }
            PlanCell other = edge.other(cell);
            int otherDepth = plan.depths().getOrDefault(other, 0);
            if (otherDepth > bestDepth) {
                bestDepth = otherDepth;
                best = cell.directionTo(other);
            }
        }
        return best;
    }

    /**
     * Places infested stone bricks (y=1) and normal stone bricks (y=2..3) one
     * block inside the wall on the exit side, matching the original template
     * decor's material mix.
     */
    private static void placeInfestedGate(ServerLevel level, BlockPos origin,
                                          DoorMask.Direction wall, Set<BlockPos> softGates) {
        BlockState infested = Blocks.INFESTED_STONE_BRICKS.defaultBlockState();
        BlockState normal = Blocks.STONE_BRICKS.defaultBlockState();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
            for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                BlockPos pos = interiorDoorPos(origin, wall, z, y);
                level.setBlock(pos, y == 1 ? infested : normal, flags);
                softGates.add(pos.immutable());
            }
        }
    }

    /**
     * Places a full doorway plug (y=1..3) of the given material one block
     * inside the wall on the exit side.
     */
    private static void placeSealedGate(ServerLevel level, BlockPos origin,
                                        DoorMask.Direction wall, BlockState material,
                                        Set<BlockPos> softGates) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
            for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
                BlockPos pos = interiorDoorPos(origin, wall, z, y);
                level.setBlock(pos, material, flags);
                softGates.add(pos.immutable());
            }
        }
    }

    /**
     * Places a single lintel block at y=3 one block inside the wall on the
     * exit side, dropping the doorway to two blocks high so a 3-tall mob
     * cannot path through.
     */
    private static void placeDoorwayTopGate(ServerLevel level, BlockPos origin,
                                            DoorMask.Direction wall) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        BlockState wallBlock = RoomBuilder.WALL;
        for (int z = RoomGeometry.DOOR_MIN; z <= RoomGeometry.DOOR_MAX; z++) {
            level.setBlock(interiorDoorPos(origin, wall, z, 3), wallBlock, flags);
        }
    }

    /**
     * One block inside the wall on the given side, at doorway column {@code z}
     * and height {@code y}. Matches the {@code WALL_X - 1} convention the
     * template decor used for the east side, generalised to all four walls.
     */
    private static BlockPos interiorDoorPos(BlockPos origin, DoorMask.Direction wall,
                                            int z, int y) {
        return switch (wall) {
            case NORTH -> origin.offset(z, y, 1);
            case SOUTH -> origin.offset(z, y, RoomGeometry.CELL - 2);
            case EAST -> origin.offset(RoomGeometry.CELL - 2, y, z);
            case WEST -> origin.offset(1, y, z);
        };
    }

    /**
     * Places a pair of iron doors (and their y=3 lintel) one block inside the
     * wall on the exit side, matching the original template decor's convention.
     * The door faces into the room (opposite of the wall direction). The
     * redstone circuit's power block, which the repeater in the template
     * drives, is already next to this position because the circuit rotates
     * with the template. So placing the door here connects it to the existing
     * redstone gate without any additional wiring.
     *
     * <p>The hinge sides are chosen so the two leaves meet in the middle when
     * closed, the same convention {@link ConnectorStamper#applyIronDoor} uses
     * for connector iron doors.
     */
    private static void placeIronDoorGate(ServerLevel level, BlockPos origin,
                                          DoorMask.Direction wall) {
        Direction facing = Instances.mcDirection(CellGeometry.opposite(wall));
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
            DoorHingeSide hinge = i == RoomGeometry.DOOR_MIN ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing)
                    .setValue(net.minecraft.world.level.block.DoorBlock.HINGE, hinge)
                    .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                    .setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false);
            BlockState upper = lower.setValue(
                    net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            level.setBlock(interiorDoorPos(origin, wall, i, 1), lower, flags);
            level.setBlock(interiorDoorPos(origin, wall, i, 2), upper, flags);
            level.setBlock(interiorDoorPos(origin, wall, i, 3), RoomBuilder.WALL, flags);
        }
    }

    /**
     * M70: whether a cell's role has the TRIAL_ENCOUNTER operation, so the
     * pocket door can be placed there. Replaces the pre-M70
     * {@code "encounter".equals(role)} check, so a third-party role with
     * operation TRIAL_ENCOUNTER also hosts a pocket door.
     */
    private static boolean isEncounterOperation(String role) {
        if (role == null) {
            return false;
        }
        RoomRoleDefinition def = RoleManifest.current().byId(role);
        return def != null && def.operation == RoomRoleDefinition.Operation.TRIAL_ENCOUNTER;
    }

    /**
     * Selects which cells get voided floor when the VOIDED affix is active.
     * Entrance and terminal cells are always excluded. Each remaining cell
     * is independently rolled against {@link PocketDungeonsConfig#voidedCellChance}.
     */
    private static Set<PlanCell> computeVoidedCells(DungeonPlan plan, Set<String> affixes) {
        if (!affixes.contains(AffixIds.VOIDED)) {
            return Set.of();
        }
        Random rng = new Random(plan.seed() ^ 0xB01DL);
        Set<PlanCell> voided = new HashSet<>();
        PlanCell entrance = plan.entrance();
        PlanCell terminal = plan.terminal();
        // PD-20: plan.cells() is a Set.copyOf, whose iteration order is salted
        // per JVM instance, not per seed. Every other consumer of it in this
        // pipeline sorts first (see stampOrder below); this one did not, so
        // the same seed voided a different cell set on every server restart.
        List<PlanCell> sortedCells = new ArrayList<>(plan.cells());
        sortedCells.sort((a, b) -> a.x() != b.x()
                ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        for (PlanCell cell : sortedCells) {
            if (cell.equals(entrance) || cell.equals(terminal)) {
                continue;
            }
            if (rng.nextDouble() < PocketDungeonsConfig.voidedCellChance()) {
                voided.add(cell);
            }
        }
        return voided;
    }

    /** Critical path first, then everything else in the geometry's stable order. */
    private static List<PlanCell> stampOrder(DungeonPlan plan) {
        Set<PlanCell> ordered = new LinkedHashSet<>(plan.criticalPath());
        List<PlanCell> rest = new ArrayList<>(plan.cells());
        rest.sort((a, b) -> a.x() != b.x()
                ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        ordered.addAll(rest);
        return List.copyOf(ordered);
    }

    /**
     * (M30) Overlays a randomized {@link ConnectorType} on every door edge
     * except the entrance's: the entrance's canonical slot stays the default
     * wide opening the jigsaw already resolved to air, since a fresh run must
     * never gate the one door a player is guaranteed to reach behind a locked
     * or narrowed connector. Both cells on an edge get the same roll, so the
     * opening lines up across the two-block partition between them.
     */
    private static void applyConnectors(ServerLevel level, PlanGeometry geometry, DungeonPlan plan,
                                        PlanCell entranceCell, RoomManifest manifest,
                                        Set<BlockPos> ironDoorFarSideSlots,
                                        Map<BlockPos, Set<BlockPos>> doorOpeners) {
        for (PlanEdge edge : plan.doors()) {
            if (edge.touches(entranceCell)) {
                continue;
            }
            Random rng = ConnectorType.rngFor(plan.seed(), edge);
            // The roll is always taken, so a rubble edge leaves every other
            // edge's rng exactly where it was.
            ConnectorType rolled = ConnectorType.pick(rng);
            ConnectorType type = plan.rubbleEdges().contains(edge) ? ConnectorType.RUBBLE : ConnectorStamper.effectiveType(rolled,
                    entryAt(manifest, plan, edge.a()), entryAt(manifest, plan, edge.b()));
            // M45B: the window band rides this same per-edge pass. It is the
            // only place that knows an edge is open, knows both cells that
            // share it, and has already rolled the connector whose shape
            // decides whether a band belongs on that edge at all.
            applyWindowBand(level, geometry, plan, manifest, edge, type);
            if (type == ConnectorType.DOOR_WIDE) {
                continue;
            }
            boolean fillNearColumn = rng.nextBoolean();
            // IRON_DOOR: only one side stamps the door and its frame. Both
            // sides stamping their own full door set would put two closed
            // doors directly against each other, and a player who opens one
            // side is trapped between them, unable to reach the other door's
            // redstone. The door goes on the cell closer to the entrance
            // (smaller depth), so the player reaches its lever-side face
            // first on the way in.
            //
            // PD-62: that first-arrival guarantee is not a standing one.
            // Backtracking is allowed by design (spec section 2), and a
            // player who crosses through and finds the door shut behind them
            // has no redstone source anywhere in the far cell. Confirmed
            // live. The door itself stays locked to the far side (it is
            // meant to be a real lock, not something to dig through); the
            // fix instead lifts placement protection off the far cell's own
            // doorway threshold, so a stuck player can place their own
            // lever, button or redstone dust there and power the door from
            // behind. See InstanceLayout.ironDoorFarSideSlots and
            // RitualListener's placement check for the rest of it.
            if (type == ConnectorType.RUBBLE) {
                // Rubble, like the iron door, goes on the side the player
                // reaches first; the far cell keeps its open slot. The plug is
                // a lever-less Ordeal that a blast resolves.
                PlanCell nearCell = nearerToEntrance(plan, edge.a(), edge.b());
                applyConnectorToSide(level, geometry, nearCell, edge, type, fillNearColumn);
                DoorMask.Direction nearWall = nearCell.directionTo(edge.other(nearCell));
                if (nearWall != null) {
                    BlockPos nearOrigin = geometry.cellOrigin(nearCell);
                    Ordeals.armAt(RubbleOrdeal.KIND, level, nearOrigin,
                            ConnectorGeometry.wallPos(nearOrigin, nearWall, RoomGeometry.DOOR_MIN, 1));
                }
            } else if (type == ConnectorType.IRON_DOOR) {
                PlanCell nearCell = nearerToEntrance(plan, edge.a(), edge.b());
                PlanCell farCell = edge.other(nearCell);
                DoorMask.Direction nearWall = nearCell.directionTo(farCell);
                DoorMask.Direction farWall = farCell.directionTo(nearCell);
                if (nearWall != null) {
                    // PD-160: the lever is on another wall of the near room and a
                    // stone button stands in the far room; neither is adjacent to
                    // the door, so IronDoorLatch opens it on use.
                    ConnectorStamper.IronDoor placedDoor = ConnectorStamper.applyIronDoor(
                            level, geometry.cellOrigin(nearCell), nearWall);
                    Set<BlockPos> lowers = Set.copyOf(placedDoor.lowers());
                    doorOpeners.put(placedDoor.lever(), lowers);
                    if (farWall != null) {
                        BlockPos button = ConnectorStamper.applyFarSideButton(
                                level, geometry.cellOrigin(farCell), farWall);
                        if (button != null) {
                            doorOpeners.put(button, lowers);
                        }
                    }
                }
                if (farWall != null) {
                    ironDoorFarSideSlots.addAll(ConnectorGeometry.rect(
                            geometry.cellOrigin(farCell), farWall,
                            RoomGeometry.DOOR_MIN, RoomGeometry.DOOR_MAX, 1, RoomGeometry.DOOR_HEIGHT));
                }
            } else {
                applyConnectorToSide(level, geometry, edge.a(), edge, type, fillNearColumn);
                applyConnectorToSide(level, geometry, edge.b(), edge, type, fillNearColumn);
            }
        }
    }

    /**
     * Of two edge cells, the one with the smaller entrance hop count, falling back
     * to {@code a} on a tie. Used to pick which side of an iron door edge
     * carries the door so the player, arriving from the entrance, always
     * reaches the door's near face first.
     */
    private static PlanCell nearerToEntrance(DungeonPlan plan, PlanCell a, PlanCell b) {
        int da = plan.depths().getOrDefault(a, 0);
        int db = plan.depths().getOrDefault(b, 0);
        return db < da ? b : a;
    }

    /**
     * M45B: cuts both halves of one edge's window band, each side filled with
     * its own room's material (spec section 4, readable from the doorway). A
     * 2 by 3 doorway shows a player very little of what they are about to walk
     * into; the band beside it is what lets them look before they commit.
     *
     * <p>Each side uses its own room's material on purpose. A dark room can
     * face the partition with tinted glass while its neighbour answers with
     * bars, and the pair still reads through.
     *
     * <p>Three rules keep this off any wall that is not a live partition
     * between two placed rooms:
     *
     * <ul>
     *   <li>only edges in {@code plan.doors()} are considered, so a sealed
     *       wall is never touched, and neither is an outer wall of the
     *       dungeon. Those have no occupied neighbour, which is exactly the
     *       test {@link BedrockEnvelope} uses to decide where its ring goes,
     *       so the two conditions are complementary and the band can never
     *       breach the envelope;</li>
     *   <li>the entrance's edges are skipped by the caller, leaving the lobby
     *       and the entrance cell exactly as they were;</li>
     *   <li>{@link ConnectorStamper#leavesWindowBandSolid} skips the
     *       connectors that already open the band's own columns.</li>
     * </ul>
     *
     * <p><strong>The mismatch rule.</strong> If one room asks for
     * {@code none} and its neighbour asks for bars, the literal result is bars
     * on one face and stone on the other: not see-through, and a player
     * reading it as a window is reading a lie. So {@code none} on either side
     * suppresses the whole band, and the disagreement is logged with both room
     * names. Suppressing is the safer half of the pair, because a solid wall
     * is a shape players already understand while glass backed by stone is a
     * bug that looks like content. The warning is what keeps that from being a
     * silent authoring loss.
     */
    private static void applyWindowBand(ServerLevel level, PlanGeometry geometry, DungeonPlan plan,
                                        RoomManifest manifest, PlanEdge edge, ConnectorType type) {
        if (!ConnectorStamper.leavesWindowBandSolid(type)) {
            return;
        }
        PlanCell a = edge.a();
        PlanCell b = edge.b();
        DoorMask.Direction wallA = a.directionTo(b);
        DoorMask.Direction wallB = b.directionTo(a);
        if (wallA == null || wallB == null) {
            return;
        }
        String nameA = roomNameAt(plan, a);
        String nameB = roomNameAt(plan, b);
        BlockState materialA = windowMaterialAt(manifest, plan, a);
        BlockState materialB = windowMaterialAt(manifest, plan, b);
        if (materialA == null || materialB == null) {
            if (materialA != materialB) {
                PocketDungeonsMod.LOG.warn(
                        "Window band suppressed on the {} to {} edge: rooms \"{}\" and \"{}\""
                                + " disagree, one of them sets window \"none\". Half a band is"
                                + " glass backed by stone, so neither side gets one. Set both to"
                                + " \"none\", or give the \"none\" side a material.",
                        a, b, nameA, nameB);
            }
            return;
        }
        RoomBuilder.windowBand(level, geometry.cellOrigin(a), Instances.mcDirection(wallA), materialA);
        RoomBuilder.windowBand(level, geometry.cellOrigin(b), Instances.mcDirection(wallB), materialB);
    }

    /** The plan's room name for {@code cell}, or {@code null} if it has none. */
    private static String roomNameAt(DungeonPlan plan, PlanCell cell) {
        DungeonPlan.PlacedRoom placed = plan.rooms().get(cell);
        return placed == null ? null : placed.name();
    }

    /**
     * The band fill the room at {@code cell} asks for, or {@code null} for {@code none}
     * and for a room the manifest cannot resolve. An unresolvable name is
     * already fatal earlier in the stamp, so reaching here with one means the
     * plan changed underneath us, and leaving the wall solid is the harmless
     * read of that.
     */
    private static BlockState windowMaterialAt(RoomManifest manifest, DungeonPlan plan, PlanCell cell) {
        RoomManifest.Entry entry = entryAt(manifest, plan, cell);
        return entry == null ? null : RoomBuilder.windowMaterial(entry.meta.window);
    }

    /**
     * PD-93: the manifest entry for the room planned at {@code cell}, or
     * {@code null} if the cell has no room or no manifest knows it. The anomaly
     * cell's room comes out of {@link RoomManifest#currentAnomaly()}, which is
     * kept apart from {@code manifest} so themed queries never surface it; a
     * lookup in {@code manifest} alone can never find it.
     */
    static RoomManifest.Entry entryAt(RoomManifest manifest, DungeonPlan plan, PlanCell cell) {
        DungeonPlan.PlacedRoom placed = plan.rooms().get(cell);
        if (placed == null) {
            return null;
        }
        if (cell.equals(plan.anomalyCell())) {
            RoomManifest.Entry anomaly = RoomManifest.currentAnomaly().byName(placed.name());
            if (anomaly != null) {
                return anomaly;
            }
        }
        return manifest.byName(placed.name());
    }

    private static void applyConnectorToSide(ServerLevel level, PlanGeometry geometry, PlanCell cell,
                                             PlanEdge edge, ConnectorType type, boolean fillNearColumn) {
        DoorMask.Direction wall = cell.directionTo(edge.other(cell));
        if (wall == null) {
            return;
        }
        ConnectorStamper.apply(level, geometry.cellOrigin(cell), wall, type, fillNearColumn);
    }

    /**
     * The single door out of the entrance cell. The graph generator holds that
     * cell to exactly one door and {@code LayoutGraphGenerator.validate} enforces
     * it, so a missing edge here is a generator bug rather than bad luck -- but
     * defaulting to EAST keeps a player facing into a wall instead of crashing
     * their entry.
     */
    private static DoorMask.Direction entranceDoor(DungeonPlan plan, PlanCell entrance) {
        for (PlanEdge edge : plan.doors()) {
            if (edge.touches(entrance)) {
                DoorMask.Direction dir = entrance.directionTo(edge.other(entrance));
                if (dir != null) {
                    return dir;
                }
            }
        }
        PocketDungeonsMod.LOG.warn("Entrance cell {} has no outgoing door; facing east", entrance);
        return DoorMask.Direction.EAST;
    }
}
