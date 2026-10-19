package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/**
 * Vertical floors phase 2, step 6: a real two story plan (the Mineshaft's opt in) is stamped and its bedrock
 * envelope and exit pad are read back. The stair room's lower story and the cell it opens onto share a height, so
 * the envelope must leave the wall between them open (the doorway) while still closing every other face on both
 * stories; the pad, one story down at the terminal, must stand on solid floor inside the layout's bounds.
 */
public final class StairEnvelopeGameTest {

    private static final int CELL = RoomGeometry.CELL;
    private static final int H = RoomGeometry.STORY_HEIGHT;

    @GameTest(maxTicks = 600)
    public void aTwoStoryPlanStampsWithItsEnvelopeAndPad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DungeonPlan plan = null;
        long seed = 0;
        for (seed = 100; seed < 140 && plan == null; seed++) {
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(new LayoutPlanner.PlanRequest(
                    seed, RoomManifest.current(), LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                    new LayoutPlanner.PathProfile(LayoutPlanner.DEFAULT_MIN_PATH, LayoutPlanner.DEFAULT_MAX_PATH,
                            LayoutPlanner.DEFAULT_BRANCH_PROBABILITY, LayoutPlanner.DEFAULT_LOOP_PROBABILITY,
                            LayoutPlanner.DEFAULT_MAX_GRID_SPAN, 2),
                    null, null, BagTags.pilgrim(), null, 0, null));
            if (outcome.plan() != null) {
                plan = outcome.plan();
            }
        }
        helper.assertTrue(plan != null, "a two story plan is found");
        PlanCell upper = null;
        PlanCell lower = null;
        for (PlanEdge edge : plan.doors()) {
            if (edge.a().y() != edge.b().y()) {
                upper = edge.a().y() > edge.b().y() ? edge.a() : edge.b();
                lower = edge.other(upper);
            }
        }
        helper.assertTrue(upper != null, "the plan steps down at a stair room");
        helper.assertTrue(plan.rooms().get(upper).name().contains("stair"),
                "the step down is a stair room: " + plan.rooms().get(upper).name());

        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos origin = new BlockPos(18432 - minX * CELL, 120, 18432 - minZ * CELL);
        List<ChunkPos> chunks = PlanGeometry.of(origin, plan.cells()).chunks();
        Instances.forceLoad(level, chunks, true);
        InstanceLayout layout = null;
        try {
            layout = LayoutStamper.stamp(level, origin, plan, 6, Set.of());
            PlanGeometry geometry = layout.geometry();
            RoomManifest manifest = RoomManifest.current();

            // The sub-floor under the lowest story and the over-ceiling are bedrock on every cell.
            for (PlanCell cell : plan.cells()) {
                int spanY = manifest.byName(plan.rooms().get(cell).name()).meta.spanY;
                BlockPos o = geometry.cellOrigin(cell);
                int subFloor = o.getY() - 1 - RoomGeometry.storyOffset(spanY);
                for (int x = 0; x < CELL; x += 5) {
                    for (int z = 0; z < CELL; z += 5) {
                        helper.assertTrue(level.getBlockState(new BlockPos(o.getX() + x, subFloor, o.getZ() + z))
                                        .is(Blocks.BEDROCK),
                                "sub-floor of " + cell + " is bedrock at " + x + "," + z);
                        helper.assertTrue(level.getBlockState(new BlockPos(o.getX() + x,
                                        o.getY() + RoomGeometry.CEILING_Y + 1, o.getZ() + z)).is(Blocks.BEDROCK),
                                "over-ceiling of " + cell + " is bedrock at " + x + "," + z);
                    }
                }
            }

            // The doorway between the stair room's lower story and the cell it opens onto is open on both
            // sides and is not bedrock.
            DoorMask.Direction wallUp = upper.wallTo(lower);
            DoorMask.Direction wallLow = lower.wallTo(upper);
            helper.assertTrue(wallUp != null && wallLow != null, "the stair edge is adjacent in plan");
            for (BlockPos pos : CellGeometry.doorSlotPositions(geometry.wallOrigin(upper, lower), wallUp)) {
                helper.assertTrue(!level.getBlockState(pos).is(Blocks.BEDROCK),
                        "the stair room's lower doorway is not bedrock at " + pos.toShortString());
            }
            for (BlockPos pos : CellGeometry.doorSlotPositions(geometry.cellOrigin(lower), wallLow)) {
                helper.assertTrue(!level.getBlockState(pos).is(Blocks.BEDROCK),
                        "the far cell's doorway is not bedrock at " + pos.toShortString());
            }
            helper.assertValueEqual(geometry.wallOrigin(upper, lower).getY(), geometry.cellOrigin(lower).getY(),
                    "the stair room's lower story and the cell it opens onto share a floor");

            // Every outer face of the stair room's lower story with nothing across it is ringed in bedrock.
            BlockPos lowStory = geometry.wallOrigin(upper, lower);
            int ringed = 0;
            for (DoorMask.Direction face : DoorMask.Direction.values()) {
                PlanCell across = new PlanCell(CellGeometry.neighbourCell(upper, face).x(), lower.y(),
                        CellGeometry.neighbourCell(upper, face).z());
                BlockPos ring = ringPos(lowStory, face, 4, 3);
                boolean standing = plan.cells().contains(across);
                if (!standing && !plan.cells().contains(CellGeometry.neighbourCell(upper, face))) {
                    helper.assertTrue(level.getBlockState(ring).is(Blocks.BEDROCK),
                            "the lower story of the stair room is ringed on its " + face + " face");
                    ringed++;
                }
            }
            helper.assertTrue(ringed >= 1, "at least one outer face of the stair room's lower story was checked");

            // The pad: one story down at the terminal when the plan ends below the stair room, on a solid floor,
            // inside the layout's bounds.
            PlanCell terminal = plan.terminal();
            BlockPos pad = layout.exitPad();
            helper.assertValueEqual(pad.getY(), geometry.cellOrigin(terminal).getY(), "the pad stands on the terminal's floor");
            helper.assertTrue(!level.getBlockState(pad).isAir(), "the pad has a floor under it");
            AABB bounds = layout.bounds();
            helper.assertTrue(bounds.contains(pad.getX() + 0.5, pad.getY() + 0.5, pad.getZ() + 0.5),
                    "the pad is inside the layout bounds");
            int lowestFloor = Integer.MAX_VALUE;
            for (PlanCell cell : plan.cells()) {
                int spanY = manifest.byName(plan.rooms().get(cell).name()).meta.spanY;
                lowestFloor = Math.min(lowestFloor,
                        geometry.cellOrigin(cell).getY() - 1 - RoomGeometry.storyOffset(spanY));
            }
            helper.assertTrue(bounds.minY <= lowestFloor,
                    "the bounds reach under the lowest sub-floor (bounds " + bounds.minY + ", sub-floor " + lowestFloor + ")");
        } finally {
            if (layout != null) {
                AABB b = layout.bounds();
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int x = (int) Math.floor(b.minX) - 1; x <= (int) Math.ceil(b.maxX); x++) {
                    for (int y = (int) Math.floor(b.minY) - 1; y <= (int) Math.ceil(b.maxY); y++) {
                        for (int z = (int) Math.floor(b.minZ) - 1; z <= (int) Math.ceil(b.maxZ); z++) {
                            cursor.set(x, y, z);
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            Instances.forceLoad(level, chunks, false);
        }
        helper.succeed();
    }

    /** The bedrock ring position {@code along} blocks down {@code face}, {@code y} above the story floor. */
    private static BlockPos ringPos(BlockPos o, DoorMask.Direction face, int along, int y) {
        return switch (face) {
            case NORTH -> o.offset(along, y, -1);
            case SOUTH -> o.offset(along, y, CELL);
            case WEST -> o.offset(-1, y, along);
            case EAST -> o.offset(CELL, y, along);
        };
    }
}
