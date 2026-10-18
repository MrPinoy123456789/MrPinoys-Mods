package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The quit rule of Act 0 (decision 2026-10-17): quitting The First Night resets the act. The run restarts at
 * floor 1 (the ordinary quit does that), and everything the player carried, stored or took from the bag chest
 * is wiped, so nothing from an unfinished night is kept and the chest hands the gear out again.
 */
final class ActZero {

    private ActZero() {}

    /** Wipes every member of {@code record}. */
    static void wipe(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            wipeMember(server, member);
        }
    }

    /** Clears one player's dungeon pack, cursor, Dungeon Storage, bag choice, kit chest, orphan and haul. */
    static void wipeMember(MinecraftServer server, UUID id) {
        DungeonLog log = DungeonLog.forServer(server);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null && DungeonWorldRules.inDungeon(player.level())) {
            player.getInventory().clearContent();
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        log.setStorage(id, List.of());
        log.setKitChest(id, List.of());
        log.setOrphan(id, InventorySwap.OrphanRecord.NONE);
        log.setBag(id, "");
        log.setKitGranted(id, false);
        log.setHaul(id, 0);
        if (player != null) {
            player.sendSystemMessage(Component.literal("The night starts over. Everything you carried is gone.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
