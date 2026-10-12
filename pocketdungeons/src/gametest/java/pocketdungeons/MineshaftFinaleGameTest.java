package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T20: the Mineshaft dark phase, driven through {@link FinaleWave#step} as the finale-script skill
 * describes. Half the wave dead starts a tell; only after the tell do the torches and lanterns go out and the
 * glowing cave spiders join; the pad stays shut for them; lights near the pad and a redstone lamp are spared;
 * everything comes back on a win or when the floor ends, without clobbering a block a player placed.
 */
public final class MineshaftFinaleGameTest {

    private static final String ID = "pocketdungeons:test_mineshaft_finale";
    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:cow", 4)), 50, null);
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    @GameTest(maxTicks = 600)
    public void tellThenDarkThenSpidersThenLightsReturn(GameTestHelper helper) {
        Run run = new Run(helper, 9981);
        BlockPos far = run.origin.offset(1, 2, 1);
        BlockPos lantern = run.origin.offset(13, 3, 3);
        BlockPos nearPad = run.origin.offset(10, 2, 9);
        BlockPos lamp = run.origin.offset(3, 2, 12);
        run.level.setBlock(far, Blocks.TORCH.defaultBlockState(), FLAGS);
        run.level.setBlock(lantern, Blocks.LANTERN.defaultBlockState(), FLAGS);
        run.level.setBlock(nearPad, Blocks.TORCH.defaultBlockState(), FLAGS);
        run.level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), FLAGS);
        long tell = FinaleRules.darkTellTicks(PocketDungeonsConfig.mineshaftDarkTellSeconds());
        try {
            run.step(true);
            run.state().spawnTick = 0;
            run.step(false);
            helper.assertValueEqual(run.state().mobs.size(), 4, "the data wave stands");
            run.kill(0);
            run.kill(1);
            run.step(false);
            helper.assertTrue(run.state().halfFired, "half the wave is dead");
            helper.assertTrue(run.level.getBlockState(far).is(Blocks.TORCH), "the tell comes before the dark");
            helper.assertValueEqual(run.state().mobs.size(), 4, "no spiders yet");
            helper.runAfterDelay(tell / 2, () -> {
                run.step(false);
                helper.assertTrue(run.level.getBlockState(far).is(Blocks.TORCH), "still lit halfway through the tell");
            });
            helper.runAfterDelay(tell + 5, () -> {
                run.step(false);
                helper.assertTrue(run.level.getBlockState(far).isAir(), "the far torch is out");
                helper.assertTrue(run.level.getBlockState(lantern).isAir(), "the lantern is out");
                helper.assertTrue(run.level.getBlockState(nearPad).is(Blocks.TORCH), "the light by the pad stays");
                helper.assertTrue(run.level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP), "a lamp is not a torch");
                int expected = FinaleRules.darkSpiders(PocketDungeonsConfig.mineshaftDarkSpiders(), 1,
                        FINALE.perMemberPercent(), 4);
                helper.assertValueEqual(run.state().mobs.size(), 4 + expected, "the spiders joined the wave");
                for (int i = 4; i < run.state().mobs.size(); i++) {
                    Entity spider = run.level.getEntity(run.state().mobs.get(i));
                    helper.assertTrue(spider != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(spider.getType()).getPath().equals("cave_spider")
                            && spider.isCurrentlyGlowing(), "a glowing cave spider");
                }
                // A torch a player places in the dark stays lit through the restore.
                BlockPos mine = run.origin.offset(5, 2, 5);
                run.level.setBlock(mine, Blocks.TORCH.defaultBlockState(), FLAGS);
                // And a block a player builds where a light was is not overwritten.
                run.level.setBlock(far, Blocks.COBBLESTONE.defaultBlockState(), FLAGS);
                run.step(false);
                helper.assertTrue(run.level.getBlockState(mine).is(Blocks.TORCH), "a player torch is left alone");
                helper.assertTrue(!run.state().done, "the spiders keep the pad shut");
                for (int i = 0; i < run.state().mobs.size(); i++) {
                    Entity e = run.level.getEntity(run.state().mobs.get(i));
                    if (e != null) {
                        e.discard();
                    }
                }
                run.step(false);
                helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the last death wins");
                helper.assertTrue(run.level.getBlockState(lantern).is(Blocks.LANTERN), "the lantern returns");
                helper.assertTrue(run.level.getBlockState(far).is(Blocks.COBBLESTONE), "a player block is not overwritten");
                helper.assertTrue(run.level.getBlockState(mine).is(Blocks.TORCH), "the player torch is still there");
                run.close();
                helper.succeed();
            });
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 600)
    public void aFloorThatEndsMidDarkGetsItsLightsBack(GameTestHelper helper) {
        Run run = new Run(helper, 9982);
        BlockPos torch = run.origin.offset(2, 2, 2);
        run.level.setBlock(torch, Blocks.SOUL_TORCH.defaultBlockState(), FLAGS);
        long tell = FinaleRules.darkTellTicks(PocketDungeonsConfig.mineshaftDarkTellSeconds());
        try {
            run.step(true);
            run.state().spawnTick = 0;
            run.step(false);
            run.kill(0);
            run.kill(1);
            run.step(false);
            helper.runAfterDelay(tell + 5, () -> {
                run.step(false);
                helper.assertTrue(run.level.getBlockState(torch).isAir(), "dark");
                FinaleWave.floorEnded(run.level, run.record);
                helper.assertTrue(run.level.getBlockState(torch).is(Blocks.SOUL_TORCH), "restored when the floor ends");
                run.close();
                helper.succeed();
            });
        } catch (RuntimeException | Error e) {
            run.close();
            throw e;
        }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final BlockPos origin;
        final int slot;

        Run(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.slot = slot;
            this.level = helper.getLevel();
            this.server = level.getServer();
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            this.origin = helper.absolutePos(BlockPos.ZERO);
            player.teleportTo(level, origin.getX() + 0.5, origin.getY() + 2, origin.getZ() + 0.5, Set.of(), 0.0F,
                    0.0F, false);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = ID;
            FinaleScripts.register(ID, new MineshaftFinale());
        }

        void step(boolean due) {
            FinaleWave.step(server, level, record, FINALE, List.of(member), due, "Test Room");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        void kill(int n) {
            Entity entity = level.getEntity(state().mobs.get(n));
            helper.assertTrue(entity != null, "mob " + n + " is in the world");
            entity.discard();
        }

        void close() {
            FinaleWave.teardown(level, slot);
            FinaleScripts.unregister(ID);
        }
    }
}
