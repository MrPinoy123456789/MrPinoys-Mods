package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

/**
 * A single-target bonemeal can. Right-click a bonemealable block to grow it.
 */
public final class GrowyCan {

    public static final String ID = "growy_can";

    private GrowyCan() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryUse(serverPlayer, hand, hitResult.getBlockPos(), (ServerLevel) level);
        });
    }

    private static InteractionResult tryUse(ServerPlayer player, InteractionHand hand,
                                            BlockPos pos, ServerLevel level) {
        ItemStack held = player.getItemInHand(hand);
        if (!WondrousTag.is(held, ID)) {
            return InteractionResult.PASS;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
            Chime.say(player, "already grown");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (state.getBlock() instanceof BonemealableBlock bonemeal
                && bonemeal.isValidBonemealTarget(level, pos, state)) {
            bonemeal.performBonemeal(level, player.getRandom(), pos, state);
            Chime.say(player, "grew one");
            Chime.confirm(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        return InteractionResult.PASS;
    }
}
