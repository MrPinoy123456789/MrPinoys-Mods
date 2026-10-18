package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The gather floor objective (The First Night): the floor stands a few more logs than its quota in
 * columns, the pad holds until the party has broken the quota by hand, blocks past it pay bonus rolls,
 * and a theme flagged {@code no_spawners} stamps no trial spawner. The record is built by hand and its
 * objective pinned through {@link FloorObjectives#registerForTesting}, the {@link ClaimFloorGameTest} way.
 */
public final class GatherFloorGameTest {

    private static final int SLOT = 9985;
    private static final PlanCell ENTRANCE = new PlanCell(0, 0);
    private static final PlanCell MID = new PlanCell(1, 0);
    private static final PlanCell BRANCH = new PlanCell(1, 1);
    private static final PlanCell TERMINAL = new PlanCell(2, 0);

    @GameTest(maxTicks = 60)
    @SuppressWarnings("removal")
    public void theTreelineOpensThePadAtTheQuota(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        List<PlanCell> cells = List.of(ENTRANCE, MID, BRANCH, TERMINAL);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, cells);
        for (BlockPos cellOrigin : geometry.cellOrigins()) {
            level.getChunk(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4, cellOrigin.getZ() >> 4);
            level.getChunk(cellOrigin.getX() >> 4, (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4,
                    (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 6; y++) {
                        level.setBlock(cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
        InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(ENTRANCE),
                0.0f, geometry.cellCentre(TERMINAL), geometry.bounds(), 1L, cells.size() - 1,
                cells.size(), 1, true, Set.of(), 3, geometry.cellOrigin(TERMINAL), 0, 0,
                Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, level.getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.dungeonId = "pocketdungeons:first_night";
        record.interval.nodeId = "treeline";
        record.members.put(member.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        DungeonPlan plan = new DungeonPlan(1L, Set.copyOf(cells), Map.of(), Map.of(), Map.of(), Set.of(),
                ENTRANCE, TERMINAL, List.of(ENTRANCE, MID, TERMINAL), null);

        FloorObjectives.registerForTesting(record, FloorObjective.GATHER);
        try {
            FloorObjectives.arm(level, record, plan, null);
            helper.assertTrue(record.floor.objective instanceof GatherObjective, "a gather floor arms a gather state");
            GatherObjective state = (GatherObjective) record.floor.objective;
            helper.assertTrue(!state.fallback, "the logs were placed");
            helper.assertValueEqual(state.quota, 8, "the Treeline asks for 8 logs");
            helper.assertValueEqual(state.placed.size(), 8 + GatherObjective.SPARE, "quota plus spares stand");
            for (BlockPos pos : state.placed) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.OAK_LOG), "an oak log at " + pos.toShortString());
                helper.assertTrue(RoomProtection.verdict(member, pos, level.getBlockState(pos)).allowed(),
                        "a fist breaks a log");
            }
            helper.assertValueEqual(FloorObjectives.padRefusal(record), "Not yet. Break 8 more Oak Logs.",
                    "the pad wants the quota");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), "Oak Log 0/8", "the bar reads the count");
            for (int i = 0; i < 7; i++) {
                level.destroyBlock(state.placed.get(i), false);
                FloorObjectives.onBreak(record, member, state.placed.get(i));
            }
            helper.assertValueEqual(FloorObjectives.padRefusal(record), "Not yet. Break 1 more Oak Log.",
                    "one left reads singular");
            level.destroyBlock(state.placed.get(7), false);
            FloorObjectives.onBreak(record, member, state.placed.get(7));
            helper.assertTrue(FloorObjectives.padRefusal(record) == null, "the pad opens at the quota");
            level.destroyBlock(state.placed.get(8), false);
            FloorObjectives.onBreak(record, member, state.placed.get(8));
            helper.assertValueEqual(FloorObjectives.bonusRolls(record), 1, "a log past the quota pays a roll");
            helper.assertTrue(!FloorObjectives.onBreak(record, member, state.placed.get(0)),
                    "a felled log credits nothing twice");
            helper.succeed();
        } finally {
            FloorObjectives.forgetForTesting(record);
        }
    }

    /** The party grows the quota by half the count per extra member. */
    @GameTest
    public void thePartyGrowsTheQuota(GameTestHelper helper) {
        helper.assertValueEqual(GatherObjective.quotaFor(8, 1), 8, "one member");
        helper.assertValueEqual(GatherObjective.quotaFor(8, 3), 16, "three members");
        helper.succeed();
    }

    /** Gather floors plan with no encounter cells; the last floor of Act 0 plans exactly one. */
    @GameTest
    public void theFirstNightCapsItsSpawners(GameTestHelper helper) {
        helper.assertValueEqual(Instances.encounterCap(Keystone.fromDoor(DungeonDefs.current(),
                new TripDoors.Door("pocketdungeons:first_night", "treeline", 1, 0, 0, 0), 1, 100), 2), -1,
                "a gather floor has no spawners");
        helper.assertValueEqual(Instances.encounterCap(Keystone.fromDoor(DungeonDefs.current(),
                new TripDoors.Door("pocketdungeons:first_night", "cave_mouth", 1, 0, 0, 0), 1, 100), 2), 1,
                "the cave mouth has one");
        helper.succeed();
    }
}
