package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks targets that were recently damaged by a summoned spirit wolf so that
 * deaths from any source within the assist window still credit the wolf.
 *
 * <p>This deliberately does not distinguish between player finishers and
 * environmental kills: if the wolf contributed damage and the target dies soon
 * after, the wolf gets the same progression as a direct kill.
 */
final class Assists {

    /** How long after the wolf's last hit a death still counts as an assist. */
    private static final long WINDOW_MS = 8_000;

    private record Mark(UUID owner, UUID wolf, long expireAt) {}

    private static final Map<UUID, Mark> marks = new ConcurrentHashMap<>();

    private Assists() {}

    static void register() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> marks.remove(entity.getUUID()));
    }

    /** Records that a summoned wolf damaged a target, opening an assist window. */
    static void mark(Wolf wolf, ServerPlayer owner, LivingEntity target) {
        long now = System.currentTimeMillis();
        marks.put(target.getUUID(), new Mark(owner.getUUID(), wolf.getUUID(), now + WINDOW_MS));
    }

    /**
     * Called from {@link WolfKill} when something dies. Returns true if an
     * assist was claimed and processed.
     */
    static boolean claim(ServerLevel level, LivingEntity entity) {
        Mark mark = marks.remove(entity.getUUID());
        if (mark == null || System.currentTimeMillis() > mark.expireAt) {
            return false;
        }

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(mark.owner);
        if (owner == null) {
            return false;
        }

        WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
        if (record == null) {
            return false;
        }

        Wolf wolf = findWolf(level, mark.wolf);
        if (wolf != null && wolf.isTame()) {
            Streak.onKill(wolf, owner);
            Fetch.recordKill(level, owner, entity.position());
            Souls.onKill(wolf, owner, record, entity);
            Abilities.onKill(wolf, owner, record, entity);
            AbilityProcs.onKill(wolf, owner, record, entity);
        } else {
            // The wolf is no longer loaded, but the player-side progression
            // (souls, family-kill counts, fetch position) still applies.
            Fetch.recordKill(level, owner, entity.position());
            Souls.onKill(owner, record, entity);
            Abilities.onKill(owner, record, entity);
        }
        return true;
    }

    /** Removes any pending assist mark for an entity that died or despawned. */
    static void forget(UUID entity) {
        marks.remove(entity);
    }

    private static Wolf findWolf(ServerLevel level, UUID wolfUuid) {
        Wolf wolf = Summoning.findWolf(level, wolfUuid);
        if (wolf != null) {
            return wolf;
        }
        if (level.getServer() == null) {
            return null;
        }
        return Summoning.findWolfAnywhere(level.getServer(), wolfUuid);
    }
}
