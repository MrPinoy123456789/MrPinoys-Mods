package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

    /** Beyond this, the owner teleported rather than walked. See {@link #poll}. */
    private static final double LEASH_RANGE_SQR = 96.0 * 96.0;

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

        // Login: the chunk may have been saved while the wolf was out, then
        // reloaded from disk on the next join. Reconcile record.summoned with
        // the real world state before the player interacts with the stone.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                reconcile(handler.player));

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

        // Teleports, /home, and chunk loads can bring a stored wolf back into
        // the world without the registry knowing. Sync before the rest of the
        // poll decides what to do.
        reconcile(player);

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
            AbilityProcs.forget(record.wolfUuid);
            Tricks.forgetOwner(player.getUUID());
            Training.forgetOwner(player.getUUID());
            return;
        }

        // The leash. A wolf this far from its owner did not walk there -- the
        // owner teleported. Left alone the wolf's chunk unloads, the poll below
        // stops finding it, the record decides it is gone, and the next
        // right-click summons a second copy from the stored NBT. Recalling here,
        // while the wolf is still loaded, is the cheap way to never reach that
        // state; WolfSweep is the backstop for when it happens anyway.
        //
        // 96 blocks is well inside any sane view distance (so the wolf is still
        // loaded when we look) and well outside the longest range any feature
        // uses -- Fetch's 32 is the current maximum.
        if (wolf.distanceToSqr(player) > LEASH_RANGE_SQR) {
            recallSilently(player, record, wolf);
            announceReturn(player, record);
            return;
        }

        Senses.growlIfThreatened(wolf, tickCounter);
        Senses.outlineCurrentTarget(wolf);
        AbilityProcs.tickSummonedWolf(player, record, wolf);
        Fetch.tickSummonedWolf(wolf, player, record);
        Training.noteAggro(player, wolf, level);
    }

    /** On login, make sure the registry reflects whether the wolf is actually in the world. */
    private static void reconcile(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel)) {
            return;
        }
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            return;
        }

        Wolf wolf = Summoning.findWolfAnywhere(player.level().getServer(), record.wolfUuid);

        if (record.summoned && wolf == null) {
            // Record thinks the wolf is out, but it is not loaded anywhere.
            record.summoned = false;
            PlayerWolfRegistry.markDirty(player.getUUID());
            Senses.forget(record.wolfUuid);
            Streak.forget(record.wolfUuid);
            RecallLock.forget(record.wolfUuid);
            AbilityProcs.forget(record.wolfUuid);
            Tricks.forgetOwner(player.getUUID());
            Training.forgetOwner(player.getUUID());
        }
        // The mirror case -- record stored, but a wolf is loaded from disk -- used
        // to adopt the live wolf. It must not. Since WolfSweep stores the wolf
        // back into the record on chunk unload, the record is now always at least
        // as fresh as anything left in a chunk, so adopting could only overwrite
        // current state with an older snapshot. A wolf in that position is a
        // leftover copy and WolfSweep discards it on the tick it loads.

        forEachBoundStone(player, stone -> SpiritStone.refreshLore(stone, record));
    }

    /**
     * Tells the owner their wolf was put away because it could not follow --
     * the leash in {@link #poll} and {@link WolfSweep}'s chunk-unload handler
     * are the same event from the player's side, so they say the same thing.
     *
     * <p>The other silent recalls (logout, death, dimension change) stay silent:
     * the player is not in a position to read chat at that moment anyway.
     */
    static void announceReturn(ServerPlayer player, WolfRecord record) {
        player.sendSystemMessage(Component.literal(
                        (record.wolfName != null ? record.wolfName : "Your wolf")
                                + " could not follow, and returns to the stone.")
                .withStyle(ChatFormatting.GRAY));
        Chime.recalled(player);
    }

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
        storeWolf(player.getUUID(), record, wolf, (ServerLevel) wolf.level(), true);
    }

    /**
     * Captures a live wolf back into its owner's record and clears every
     * per-outing cache -- the shared half of every recall path.
     *
     * <p>{@code discard} is false for exactly one caller: {@link WolfSweep}'s
     * {@code ENTITY_UNLOAD} handler, which runs while the entity is already
     * being removed. Discarding there would re-enter removal on an entity the
     * section manager has already committed to unloading.
     *
     * <p>The level is passed rather than read off the wolf because the unload
     * path cannot assume {@code wolf.level()} is still meaningful.
     */
    static void storeWolf(UUID ownerUuid, WolfRecord record, Wolf wolf, ServerLevel level, boolean discard) {
        Streak.onReturn(wolf, record);
        RecallLock.forget(wolf.getUUID());
        AbilityProcs.forget(wolf.getUUID());
        Senses.forget(wolf.getUUID());
        Tricks.forgetOwner(ownerUuid);
        Training.forgetOwner(ownerUuid);
        record.wolfUuid = wolf.getUUID();
        record.wolfTag = WolfCapture.capture(wolf, level);
        record.wolfName = WolfCapture.nameOf(wolf);
        record.collar = WolfCapture.collarOf(wolf);
        record.summoned = false;
        PlayerWolfRegistry.markDirty(ownerUuid);
        if (discard) {
            wolf.discard();
        }
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
