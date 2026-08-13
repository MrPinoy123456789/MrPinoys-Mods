package wondrous;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

/**
 * Piggyback Glove: sneak-right-click a container to fold it into the glove,
 * then right-click again to place the block and restore its contents.
 */
public final class CarryGlove {

    public static final String ID = "carry_glove";
    private static final String BLOCK_KEY = "block";
    private static final String STATE_KEY = "block_state";
    private static final String STORED_KEY = "stored";

    public void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            ItemStack held = player.getItemInHand(hand);
            if (!WondrousTag.is(held, ID)) {
                return InteractionResult.PASS;
            }

            ServerLevel serverLevel = (ServerLevel) level;
            BlockPos pos = hitResult.getBlockPos();

            if (hasStored(held)) {
                return tryPlace(serverPlayer, hand, held, serverLevel, pos, hitResult.getDirection());
            }

            if (player.isShiftKeyDown()) {
                return tryPick(serverPlayer, held, serverLevel, pos);
            }

            return InteractionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount > 0.0f && entity instanceof ServerPlayer player) {
                dropCarry(player);
            }
            return true;
        });
    }

    private InteractionResult tryPick(ServerPlayer player, ItemStack held, ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container)) {
            return refuse(player, "not a container");
        }

        // A locked container is not yours to walk off with, and one that somebody
        // is standing in front of would vanish out from under their open screen.
        if (be instanceof BaseContainerBlockEntity container
                && (container.isLocked() || !container.canOpen(player))) {
            return refuse(player, "its locked");
        }
        if (be instanceof ChestBlockEntity && ChestBlockEntity.getOpenCount(level, pos) > 0) {
            return refuse(player, "someones in there");
        }

        if (isCarryingAnywhere(player)) {
            return refuse(player, "already carrying");
        }

        // The glove is a single item; picking up must not stamp one stored
        // container onto a stack of them, which would place it once per glove.
        if (held.getCount() > 1) {
            return refuse(player, "one glove at a time");
        }

        BlockState state = level.getBlockState(pos);
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        CompoundTag stateTag = NbtUtils.writeBlockState(state);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        WondrousState.forLevel(level).removeLinksAt(level, pos);

        CustomData.update(DataComponents.CUSTOM_DATA, held, t -> {
            t.putString(BLOCK_KEY, blockId);
            t.put(STATE_KEY, stateTag);
            t.put(STORED_KEY, tag);
        });

        Chime.say(player, "come here you");
        Chime.confirm(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private InteractionResult tryPlace(ServerPlayer player, InteractionHand hand, ItemStack held,
                                       ServerLevel level, BlockPos pos, Direction side) {
        BlockPos target = pos.relative(side);
        if (!level.getBlockState(target).isAir() && !level.getBlockState(target).canBeReplaced()) {
            return refuse(player, "no room");
        }

        CustomData data = held.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return InteractionResult.PASS;
        }
        CompoundTag tag = data.copyTag();
        String blockId = tag.getString(BLOCK_KEY).orElse("");
        CompoundTag stored = tag.getCompound(STORED_KEY).orElse(null);
        if (stored == null) {
            return InteractionResult.PASS;
        }

        // Every failure below has to leave the glove loaded. Putting down a block
        // we cannot resolve would delete whatever the player is carrying.
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            return refuse(player, "cant put that back");
        }
        Block block = BuiltInRegistries.BLOCK.get(id).map(net.minecraft.core.Holder::value).orElse(null);
        if (block == null || block == Blocks.AIR) {
            return refuse(player, "cant put that back");
        }

        // The saved state carries facing, chest type and waterlogging. A default
        // state can also be the wrong shape for the stored block entity, and
        // loadStatic returns null when it is -- which would drop the contents.
        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
        BlockState state = tag.getCompound(STATE_KEY)
                .map(t -> NbtUtils.readBlockState(blocks, t))
                .orElse(block.defaultBlockState());
        if (!state.is(block)) {
            state = block.defaultBlockState();
        }

        BlockEntity loaded = BlockEntity.loadStatic(target, state, stored, level.registryAccess());
        if (loaded == null) {
            return refuse(player, "cant put that back");
        }

        level.setBlock(target, state, 3);
        level.setBlockEntity(loaded);

        CustomData.update(DataComponents.CUSTOM_DATA, held, t -> {
            t.remove(BLOCK_KEY);
            t.remove(STATE_KEY);
            t.remove(STORED_KEY);
        });
        player.setItemInHand(hand, held);

        Chime.say(player, "there you go");
        Chime.confirm(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private InteractionResult refuse(ServerPlayer player, String reason) {
        Chime.say(player, reason);
        Chime.reject(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private boolean hasStored(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty() && data.copyTag().contains(STORED_KEY);
    }

    private boolean isCarryingAnywhere(ServerPlayer player) {
        if (WondrousTag.is(player.getMainHandItem(), ID) && hasStored(player.getMainHandItem())) {
            return true;
        }
        if (WondrousTag.is(player.getOffhandItem(), ID) && hasStored(player.getOffhandItem())) {
            return true;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (WondrousTag.is(stack, ID) && hasStored(stack)) {
                return true;
            }
        }
        return false;
    }

    private void dropCarry(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (WondrousTag.is(main, ID) && hasStored(main)) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            drop(player, main);
            return;
        }

        ItemStack off = player.getOffhandItem();
        if (WondrousTag.is(off, ID) && hasStored(off)) {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            drop(player, off);
            return;
        }

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (WondrousTag.is(stack, ID) && hasStored(stack)) {
                inv.setItem(i, ItemStack.EMPTY);
                drop(player, stack);
                return;
            }
        }
    }

    private void drop(ServerPlayer player, ItemStack stack) {
        player.level().addFreshEntity(new ItemEntity(
                player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack.copy()));
        player.getInventory().setChanged();
    }
}
