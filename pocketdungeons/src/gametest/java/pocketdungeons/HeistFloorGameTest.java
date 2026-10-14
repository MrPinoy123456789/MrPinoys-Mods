package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The heist floor objective (act 3-4 floor types): a quiet floor that walks in with its trial
 * spawners hidden under reinforced deepslate, the Vault Core in the terminal cell and strongboxes
 * in the branches; the pad stays sealed until a member channels the core open, which wakes the
 * spawners, pays the strongboxes and raises the crew.
 *
 * <p>No heist dungeon JSON exists yet, so the record is built by hand and its objective is pinned
 * through {@link FloorObjectives#registerForTesting} (the {@link MineGuardianGameTest} way: the
 * pieces are driven directly rather than a real descent, which the gametest world cannot stand up).
 * The record stays out of the live slot registry, so the watch loop never touches it.
 */
public final class HeistFloorGameTest {

    private static final int SLOT = 9982;

    /** Entrance, mid (critical path), terminal, and one branch cell off the path. */
    private static final PlanCell ENTRANCE = new PlanCell(0, 0);
    private static final PlanCell MID = new PlanCell(1, 0);
    private static final PlanCell BRANCH = new PlanCell(1, 1);
    private static final PlanCell TERMINAL = new PlanCell(2, 0);

    private static InstanceRecord heistRecord(GameTestHelper helper, Set<String> affixes,
                                              Set<BlockPos> spawners) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(ENTRANCE, MID, BRANCH, TERMINAL));
        InstanceLayout layout = new InstanceLayout(origin, geometry, geometry.cellCentre(ENTRANCE),
                0.0f, geometry.cellCentre(TERMINAL), geometry.bounds(), 1L, 3, geometry.cells().size(),
                1, true, Set.of(), 3, geometry.cellOrigin(TERMINAL), 0, 0, spawners, null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, level.getServer().getTickCount(),
                layout, affixes, UUID.randomUUID(), false);
        record.interval.dungeonId = "pocketdungeons:test_heist";
        record.interval.nodeId = "vault";
        return record;
    }

    private static DungeonPlan heistPlan() {
        return new DungeonPlan(1L, Set.of(ENTRANCE, MID, BRANCH, TERMINAL), Map.of(), Map.of(),
                Map.of(), Set.of(), ENTRANCE, TERMINAL, List.of(ENTRANCE, MID, TERMINAL), null);
    }

    /** Loads every chunk a cell touches, lays a stone floor and clears the air so fixtures can stand. */
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

    private static List<Mob> crew(ServerLevel level, BlockPos terminalOrigin) {
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(HeistObjective.CREW_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void discardCrew(ServerLevel level, BlockPos terminalOrigin) {
        for (Mob mob : crew(level, terminalOrigin)) {
            mob.discard();
        }
    }

    /**
     * The full heist beat: armed quiet, sealed pad, protected fixtures, the channel, the crack
     * restoring spawners and paying strongboxes, the crew, and a second crack doing nothing.
     */
    @GameTest(maxTicks = 60)
    @SuppressWarnings("removal")
    public void theVaultCracksOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // A trial spawner in the mid cell and one in the branch cell.
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(ENTRANCE, MID, BRANCH, TERMINAL));
        prepareCells(level, geometry);
        Set<BlockPos> spawners = new HashSet<>();
        for (PlanCell cell : List.of(MID, BRANCH)) {
            BlockPos at = geometry.cellCentre(cell);
            level.setBlock(at, Blocks.TRIAL_SPAWNER.defaultBlockState(), 3);
            spawners.add(at);
        }
        // What each spawner must come back as, captured before the arm hides them.
        Map<BlockPos, CompoundTag> expectedTags = new HashMap<>();
        for (BlockPos pos : spawners) {
            expectedTags.put(pos, level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess()));
        }

        InstanceRecord record = heistRecord(helper, Set.of(), spawners);
        record.members.put(member.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.members.put(UUID.randomUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        FloorObjectives.registerForTesting(record, FloorObjective.HEIST);
        try {
            FloorObjectives.arm(level, record, heistPlan(), null);
            helper.assertTrue(record.floor.objective instanceof HeistObjective,
                    "a heist floor arms a heist state");
            HeistObjective state = (HeistObjective) record.floor.objective;
            helper.assertTrue(!state.fallback, "the heist placed on this floor");
            helper.assertTrue(state.core != null && state.plinth != null, "the vault core stands");

            // 1. Every trial spawner is now reinforced deepslate with its tag captured.
            for (BlockPos pos : record.layout.trialSpawners()) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.REINFORCED_DEEPSLATE),
                        "spawner at " + pos.toShortString() + " is deepslate until the crack");
            }
            helper.assertValueEqual(state.standIns.size(), spawners.size(), "every spawner captured");
            for (HeistObjective.StandIn standIn : state.standIns) {
                helper.assertTrue(!standIn.tag().isEmpty(), "the captured spawner tag is not empty");
                helper.assertTrue(standIn.tag().equals(expectedTags.get(standIn.pos())),
                        "the captured tag is the spawner's own");
            }

            // 2. The pad stays sealed.
            helper.assertValueEqual(FloorObjectives.padRefusal(record), HeistObjective.SEALED_PAD,
                    "the pad refuses while the vault is sealed");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), HeistObjective.SEALED_LINE,
                    "the bar reads sealed");

            // 3. The core, plinth and strongboxes are protected fixtures.
            helper.assertTrue(FloorObjectives.isFixture(state.core), "the core is a fixture");
            helper.assertTrue(FloorObjectives.isFixture(state.plinth), "the plinth is a fixture");
            helper.assertTrue(!state.strongboxes.isEmpty(), "strongboxes were placed");
            for (BlockPos box : state.strongboxes) {
                helper.assertTrue(FloorObjectives.isFixture(box), "a strongbox is a fixture");
                helper.assertTrue(level.getBlockState(box).is(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                                net.minecraft.resources.Identifier.parse("minecraft:waxed_oxidized_copper"))),
                        "a strongbox is waxed oxidized copper until the crack");
            }
            helper.assertValueEqual(
                    BreakRule.decide(FloorObjectives.isFixture(state.core), false, false, false, true),
                    BreakRule.Verdict.REFUSE_FIXTURE, "the break rule refuses the core");

            // 4. The channel: a click starts it, a swing starts it too (the break rule feeds the
            // core's hits to FloorObjectives.onAttack), leaving reach resets it, a hit on the
            // channeler breaks it, and letting it run cracks the vault.
            helper.assertTrue(FloorObjectives.onUse(member, state.core), "the core claims the click");
            helper.assertTrue(member.getUUID().equals(state.channeler), "the channel started");
            state.channeler = null;
            helper.assertTrue(FloorObjectives.onAttack(member, state.core),
                    "a swing at the core starts the channel the same as a click");
            member.teleportTo(level, state.core.getX() + 20.5, state.core.getY(), state.core.getZ() + 0.5,
                    Set.of(), 0.0f, 0.0f, false);
            FloorObjectives.tick(level.getServer(), record);
            helper.assertTrue(state.channeler == null && !state.cracked,
                    "straying out of reach resets the channel");
            member.teleportTo(level, state.core.getX() + 1.5, state.core.getY(), state.core.getZ() + 0.5,
                    Set.of(), 0.0f, 0.0f, false);
            FloorObjectives.onUse(member, state.core);
            state.channelerHurt(member);
            helper.assertTrue(state.channeler == null && !state.cracked,
                    "a hit breaks the channel");
            FloorObjectives.onUse(member, state.core);
            state.channelEndsAt = level.getServer().getTickCount();
            FloorObjectives.tick(level.getServer(), record);
            helper.assertTrue(state.cracked, "the finished channel cracked the vault");

            // 5. The wake: spawners restored tag for tag, strongboxes pay, the crew stands, the pad opens.
            for (BlockPos pos : record.layout.trialSpawners()) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.TRIAL_SPAWNER),
                        "the spawner is back at " + pos.toShortString());
                helper.assertTrue(level.getBlockEntity(pos)
                                .saveWithFullMetadata(level.registryAccess()).equals(expectedTags.get(pos)),
                        "the spawner's stamped config survived the swap");
            }
            for (BlockPos box : state.strongboxes) {
                helper.assertTrue(level.getBlockState(box).is(Blocks.BARREL),
                        "a cracked strongbox is a barrel");
                helper.assertTrue(level.getBlockEntity(box)
                                instanceof net.minecraft.world.RandomizableContainer container
                                && container.getLootTable() != null,
                        "the barrel holds a deferred roll of the floor's tier table");
            }
            int crew = crew(level, record.layout.terminal()).size();
            helper.assertValueEqual(crew, PocketDungeonsConfig.heistCrewBase() + 2,
                    "the crew is the base plus one per member");
            for (Mob mob : crew(level, record.layout.terminal())) {
                helper.assertTrue(mob.getType() == EntityTypes.DROWNED, "the crew is drowned");
                helper.assertTrue(mob.entityTags().contains(PocketDungeonsMod.OMEN_WAVE_TAG),
                        "crew mobs carry the no-drops wave tag");
            }
            helper.assertTrue(FloorObjectives.padRefusal(record) == null, "the pad is open");
            helper.assertValueEqual(FloorObjectives.objectiveLine(record), HeistObjective.OPEN_LINE,
                    "the bar reads open");

            // 6. Idempotent: a second crack changes nothing.
            state.crack(record);
            helper.assertValueEqual(crew(level, record.layout.terminal()).size(), crew,
                    "a second crack raises no second crew");
            for (BlockPos pos : record.layout.trialSpawners()) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.TRIAL_SPAWNER),
                        "a second crack leaves the spawners alone");
            }
            helper.succeed();
        } finally {
            discardCrew(level, record.layout.terminal());
            FloorObjectives.forgetForTesting(record);
        }
    }

    /** An ominous heist raises the crew twice: once on the crack, once a watch tick later. */
    @GameTest(maxTicks = 80)
    @SuppressWarnings("removal")
    public void anOminousVaultRaisesTheCrewTwice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(ENTRANCE, MID, BRANCH, TERMINAL));
        prepareCells(level, geometry);
        Set<BlockPos> spawners = new HashSet<>();
        BlockPos spawnerAt = geometry.cellCentre(MID);
        level.setBlock(spawnerAt, Blocks.TRIAL_SPAWNER.defaultBlockState(), 3);
        spawners.add(spawnerAt);

        InstanceRecord record = heistRecord(helper, Set.of(AffixIds.OMINOUS), spawners);
        record.members.put(member.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        FloorObjectives.registerForTesting(record, FloorObjective.HEIST);
        try {
            FloorObjectives.arm(level, record, heistPlan(), null);
            HeistObjective state = (HeistObjective) record.floor.objective;
            helper.assertTrue(state != null && !state.fallback, "the ominous heist armed");
            state.crack(record);
            int firstWave = crew(level, record.layout.terminal()).size();
            helper.assertTrue(firstWave > 0, "the crack raised the crew");
            helper.assertTrue(state.ominousEchoAt != 0, "an ominous floor owes a second wave");
            discardCrew(level, record.layout.terminal());
            // The finally below runs now, before this callback; the echo wave
            // it counts is cleaned up inside the callback itself.
            helper.runAfterDelay(HeistObjective.OMINOUS_ECHO_TICKS + 2L, () -> {
                try {
                    FloorObjectives.tick(level.getServer(), record);
                    helper.assertValueEqual(crew(level, record.layout.terminal()).size(), firstWave,
                            "the echo wave matches the first");
                    helper.succeed();
                } finally {
                    discardCrew(level, record.layout.terminal());
                }
            });
        } finally {
            discardCrew(level, record.layout.terminal());
            FloorObjectives.forgetForTesting(record);
        }
    }
}
