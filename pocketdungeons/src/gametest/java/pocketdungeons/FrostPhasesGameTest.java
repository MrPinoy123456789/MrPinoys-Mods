package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * TODO: one or two lines saying what this class pins (the PD number or the roadmap task, and the rule).
 */
public final class FrostPhasesGameTest {

    @GameTest
    public void todoNameThisAfterTheRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The cell origin to build on. Clear what you place; a far location needs setChunkForced and a finally.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));

        // TODO: set up, call the code under test, then assert with a message that names the rule:
        // helper.assertTrue(condition, "what must hold");
        // helper.assertValueEqual(actual, expected, "what is compared");
        // A mock player: @SuppressWarnings("removal") ServerPlayer p = helper.makeMockServerPlayerInLevel();
        // Slow code: @GameTest(maxTicks = 200) and helper.runAfterDelay(ticks, () -> { ...; helper.succeed(); });

        helper.succeed();
    }
}
