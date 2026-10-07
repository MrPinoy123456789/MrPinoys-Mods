package pocketdungeons;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What is left in a player's bag chest slots. The chest used to be a kit station that a trip
 * home refilled (playtest 2026-10-03-2, owner decision 2026-10-04); dungeon structure W5 (design
 * D14) removed the refill: the kit is granted once, at the pick. This view survives only so a
 * player who still has items stored there can take them out; with nothing in it the chest says
 * so instead of opening. The {@link DungeonLog} codec field stays (migration rule).
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
     * Whether {@code player}'s bag chest slots still hold anything: a kit left from before the
     * refill was removed (dungeon structure W5, design D14). The station opens only then.
     */
    static boolean holdsLeftovers(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        for (ItemStack stack : DungeonLog.forServer(server).kitChestOf(player.getUUID())) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
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
