package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
                                int keystoneLevel, Set<Affix> affixes) {
        return stamp(level, origin, plan, keystoneLevel, affixes, null);
    }

    /**
     * As the 5-argument {@link #stamp}, with {@code owner}'s persisted room (M2)
     * overlaid onto the entrance cell if one exists. {@code owner} is
     * {@code null} for a run with no room concept at all
     * ({@code /dungeon admin build}/{@code untimed}).
     */
    static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                int keystoneLevel, Set<Affix> affixes, UUID owner) {
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
     */
    static InstanceLayout stampBehindLobby(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                           int keystoneLevel, Set<Affix> affixes, UUID owner,
                                           String theme) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, true, theme);
    }

    private static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                        int keystoneLevel, Set<Affix> affixes, UUID owner,
                                        boolean entranceAlreadyStamped) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, entranceAlreadyStamped, null);
    }

    private static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                        int keystoneLevel, Set<Affix> affixes, UUID owner,
                                        boolean entranceAlreadyStamped, String theme) {
        PlanGeometry geometry = PlanGeometry.of(origin, plan.cells());
        StructureTemplateManager manager = level.getStructureManager();
        RoomManifest manifest = RoomManifest.current();
        DifficultyProfile profile = DifficultyProfile.of(plan.criticalPath().size(), keystoneLevel);
        PlanCell entranceCell = plan.entrance();
        // M10: every trial spawner this stamp places, for the spawner-clear
        // completion gate. Collected here rather than passed an InstanceRecord,
        // since one does not exist yet this early; it travels on the returned
        // layout instead, the way everything else about this run's shape does.
        Set<BlockPos> trialSpawners = new LinkedHashSet<>();
        // M25: the rare door to a Pocket2 child, if this run rolled one. Placed
        // in the first cleared encounter cell; carried on the layout so the
        // right-click handler can find it without scanning the world.
        // Rolled once per run off the plan seed, and gated on the adventure
        // graph: a theme that has no node in it (an unthemed run, or a datapack
        // that never declared one) never hosts a pocket door.
        boolean pocket2Rolled = theme != null
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
            RoomManifest.Entry entry = manifest.byName(placed.name());
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

            int depth = plan.depths().getOrDefault(cell, 0);
            String lootSuffix = (isAnomalyCell || runTheme == null) ? null : runTheme.meta().lootSuffix;
            BlockPos spawnerAnchor = RoomContent.apply(level, cellOrigin, plan.roles().get(cell),
                    depth, profile, spawns, plan.seed(), affixes, lootSuffix, isAnomalyCell ? null : theme,
                    voidedCells.contains(cell), isAnomalyCell, entry.meta.content);
            if (spawnerAnchor != null) {
                trialSpawners.add(spawnerAnchor);
            }

            // M25: the pocket door lives in the run's first cleared encounter
            // cell. A cell with no sealed wall cannot host one (every wall
            // leads to a neighbour), so the roll falls through to the next
            // encounter cell rather than failing the run.
            if (pocket2Rolled && pocket2Door == null
                    && "encounter".equals(plan.roles().get(cell))) {
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
        applyConnectors(level, geometry, plan, entranceCell, manifest, ironDoorFarSideSlots);

        BedrockEnvelope.apply(level, geometry, voidedCells);

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
                Set.copyOf(ironDoorFarSideSlots));
    }

    /**
     * Selects which cells get voided floor when the VOIDED affix is active.
     * Entrance and terminal cells are always excluded. Each remaining cell
     * is independently rolled against {@link PocketDungeonsConfig#voidedCellChance}.
     */
    private static Set<PlanCell> computeVoidedCells(DungeonPlan plan, Set<Affix> affixes) {
        if (!affixes.contains(Affix.VOIDED)) {
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
                                        Set<BlockPos> ironDoorFarSideSlots) {
        for (PlanEdge edge : plan.doors()) {
            if (edge.touches(entranceCell)) {
                continue;
            }
            Random rng = ConnectorType.rngFor(plan.seed(), edge);
            ConnectorType type = ConnectorType.pick(rng);
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
            if (type == ConnectorType.IRON_DOOR) {
                PlanCell nearCell = nearerToEntrance(plan, edge.a(), edge.b());
                applyConnectorToSide(level, geometry, nearCell, edge, type, fillNearColumn);
                PlanCell farCell = edge.other(nearCell);
                DoorMask.Direction farWall = farCell.directionTo(nearCell);
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
        BlockState materialA = windowMaterialAt(manifest, nameA);
        BlockState materialB = windowMaterialAt(manifest, nameB);
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
     * The band fill a named room asks for, or {@code null} for {@code none}
     * and for a room the manifest cannot resolve. An unresolvable name is
     * already fatal earlier in the stamp, so reaching here with one means the
     * plan changed underneath us, and leaving the wall solid is the harmless
     * read of that.
     */
    private static BlockState windowMaterialAt(RoomManifest manifest, String roomName) {
        if (roomName == null) {
            return null;
        }
        RoomManifest.Entry entry = manifest.byName(roomName);
        return entry == null ? null : RoomBuilder.windowMaterial(entry.meta.window);
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
