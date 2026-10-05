package pocketdungeons;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bag chest as a kit station (playtest 2026-10-03-2, owner decision
 * 2026-10-04). The chest stands in the safe room for good. A player without
 * a bag picks one there; a player with a bag opens their own 27 slots in it,
 * the way run storage opens on an ender chest.
 *
 * <p>Every trip home that banked a floor rolls a fresh full kit from the
 * player's bag and writes it into their slots, overwriting whatever was left:
 * no top-up arithmetic, no leftovers carried over. It replaces
 * {@link KitTopUp}, which pushed a band-scaled deficit into the pack. The
 * contents are kept in {@link DungeonLog} so a restart does not lose a kit
 * the player has not taken yet.
 */
final class KitChest {

    static final int SLOTS = 27;

    private KitChest() {}

    /** Opens {@code player}'s view of the bag chest. */
    static void open(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        View view = new View(DungeonLog.forServer(server), player.getUUID());
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, view),
                Component.literal("Your Kit")));
        Chime.menuOpens(player);
    }

    /**
     * Rolls a fresh full kit from {@code member}'s bag into their slots,
     * overwriting what was there. Nothing for a player with no bag yet.
     *
     * @return the kit rolled, or an empty list when nothing was refilled
     */
    static List<ItemStack> refill(MinecraftServer server, InstanceRecord record, ServerPlayer member) {
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = member.getUUID();
        String bag = log.bagOf(id);
        // Any server level rolls a loot table; the member's own is at hand.
        ServerLevel level = (ServerLevel) member.level();
        if (bag.isEmpty()) {
            return List.of();
        }
        List<ItemStack> kit = Bags.rollKit(level, bag);
        if (kit == null || kit.isEmpty()) {
            return List.of();
        }
        List<ItemStack> overwritten = copyOf(log.kitChestOf(id));
        List<ItemStack> slots = new ArrayList<>();
        for (ItemStack stack : kit) {
            if (!stack.isEmpty() && slots.size() < SLOTS) {
                slots.add(stack.copy());
            }
        }
        log.setKitChest(id, slots);
        PlaytestJournal.kitRefill(member, record, slots, overwritten);
        member.sendSystemMessage(Component.literal("A fresh kit is waiting in the bag chest.")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        return slots;
    }

    private static List<ItemStack> copyOf(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            copy.add(stack.copy());
        }
        return copy;
    }

    /**
     * One player's 27 slots, loaded from {@link DungeonLog} and written back on
     * every change. 26.2's {@code SimpleContainer} has no listener list, so the
     * write rides {@code setChanged}, as the salvage bench's input does.
     */
    private static final class View extends SimpleContainer {
        private final DungeonLog log;
        private final UUID player;
        private boolean loading = true;

        View(DungeonLog log, UUID player) {
            super(SLOTS);
            this.log = log;
            this.player = player;
            List<ItemStack> items = log.kitChestOf(player);
            for (int i = 0; i < items.size() && i < SLOTS; i++) {
                setItem(i, items.get(i).copy());
            }
            loading = false;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!loading) {
                List<ItemStack> items = new ArrayList<>(SLOTS);
                for (int i = 0; i < SLOTS; i++) {
                    items.add(getItem(i).copy());
                }
                log.setKitChest(player, items);
            }
        }
    }
}
