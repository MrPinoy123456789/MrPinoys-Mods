package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The claim floor objective (act 3-4 floor types): the floor hides gilded blackstone veins in
 * every cell but the entrance, the pad holds until the party claims the quota by right-click,
 * every claim raises Tremor (an ominous floor starts one louder), loud claims raise hoglin
 * packs, and at the hunter tremor the floor sends The Assayer once. Veins past the quota pay
 * bonus reward rolls.
 *
 * <p>No claim dungeon JSON exists yet, so the record is built by hand and its objective is
 * pinned through {@link FloorObjectives#registerForTesting} (the {@link HeistFloorGameTest}
 * way: the pieces are driven directly rather than a real descent, which the gametest world
 * cannot stand up). The record stays out of the live slot registry, so the watch loop never
 * touches it.
 */
public final class ClaimFloorGameTest {

    private static final int SLOT = 9983;
    private static final int FALLBACK_SLOT = 9984;

    /** Entrance, mid (critical path), terminal, and one branch cell off the path. */
    private static final PlanCell ENTRANCE = new PlanCell(0, 0);
    private static final PlanCell MID = new PlanCell(1, 0);
    private static final PlanCell BRANCH = new PlanCell(1, 1);
    private static final PlanCell TERMINAL = new PlanCell(2, 0);

    private static InstanceRecord claimRecord(GameTestHelper helper, int slot, List<PlanCell> cells,
                                              PlanCell entrance, PlanCell terminal) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, cells);
        InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(entrance),
                0.0f, geometry.cellCentre(terminal), geometry.bounds(), 1L, cells.size() - 1,
                cells.size(), 1, true, Set.of(), 3, geometry.cellOrigin(terminal), 0, 0,
                Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, level.getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.dungeonId = "pocketdungeons:test_claim";
        record.interval.nodeId = "seam";
        return record;
    }

    private static DungeonPlan claimPlan(List<PlanCell> cells, PlanCell entrance, PlanCell terminal,
                                         List<PlanCell> path) {
        return new DungeonPlan(1L, Set.copyOf(cells), Map.of(), Map.of(), Map.of(), Set.of(),
                entrance, terminal, path, null);
    }

    /** Loads every chunk a cell touches, lays a stone floor and clears the air so veins can stand. */
    private static void prepareCells(ServerLevel level, PlanGeometry geometry) {
        for (BlockPos cellOrigin : geometry.cellOrigins()) {
            level.getChunk(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4, cellOrigin.getZ() >> 4);
            level.getChunk(cellOrigin.getX() >> 4, (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            level.getChunk((cellOrigin.getX() + RoomGeometry.CELL - 1) >> 4,
                    (cellOrigin.getZ() + RoomGeometry.CELL - 1) >> 4);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cellOrigin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    // The test cells sit at real-world height: some land in solid rock. Free
                    // spots need air, so carve the cell up to head height plus clearance.
                    for (int y = 1; y <= 6; y++) {
                        level.setBlock(cellOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /** Fills a cell solid at vein height and above, so {@code freeSpotInCell} can never land there. */
    private static void fillCell(ServerLevel level, BlockPos cellOrigin) {
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= 2; y++) {
                    level.setBlock(cellOrigin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Every wave mob this floor raised, found by the claim's own tag. */
    private static List<Mob> waveMobs(ServerLevel level, AABB bounds) {
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(ClaimObjective.WAVE_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    /** Every piglin brute in range, found by type (The Assayer carries the wave tag too). */
    private static List<Mob> assayers(ServerLevel level, AABB bounds) {
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.getType() == EntityTypes.PIGLIN_BRUTE) {
                out.add(mob);
            }
        }
        return out;
    }

    /** How many of the veins stand inside {@code cell}'s bounds. */
    private static int veinsInCell(PlanGeometry geometry, PlanCell cell, List<BlockPos> veins) {
        AABB bounds = CellGeometry.cellBounds(geometry.cellOrigin(cell));
        int count = 0;
        for (BlockPos vein : veins) {
            if (bounds.contains(vein.getX() + 0.5, vein.getY() + 0.5, vein.getZ() + 0.5)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The full claim beat: the seam placed outside the entrance at two a cell, the pad counting
     * the claims down, claims past the quota paying bonus rolls, the hoglin pack at the wave
     * tremor, The Assayer once at the hunter tremor, and a spent vein crediting nothing.
     */
    @GameTest(maxTicks = 60)
    @SuppressWarnings("removal")
    public void theSeamOpensThePad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        List<PlanCell> cells = List.of(ENTRANCE, MID, BRANCH, TERMINAL);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, cells);
        prepareCells(level, geometry);

        InstanceRecord record = claimRecord(helper, SLOT, cells, ENTRANCE, TERMINAL);
        record.members.put(member.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        AABB mobBounds = record.layout.bounds().inflate(RoomGeometry.CELL);
        FloorObjectives.registerForTesting(record, FloorObjective.CLAIM);
        try {
            FloorObjectives.arm(level, record,
                    claimPlan(cells, ENTRANCE, TERMINAL, List.of(ENTRANCE, MID, TERMINAL)), null);
            helper.assertTrue(record.floor.objective instanceof ClaimObjective,
                    "a claim floor arms a claim state");
            ClaimObjective state = (ClaimObjective) record.floor.objective;
            helper.assertTrue(!state.fallback, "the seam placed on this floor");

            // 1. Quota plus extras for a one-member party, every vein gilded, every vein a
            // fixture, none in the entrance, at most two to a cell.
            int want = PocketDungeonsConfig.claimQuota() + PocketDungeonsConfig.claimExtraVeins();
            helper.assertValueEqual(state.veins.size(), want, "quota plus extras were placed");
            helper.assertValueEqual(state.quota, PocketDungeonsConfig.claimQuota(),
                    "the full quota was placed");
            for (BlockPos vein : state.veins) {
                helper.assertTrue(level.getBlockState(vein).is(Blocks.GILDED_BLACKSTONE),
                        "a vein is gilded blackstone at " + vein.toShortString());
                helper.assertTrue(FloorObjectives.isFixture(vein), "a vein is a fixture");
            }
            helper.assertValueEqual(veinsInCell(geometry, ENTRANCE, state.veins), 0,
                    "no vein stands in the entrance cell");
            for (PlanCell cell : cells) {
                helper.assertTrue(veinsInCell(geometry, cell, state.veins) <= 2,
                        "at most two veins to a cell: " + cell);
            }

            // 2. The pad counts the claims down as the seam is worked.
            helper.assertValueEqual(FloorObjectives.padRefusal(record),
                    "The seam holds. Claim 3 more veins.", "the pad wants the full quota");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), "Veins 0/3",
                    "the bar reads the seam's progress");
            helper.assertTrue(FloorObjectives.onUse(member, state.veins.get(0)),
                    "the first vein claims the click");
            helper.assertTrue(level.getBlockState(state.veins.get(0)).is(Blocks.BLACKSTONE),
                    "a claimed vein spends to blackstone");
            helper.assertValueEqual(FloorObjectives.padRefusal(record),
                    "The seam holds. Claim 2 more veins.", "two left");
            FloorObjectives.onUse(member, state.veins.get(1));
            helper.assertValueEqual(FloorObjectives.padRefusal(record),
                    "The seam holds. Claim 1 more vein.", "the last vein reads singular");
            FloorObjectives.onUse(member, state.veins.get(2));

            // 3. At quota the pad opens; the floor's spawners were never part of the gate.
            helper.assertTrue(FloorObjectives.padRefusal(record) == null, "the pad is open");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), "Veins 3/3",
                    "the bar reads the filled quota");

            // 4. Digging past the quota pays bonus rolls, and the wave-tremor claim raises
            // hoglins tagged as an omen wave.
            FloorObjectives.onUse(member, state.veins.get(3));
            FloorObjectives.onUse(member, state.veins.get(4));
            helper.assertValueEqual(FloorObjectives.bonusRolls(record), 2,
                    "two veins past the quota pay two rolls");
            List<Mob> hoglins = waveMobs(level, mobBounds);
            helper.assertTrue(!hoglins.isEmpty(), "the loud claim raised a pack");
            for (Mob mob : hoglins) {
                helper.assertTrue(mob.getType() == EntityTypes.HOGLIN, "the pack is hoglins");
                helper.assertTrue(mob.entityTags().contains(PocketDungeonsMod.OMEN_WAVE_TAG),
                        "the pack carries the no-drops wave tag");
            }
            helper.assertValueEqual(FloorObjectives.objectiveLine(record),
                    "Veins 5/3, the seam is loud.", "a loud seam says so");

            // 5. The hunter-tremor claim sends The Assayer, exactly once.
            FloorObjectives.onUse(member, state.veins.get(5));
            helper.assertValueEqual(assayers(level, mobBounds).size(), 1, "The Assayer stands");
            Mob assayer = assayers(level, mobBounds).get(0);
            helper.assertTrue(assayer.hasCustomName()
                            && ClaimObjective.ASSAYER_NAME.equals(assayer.getCustomName().getString()),
                    "the hunter is named The Assayer");
            helper.assertTrue(assayer.entityTags().contains(PocketDungeonsMod.OMEN_WAVE_TAG),
                    "The Assayer carries the no-drops wave tag");

            // A further claim does not send a second Assayer: stand one more vein by hand and
            // work it (the floor only places quota plus extras, but the state is the gate).
            BlockPos bonus = MineGuardians.freeSpotInCell(level, geometry.cellOrigin(MID),
                    new java.util.HashSet<>(state.veins), level.getRandom());
            helper.assertTrue(bonus != null, "a bonus vein spot exists");
            level.setBlock(bonus, Blocks.GILDED_BLACKSTONE.defaultBlockState(), 3);
            state.fixtures.add(bonus);
            helper.assertTrue(FloorObjectives.onUse(member, bonus), "the bonus vein claims");
            helper.assertValueEqual(assayers(level, mobBounds).size(), 1,
                    "no second Assayer comes");

            // 6. Idempotent: a spent vein credits nothing.
            int claimed = state.claimed;
            helper.assertTrue(!FloorObjectives.onUse(member, state.veins.get(0)),
                    "a spent vein does not claim the click");
            helper.assertValueEqual(state.claimed, claimed, "a spent vein credits nothing");
            helper.succeed();
        } finally {
            for (Mob mob : waveMobs(level, mobBounds)) {
                mob.discard();
            }
            FloorObjectives.forgetForTesting(record);
        }
    }

    /**
     * The generic fallback: a plan whose only vein cell (the terminal) offers no free spot arms
     * a fallback state with no fixtures, and the floor runs as an ordinary spawner floor.
     */
    @GameTest(maxTicks = 60)
    @SuppressWarnings("removal")
    public void aSeamWithNoSpotFallsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<PlanCell> cells = List.of(ENTRANCE, TERMINAL);
        PlanGeometry geometry = PlanGeometry.of(helper.absolutePos(BlockPos.ZERO), cells);
        prepareCells(level, geometry);
        fillCell(level, geometry.cellOrigin(TERMINAL));

        InstanceRecord record = claimRecord(helper, FALLBACK_SLOT, cells, ENTRANCE, TERMINAL);
        FloorObjectives.registerForTesting(record, FloorObjective.CLAIM);
        try {
            FloorObjectives.arm(level, record,
                    claimPlan(cells, ENTRANCE, TERMINAL, cells), null);
            helper.assertTrue(record.floor.objective instanceof ClaimObjective,
                    "a claim floor still arms a claim state");
            ClaimObjective state = (ClaimObjective) record.floor.objective;
            helper.assertTrue(state.fallback, "no vein spot means the fallback floor");
            helper.assertTrue(state.fixtures.isEmpty(), "a fallback floor protects nothing");
            helper.assertTrue(state.veins.isEmpty(), "a fallback floor places no veins");
            helper.assertTrue(FloorObjectives.padRefusal(record) == null,
                    "a fallback floor never refuses the pad");
            helper.assertTrue(FloorObjectives.isFallback(record), "the record reads fallback");
            helper.succeed();
        } finally {
            FloorObjectives.forgetForTesting(record);
        }
    }
}
