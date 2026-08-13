package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

/**
 * Places a small water-like {@code Display.BlockDisplay} that bonemeals a 5x5
 * area once every 30 seconds for 5 minutes.
 */
public final class LazySprinkler {

    public static final String ID = "lazy_sprinkler";
    private static final int TICKS_BETWEEN = 600; // 30 seconds
    private static final int LIFETIME = 6000;     // 5 minutes

    private LazySprinkler() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryUse(serverPlayer, hand, hitResult.getBlockPos(),
                    hitResult.getDirection(), (ServerLevel) level);
        });
    }

    private static InteractionResult tryUse(ServerPlayer player, InteractionHand hand,
                                            BlockPos pos, Direction side, ServerLevel level) {
        ItemStack held = player.getItemInHand(hand);
        if (!WondrousTag.is(held, ID)) {
            return InteractionResult.PASS;
        }

        if (side != Direction.UP) {
            Chime.say(player, "place it on top");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        BlockPos above = pos.above();
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "block_display"));
        SprinklerDisplay sprinkler = new SprinklerDisplay(type, level);
        sprinkler.setPos(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
        sprinkler.setBlockState(Blocks.WATER.defaultBlockState());
        level.addFreshEntity(sprinkler);

        Chime.say(player, "sprinkler placed");
        Chime.confirm(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** A self-ticking block display that bonemeals nearby crops. */
    public static class SprinklerDisplay extends Display.BlockDisplay {

        private int age = 0;

        public SprinklerDisplay(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        public void tick() {
            super.tick();
            age++;
            if (age > LIFETIME) {
                this.discard();
                return;
            }
            if (age % TICKS_BETWEEN != 0) {
                return;
            }
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            BlockPos centre = this.blockPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos target = centre.offset(dx, 0, dz);
                    BlockState state = serverLevel.getBlockState(target);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        continue;
                    }
                    if (state.getBlock() instanceof BonemealableBlock bonemeal
                            && bonemeal.isValidBonemealTarget(serverLevel, target, state)) {
                        bonemeal.performBonemeal(serverLevel, this.getRandom(), target, state);
                    }
                }
            }
        }
    }
}
