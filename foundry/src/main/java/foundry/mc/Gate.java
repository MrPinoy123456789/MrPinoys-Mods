package foundry.mc;

import foundry.core.ItemDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * Item level locks. An item with {@code require_permission} works only for a player holding that node (asked through
 * {@link MechanicHost#permissionCheck}), and an item with {@code keep_on_death} returns to its owner after a death.
 * The melee hit is stopped here because attack damage is a plain attribute that no mechanic can switch off.
 */
public final class Gate {
    /** Stacks set aside at a death, handed back when the player respawns. */
    private static final Map<UUID, List<ItemStack>> KEPT = new HashMap<>();

    private Gate() {}

    /** The definition behind a stack, or null when it is not a Foundry item. */
    static ItemDef defOf(ItemStack stack) {
        return stack.getItem() instanceof FoundryItem item ? item.def() : null;
    }

    /** True when the player may use this definition's item. Operators pass, so they can test any item. */
    public static boolean allowed(ServerPlayer player, ItemDef def) {
        return def == null || !def.locked() || operator(player)
                || MechanicHost.permissionCheck.test(player, Identifier.parse(def.requirePermission()));
    }

    private static boolean operator(ServerPlayer player) {
        return player.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS));
    }

    /** Checks the lock and, when it holds, tells the player why. True when the item may be used. */
    static boolean check(ServerPlayer player, ItemDef def) {
        if (allowed(player, def)) return true;
        player.sendSystemMessage(Component.literal(def.lockedMessage()), true);
        return false;
    }

    static void register() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp && !check(sp, defOf(sp.getItemInHand(hand)))) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer sp) setAside(sp);
            return true;
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> handBack(newPlayer));
    }

    /** Takes every keep_on_death stack out of the inventory before the death drops it. */
    public static void setAside(ServerPlayer player) {
        List<ItemStack> kept = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemDef def = defOf(stack);
            if (def != null && def.keepOnDeath()) {
                kept.add(stack.copy());
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        if (!kept.isEmpty()) KEPT.computeIfAbsent(player.getUUID(), id -> new ArrayList<>()).addAll(kept);
    }

    /** Gives the set aside stacks to the respawned player; whatever does not fit is dropped at their feet. */
    public static void handBack(ServerPlayer player) {
        List<ItemStack> kept = KEPT.remove(player.getUUID());
        if (kept == null) return;
        for (ItemStack stack : kept) {
            if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
        }
    }


    public static void clear() {
        KEPT.clear();
    }
}
