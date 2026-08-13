package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

/**
 * Right-click a block to crush it into a coarser version. 1-second cooldown.
 */
public final class SmashyMortar {

    public static final String ID = "smashy_mortar";
    private static final int COOLDOWN_TICKS = 20;

    private SmashyMortar() {}

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

        BlockState next = crush(level.getBlockState(pos));
        if (next == null) {
            Chime.say(player, "wont smash");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        level.setBlockAndUpdate(pos, next);
        player.getCooldowns().addCooldown(held, COOLDOWN_TICKS);

        Chime.say(player, "crushed");
        Chime.crush(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static BlockState crush(BlockState state) {
        if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (state.is(Blocks.GRAVEL)) {
            return Blocks.SAND.defaultBlockState();
        }
        if (state.is(Blocks.SANDSTONE)) {
            return Blocks.SAND.defaultBlockState();
        }
        if (state.is(Blocks.RED_SANDSTONE)) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        return null;
    }
}
