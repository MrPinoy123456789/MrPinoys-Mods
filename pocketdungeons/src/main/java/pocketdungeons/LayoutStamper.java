package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
     * bookkeeping -- is identical.
     */
    static InstanceLayout stampBehindLobby(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                           int keystoneLevel, Set<Affix> affixes, UUID owner) {
        return stamp(level, origin, plan, keystoneLevel, affixes, owner, true);
    }

    private static InstanceLayout stamp(ServerLevel level, BlockPos origin, DungeonPlan plan,
                                        int keystoneLevel, Set<Affix> affixes, UUID owner,
                                        boolean entranceAlreadyStamped) {
        PlanGeometry geometry = PlanGeometry.of(origin, plan.cells());
        StructureTemplateManager manager = level.getStructureManager();
        RoomManifest manifest = RoomManifest.current();
        DifficultyProfile profile = DifficultyProfile.of(plan.criticalPath().size(), keystoneLevel);
        PlanCell entranceCell = plan.entrance();

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
            List<BlockPos> spawns = TemplateStamper.place(
                    level, manager, cellOrigin, Identifier.parse(entry.meta.template),
                    placed.rotation(), plan.seed() ^ cellOrigin.asLong(),
                    entry.meta.processors == null ? null : Identifier.parse(entry.meta.processors));

            int depth = plan.depths().getOrDefault(cell, 0);
            RoomContent.apply(level, cellOrigin, plan.roles().get(cell), depth, profile, spawns,
                    plan.seed(), affixes);

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

        BedrockEnvelope.apply(level, geometry);

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
                plan.rooms().get(plan.terminal()).rotation());
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
