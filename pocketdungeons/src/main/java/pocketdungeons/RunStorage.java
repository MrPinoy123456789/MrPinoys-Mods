package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run storage (playtest 2026-10-02-1: "a storage that stays in the staging room
 * to give players a bit of extra inventory space during runs"). Every staging
 * room is stamped with an ender chest set into its wall
 * ({@link RoomTemplateGenerator#placeRunStorage}); right-clicking it, or any
 * other ender chest in the dungeon, opens the player's own 27 slots, kept on the
 * run's {@link InstanceRecord}, so it feels vanilla while being gated behind
 * the scenes. (The waxed oxidized copper chest it swapped places with is now
 * the bag chest.)
 *
 * <p>Run scoped, never cross-dimension: the storage is part of the dungeon
 * pack, not of the survival inventory. When the run closes the contents go
 * into each member's dungeon pack record ({@link InventorySwap#keepForNextEntry}),
 * online or not, so nothing in it ever reaches survival. A max-omen death rolls
 * it back to the interval start with the pack itself. The block is only a
 * handle (the chest's own inventory is never opened), so breaking or losing it
 * loses nothing.
 */
final class RunStorage {

    static final int SLOTS = 27;

    private static final ConfiguredItem STORAGE_BLOCK_ITEM = new ConfiguredItem("storageBlock",
            PocketDungeonsConfig::storageBlock,
            "run storage will never open for anybody.");

    private RunStorage() {}

    /** Resolves the configured block once, so a typo is a boot-time log line. */
    static void warmUp() {
        STORAGE_BLOCK_ITEM.get();
    }

    static boolean matchesStation(BlockState state) {
        return StationSupport.matchesBlock(STORAGE_BLOCK_ITEM, state);
    }

    /**
     * The block the staging room is stamped with: the configured storage block,
     * its front turned to {@code facing} when it has one. An ender chest when the
     * configured id is not a block.
     */
    static BlockState stationState(Direction facing) {
        Item item = STORAGE_BLOCK_ITEM.get();
        Block block = item == null ? Blocks.AIR : Block.byItem(item);
        if (block == Blocks.AIR) {
            block = Blocks.ENDER_CHEST;
        }
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            state = state.setValue(HorizontalDirectionalBlock.FACING, facing);
        }
        return state;
    }

    /**
     * A right-click on a block in the dungeon. Claimed when it is the storage
     * block and the player is in a run's floor loop; the vanilla chest never
     * opens. A visit, a build room, an untimed run or a Pocket2 child has no
     * storage, and falls through to the ender chest denial.
     */
    static boolean onUse(ServerPlayer player, BlockState state, boolean inDungeon) {
        if (!inDungeon || !matchesStation(state)) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.inFloorLoop()) {
            return false;
        }
        SimpleContainer storage = record.runStorage.computeIfAbsent(player.getUUID(), id -> new SimpleContainer(SLOTS));
        record.interval.storageSnapshot.computeIfAbsent(player.getUUID(), id -> copyOf(storage));
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, storage),
                Component.literal("Run Storage")));
        return true;
    }

    /** A copy of every slot, empty ones included. */
    private static List<ItemStack> copyOf(SimpleContainer container) {
        List<ItemStack> copy = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            copy.add(container.getItem(i).copy());
        }
        return copy;
    }

    /**
     * A max-omen death: every member's storage goes back to what it held when
     * the interval began, the same as their pack, so stashing the unbanked
     * floors' loot here does not keep it.
     *
     * <p>The snapshot is taken on the first open in each interval
     * ({@link #onUse}). Storage only changes while it is open, so that copy is
     * exactly what the interval started with, and a storage with no snapshot
     * was not touched this interval and needs no rollback.
     */
    static void rollBackToInterval(InstanceRecord record) {
        for (Map.Entry<UUID, SimpleContainer> entry : record.runStorage.entrySet()) {
            List<ItemStack> snapshot = record.interval.storageSnapshot.get(entry.getKey());
            if (snapshot == null) {
                continue;
            }
            SimpleContainer container = entry.getValue();
            for (int i = 0; i < container.getContainerSize(); i++) {
                container.setItem(i, i < snapshot.size() ? snapshot.get(i).copy() : ItemStack.EMPTY);
            }
        }
    }

    /** Every non-empty stack the run's members stored, per member, emptying the containers. */
    static Map<UUID, List<ItemStack>> drain(InstanceRecord record) {
        Map<UUID, List<ItemStack>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, SimpleContainer> entry : record.runStorage.entrySet()) {
            List<ItemStack> stacks = new ArrayList<>();
            SimpleContainer container = entry.getValue();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.removeItemNoUpdate(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
            if (!stacks.isEmpty()) {
                out.put(entry.getKey(), stacks);
            }
        }
        record.runStorage.clear();
        return out;
    }

    /**
     * The run closed: puts each member's stored items into their dungeon pack
     * record, to come back with the pack on their next entry. Never the live
     * inventory: by the time a purge gets here the members have been ejected,
     * and what they hold is their survival inventory.
     */
    static void returnAll(MinecraftServer server, InstanceRecord record) {
        DungeonLog log = DungeonLog.forServer(server);
        for (Map.Entry<UUID, List<ItemStack>> entry : drain(record).entrySet()) {
            InventorySwap.keepForNextEntry(log, entry.getKey(), entry.getValue());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                                "Your run storage went back into your dungeon pack.")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
