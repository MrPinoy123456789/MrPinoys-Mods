package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T24: the Infestation finale, driven through {@link FinaleWave#step} as {@code FinaleScriptGameTest}
 * does (the gametest world has no dungeon dimension). The giant silverfish stands in for the data wave, burrows
 * after its tell (invulnerable, out of reach), the adds phase plants bricks that burst into silverfish counted
 * toward the wave, she emerges again, and killing her ends the fight with the bricks and brood cleared. A floor
 * that ends mid way gives the blocks back. Time is skipped by moving the phase start back.
 */
public final class InfestationFinaleGameTest {

    private static final DungeonDef.Finale DATA = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:silverfish", 10),
                    new DungeonDef.FinaleMob("minecraft:cave_spider", 4)), 50, null);

    @GameTest
    public void theFinaleScriptIsRegistered(GameTestHelper helper) {
        helper.assertTrue(FinaleScripts.has(InfestationFinale.DUNGEON_ID), "the Infestation has its finale script");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void theQueenBurrowsPlantsBricksAndEmerges(GameTestHelper helper) {
        Arena arena = new Arena(helper, 9981);
        try {
            arena.step(true);
            arena.state().spawnTick = 0;
            arena.step(false);
            helper.assertValueEqual(arena.state().mobs.size(), 1, "the boss alone stands in for the data wave");
            Mob boss = arena.boss();
            helper.assertTrue(boss.getType() == net.minecraft.world.entity.EntityTypes.SILVERFISH, "a silverfish");
            helper.assertTrue(boss.getAttributeBaseValue(Attributes.SCALE) == PocketDungeonsConfig.infestationBossScale(),
                    "scaled by the knob");
            helper.assertTrue(arena.state().bar != null && arena.state().elite != null, "named, with a boss bar");
            helper.assertTrue(!boss.isInvulnerable() && !boss.isInvisible(), "she starts up and hittable");
            double surfaceY = boss.getY();

            // Surface, then the first tell: still hittable.
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.SURFACE, "fighting on the surface");
            arena.skip(PocketDungeonsConfig.infestationSurfaceSeconds() * 20L);
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.BURROW_TELL, "she rears up first");
            helper.assertTrue(!boss.isInvulnerable() && !boss.isInvisible(), "the tell leaves her hittable");

            // The burrow: gone, invulnerable, below the floor.
            arena.skip(InfestationRules.MIN_TELL_TICKS + PocketDungeonsConfig.infestationBurrowTellSeconds() * 20L);
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.ADDS_TELL, "burrowed, the floor cracks");
            helper.assertTrue(boss.isInvulnerable() && boss.isInvisible(), "burrowed: invulnerable and invisible");
            helper.assertTrue(boss.getY() < surfaceY - 2, "burrowed: under the floor");
            helper.assertTrue(arena.state().mobs.contains(boss.getUUID()) && FinaleWave.alive(arena.level, arena.state()) == 1,
                    "she still counts, so the pad stays shut");

            // The adds phase: bricks appear, one per interval.
            arena.skip(InfestationRules.MIN_TELL_TICKS + PocketDungeonsConfig.infestationAddsTellSeconds() * 20L);
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.ADDS, "the adds phase");
            int total = arena.run().total;
            helper.assertValueEqual(total, InfestationRules.bricksFor(1, PocketDungeonsConfig.infestationBricksPerPhase(),
                    PocketDungeonsConfig.infestationBricksPerExtraMember()), "bricks for a party of one");
            for (int i = 0; i < 40 && arena.run().planted < total; i++) {
                arena.run().nextPlant = 0;
                arena.step(false);
            }
            helper.assertValueEqual(arena.run().planted, total, "every brick of the phase is planted");
            helper.assertTrue(arena.run().field.active() > 0 && arena.run().field.active()
                    <= PocketDungeonsConfig.infestationArenaBrickCap(), "bricks grow, within the arena cap");
            helper.assertTrue(CreepingBricks.floorActive(arena.record.origin)
                    == arena.run().field.active(), "they count against the floor");

            // They ripen and burst; the silverfish join the wave.
            arena.run().field.tick(arena.server.getTickCount() + 100000L);
            arena.step(false);
            int adds = arena.state().mobs.size() - 1;
            helper.assertTrue(adds > 0 && adds <= PocketDungeonsConfig.infestationSilverfishCap(),
                    "silverfish came out, within the cap: " + adds);
            helper.assertValueEqual(FinaleWave.alive(arena.level, arena.state()), 1 + adds, "all of them count");
            helper.assertTrue(!arena.state().done, "the pad stays shut");

            // All bricks gone: the third tell, then she rises.
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.EMERGE_TELL,
                    "no bricks left ends the adds phase early");
            helper.assertTrue(boss.isInvulnerable(), "still underground during the tell");
            arena.skip(InfestationRules.MIN_TELL_TICKS + PocketDungeonsConfig.infestationEmergeTellSeconds() * 20L);
            arena.step(false);
            helper.assertTrue(arena.run().phase == InfestationRules.Phase.SURFACE, "back on the surface");
            helper.assertTrue(!boss.isInvulnerable() && !boss.isInvisible() && boss.getY() > surfaceY - 2,
                    "she is up, visible and killable again");

            // Killing her ends the fight and clears the brood.
            boss.discard();
            arena.step(false);
            helper.assertTrue(arena.state().done && arena.record.floor.finaleWon, "her death wins the finale");
            helper.assertValueEqual(FinaleWave.alive(arena.level, arena.state()), 0, "the brood goes with her");
        } finally {
            arena.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aFloorThatEndsMidBurrowGivesTheBlocksBack(GameTestHelper helper) {
        Arena arena = new Arena(helper, 9982);
        try {
            arena.step(true);
            arena.state().spawnTick = 0;
            arena.step(false);
            arena.step(false);
            arena.skip(100000L);
            arena.step(false); // burrow tell
            arena.skip(100000L);
            arena.step(false); // burrowed
            arena.skip(100000L);
            arena.step(false); // adds
            for (int i = 0; i < 3; i++) {
                arena.run().nextPlant = 0;
                arena.step(false);
            }
            CreepingBricks.Field field = arena.run().field;
            helper.assertTrue(field.active() > 0, "bricks stand");
            List<BlockPos> spots = List.copyOf(field.bricks.keySet());
            FinaleWave.floorEnded(arena.level, arena.record);
            for (BlockPos spot : spots) {
                helper.assertTrue(arena.level.getBlockState(spot).isAir(), "the floor ended: the block is back");
            }
            helper.assertValueEqual(field.active(), 0, "nothing is left tracked");
        } finally {
            arena.close();
        }
        helper.succeed();
    }

    /** One scripted finale on a hand built record: a stone floor for the arena cell and one mock member on it. */
    private static final class Arena {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final BlockPos origin;

        Arena(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.origin = helper.absolutePos(new BlockPos(0, 1, 0));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (int y = 1; y <= 6; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, origin.getX() + 3.5, origin.getY() + 1, origin.getZ() + 3.5, Set.of(), 0.0F,
                    0.0F, false);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = InfestationFinale.DUNGEON_ID;
        }

        void step(boolean due) {
            FinaleWave.step(server, level, record, DATA, List.of(member), due, "The Queen's Nest");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        InfestationFinale.Run run() {
            return (InfestationFinale.Run) state().scriptState.get(InfestationFinale.Run.class);
        }

        Mob boss() {
            Entity entity = level.getEntity(state().elite);
            helper.assertTrue(entity instanceof Mob, "the boss is in the world");
            return (Mob) entity;
        }

        /** Moves the phase start back, as if {@code ticks} had passed. */
        void skip(long ticks) {
            run().phaseStart -= ticks;
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            member.discard();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}
