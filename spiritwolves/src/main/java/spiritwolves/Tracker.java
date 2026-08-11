package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The 30-tick poll that keeps the {@link PlayerWolfRegistry} honest, flushes
 * dirty records to disk, and drives the recall triggers that aren't
 * event-shaped (dimension change).
 *
 * <p>v3 (SPEC.md section 16.7): the old stone-in-inventory invariant is gone
 * -- the wolf is bound to the player, not the stone, so it stays out even if
 * the stone leaves the inventory. Recall now happens on exactly: right-click
 * (Summoning), logout, owner death, dimension change, and death-save
 * (Deaths).
 */
public final class Tracker {

    private static final int POLL_INTERVAL_TICKS = 30;

    private static int tickCounter;

    /** Last known dimension per player, used only to detect a change while summoned. */
    private static final Map<UUID, ResourceKey<Level>> lastDimension = new HashMap<>();

    private Tracker() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerWolfRegistry::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PlayerWolfRegistry.flushAll());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % POLL_INTERVAL_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                poll(player);
            }
            PlayerWolfRegistry.flushDirty();
        });

        // Logout: recall any wolf the player has out. Prevents an orphaned wolf
        // with no online owner to decrement charges on death.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            recallSilently(handler.player);
            lastDimension.remove(handler.player.getUUID());
        });

        // Owner death: recall silently before the death resolves, rather than
        // leave an ownerless wolf fighting on at the death site.
        ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, amount) -> {
            recallSilently(player);
            return true;
        });
    }

    private static void poll(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            lastDimension.remove(player.getUUID());
            return;
        }

        // Cosmetic journal milestone -- cheap, independent of whether the wolf is
        // currently out (SPEC.md section 15.1). Charges come from whichever bound
        // stone the player happens to be carrying, if any.
        ItemStack stone = findStoneFor(player);
        if (stone != null && Journal.checkUntested(record, SpiritStone.chargesRemaining(stone))) {
            PlayerWolfRegistry.markDirty(player.getUUID());
            SpiritStone.refreshLore(stone, record);
        }

        if (!record.summoned) {
            lastDimension.remove(player.getUUID());
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        ResourceKey<Level> previous = lastDimension.put(player.getUUID(), dimension);
        if (previous != null && previous != dimension) {
            recallSilently(player, record);
            return;
        }

        Wolf wolf = Summoning.findWolf(level, record.wolfUuid);
        if (wolf == null) {
            // The wolf might still be alive in another dimension (rapid portal hop,
            // chunk load order, etc.). Search everywhere before declaring it gone.
            wolf = Summoning.findWolfAnywhere(level.getServer(), record.wolfUuid);
            if (wolf != null) {
                recallSilently(player, record, wolf);
                return;
            }

            // Wolf is gone but the record still thinks it's out -- reconcile.
            record.summoned = false;
            PlayerWolfRegistry.markDirty(player.getUUID());
            Senses.forget(record.wolfUuid);
            Streak.forget(record.wolfUuid);
            RecallLock.forget(record.wolfUuid);
            VerbProcs.forget(record.wolfUuid);
            return;
        }

        Senses.growlIfThreatened(wolf, tickCounter);
        Senses.outlineCurrentTarget(wolf);
        VerbProcs.tickSummonedWolf(player, record, wolf);
        Scavenger.tick(wolf, player, record);
    }

    /** Recalls the player's summoned wolf into the registry, silently -- no charge cost, no message. */
    private static void recallSilently(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record != null) {
            recallSilently(player, record);
        }
    }

    private static void recallSilently(ServerPlayer player, WolfRecord record) {
        if (!record.summoned || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Wolf wolf = Summoning.findWolf(level, record.wolfUuid);
        if (wolf == null) {
            // Cross-dimension fallback: the wolf may be in a level the player just left.
            wolf = Summoning.findWolfAnywhere(level.getServer(), record.wolfUuid);
        }
        if (wolf == null) {
            record.summoned = false;
            PlayerWolfRegistry.markDirty(player.getUUID());
            return;
        }
        recallSilently(player, record, wolf);
    }

    private static void recallSilently(ServerPlayer player, WolfRecord record, Wolf wolf) {
        ServerLevel level = (ServerLevel) wolf.level();
        Streak.onReturn(wolf, record);
        RecallLock.forget(wolf.getUUID());
        VerbProcs.forget(wolf.getUUID());
        record.wolfUuid = wolf.getUUID();
        record.wolfTag = WolfCapture.capture(wolf, level);
        record.wolfName = WolfCapture.nameOf(wolf);
        record.collar = WolfCapture.collarOf(wolf);
        record.summoned = false;
        PlayerWolfRegistry.markDirty(player.getUUID());
        Senses.forget(wolf.getUUID());
        wolf.discard();
    }

    /** Finds the online player whose registry record has this wolf UUID summoned, if any. */
    public static ServerPlayer findOwner(ServerLevel level, UUID wolfUuid) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
            if (record != null && record.summoned && wolfUuid.equals(record.wolfUuid)) {
                return player;
            }
        }
        return null;
    }

    /**
     * Finds a bound stone in the given player's inventory, if any. One wolf per
     * player means any bound stone found operates on the same registry record.
     */
    public static ItemStack findStoneFor(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && SpiritStone.isBound(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** Runs {@code action} on every bound stone in the player's inventory. */
    public static void forEachBoundStone(ServerPlayer player, Consumer<ItemStack> action) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && SpiritStone.isBound(stack)) {
                action.accept(stack);
            }
        }
    }

    // Kept for callers that still want to restrict scanning to the main inventory.
    static final int MAIN_SLOTS = 36;
}
