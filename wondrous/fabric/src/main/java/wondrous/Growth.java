package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * growy_can: active right-click that consumes one bone meal and grows a 5x5x3
 * patch around the clicked block.
 * lazy_sprinkler: an Aura pass that grows one random crop every 60 ticks while
 * the item is anywhere in the inventory.
 */
public final class Growth {

    public static final String GROWY_CAN_ID = "growy_can";
    public static final String LAZY_SPRINKLER_ID = "lazy_sprinkler";
    private static final int CAN_COOLDOWN = 4;
    private static final int SPRINKLER_INTERVAL = 60;

    private static final Map<UUID, Cache> sprinklerCache = new HashMap<>();

    private Growth() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryCan(serverPlayer, hand, hitResult.getBlockPos(), (ServerLevel) level);
        });

        Aura.add((player, carried) -> {
            if (!carried.anywhere(LAZY_SPRINKLER_ID)) {
                sprinklerCache.remove(player.getUUID());
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            MinecraftServer server = level.getServer();
            if (server == null || server.getTickCount() % SPRINKLER_INTERVAL != 0) {
                return;
            }
            passSprinkler(player, level);
        });
    }

    private static InteractionResult tryCan(ServerPlayer player, InteractionHand hand,
                                            BlockPos pos, ServerLevel level) {
        ItemStack held = player.getItemInHand(hand);
        if (!WondrousTag.is(held, GROWY_CAN_ID)) {
            return InteractionResult.PASS;
        }

        if (player.getCooldowns().isOnCooldown(held)) {
            return InteractionResult.SUCCESS_SERVER;
        }

        int boneMealSlot = findBoneMeal(player);
        if (boneMealSlot < 0) {
            Chime.say(player, "need bone meal");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        ItemStack bone = player.getInventory().getItem(boneMealSlot);
        bone.shrink(1);
        player.getInventory().setChanged();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos target = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(target);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        continue;
                    }
                    if (state.getBlock() instanceof BonemealableBlock bonemeal
                            && bonemeal.isValidBonemealTarget(level, target, state)) {
                        bonemeal.performBonemeal(level, player.getRandom(), target, state);
                    }
                }
            }
        }

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                8, 1.5, 1.5, 1.5, 0.0);

        player.getCooldowns().addCooldown(held, CAN_COOLDOWN);
        Chime.say(player, "green fingers");
        Chime.confirm(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static int findBoneMeal(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.BONE_MEAL) && !WondrousTag.read(stack).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private static void passSprinkler(ServerPlayer player, ServerLevel level) {
        UUID uuid = player.getUUID();
        BlockPos here = player.blockPosition();

        Cache cache = sprinklerCache.get(uuid);
        if (cache != null && cache.pos.equals(here) && !cache.hadTarget) {
            return;
        }

        int boneMealSlot = findBoneMeal(player);
        if (boneMealSlot < 0) {
            return;
        }

        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos target = here.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(target);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        continue;
                    }
                    if (state.getBlock() instanceof BonemealableBlock bonemeal
                            && bonemeal.isValidBonemealTarget(level, target, state)) {
                        targets.add(target);
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            sprinklerCache.put(uuid, new Cache(here, false));
            return;
        }

        BlockPos target = targets.get(player.getRandom().nextInt(targets.size()));
        BlockState state = level.getBlockState(target);
        ((BonemealableBlock) state.getBlock()).performBonemeal(level, player.getRandom(), target, state);

        ItemStack bone = player.getInventory().getItem(boneMealSlot);
        bone.shrink(1);
        player.getInventory().setChanged();

        sprinklerCache.put(uuid, new Cache(here, true));
    }

    private record Cache(BlockPos pos, boolean hadTarget) {}
}
