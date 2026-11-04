package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The operator screens (2026-11-04): the wild overview, the list with its filters, the info card, the offerings and
 * the help all render from the console without error and count what they show; the ruin rebuild stamps the current
 * ruin and records it.
 */
public final class WildAdminGameTest {

    @GameTest(maxTicks = 200)
    public void theAdminScreensRender(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WildDungeons.loadFrom(server);
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        long now = WildClock.now();
        WildState state = new WildState();
        WildState.Lodestone ready = new WildState.Lodestone("w1", WildLodestones.dimensionKey(level), at.getX(),
                at.getY(), at.getZ(), "pocketdungeons:working_face", 3, now, now + 86400L);
        WildState.Lodestone cooling = new WildState.Lodestone("w2", WildLodestones.dimensionKey(level), at.getX() + 400,
                at.getY(), at.getZ(), "pocketdungeons:boiler_hall", 5, now, now + 86400L);
        cooling.markCleared(now, 3600L);
        cooling.recordClear("Aurelia", 0xFF6A1A, now);
        ready.ominous = true;
        state.add(ready);
        state.add(cooling);
        WildStore.setForTesting(state);
        WildMode.setActiveForTesting(true);
        try {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            helper.assertValueEqual(WildCommands.overview(source), 2, "the overview counts both");
            helper.assertValueEqual(WildCommands.list(source, AdminText.Filter.ALL, 1), 2, "the list shows both");
            helper.assertValueEqual(WildCommands.list(source, AdminText.Filter.OMINOUS, 9), 1, "one is ominous");
            helper.assertValueEqual(WildCommands.list(source, AdminText.Filter.COOLING, 1), 1, "one is cooling");
            helper.assertValueEqual(WildCommands.info(source, "w2"), 1, "the info card renders");
            helper.assertValueEqual(WildCommands.info(source, "nope"), 0, "an unknown id is refused");
            helper.assertTrue(WildCommands.offerings(source) >= 4, "the offerings list");
            helper.assertValueEqual(AdminUi.help(source, null), 1, "the help renders");
            helper.assertValueEqual(AdminUi.help(source, "wild"), 1, "one section renders");
            helper.assertValueEqual(AdminUi.help(source, "bogus"), 0, "an unknown section is refused");

            helper.assertTrue(ready.ruin == 1, "a lodestone from an old file has the old ruin");
            helper.assertValueEqual(WildCommands.rebuild(source, "w1", true), 1, "rebuild runs");
            helper.assertTrue(ready.ruin == WildRuin.VERSION, "rebuild records the current ruin");
            helper.assertTrue(level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.BEDROCK),
                    "the marker stands again after a rebuild");
        } finally {
            WildMode.setActiveForTesting(null);
            WildStore.setForTesting(null);
            WildLodestones.rubble(level, at, WildRuin.VERSION);
        }
        helper.succeed();
    }
}
