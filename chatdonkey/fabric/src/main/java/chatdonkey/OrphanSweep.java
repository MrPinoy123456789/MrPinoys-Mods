package chatdonkey;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes event donkeys that outlived their event (SPEC.md section 6).
 *
 * <p>An event never survives a restart, so any tagged donkey found at startup is
 * a leftover from a crash mid-event. It is harmless on its own -- with no live
 * event it is not immortal, just a named vanilla donkey -- but a server that
 * accumulates a herd of Duncans over months is its own kind of bug.
 *
 * <p>Two nets, because one is not enough:
 * <ol>
 *   <li>a sweep of loaded entities at {@code SERVER_STARTED}, which catches
 *       everything in chunks loaded at boot;</li>
 *   <li>{@code ENTITY_LOAD}, which catches stragglers in chunks that load
 *       later.</li>
 * </ol>
 *
 * <p>SPEC.md section 6 hedged that the second case might have to be accepted as
 * a permanent rare orphan if no chunk-load event existed. One does, so it does
 * not.
 */
public final class OrphanSweep {

    /**
     * Candidates seen this tick, judged on the next one.
     *
     * <p>This delay is load-bearing, not caution. {@code ENTITY_LOAD} fires from
     * inside {@code addFreshEntity}, which means it fires for our <em>own</em>
     * donkey while {@link Events#start} is still mid-flight -- the donkey is
     * already tagged but the event that owns it has not been registered yet.
     * Judging on the spot would therefore discard every single event donkey the
     * instant it spawned. By the next tick the event exists and the donkey is
     * correctly recognised as owned.
     */
    private final List<Entity> pending = new ArrayList<>();

    private OrphanSweep() {}

    /** @return the sweep, which the mod must {@link #tick} each server tick */
    public static OrphanSweep register(Events events) {
        OrphanSweep sweep = new OrphanSweep();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            int removed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (isOrphan(events, entity)) {
                        entity.discard();
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                ChatDonkeyMod.LOG.info("Swept {} leftover donkey(s) from a previous session",
                        removed);
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            // Cheap tag test now; the expensive question of ownership waits.
            if (isTagged(entity)) {
                sweep.pending.add(entity);
            }
        });

        return sweep;
    }

    /** Called once per server tick, after events have had their chance to register. */
    public void tick(Events events) {
        if (pending.isEmpty()) {
            return;
        }
        for (Entity entity : pending) {
            if (!entity.isRemoved() && isOrphan(events, entity)) {
                ChatDonkeyMod.LOG.info("Discarded a leftover donkey in a newly loaded chunk");
                entity.discard();
            }
        }
        pending.clear();
    }

    private static boolean isTagged(Entity entity) {
        // entityTags(), not getTags() -- renamed in 26.2.
        return entity.entityTags().contains(DonkeySpawn.TAG);
    }

    /**
     * A tagged donkey with no live event behind it.
     *
     * <p>The live-event check is what keeps a donkey whose player walks it
     * across a chunk border mid-event from being swept out from under them.
     */
    private static boolean isOrphan(Events events, Entity entity) {
        return isTagged(entity) && !events.isEventDonkey(entity.getUUID());
    }
}
