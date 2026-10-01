package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The Ordeal framework (2026-09-30): a lever click resolves an Ordeal at once
 * and lights its lamp, the lever never goes back up, the lever and lamp are
 * protected fixtures, and a spawner Ordeal's lever shuts its spawner off.
 * The per-room danger ticks keep their own regressions in
 * {@link HandlerGameTest}.
 */
@SuppressWarnings("removal")
public final class OrdealGameTest {

    /** A pulled lever ends the Ordeal, lights the lamp, and stays down on a second pull. */
    @GameTest(maxTicks = 40)
    public void leverResolvesOnceAndStaysDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos wall = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos lever = wall.north();
        BlockPos lava = helper.absolutePos(new BlockPos(5, 1, 5));

        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        Ordeals.placeWallLever(level, lever, Direction.NORTH);
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
        Ordeals.arm(RisingLavaOrdeal.INSTANCE, level, origin);
        helper.assertTrue(Ordeals.isActive(RisingLavaOrdeal.INSTANCE, origin), "armed and unresolved");

        helper.assertTrue(Ordeals.onUse(player, lever), "the click on the lever was claimed");
        helper.assertTrue(level.getBlockState(lever).getValue(BlockStateProperties.POWERED), "the lever is down");
        helper.assertTrue(Ordeals.isResolved(origin), "the Ordeal resolved on the pull");
        helper.assertFalse(level.getBlockState(lava).is(Blocks.LAVA), "resolving drained the lava");

        helper.assertTrue(Ordeals.onUse(player, lever), "a second pull is claimed too");
        helper.assertTrue(level.getBlockState(lever).getValue(BlockStateProperties.POWERED),
                "and the lever stays down");

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(level.getBlockState(lever.above()).getValue(RedstoneLampBlock.LIT),
                    "the lamp above the lever is lit");
            Ordeals.clear(origin);
            server(helper).getPlayerList().remove(player);
            helper.succeed();
        });
    }

    /** The lever and its lamp are fixtures while armed; nothing else is, and nothing is after teardown. */
    @GameTest
    public void leverAndLampAreFixtures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos wall = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos lever = wall.north();

        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        Ordeals.placeWallLever(level, lever, Direction.NORTH);
        Ordeals.arm(RisingLavaOrdeal.INSTANCE, level, origin);

        helper.assertTrue(Ordeals.isFixture(lever), "the lever is protected");
        helper.assertTrue(Ordeals.isFixture(lever.above()), "the lamp is protected");
        helper.assertFalse(Ordeals.isFixture(wall), "the wall it hangs on is not an Ordeal fixture");
        Ordeals.clear(origin);
        helper.assertFalse(Ordeals.isFixture(lever), "after teardown the lever is ordinary again");
        helper.succeed();
    }

    /** The thicket's lever shuts its spawner off: its required player range drops to 0. */
    @GameTest
    public void spawnerLeverDousesTheSpawner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos spawner = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos lever = spawner.east();

        level.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), 3);
        Ordeals.placeWallLever(level, lever, Direction.EAST);
        Ordeals.arm(SpawnerOrdeal.THICKET, level, origin);
        helper.assertTrue(Ordeals.isActive(SpawnerOrdeal.THICKET, origin), "the thicket armed");
        helper.assertValueEqual(requiredRange(level, spawner), 12, "a live spawner wakes at 12 blocks");

        Ordeals.onUse(player, lever);
        helper.assertValueEqual(requiredRange(level, spawner), 0, "the doused spawner never wakes");
        helper.assertTrue(Ordeals.isResolved(origin), "and the Ordeal is resolved");

        Ordeals.clear(origin);
        server(helper).getPlayerList().remove(player);
        helper.succeed();
    }

    private static int requiredRange(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
            return -1;
        }
        return spawner.saveWithoutMetadata(level.registryAccess()).getIntOr("RequiredPlayerRange", -1);
    }

    private static net.minecraft.server.MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }
}
