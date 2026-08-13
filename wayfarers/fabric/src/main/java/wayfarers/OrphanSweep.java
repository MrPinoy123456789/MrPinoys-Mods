package wayfarers;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes wayfarer bodies that outlived their encounter.
 *
 * <p>Two nets: a sweep of loaded entities at {@code SERVER_STARTED}, and
 * {@code ENTITY_LOAD} for stragglers. The list of candidates is judged on the
 * next tick so an encounter registering a brand-new body in the same tick does
 * not accidentally discard it.
 */
public final class OrphanSweep {

    private final List<Entity> pending = new ArrayList<>();

    private OrphanSweep() {}

    public static OrphanSweep register(Encounters encounters) {
        OrphanSweep sweep = new OrphanSweep();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            int removed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (isOrphan(encounters, entity)) {
                        entity.discard();
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                WayfarersMod.LOG.info("Swept {} leftover wayfarer(s) from a previous session", removed);
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (isTagged(entity)) {
                sweep.pending.add(entity);
            }
        });

        return sweep;
    }

    public void tick(Encounters encounters) {
        if (pending.isEmpty()) {
            return;
        }
        for (Entity entity : pending) {
            if (!entity.isRemoved() && isOrphan(encounters, entity)) {
                WayfarersMod.LOG.info("Discarded an orphan wayfarer in a newly loaded chunk");
                entity.discard();
            }
        }
        pending.clear();
    }

    private static boolean isTagged(Entity entity) {
        return entity.entityTags().contains(Spawns.TAG);
    }

    private static boolean isOrphan(Encounters encounters, Entity entity) {
        return isTagged(entity) && !encounters.isEventEntity(entity.getUUID());
    }
}
