package foundry.mc;

import foundry.core.mechanics.Trigger;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * What a player is wearing, for the while_worn trigger. The worn slots come from the Trinkets mod when it is installed;
 * the four armour slots always count, so an item can be worn without the mod. Wings (a glider item) are worn in the
 * chest slot, as an elytra is, and their lock is enforced here: a player without the permission stops gliding.
 */
final class WornItems {
    private static final boolean TRINKETS = FabricLoader.getInstance().isModLoaded("trinkets_updated");
    /** while_worn runs once a second. */
    static final int EVERY_TICKS = 20;

    private WornItems() {}

    static boolean trinkets() {
        return TRINKETS;
    }

    static List<ItemStack> of(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) out.add(stack);
        }
        if (TRINKETS) TrinketsHook.worn(player, out::add);
        return out;
    }

    static void tick(MinecraftServer server) {
        boolean pulse = server.getTickCount() % EVERY_TICKS == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isFallFlying()) checkWings(player);
            if (!pulse) continue;
            for (ItemStack stack : of(player)) {
                if (stack.getItem() instanceof FoundryItem item && item.def() != null && item.def().worn()) {
                    item.fire(Trigger.WHILE_WORN, player, null);
                }
            }
        }
    }

    /** A glider that is locked to a permission only lifts the holders of that permission. */
    private static void checkWings(ServerPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() instanceof FoundryItem item && item.def() != null && item.def().glider()
                && !Gate.allowed(player, item.def())) {
            player.stopFallFlying();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(item.def().lockedMessage()), true);
        }
    }
}
