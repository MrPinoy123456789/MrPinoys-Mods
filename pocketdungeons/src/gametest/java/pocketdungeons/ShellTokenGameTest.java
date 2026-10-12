package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * PD-206: a shell token claims itself on right-click, so the player never has
 * to find the room menu's Unlock button. The token is consumed on the first
 * claim and refused, not eaten, on a repeat.
 */
public final class ShellTokenGameTest {

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    @GameTest(maxTicks = 20)
    public void shellTokenClaimsOnUse(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        DungeonLog log = DungeonLog.forServer(player.level().getServer());

        // An ordinary item is not a token and passes straight through.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 1));
        helper.assertTrue(ShellTokenListener.claim(player) == InteractionResult.PASS,
                "a plain item is not a shell token");

        player.setItemInHand(InteractionHand.MAIN_HAND, ShellTokenListener.token("sandstone"));
        helper.assertTrue(ShellTokenListener.claim(player) == InteractionResult.SUCCESS_SERVER,
                "right-clicking the token claims it");
        helper.assertTrue(log.get(player.getUUID()).unlockedShells().contains("sandstone"),
                "the palette joined the player's permanent set");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the token was consumed");

        // A second claim of an owned shell refuses and keeps the token.
        player.setItemInHand(InteractionHand.MAIN_HAND, ShellTokenListener.token("sandstone"));
        helper.assertTrue(ShellTokenListener.claim(player) == InteractionResult.SUCCESS_SERVER,
                "a repeat claim still answers the click");
        helper.assertTrue(player.getMainHandItem().getCount() == 1,
                "an already-unlocked token is not eaten");
        helper.succeed();
    }
}
