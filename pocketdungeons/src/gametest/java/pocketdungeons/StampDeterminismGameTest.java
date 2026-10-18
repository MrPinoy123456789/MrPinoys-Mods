package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/**
 * Stamping is seeded: the same plan stamped at the same origin builds the same
 * blocks, so the same seed still gives the same dungeon after a change to the
 * stamper's structure.
 */
public final class StampDeterminismGameTest {

    /**
     * The block states over the stamped layouts folded to one number, taken on the
     * stamper before its parameter record refactor (and matched after it). Zero would
     * mean "not pinned": the test then only checks that two stamps agree.
     */
    private static final int STAMP_FINGERPRINT = -1458388674;
    // The First Night rooms: re-pinned again (was -182528007) when the eighteen Act 0 rooms joined the
    // manifest, so seed 7 draws a different set again.
    // T10: re-pinned once (was 935166682) when Voided stopped voiding puzzle and ordeal cells; the
    // second variant carries Voided, so seed 7 now keeps a puzzle or ordeal cell whole.
    // Act 3/4: re-pinned again (was -1975741233) when the nine new signature rooms joined the
    // manifest, so seed 7 draws a different set.
    // The Proving: re-pinned again (was 1659377431) when the gate and arena rooms joined the
    // manifest, so seed 7 draws a different set again.
    // Timber vein: re-pinned again (was -1161058281) when mineshaft_timber_vein joined the
    // manifest, so seed 7 draws a different set again.

    /**
     * One plan stamped twice at one origin builds the same blocks, with and without
     * affixes, and the whole set folds to {@link #STAMP_FINGERPRINT}.
     */
    @GameTest(maxTicks = 400)
    public void stampIsDeterministicAndPinned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(7L, RoomManifest.current(),
                LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                LayoutPlanner.DEFAULT_MAX_GRID_SPAN, null, null);
        helper.assertTrue(outcome.plan() != null, "seed 7 plans: " + outcome.failureReason());
        DungeonPlan plan = outcome.plan();
        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos origin = new BlockPos(12288 - minX * RoomGeometry.CELL, 120, 12288 - minZ * RoomGeometry.CELL);
        List<net.minecraft.world.level.ChunkPos> chunks = PlanGeometry.of(origin, plan.cells()).chunks();
        List<Set<String>> variants = List.of(
                Set.of(),
                Set.of(AffixIds.VOIDED, AffixIds.MOLTEN));
        int folded = 17;
        Instances.forceLoad(level, chunks, true);
        try {
            for (Set<String> affixes : variants) {
                InstanceLayout first = LayoutStamper.stamp(level, origin, plan, 6, affixes);
                int one = blockFingerprint(level, first.bounds());
                clearBlocks(level, first.bounds());
                InstanceLayout second = LayoutStamper.stamp(level, origin, plan, 6, affixes);
                int two = blockFingerprint(level, second.bounds());
                clearBlocks(level, second.bounds());
                helper.assertTrue(one == two, "stamping the same plan twice built different blocks with affixes "
                        + affixes);
                helper.assertTrue(first.trialSpawners().equals(second.trialSpawners()),
                        "stamping the same plan twice registered different trial spawners with affixes " + affixes);
                folded = 31 * folded + one;
            }
        } finally {
            Instances.forceLoad(level, chunks, false);
        }
        PocketDungeonsMod.LOG.info("stamp fingerprint {}", folded);
        helper.assertTrue(STAMP_FINGERPRINT == 0 || folded == STAMP_FINGERPRINT,
                "stamp fingerprint changed: expected " + STAMP_FINGERPRINT + " but was " + folded);
        helper.succeed();
    }

    /** The block ids and properties over {@code bounds} (plus a one block margin), folded in a fixed order. */
    private static int blockFingerprint(ServerLevel level, AABB bounds) {
        int folded = 17;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(bounds.minX) - 1; x <= (int) Math.ceil(bounds.maxX); x++) {
            for (int y = (int) Math.floor(bounds.minY) - 1; y <= (int) Math.ceil(bounds.maxY); y++) {
                for (int z = (int) Math.floor(bounds.minZ) - 1; z <= (int) Math.ceil(bounds.maxZ); z++) {
                    cursor.set(x, y, z);
                    folded = 31 * folded + level.getBlockState(cursor).toString().hashCode();
                }
            }
        }
        return folded;
    }

    /** Sets every block over {@code bounds} (plus a one block margin) to air. */
    private static void clearBlocks(ServerLevel level, AABB bounds) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(bounds.minX) - 1; x <= (int) Math.ceil(bounds.maxX); x++) {
            for (int y = (int) Math.floor(bounds.minY) - 1; y <= (int) Math.ceil(bounds.maxY); y++) {
                for (int z = (int) Math.floor(bounds.minZ) - 1; z <= (int) Math.ceil(bounds.maxZ); z++) {
                    cursor.set(x, y, z);
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }
}
