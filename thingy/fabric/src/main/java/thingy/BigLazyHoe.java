package thingy;

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
import thingy.api.VirtualTag;

/**
 * Right-click a crop: bonemeal a 3×3 patch around it (same y-level), skipping any
 * crop that is already at max age.
 */
public final class BigLazyHoe {

    public static final String ID = "big_lazy_hoe";

    private BigLazyHoe() {}

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
        if (!VirtualTag.is(held, "wondrous", ID)) {
            return InteractionResult.PASS;
        }

        int affected = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos target = pos.offset(dx, 0, dz);
                BlockState state = level.getBlockState(target);

                if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                    continue;
                }

                if (state.getBlock() instanceof BonemealableBlock bonemeal
                        && bonemeal.isValidBonemealTarget(level, target, state)) {
                    bonemeal.performBonemeal(level, player.getRandom(), target, state);
                    affected++;
                }
            }
        }

        if (affected > 0) {
            Chime.say(player, "grew a patch");
            Chime.confirm(player);
        } else {
            Chime.say(player, "nothing grew");
            Chime.reject(player);
        }

        return InteractionResult.SUCCESS_SERVER;
    }
}
