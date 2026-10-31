package excellentenchants;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Silk Chest, Silk Spawner, Tunnel and Telekinesis: everything that changes how a block breaks. */
public final class BlockDrops {

    /** Stops Tunnel's extra breaks from tunnelling again. */
    private static boolean tunnelling;

    private BlockDrops() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) return true;
            if (sp.isCreative()) return true;
            ItemStack tool = sp.getMainHandItem();
            if (state.getBlock() instanceof ChestBlock && Enchants.level(server, tool, Enchants.SILK_CHEST) > 0 && be != null) {
                silkChest(server, sp, pos, state, be, tool);
                return false;
            }
            int spawner = Enchants.level(server, tool, Enchants.SILK_SPAWNER);
            if (state.getBlock() instanceof SpawnerBlock && spawner > 0 && be != null
                    && OnHit.roll(server, Numbers.silkSpawnerChance(spawner), -1)) {
                silkSpawner(server, sp, pos, be, tool);
                return false;
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) return;
            ItemStack tool = sp.getMainHandItem();
            if (Enchants.level(server, tool, Enchants.TELEKINESIS) > 0) telekinesis(server, sp, pos);
            int tunnel = Enchants.level(server, tool, Enchants.TUNNEL);
            if (tunnel > 0 && !tunnelling && !sp.isShiftKeyDown()) tunnel(server, sp, pos, state, tunnel);
        });
    }

    /** The chest comes out as one item with everything still inside it. */
    public static void silkChest(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, BlockEntity be, ItemStack tool) {
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        stack.applyComponents(be.collectComponents());
        if (be instanceof Clearable clearable) clearable.clearContent();
        level.removeBlock(pos, false);
        Block.popResource(level, pos, stack);
        tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
    }

    /** The spawner comes out as an item that remembers what it spawns. */
    public static void silkSpawner(ServerLevel level, ServerPlayer player, BlockPos pos, BlockEntity be, ItemStack tool) {
        ItemStack stack = new ItemStack(Items.SPAWNER);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(be.getType(), be.saveWithoutMetadata(level.registryAccess())));
        level.removeBlock(pos, false);
        Block.popResource(level, pos, stack);
        tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
    }

    /** Moves the loot this break just spawned into the player's inventory, when it fits. */
    public static void telekinesis(ServerLevel level, ServerPlayer player, BlockPos pos) {
        AABB box = new AABB(pos).inflate(1.0);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.tickCount == 0)) {
            ItemStack stack = drop.getItem().copy();
            player.getInventory().add(stack);
            if (stack.isEmpty()) drop.discard();
            else drop.setItem(stack);
        }
    }

    /** Breaks the blocks around the one mined, in the shape the level gives. */
    public static void tunnel(ServerLevel level, ServerPlayer player, BlockPos mined, BlockState minedState, int enchantLevel) {
        HitResult hit = player.pick(10.0, 0.0f, false);
        Direction face = hit instanceof BlockHitResult block ? block.getDirection() : player.getDirection().getOpposite();
        tunnelAround(level, player, mined, minedState, face, enchantLevel);
    }

    static final int[][] OFFSETS = {{0, 0}, {0, -1}, {-1, 0}, {0, 1}, {1, 0}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    public static int tunnelAround(ServerLevel level, ServerPlayer player, BlockPos mined, BlockState minedState, Direction face, int enchantLevel) {
        int count = Numbers.tunnelBlocks(enchantLevel);
        boolean vertical = face == Direction.UP || face == Direction.DOWN;
        boolean alongZ = face == Direction.EAST || face == Direction.WEST;
        int broken = 0;
        tunnelling = true;
        try {
            for (int i = 1; i < count; i++) {
                if (player.getMainHandItem().isEmpty()) break;
                int a = OFFSETS[i][0];
                int b = OFFSETS[i][1];
                BlockPos target = vertical ? mined.offset(a, 0, b) : mined.offset(alongZ ? 0 : a, b, alongZ ? a : 0);
                if (!tunnelable(level, target, minedState, player.getMainHandItem())) continue;
                if (player.gameMode.destroyBlock(target)) broken++;
            }
        } finally {
            tunnelling = false;
        }
        return broken;
    }

    public static boolean tunnelable(ServerLevel level, BlockPos pos, BlockState minedState, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false;
        if (state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME)) return false;
        if (state.is(Blocks.OBSIDIAN) && !minedState.is(Blocks.OBSIDIAN)) return false;
        if (state.hasBlockEntity()) return false;
        return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
    }
}
