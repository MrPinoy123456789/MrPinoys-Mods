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
        // T6 (decision 2026-10-18): the Proving's own door is retired; its rooms and waves are the dive's arena.
        helper.assertTrue(EndlessMineRules.openMines(DungeonDefs.current().all(), Set.of(1, 2), 99)
                        .stream().noneMatch(d -> PROVING.equals(d.id()) || DIVE.equals(d.id())),
                "neither the Proving nor the dive is an act's door in the astrolabe");
        helper.assertValueEqual(def.hall(), HallLayout.HALL_CHART, "the Proving file is parked outside the astrolabe");
        helper.assertTrue(!EndlessMineRules.isDive(def), "the retired Proving is not the dive");
        helper.succeed();
    }

    private static final String DIVE = HallOffers.CHART_DIVE_ID;

    /** A record in the endgame dive, about to play floor {@code floor} (the floors cleared so far is one less). */
    private static InstanceRecord diveRecord(GameTestHelper helper, InstanceLayout layout, int floor) {
        InstanceRecord record = new InstanceRecord(9982, layout.origin(), helper.getLevel().getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.endlessMine = true;
        record.interval.mineDungeonId = DIVE;
        record.interval.floorIndex = floor - 1;
        return record;
    }

    private static void clearGuardians(ServerLevel level, BlockPos terminalOrigin) {
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(MineGuardians.GUARDIAN_TAG)) {
                mob.discard();
            }
        }
    }

    /** The dive is loaded as an endless chart descent, and only every third floor is an arena. */
    @GameTest(maxTicks = 60)
    public void theDiveLoadsAndOnlyEveryThirdFloorIsAnArena(GameTestHelper helper) {
        DungeonDef def = DungeonDefs.current().byId(DIVE);
        helper.assertTrue(def != null, "endless_mine_chart is loaded");
        helper.assertTrue(EndlessMineRules.isDive(def) && EndlessMineRules.isMineId(DIVE), "it is the dive, a descent");
        helper.assertTrue(HallOffers.chartDive(DungeonDefs.current()) == def, "the Chart Room finds it");
        helper.assertTrue(def.hiddenOre() != null, "its floors hide ore like the Deep Mine's");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry,
                origin.offset(3, 1, 3), 0.0f, origin.offset(8, 1, 8), geometry.bounds(),
                1L, 4, 4, 1, true, Set.of(), 3, origin, 0, 0, Set.of(), null, Set.of());
        InstanceRecord record = diveRecord(helper, layout, 1);
        for (int floor = 1; floor <= 12; floor++) {
            boolean arena = floor % 3 == 0;
            helper.assertValueEqual(ProvingWaves.isDiveGuardianFloor(record, null, floor), arena,
                    "dive floor " + floor + (arena ? " is an arena" : " is a mining floor"));
            helper.assertValueEqual(ProvingWaves.isArenaFloor(record, null, floor), arena,
                    "dive floor " + floor + " arena planning");
        }
        helper.assertValueEqual(EndlessMineRules.actOf(record), Campaign.releaseMaxAct(),
                "the dive fights and pays from the highest finished act");
        helper.assertValueEqual(ProvingWaves.payingTable(record, 3, "fallback"), "fallback",
                "the Proving's own pay table never answers for the dive");
        helper.assertValueEqual(MineGuardians.payingTable(record, 3, "fallback"),
                EndlessMineRules.lootTableId(Campaign.releaseMaxAct(), 1), "floor 3 pays guardian 1's tier A table");
        helper.assertValueEqual(MineGuardians.payingTable(record, 4, "fallback"), "fallback",
                "a mining floor pays the plain floor haul");
        helper.succeed();
    }

    /**
     * The dive's arena floor plans the way the live door preview does: a gate beside the ring, the
     * {@code proving_arena} room in the terminal cell, on the dive's own theme.
     */
    @GameTest(maxTicks = 100)
    public void aDiveArenaFloorPlansTheRing(GameTestHelper helper) {
        ThemeManifest.Entry theme = ThemeManifest.current().byId(DungeonDefs.current().byId(DIVE).mainTheme());
        helper.assertTrue(theme != null, "the dive has a theme");
        DungeonPlan plan = PlaytestBias.withNodeBias(List.of(ProvingWaves.ARENA_ROOM), () -> LayoutPlanner.plan(
                new LayoutPlanner.PlanRequest(11L, RoomManifest.current(), LayoutPlanner.DEFAULT_ATTEMPT_BUDGET,
                        new LayoutPlanner.PathProfile(2, 2, LayoutPlanner.DEFAULT_BRANCH_PROBABILITY,
                                LayoutPlanner.DEFAULT_LOOP_PROBABILITY, LayoutPlanner.DEFAULT_MAX_GRID_SPAN),
                        ProvingWaves.ARENA_ROOM_THEME, DoorMask.Direction.NORTH, BagTags.pilgrim(1), null, 0,
                        null))).plan();
        helper.assertTrue(plan != null, "a dive guardian floor plans a gate and a ring");
        helper.assertValueEqual(plan.cells().size(), 2, "a dive arena floor is two cells");
        helper.succeed();
    }

    /**
     * The guardian floor's beats: wave one at commit, the next wave when it falls, the guardian when the
     * last wave falls, and the pad room opens only once the guardian is down. The pad stays shut throughout.
     */
    @GameTest(maxTicks = 300)
    public void theDiveArenaSendsWavesThenTheGuardianThenOpens(GameTestHelper helper) {
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
        InstanceRecord record = diveRecord(helper, layout, 3);
        int waves = Math.max(1, PocketDungeonsConfig.provingWavesPerFloor());
        try {
            RoomBuilder.buildShell(level, terminal, RoomBuilder.FLOOR);
            ProvingWaves.armDiveFloor(level, record, layout, 3, 20, "pocketdungeons:endless_mine_chart");
            helper.assertTrue(record.floor.provingPadOrigin != null, "the pad room is stamped at commit");
            helper.assertValueEqual(record.floor.arenaWavesLeft, waves - 1, "the first wave is out, the rest wait");
            helper.assertValueEqual(waveMobs(level, terminal).size(), ProvingWaves.waveCount(1, 1),
                    "the first guardian floor sends the first rung of the ladder");
            for (Mob mob : waveMobs(level, terminal)) {
                helper.assertTrue(!"Champion".equals(mob.getName().getString()),
                        "the guardian is the climax; the arena waves send no champion");
            }
            helper.assertTrue(ProvingWaves.padRefusal(level, record) != null, "the pad is shut while the wave stands");

            // Each wave falls; the next comes. The guardian is not called until the last wave is down.
            for (int wave = 2; wave <= waves; wave++) {
                clear(level, terminal);
                ProvingWaves.tick(level, level.getServer(), record);
                helper.assertTrue(!waveMobs(level, terminal).isEmpty(), "wave " + wave + " arrives");
                helper.assertTrue(!record.floor.arenaGuardianCalled, "no guardian while waves remain");
                helper.assertTrue(!record.floor.provingPadRevealed, "the pad room stays sealed");
            }
            helper.assertValueEqual(record.floor.arenaWavesLeft, 0, "every wave has been sent");

            clear(level, terminal);
            ProvingWaves.tick(level, level.getServer(), record);
            helper.assertTrue(record.floor.arenaGuardianCalled, "the last wave down calls the guardian");
            helper.assertTrue(MineGuardians.alive(level, terminal), "the guardian stands in the ring");
            helper.assertTrue(!record.floor.provingPadRevealed, "the pad room stays sealed with the guardian up");
            ProvingWaves.tick(level, level.getServer(), record);
            helper.assertTrue(!record.floor.provingPadRevealed, "and stays sealed while it lives");
            helper.assertTrue(ProvingWaves.padRefusal(level, record) != null
                            || MineGuardians.padRefusal(level, record) != null,
                    "the pad refuses while the guardian lives");

            clearGuardians(level, terminal);
            clear(level, terminal);
            ProvingWaves.tick(level, level.getServer(), record);
            helper.assertTrue(record.floor.provingPadRevealed, "the guardian down opens the way on");
            helper.assertTrue(ProvingWaves.padRefusal(level, record) == null
                            && MineGuardians.padRefusal(level, record) == null,
                    "and the pad opens: the floor can never be left shut");
        } finally {
            clear(level, terminal);
            clearGuardians(level, terminal);
        }
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
