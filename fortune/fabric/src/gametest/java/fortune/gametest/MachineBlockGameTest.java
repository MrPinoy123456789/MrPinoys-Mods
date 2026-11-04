package fortune.gametest;

import fortune.mc.FortuneService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A Fortune Machine block: a right-click opens the machine through the real use block event, both hands of the
 * click are answered, and a removed machine is a plain block again. Machines are shared by every test, so this one
 * removes its own before it ends.
 */
public final class MachineBlockGameTest {

    @GameTest
    public void rightClickingAMachineOpensIt(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(at, Blocks.GOLD_BLOCK.defaultBlockState());
        helper.assertTrue(service.addMachine(helper.getLevel(), at), "The block could not be made a machine");
        helper.assertTrue(!service.addMachine(helper.getLevel(), at), "The same block was registered twice");

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false);
        int before = player.containerMenu.containerId;
        // The game mode's use item on path a client's click takes (every mixin and the whole use block event), then
        // the off hand's half of the same click, as Charter's terminal test does.
        InteractionResult main = player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        InteractionResult off = UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.OFF_HAND, hit);
        helper.assertTrue(main == InteractionResult.SUCCESS_SERVER, "The main hand click was not answered: " + main);
        helper.assertTrue(off == InteractionResult.SUCCESS_SERVER, "The off hand click was not answered: " + off);
        helper.assertTrue(player.containerMenu.containerId != before, "The machine did not open");
        player.closeContainer();

        helper.assertTrue(service.removeMachine(helper.getLevel(), at), "The machine could not be removed");
        InteractionResult plain = UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(plain != InteractionResult.SUCCESS_SERVER, "A removed machine still answered");
        helper.succeed();
    }
}
