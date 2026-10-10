package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dungeon Storage (playtest 2026-10-02-1: "a storage that stays in the staging room
 * to give players a bit of extra inventory space during runs"; made permanent in the
 * 2026-10-06 design, item 5). Every staging room is stamped with an ender chest set
 * into its wall ({@link RoomTemplateGenerator#placeRunStorage}); right-clicking it, or
 * any other ender chest in the safe room, at the Doors or mid-run (J6), opens the
 * player's own 27 slots, so it feels vanilla while being gated behind the scenes.
 *
 * <p>The contents belong to the player, not the run: they live in {@link DungeonLog}
 * ({@link DungeonLog#storageOf}), the way the dungeon pack does, and survive floors,
 * trips, runs, logouts and restarts. Nothing moves them when a run closes; the old
 * return to the pack, and its offline notice, are gone. Never cross-dimension: the
 * storage is part of the dungeon, never of the survival inventory. The block is only
 * a handle (the chest's own inventory is never opened), so breaking it loses nothing.
 *
 * <p>A failed run does not roll it back (J2): the storage belongs to the player,
 * so stashing the unbanked floors' loot here keeps it, the same as the pack.
 */
final class RunStorage {

    static final int SLOTS = 27;

    /** The menu title. The block is an ender chest, so it says whose storage this is not. */
    static final String TITLE = "Dungeon Storage";

    private static final ConfiguredItem STORAGE_BLOCK_ITEM = new ConfiguredItem("storageBlock",
            PocketDungeonsConfig::storageBlock,
            "run storage will never open for anybody.");

    /**
     * The open storage of each player: one live container per player, so two opens
     * share it. An entry exists only while someone has it open; the log is the truth
     * the rest of the time.
     */
    private static final Map<UUID, StorageContainer> live = new HashMap<>();

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
     * A right-click on a block in the dungeon. J6 (D38, owner amendment): any
     * ender chest anywhere in the dungeon world, the safe room, the Doors or
     * a floor, opens the player's Dungeon Storage whether a run is up or not.
     * The vanilla ender chest never opens there.
     */
    static boolean onUse(ServerPlayer player, BlockState state, boolean inDungeon) {
        if (!inDungeon || !matchesStation(state)) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        StorageContainer storage = openFor(server, player.getUUID());
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, storage),
                Component.literal(TITLE)));
        return true;
    }

    /** The player's live container, built from the log unless someone already has it open. */
    private static StorageContainer openFor(MinecraftServer server, UUID owner) {
        StorageContainer open = live.get(owner);
        if (open != null) {
            return open;
        }
        StorageContainer fresh = new StorageContainer(DungeonLog.forServer(server), owner);
        live.put(owner, fresh);
        return fresh;
    }

    /** A copy of every slot, empty ones included. */
    private static List<ItemStack> copyOf(SimpleContainer container) {
        List<ItemStack> copy = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            copy.add(container.getItem(i).copy());
        }
        return copy;
    }

    /** Every non-empty stack the record's legacy live storage held, per member, emptying it. */
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
     * Migration (design item 5): anything still in a live run's old per-run storage when
     * the run closes moves into its member's Dungeon Storage, not the pack. What does not
     * fit the 27 slots goes to the dungeon pack record, the way a stack that did not fit
     * always has. A run that never held old storage does nothing here.
     */
    static void migrateLegacy(MinecraftServer server, InstanceRecord record) {
        DungeonLog log = DungeonLog.forServer(server);
        for (Map.Entry<UUID, List<ItemStack>> entry : drain(record).entrySet()) {
            SimpleContainer box = new SimpleContainer(SLOTS);
            List<ItemStack> held = log.storageOf(entry.getKey());
            for (int i = 0; i < held.size() && i < SLOTS; i++) {
                box.setItem(i, held.get(i).copy());
            }
            List<ItemStack> overflow = new ArrayList<>();
            for (ItemStack stack : entry.getValue()) {
                ItemStack left = box.addItem(stack);
                if (!left.isEmpty()) {
                    overflow.add(left);
                }
            }
            log.setStorage(entry.getKey(), copyOf(box));
            if (!overflow.isEmpty()) {
                InventorySwap.keepForNextEntry(log, entry.getKey(), overflow);
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                                "What was in your run storage is now in your Dungeon Storage.")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /**
     * A 27 slot container backed by the log: built from the saved list, written back on
     * every change and when the last viewer closes it. One per player while it is open.
     */
    static final class StorageContainer extends SimpleContainer {

        private final DungeonLog log;
        private final UUID owner;
        private int viewers;

        StorageContainer(DungeonLog log, UUID owner) {
            super(SLOTS);
            this.log = log;
            this.owner = owner;
            List<ItemStack> saved = log.storageOf(owner);
            for (int i = 0; i < saved.size() && i < SLOTS; i++) {
                items.set(i, saved.get(i).copy());
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            log.setStorage(owner, copyOf(this));
        }

        @Override
        public void startOpen(ContainerUser user) {
            viewers++;
        }

        @Override
        public void stopOpen(ContainerUser user) {
            viewers = Math.max(0, viewers - 1);
            log.setStorage(owner, copyOf(this));
            if (viewers == 0 && live.get(owner) == this) {
                live.remove(owner);
            }
        }
    }
}
