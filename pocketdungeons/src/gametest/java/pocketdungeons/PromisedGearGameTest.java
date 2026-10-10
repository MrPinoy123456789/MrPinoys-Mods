package pocketdungeons;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * PD-197: a floor can promise a rolled piece of gear on its door, and the copper chest pays that same piece.
 * PD-196: vault keys settle when the haul banks, and a failed dungeon pays only its share.
 */
public final class PromisedGearGameTest {

    @GameTest(maxTicks = 40)
    public void theBoardAndTheChestAgreeOnThePromisedPiece(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        IntervalState trip = new IntervalState();
        ItemStack onBoard = PromisedGear.roll(level, owner, trip, "collapsed_landing", "gear:weapon:2");
        ItemStack inChest = PromisedGear.roll(level, owner, trip, "collapsed_landing", "gear:weapon:2");
        helper.assertTrue(!onBoard.isEmpty(), "a weapon tier 2 promise rolls a piece");
        helper.assertTrue(ItemStack.isSameItemSameComponents(onBoard, inChest),
                "the board and the chest roll the same piece for the same trip and floor: " + onBoard + " vs " + inChest);
        helper.assertTrue(PromisedGear.describe(onBoard).length() > 0, "the board has a name for it");
        helper.assertTrue(PromisedGear.isGear("gear:weapon:2") && !PromisedGear.isGear("minecraft:emerald"),
                "only gear entries are gear promises");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(maxTicks = 40)
    public void aFailedDungeonPaysItsShareOfTheKeys(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        player.getInventory().setItem(0, new ItemStack(Items.TRIAL_KEY, 2));
        player.getInventory().setItem(1, new ItemStack(Items.OMINOUS_TRIAL_KEY));
        int half = RunLifecycle.redeemKeys(player, 50);
        helper.assertValueEqual(half, 2, "5 emeralds of keys pay 2 at fifty percent");
        helper.assertValueEqual(RunLifecycle.redeemKeys(player, 100), 0, "the keys are gone after one settling");
        helper.succeed();
    }
}
