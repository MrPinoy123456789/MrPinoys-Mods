package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
            ThemeManifest.Entry runTheme = ThemeManifest.current().byId(theme);
            String processors = entry.meta.processors != null ? entry.meta.processors
                    : runTheme == null ? null : runTheme.meta().processors;
            List<BlockPos> spawns = TemplateStamper.place(
                    level, manager, cellOrigin, Identifier.parse(entry.meta.template),
                    placed.rotation(), plan.seed() ^ cellOrigin.asLong(),
                    processors == null ? null : Identifier.parse(processors));

            int depth = plan.depths().getOrDefault(cell, 0);
            String lootSuffix = runTheme == null ? null : runTheme.meta().lootSuffix;
            BlockPos spawnerAnchor = RoomContent.apply(level, cellOrigin, plan.roles().get(cell),
                    depth, profile, spawns, plan.seed(), affixes, lootSuffix,
                    voidedCells.contains(cell));
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
                pocket2Door);
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
        for (PlanCell cell : plan.cells()) {
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
