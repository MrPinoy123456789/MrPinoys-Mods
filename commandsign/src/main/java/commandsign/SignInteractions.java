package commandsign;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-clicks on signs.
 *
 * <p>Two paths, decided by who is clicking and how:
 * <ul>
 *   <li>Op + shift: open the setup dialog. Consumes the click so the vanilla
 *       sign editor does not also open.
 *   <li>Anyone else, or an op without shift, on a marked command sign: run the
 *       stored command as the server. Consumes the click so the vanilla sign
 *       editor never opens for a command sign.
 * </ul>
 *
 * <p>Unmarked signs pass straight through and behave normally. Shape after
 * {@code ballot.mc.Interactions}, which intercepts sign right-clicks the same
 * way.
 */
final class SignInteractions {

    private SignInteractions() {}

    static InteractionResult onUseBlock(ServerPlayer player, Level level,
                                        InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return InteractionResult.PASS;
        }

        boolean isCommandSign = ((CommandSignAccess) sign).commandsign$isCommandSign();
        boolean op = CommandSignMod.isOp(level.getServer(), player);

        // Op + shift: open the setup dialog, whether or not it is already a
        // command sign. Pre-fill with the current command so editing is easy.
        if (op && player.isShiftKeyDown()) {
            String dim = level.dimension().identifier().toString();
            String currentCommand = SignCommand.commandFrom(sign);
            boolean currentEnabled = isCommandSign;
            DialogKit.show(player, DialogScreens.setup(player, dim,
                    pos.getX(), pos.getY(), pos.getZ(), currentCommand, currentEnabled));
            return InteractionResult.SUCCESS_SERVER;
        }

        // A command sign, clicked without shift: run the command.
        if (isCommandSign) {
            SignCommand.run(sign, level, player);
            return InteractionResult.SUCCESS_SERVER;
        }

        return InteractionResult.PASS;
    }

    /** The callback registered in {@link CommandSignMod#onInitialize}. */
    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return onUseBlock(serverPlayer, level, hand, hit);
        });
    }
}
