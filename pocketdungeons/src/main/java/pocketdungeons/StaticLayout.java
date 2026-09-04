package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The Milestone 0 dungeon: four cells in a line running east.
 *
 * <pre>
 *   [0 skeleton] -- [1 zombie] -- [2 chest] -- [3 exit]
 * </pre>
 *
 * <p>This is the hand-authored fallback layout the spec calls for in section
 * 7.2 step 8, built first so that everything downstream of the planner --
 * allocation, force-loading, stamping, teleport, lifecycle, teardown -- can be
 * proven before any procedural generation exists. {@code LayoutPlanner} will
 * later produce the same shape of data; the stamper below is what consumes it.
 */
final class StaticLayout {

    static final int CELL_COUNT = 4;

    private StaticLayout() {}

    /** Builds the rooms and their contents. Must run on the server thread. */
    static void stamp(ServerLevel level, BlockPos origin) {
        TemplateStamper.stamp(level, origin);
    }

    /**
     * This layout packaged the way {@link Instances} now consumes every layout.
     *
     * <p>{@code procedural = false} is what tells a completion message, an admin
     * listing, or a bug report that the player got the fallback rather than a
     * planned dungeon -- worth being able to distinguish, because the fallback
     * firing at all means the planner or the room library needs looking at.
     */
    static InstanceLayout layout(BlockPos origin) {
        return layout(origin, 0, EnumSet.noneOf(Affix.class));
    }

    /**
     * The fallback, carrying the run's keystone level and ominous flag so a player
     * whose dungeon collapsed into the M0 four-room line is still paid and still
     * handed their keystone back on the terms they paid for.
     */
    static InstanceLayout layout(BlockPos origin, int keystoneLevel, Set<Affix> affixes) {
        List<PlanCell> cells = new ArrayList<>(CELL_COUNT);
        for (int i = 0; i < CELL_COUNT; i++) {
            cells.add(new PlanCell(i, 0));
        }
        return new InstanceLayout(
                origin,
                PlanGeometry.of(origin, cells),
                entrance(origin),
                entranceYaw(),
                exitPad(origin),
                bounds(origin),
                0L,
                CELL_COUNT,
                CELL_COUNT,
                keystoneLevel > 0 ? KeystoneMath.lootTier(keystoneLevel) : 1,
                false,
                affixes,
                keystoneLevel,
                RoomBuilder.cellOrigin(origin, CELL_COUNT - 1, 0),
                0, 0, Set.of(), null, Set.of());
    }

    /** Where the player lands: centre of the first room, facing east down the run. */
    static BlockPos entrance(BlockPos origin) {
        return RoomBuilder.cellCentre(origin, 0, 0);
    }

    static float entranceYaw() {
        return -90.0f; // east, toward the rest of the dungeon
    }

    /** Standing here extracts the player. Floor block at the centre of the last room. */
    static BlockPos exitPad(BlockPos origin) {
        return RoomBuilder.cellCentre(origin, CELL_COUNT - 1, 0).below();
    }

    /** Everything the instance owns, used for entity sweeps and teardown. */
    static AABB bounds(BlockPos origin) {
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + CELL_COUNT * RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + RoomGeometry.CELL);
    }
}
