package wondrous;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import wondrous.api.WondrousTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A crafting table that remembers its 3x3 grid. Placed from a tagged
 * {@code crafting_table} item and persists its contents in {@link WondrousState}.
 */
public final class CraftStation {

    public static final String ID = "crafting_station";
    public static final int COST = 32;

    /** A right-click that may or may not turn into a placed station. */
    private record Placement(ResourceKey<Level> dimension, BlockPos pos, UUID playerId,
                             InteractionHand hand, ItemStack stack, int count, boolean creative) {}

    private final ItemRegistry registry;
    private final List<Placement> pending = new ArrayList<>();

    public CraftStation(ItemRegistry registry) {
        this.registry = registry;
    }

    public void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            ItemStack held = serverPlayer.getItemInHand(hand);
            BlockPos pos = hitResult.getBlockPos();
            ServerLevel serverLevel = (ServerLevel) level;
            WondrousState state = WondrousState.forLevel(serverLevel);

            // Opening comes first: a player holding a station item and clicking a
            // station would otherwise get vanilla's throwaway crafting menu.
            if (!player.isShiftKeyDown()
                    && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && state.hasStation(serverLevel, pos)) {
                serverPlayer.openMenu(createMenu(serverLevel, pos));
                return InteractionResult.SUCCESS_SERVER;
            }

            // Placing: vanilla has not run yet and may refuse, so only note the
            // target and confirm next tick that a table actually appeared.
            if (WondrousTag.is(held, ID)) {
                BlockPlaceContext context = new BlockPlaceContext(serverPlayer, hand, held, hitResult);
                BlockPos target = context.getClickedPos();
                if (context.canPlace() && !level.getBlockState(target).is(Blocks.CRAFTING_TABLE)) {
                    pending.add(new Placement(serverLevel.dimension(), target, serverPlayer.getUUID(), hand,
                            held.copy(), held.getCount(), serverPlayer.isCreative()));
                }
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pending.isEmpty()) {
                return;
            }
            WondrousState state = WondrousState.forServer(server);
            for (Placement placement : pending) {
                ServerLevel level = server.getLevel(placement.dimension());
                if (level == null || !level.hasChunkAt(placement.pos())) {
                    continue;
                }
                if (!level.getBlockState(placement.pos()).is(Blocks.CRAFTING_TABLE)) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(placement.playerId());
                if (player == null || !placementVerified(player, placement)) {
                    continue;
                }
                if (!state.hasStation(level, placement.pos())) {
                    state.setStation(level, placement.pos(), emptyGrid());
                }
            }
            pending.clear();
        });

        // BEFORE, not AFTER: the break is cancelled and redone by hand, otherwise
        // vanilla also drops a plain crafting_table alongside the tagged item.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, blockState, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel) || !blockState.is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
            WondrousState state = WondrousState.forLevel(serverLevel);
            if (!state.hasStation(serverLevel, pos)) {
                return true;
            }

            // A menu that is open owns the live grid and will hand it back on
            // close; dropping the saved copy too would duplicate it.
            List<ItemStack> grid = StationMenu.isOpen(serverLevel, pos)
                    ? List.of()
                    : List.copyOf(state.getStation(serverLevel, pos));
            state.removeStation(serverLevel, pos);
            serverLevel.removeBlock(pos, false);

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;

            // The grid drops either way -- a creative break should not eat what a
            // player left in the slots.
            for (ItemStack stack : grid) {
                if (!stack.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(level, x, y, z, stack.copy()));
                }
            }
            if (!player.isCreative()) {
                ItemStack station = registry.byId(ID).orElseThrow().createStack(1);
                level.addFreshEntity(new ItemEntity(level, x, y, z, station));
            }

            return false;
        });
    }

    static List<ItemStack> emptyGrid() {
        return Collections.nCopies(9, ItemStack.EMPTY);
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

    private MenuProvider createMenu(ServerLevel level, BlockPos pos) {
        return new SimpleMenuProvider(
                (id, inv, player) -> new StationMenu(id, inv, ContainerLevelAccess.create(level, pos)),
                Component.translatable("container.crafting"));
    }
}
