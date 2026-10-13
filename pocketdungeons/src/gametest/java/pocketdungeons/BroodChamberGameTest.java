package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Spawner Dungeon's Brood Chamber (roadmap T43, decision 6b): each cage frees its own Act 1
 * mechanic, the brood mother arrives with the last wave, and the pad holds until she and every
 * brood mob are dead.
 *
 * <p>The harness has no dungeon dimension, so the watch tick is driven directly
 * ({@link CapstoneFights#tickBrood}, {@link CapstoneFights#broodPadRefusal}) with a hand laid
 * chamber: a stone floor and the four caged spawners of {@link CapstoneSpecs#BROOD_SPAWNERS}.
 */
public final class BroodChamberGameTest {

    private static InstanceRecord chamberRecord(GameTestHelper helper, int slot) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry,
                origin.offset(RoomGeometry.CELL + 3, 1, 3), 0.0f, origin.offset(8, 1, 8),
                geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 5, origin, 0, 0, Set.of(), null,
                Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin,
                helper.getLevel().getServer().getTickCount(), layout, Set.of(), UUID.randomUUID(), false);
        record.interval.dungeonId = PocketDungeonsMod.MOD_ID + ":" + CapstoneFights.SPAWNER_DUNGEON;
        record.interval.nodeId = "brood_chamber";
        return record;
    }

    /** A stone floor under the cell and the four caged spawners of the real room. */
    private static void chamber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
        for (BlockPos rel : CapstoneSpecs.BROOD_SPAWNERS) {
            level.setBlock(helper.absolutePos(rel), Blocks.SPAWNER.defaultBlockState(), 3);
        }
        // The test structure's barrier band sits a couple of blocks up; the drip needs three of air.
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 2; y <= 16; y++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static List<Mob> brood(ServerLevel level, BlockPos terminal) {
        AABB bounds = CellGeometry.cellBounds(terminal).inflate(RoomGeometry.CELL);
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(CapstoneFights.WAVE_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void tick(GameTestHelper helper, InstanceRecord record, CapstoneFights.State state,
                             List<ServerPlayer> members) {
        CapstoneFights.tickBrood(helper.getLevel().getServer(), helper.getLevel(), record, state, members);
    }

    /**
     * Breaking a cage frees exactly its own mechanic (decision 6b, the fixed cage order): the first
     * the coffins, the second the bricks, the third the dark, the fourth the drip.
     */
    @GameTest(maxTicks = 100)
    public void eachCageFreesItsOwnMechanic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        chamber(helper);
        InstanceRecord record = chamberRecord(helper, 9948);
        BlockPos terminal = record.layout.terminal();
        List<BlockPos> cages = CapstoneFights.spawnerPositions(level, terminal);
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(GameType.SURVIVAL);
        member.teleportTo(level, terminal.getX() + 8.5, terminal.getY() + 1.0, terminal.getZ() + 8.5,
                Set.of(), 0.0f, 0.0f, false);
        try {
            CapstoneFights.State state = CapstoneFights.state(record);
            List<ServerPlayer> members = List.of(member);
            tick(helper, record, state, members);
            BroodPhases.Phases phases = BroodPhases.phasesOf(record.slot);
            helper.assertTrue(phases != null, "the chamber holds phase state after the first tick");
            for (boolean released : phases.released) {
                helper.assertTrue(!released, "no cage is done before one is broken");
            }
            helper.assertTrue(CapstoneFights.broodPadRefusal(level, record, members) != null,
                    "the pad is shut while the cages stand");

            // Cage one: the Ossuary's coffins stand, nothing else stirs.
            level.destroyBlock(cages.get(0), false);
            tick(helper, record, state, members);
            helper.assertTrue(phases.released[0] && !phases.released[1]
                            && !phases.released[2] && !phases.released[3],
                    "only the first mechanic is loose");
            helper.assertValueEqual(MineGuardianCoffins.phases(terminal).size(),
                    BroodPhaseRules.coffinsFor(1), "the coffins stand for a party of one");
            helper.assertTrue(phases.bricks == null && phases.darkAt < 0
                            && phases.nextDrip == Long.MAX_VALUE, "the other three stay shut");

            // Cage two: creeping brick seeds queue and the first is planted.
            level.destroyBlock(cages.get(1), false);
            tick(helper, record, state, members);
            helper.assertTrue(phases.released[1] && phases.bricks != null,
                    "the second cage opens the brick field");
            helper.assertTrue(phases.bricks.active() > 0, "the first seed is already planted");
            helper.assertValueEqual(MineGuardianCoffins.phases(terminal).size(),
                    BroodPhaseRules.coffinsFor(1), "the coffins are still stood");

            // Cage three: the dark is scheduled after its tell, then the lights go and spiders come.
            level.setBlock(terminal.offset(2, 1, 2), Blocks.TORCH.defaultBlockState(), 3);
            level.destroyBlock(cages.get(2), false);
            tick(helper, record, state, members);
            helper.assertTrue(phases.released[2] && phases.darkAt >= 0, "the dark is scheduled");
            helper.assertTrue(!phases.dark && level.getBlockState(terminal.offset(2, 1, 2)).is(Blocks.TORCH),
                    "the lights hold during the tell");
            phases.darkAt = server.getTickCount();
            tick(helper, record, state, members);
            helper.assertTrue(phases.dark, "the dark lands once the tell has run");
            helper.assertTrue(level.getBlockState(terminal.offset(2, 1, 2)).isAir(),
                    "the torch went out with the rest");
            helper.assertTrue(level.getBlockState(terminal.offset(2, 1, 2)).isAir(),
                    "the removed torch stays out");

            // Cage four: the drip starts, and the first brew falls at the member.
            level.destroyBlock(cages.get(3), false);
            tick(helper, record, state, members);
            helper.assertTrue(phases.released[3] && phases.nextDrip < Long.MAX_VALUE,
                    "the drip is scheduled");
            phases.nextDrip = server.getTickCount();
            tick(helper, record, state, members);
            boolean potion = false;
            for (ThrownSplashPotion thrown : level.getEntitiesOfClass(ThrownSplashPotion.class,
                    CellGeometry.cellBounds(terminal).inflate(RoomGeometry.CELL))) {
                if (thrown.entityTags().contains(PotionDrip.TAG)) {
                    potion = true;
                }
            }
            helper.assertTrue(potion, "a tagged brew is falling on the member");
            helper.succeed();
        } finally {
            member.discard();
            CapstoneFights.teardown(level, record.slot, record.layout);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * The pad stays shut until the brood mother and every brood mob are dead: the fourth cage calls
     * her with the wave, her half health beat empowers the pack, and the refusal only lifts when
     * nothing tagged still stands.
     */
    @GameTest(maxTicks = 100)
    public void thePadHoldsUntilTheBroodIsDead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        chamber(helper);
        InstanceRecord record = chamberRecord(helper, 9949);
        BlockPos terminal = record.layout.terminal();
        List<BlockPos> cages = CapstoneFights.spawnerPositions(level, terminal);
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(GameType.SURVIVAL);
        member.teleportTo(level, terminal.getX() + 8.5, terminal.getY() + 1.0, terminal.getZ() + 8.5,
                Set.of(), 0.0f, 0.0f, false);
        try {
            CapstoneFights.State state = CapstoneFights.state(record);
            List<ServerPlayer> members = List.of(member);
            tick(helper, record, state, members);
            for (BlockPos cage : cages) {
                level.destroyBlock(cage, false);
            }
            tick(helper, record, state, members);
            BroodPhases.Phases phases = BroodPhases.phasesOf(record.slot);
            helper.assertTrue(state.waveSpawned, "the last cage looses the wave");
            helper.assertTrue(phases.mother != null, "the mother arrives with it");
            helper.assertTrue(phases.bar != null, "she has a boss bar");
            List<Mob> standing = brood(level, terminal);
            Mob mother = null;
            for (Mob mob : standing) {
                if (mob.getUUID().equals(phases.mother)) {
                    mother = mob;
                }
            }
            helper.assertTrue(mother != null, "the mother is among the tagged brood");
            helper.assertTrue(standing.size() > 1, "her retinue came with her");
            helper.assertTrue(CapstoneFights.broodPadRefusal(level, record, members) != null,
                    "the pad is shut while the brood stands");

            // Her half health beat: the windup, then the pack grows and glows.
            mother.setHealth(mother.getMaxHealth() * 0.4f);
            tick(helper, record, state, members);
            helper.assertTrue(phases.beat == KennelsFinaleRules.Phase.WINDUP,
                    "she starts the windup at half health");
            phases.windupEnd = server.getTickCount();
            tick(helper, record, state, members);
            helper.assertTrue(phases.beat == KennelsFinaleRules.Phase.EMPOWERED,
                    "the pack grows when the windup ends");
            helper.assertTrue(!phases.originals.isEmpty(), "the empowered pack is remembered");

            // Everything down: the bar lifts and the pad opens.
            for (Mob mob : brood(level, terminal)) {
                mob.discard();
            }
            tick(helper, record, state, members);
            helper.assertTrue(phases.bar == null, "her bar goes with her");
            helper.assertTrue(CapstoneFights.broodPadRefusal(level, record, members) == null,
                    "the pad opens once the mother and every brood mob are dead");
            helper.succeed();
        } finally {
            member.discard();
            CapstoneFights.teardown(level, record.slot, record.layout);
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
