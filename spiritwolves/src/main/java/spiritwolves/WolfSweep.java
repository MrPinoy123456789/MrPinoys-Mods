package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps a summoned wolf from surviving as a second copy when its chunk unloads
 * out from under it -- the {@code /teleport} duplication bug.
 *
 * <p>The failure it closes: the registry decides a wolf is gone by asking
 * {@code level.getEntity(uuid)}, which only ever sees <em>loaded</em> entities.
 * Teleport away, the wolf's chunk unloads, the wolf is serialised into that
 * chunk (its removal reason is {@code UNLOADED_TO_CHUNK}, which saves), the poll
 * finds nothing and flips {@code summoned} to false, and the next right-click
 * summons a fresh wolf from the stored NBT. Two wolves, in two chunks, on disk.
 *
 * <p>The copies even share a UUID -- {@link WolfCapture#capture} writes it and
 * {@code Entity.load} reads it back -- so vanilla's duplicate-UUID guard drops
 * whichever tries to load second. That is not a fix: it only fires while both
 * are loaded at once, and when it does the survivor is whichever chunk loaded
 * first, which may be the stale one. A stale survivor then gets adopted by
 * {@code Tracker.reconcile}, overwriting the record's NBT and rolling the wolf's
 * state back to that older snapshot.
 *
 * <p>Two nets here, either of which alone leaves a hole:
 * <ol>
 *   <li>{@code ENTITY_UNLOAD} stores the wolf back into its record while it is
 *       still live, so the capture is fresh rather than the last recall's;</li>
 *   <li>{@code ENTITY_LOAD} discards any wolf whose owner's record says it is
 *       stored in the stone. This is the one that actually kills duplicates,
 *       including ones already sitting in a world's region files.</li>
 * </ol>
 *
 * <p>The invariant both enforce: <em>the registry is the only copy of a spirit
 * wolf, and a spirit wolf in the world that the registry does not currently
 * claim is garbage.</em>
 *
 * @see Tracker the leash in {@code poll}, which stops most of this arising
 */
public final class WolfSweep {

    /**
     * Candidates seen this tick, judged at the end of it.
     *
     * <p>The delay is load-bearing, not caution -- the same reason
     * {@code chatdonkey}'s OrphanSweep defers. {@code ENTITY_LOAD} fires from
     * inside {@code addFreshEntity}, so it fires for our own wolf partway
     * through {@code Summoning.summon}, after the restore but before
     * {@code record.summoned} is set back to true. Judging on the spot would
     * discard every wolf the instant it was summoned.
     */
    private static final List<Wolf> pending = new ArrayList<>();

    private WolfSweep() {}

    public static void register() {
        ServerEntityEvents.ENTITY_UNLOAD.register(WolfSweep::onUnload);

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            // Cheap test now -- a spirit wolf is always tamed, which rules out
            // every wild wolf in every chunk that will ever load. The expensive
            // question of ownership waits for the tick end.
            if (entity instanceof Wolf wolf && wolf.isTame()) {
                pending.add(wolf);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> judgePending());
    }

    /**
     * A summoned wolf whose chunk is unloading. Store it back into the record
     * now, while its state is still readable, so the next summon restores what
     * the wolf actually was rather than the last recall's snapshot.
     */
    private static void onUnload(Entity entity, ServerLevel level) {
        if (!(entity instanceof Wolf wolf) || !wolf.isTame()) {
            return;
        }
        // Only a genuine chunk unload. DISCARDED means one of our own recall
        // paths just called discard() and has already done this bookkeeping;
        // CHANGED_DIMENSION is the poll's business.
        if (wolf.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            return;
        }

        UUID owner = PlayerWolfRegistry.ownerOf(wolf.getUUID());
        if (owner == null) {
            return;
        }
        WolfRecord record = PlayerWolfRegistry.get(owner);
        if (record == null || !record.summoned) {
            return;
        }

        // discard = false: the entity is mid-removal already. The copy this
        // leaves in the chunk on disk is what the ENTITY_LOAD net is for.
        Tracker.storeWolf(owner, record, wolf, level, false);

        // From the player's side this is the leash, just reached by unload
        // rather than by the poll -- so it reads the same in chat. Null owner
        // means they logged out, and DISCONNECT already recalled silently.
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            Tracker.announceReturn(online, record);
        }
    }

    private static void judgePending() {
        if (pending.isEmpty()) {
            return;
        }
        for (Wolf wolf : pending) {
            if (!wolf.isRemoved() && isLeftover(wolf)) {
                SpiritWolvesMod.LOG.info("Discarded a leftover spirit wolf in a newly loaded chunk");
                wolf.discard();
            }
        }
        pending.clear();
    }

    /**
     * A wolf the registry knows, whose record says it is stored in the stone.
     *
     * <p>The record being the authority is the whole point: if it says stored,
     * this wolf in the world is a copy that outlived its chunk, no matter which
     * path leaked it.
     *
     * <p>A record that says <em>summoned</em> is deliberately left alone. Both
     * copies carry the same UUID, so there is nothing to tell a stale one from
     * the real one -- but there is also no need to, because vanilla's
     * duplicate-UUID guard means only one of them can be loaded at a time, and
     * whichever one that is, it is the wolf the player has out.
     */
    private static boolean isLeftover(Wolf wolf) {
        UUID owner = PlayerWolfRegistry.ownerOf(wolf.getUUID());
        if (owner == null) {
            return false;
        }
        WolfRecord record = PlayerWolfRegistry.get(owner);
        return record != null && !record.summoned;
    }
}
