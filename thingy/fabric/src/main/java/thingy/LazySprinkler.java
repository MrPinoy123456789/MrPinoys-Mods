package thingy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import thingy.api.VirtualTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An oxidized copper grate that remembers its position and a hopper's worth of
 * bone meal. Placed from a tagged {@code lazy_sprinkler} item, it fertilizes 3
 * random growable blocks every {@link #TICKS_BETWEEN} ticks, scanning an 11x11
 * area on its own y-level only: it waters what's planted flush with it, not
 * whatever happens to be stacked above or below.
 */
public final class LazySprinkler {

    public static final String ID = "lazy_sprinkler";
    private static final int TICKS_BETWEEN = 60;
    private static final int RADIUS = 5; // 11x11
    private static final int TARGETS_PER_PULSE = 3;

    /** Oxidized weathering stage of the copper grate family; no dedicated Blocks field. */
    private static final Block GRATE = Blocks.COPPER_GRATE.weathering().oxidized();

    /** A right-click that may or may not turn into a placed sprinkler. */
    private record Placement(ResourceKey<Level> dimension, BlockPos pos, UUID playerId,
                             InteractionHand hand, ItemStack stack, int count, boolean creative) {}

    private final ItemRegistry registry;
    private final List<Placement> pending = new ArrayList<>();

    public LazySprinkler(ItemRegistry registry) {
        this.registry = registry;
    }

    public void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            // Sneaking skips the sprinkler's own interaction so a block can be
            // placed against it, same as any other vanilla container.
            if (serverPlayer.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }

            ItemStack held = serverPlayer.getItemInHand(hand);
            BlockPos pos = hitResult.getBlockPos();
            ServerLevel serverLevel = (ServerLevel) level;
            WondrousState state = WondrousState.forLevel(serverLevel);

            if (level.getBlockState(pos).is(GRATE)
                    && state.hasSprinkler(serverLevel, pos)) {
                serverPlayer.openMenu(createMenu(serverLevel, pos));
                return InteractionResult.SUCCESS_SERVER;
            }

            if (VirtualTag.is(held, "wondrous", ID)) {
                BlockPlaceContext context = new BlockPlaceContext(serverPlayer, hand, held, hitResult);
                BlockPos target = context.getClickedPos();
                if (context.canPlace() && !level.getBlockState(target).is(GRATE)) {
                    pending.add(new Placement(serverLevel.dimension(), target, serverPlayer.getUUID(), hand,
                            held.copy(), held.getCount(), serverPlayer.isCreative()));
                }
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            confirmPending(server);
            if (server.getTickCount() % TICKS_BETWEEN == 0) {
                pulse(server);
            }
        });

        // BEFORE, not AFTER: the break is cancelled and redone by hand, otherwise
        // vanilla also drops a plain oxidized_copper_grate alongside the tagged item.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, blockState, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel) || !blockState.is(GRATE)) {
                return true;
            }
            WondrousState state = WondrousState.forLevel(serverLevel);
            if (!state.hasSprinkler(serverLevel, pos)) {
                return true;
            }

            List<ItemStack> ammo = SprinklerMenu.isOpen(serverLevel, pos)
                    ? List.of()
                    : List.copyOf(state.getSprinkler(serverLevel, pos));
            state.removeSprinkler(serverLevel, pos);
            serverLevel.removeBlock(pos, false);

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;

            for (ItemStack stack : ammo) {
                if (!stack.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(level, x, y, z, stack.copy()));
                }
            }
            if (!player.isCreative()) {
                ItemStack sprinkler = registry.byId(ID).orElseThrow().createStack(1);
                level.addFreshEntity(new ItemEntity(level, x, y, z, sprinkler));
            }

            return false;
        });
    }

    static List<ItemStack> emptyAmmo() {
        return Collections.nCopies(9, ItemStack.EMPTY);
    }

    private void confirmPending(MinecraftServer server) {
        if (pending.isEmpty()) {
            return;
        }
        WondrousState state = WondrousState.forServer(server);
        for (Placement placement : pending) {
            ServerLevel level = server.getLevel(placement.dimension());
            if (level == null || !level.hasChunkAt(placement.pos())) {
                continue;
            }
            if (!level.getBlockState(placement.pos()).is(GRATE)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(placement.playerId());
            if (player == null || !placementVerified(player, placement)) {
                continue;
            }
            if (!state.hasSprinkler(level, placement.pos())) {
                state.setSprinkler(level, placement.pos(), emptyAmmo());
            }
        }
        pending.clear();
    }

    private static boolean placementVerified(ServerPlayer player, Placement placement) {
        ItemStack current = player.getItemInHand(placement.hand());
        if (placement.creative()) {
            return current.getCount() == placement.count()
                    && ItemStack.isSameItemSameComponents(current, placement.stack());
        }
        int expected = placement.count() - 1;
        return expected == 0 ? current.isEmpty() : current.getCount() == expected
                && ItemStack.isSameItemSameComponents(current, placement.stack());
    }

    private void pulse(MinecraftServer server) {
        WondrousState state = WondrousState.forServer(server);
        for (var entry : List.copyOf(state.allSprinklers().entrySet())) {
            WondrousState.PosKey key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.hasChunkAt(key.pos())) {
                continue;
            }
            // An open menu owns the live contents; the saved copy is stale until close.
            if (SprinklerMenu.isOpen(level, key.pos())) {
                continue;
            }
            fertilize(state, level, key.pos());
        }
    }

    private void fertilize(WondrousState state, ServerLevel level, BlockPos centre) {
        List<ItemStack> ammo = new ArrayList<>(state.getSprinkler(level, centre));
        if (ammo.isEmpty()) {
            return;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                BlockPos target = centre.offset(dx, 0, dz);
                if (target.equals(centre)) {
                    continue;
                }
                BlockState blockState = level.getBlockState(target);
                if (blockState.getBlock() instanceof CropBlock crop && crop.isMaxAge(blockState)) {
                    continue;
                }
                if (blockState.getBlock() instanceof BonemealableBlock bonemeal
                        && bonemeal.isValidBonemealTarget(level, target, blockState)) {
                    candidates.add(target);
                }
            }
        }

        boolean changed = false;
        for (int i = 0; i < TARGETS_PER_PULSE && !candidates.isEmpty(); i++) {
            if (!consumeOneBoneMeal(ammo)) {
                break;
            }
            BlockPos target = candidates.remove(level.getRandom().nextInt(candidates.size()));
            BlockState blockState = level.getBlockState(target);
            ((BonemealableBlock) blockState.getBlock())
                    .performBonemeal(level, level.getRandom(), target, blockState);
            changed = true;
        }

        if (changed) {
            state.setSprinkler(level, centre, ammo);
        }
    }

    private static boolean consumeOneBoneMeal(List<ItemStack> ammo) {
        for (int i = 0; i < ammo.size(); i++) {
            ItemStack stack = ammo.get(i);
            if (!stack.isEmpty() && stack.is(Items.BONE_MEAL)) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    ammo.set(i, ItemStack.EMPTY);
                }
                return true;
            }
        }
        return false;
    }

    private MenuProvider createMenu(ServerLevel level, BlockPos pos) {
        return new SimpleMenuProvider(
                (id, inv, player) -> new SprinklerMenu(id, inv, ContainerLevelAccess.create(level, pos)),
                Component.translatable("container.hopper"));
    }
}
