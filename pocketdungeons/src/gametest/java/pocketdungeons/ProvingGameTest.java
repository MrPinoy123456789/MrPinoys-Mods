package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Proving (decision 2026-10-16): a descent dungeon of kind {@code proving} whose every
 * floor is one wave fight. These tests run the wave engine directly, the same seam
 * {@link MineGuardianGameTest} uses: a record flagged as a proving descent, a wave spawned
 * in its terminal cell, the pad gate, the climb and the pay table.
 */
public final class ProvingGameTest {

    private static final String PROVING = "pocketdungeons:the_proving";

    private static InstanceRecord provingRecord(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        loadCell(helper.getLevel(), origin);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(9980, origin, helper.getLevel().getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.endlessMine = true;
        record.interval.mineDungeonId = PROVING;
        return record;
    }

    private static void loadCell(ServerLevel level, BlockPos cellOrigin) {
        level.getChunk(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4);
        level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4, cellOrigin.getZ() >> 4);
        level.getChunk(cellOrigin.getX() >> 4, (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
        level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4,
                (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
    }

    /** Lays a solid floor under the cell so the wave can stand on it. */
    private static void floor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    /** The wave mobs scoped to {@code terminalOrigin}: counted by tag, the way {@link ProvingWaves#alive} finds them. */
    private static List<Mob> waveMobs(ServerLevel level, BlockPos terminalOrigin) {
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(ProvingWaves.PROVING_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void clear(ServerLevel level, BlockPos terminalOrigin) {
        for (Mob mob : waveMobs(level, terminalOrigin)) {
            mob.discard();
        }
    }

    @GameTest(maxTicks = 60)
    public void theProvingLoadsAsADescent(GameTestHelper helper) {
        DungeonDef def = DungeonDefs.current().byId(PROVING);
        helper.assertTrue(def != null, "the_proving is loaded");
        helper.assertValueEqual(def.kind(), DungeonDef.Kind.PROVING, "the_proving parses its kind");
        helper.assertTrue(EndlessMineRules.isMineId(PROVING), "a proving id opens a descent");
        helper.assertTrue(EndlessMineRules.openMines(DungeonDefs.current().all(), Set.of(1), 99)
                        .stream().anyMatch(d -> PROVING.equals(d.id())),
                "the astrolabe's special slot offers it to an open act");
        helper.succeed();
    }

    /**
     * The Proving plans a two-cell floor (gate beside the arena): the sight-line rule used to
     * reject every seed, so the live door preview burned its whole budget and refused
     * (server log, playtest 2026-10-16). Plans the floor the way {@code Instances#planFloor}
     * does: min and max path 2, the node's room bias, the live manifest.
     */
    @GameTest(maxTicks = 100)
    public void aProvingFloorPlans(GameTestHelper helper) {
        DungeonDef def = DungeonDefs.current().byId(PROVING);
        helper.assertTrue(def != null, "the_proving is loaded");
        DungeonDef.Node node = def.node("the_ring");
        helper.assertTrue(node != null, "the_ring node is loaded");
        ThemeManifest.Entry theme = ThemeManifest.current().byId(def.mainTheme());
        String roomTheme = theme == null ? null : theme.meta().roomTheme;
        RoomEligibility.Floor floor = new RoomEligibility.Floor(def.id(), def.mainTheme(), roomTheme,
                def.act(), false, def.mainTheme(), "", node.layer() == 1, node.isFinal(), false,
                def.minNodeRooms(node));
        DungeonPlan plan = PlaytestBias.withNodeBias(node.roomBias(), () -> LayoutPlanner.plan(
                new LayoutPlanner.PlanRequest(7L, RoomManifest.current(),
                        LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                        new LayoutPlanner.PathProfile(2, 2, LayoutPlanner.DEFAULT_BRANCH_PROBABILITY,
                                LayoutPlanner.DEFAULT_LOOP_PROBABILITY, LayoutPlanner.DEFAULT_MAX_GRID_SPAN),
                        roomTheme, DoorMask.Direction.NORTH, BagTags.pilgrim(1), null, 0, floor))).plan();
        helper.assertTrue(plan != null, "a proving floor plans a gate and an arena");
        helper.assertValueEqual(plan.cells().size(), 2, "a proving floor is two cells");
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void everyFloorHoldsAWaveThatHoldsThePad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = provingRecord(helper);
        BlockPos terminal = record.layout.terminal();
        floor(helper);
        try {
            helper.assertTrue(ProvingWaves.isProving(record), "the record reads as a Proving");
            int previous = 0;
            for (int floor = 1; floor <= 4; floor++) {
                clear(level, terminal);
                ProvingWaves.spawn(level, record, terminal, floor, 5, 1);
                List<Mob> wave = waveMobs(level, terminal);
                helper.assertValueEqual(wave.size(), ProvingWaves.waveCount(floor, 1),
                        "floor " + floor + " wave size");
                helper.assertTrue(wave.size() > previous, "a deeper wave is never smaller");
                previous = wave.size();
                helper.assertTrue(ProvingWaves.padRefusal(level, record) != null,
                        "the pad stays shut while the wave stands");
                for (Mob mob : wave) {
                    helper.assertTrue(!mob.entityTags().contains(MineGuardians.GUARDIAN_TAG),
                            "a proving mob is not a guardian");
                }
            }
            clear(level, terminal);
            helper.assertTrue(ProvingWaves.padRefusal(level, record) == null,
                    "with the wave down the pad opens");
        } finally {
            clear(level, terminal);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aChampionFloorNamesItsChampion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = provingRecord(helper);
        BlockPos terminal = record.layout.terminal();
        floor(helper);
        try {
            helper.assertTrue(ProvingWaves.isChampionFloor(3) && !ProvingWaves.isChampionFloor(1),
                    "every third floor is a champion floor");
            ProvingWaves.spawn(level, record, terminal, 3, 5, 1);
            long champions = waveMobs(level, terminal).stream()
                    .filter(m -> "Champion".equals(m.getName().getString())).count();
            helper.assertValueEqual((int) champions, 1, "floor 3 sends one champion");
        } finally {
            clear(level, terminal);
        }
        helper.succeed();
    }

    /**
     * PD-215 (playtest 2026-10-16): the arena carries no lodestone of its own, so a
     * cleared wave left nothing to complete on. The exit pad is a hidden room one cell
     * beyond the arena's far wall, stamped at commit and revealed by
     * {@link ProvingWaves#tick} once the wave is down. Two cells (gate, arena), a real
     * shell so the far wall can open, and a mock player standing the pad.
     */
    @GameTest(maxTicks = 200)
    public void thePadRoomHidesBehindTheArenaUntilTheWaveDies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        for (int x = 0; x <= 2; x++) {
            loadCell(level, origin.east(RoomGeometry.CELL * x));
        }
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
        BlockPos terminal = geometry.cellOrigin(new PlanCell(1, 0));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                terminal.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3,
                terminal, 0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(9981, origin, level.getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.endlessMine = true;
        record.interval.mineDungeonId = PROVING;
        try {
            // A shell so the arena's far wall physically stands, as a stamped floor's does.
            RoomBuilder.buildShell(level, terminal, RoomBuilder.FLOOR);
            ProvingWaves.stampPadRoom(level, record, layout, "the_proving");
            BlockPos pad = record.floor.provingPadOrigin;
            helper.assertValueEqual(pad, terminal.east(RoomGeometry.CELL),
                    "the pad room hides one cell past the arena's far wall");
            for (BlockPos pos : CellGeometry.doorSlotPositions(terminal, DoorMask.Direction.EAST)) {
                helper.assertTrue(!level.getBlockState(pos).isAir(),
                        "the far wall is sealed while the wave stands");
            }
            for (int x = 7; x <= 8; x++) {
                for (int z = 7; z <= 8; z++) {
                    helper.assertTrue(level.getBlockState(pad.offset(x, 0, z)).is(Blocks.LODESTONE),
                            "the hidden room holds the lodestone pad at " + x + "," + z);
                }
            }

            ProvingWaves.spawn(level, record, terminal, 1, 5, 1);
            ProvingWaves.tick(level, level.getServer(), record);
            helper.assertTrue(!record.floor.provingPadRevealed,
                    "the pad room stays sealed while the wave stands");
            for (BlockPos pos : CellGeometry.doorSlotPositions(terminal, DoorMask.Direction.EAST)) {
                helper.assertTrue(!level.getBlockState(pos).isAir(),
                        "the far wall is still sealed with the wave up");
            }

            clear(level, terminal);
            ProvingWaves.tick(level, level.getServer(), record);
            helper.assertTrue(record.floor.provingPadRevealed,
                    "the pad room opens once the wave is down");
            for (BlockPos pos : CellGeometry.doorSlotPositions(terminal, DoorMask.Direction.EAST)) {
                helper.assertTrue(level.getBlockState(pos).isAir(),
                        "the far wall opens onto the pad room");
            }

            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.teleportTo(level, pad.getX() + 8.5, pad.getY() + 1, pad.getZ() + 8.5,
                    Set.of(), 0.0f, 0.0f, false);
            helper.assertTrue(Instances.isOnExitPad(player, record),
                    "the hidden room's pad is the run's exit pad");
        } finally {
            clear(level, terminal);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theWaveLadderPaysDeeper(GameTestHelper helper) {
        InstanceRecord record = provingRecord(helper);
        helper.assertValueEqual(ProvingWaves.payingTable(record, 1, null),
                "pocketdungeons:endless_mine/act1_a", "floor 1 pays tier A");
        helper.assertValueEqual(ProvingWaves.payingTable(record, 2, null),
                "pocketdungeons:endless_mine/act1_b", "floor 2 pays tier B");
        helper.assertValueEqual(ProvingWaves.payingTable(record, 9, null),
                "pocketdungeons:endless_mine/act1_c", "deeper floors cap at tier C");
        DungeonDef def = EndlessMineRules.mineOf(record);
        helper.assertTrue(ProvingWaves.floorTier(EndlessMineRules.bandOf(def), 3)
                        > ProvingWaves.floorTier(EndlessMineRules.bandOf(def), 1),
                "the completion chest climbs with depth too");
        helper.succeed();
    }
}
