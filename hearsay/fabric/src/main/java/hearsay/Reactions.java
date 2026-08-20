package hearsay;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import hearsay.core.Triggers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off reactions to world events, villager interactions, time and weather.
 *
 * <p>Time and weather use the pure {@link Triggers} helpers from core.
 * Villager use is detected via the entity-interaction event; it does not imply
 * that a trade was completed.
 */
public final class Reactions {

    private final HearsayConfig config;
    private final Speech speech;
    private final Map<ServerLevel, Snapshot> prev = new HashMap<>();
    private final Map<UUID, Integer> interactionCooldown = new HashMap<>();
    private int tick;

    public Reactions(HearsayConfig config, Speech speech) {
        this.config = config;
        this.speech = speech;
    }

    /** Detects dawn, dusk, rain, and thunder edges and offers their configured lines. */
    public void tick(MinecraftServer server, Listeners listeners) {
        tick++;
        for (ServerLevel level : server.getAllLevels()) {
            Snapshot now = new Snapshot(level.getLevelData().getGameTime() % 24000, level.isRaining(), level.isThundering());
            Snapshot was = prev.put(level, now);
            if (was == null) continue;

            List<String> pools = new ArrayList<>();
            if (Triggers.crossedDawn(was.time, now.time)) pools.add("morning");
            if (Triggers.crossedDusk(was.time, now.time)) pools.add("night");
            if (Triggers.weatherStarted(was.rain, now.rain)) pools.add("weather");
            if (Triggers.weatherStarted(was.thunder, now.thunder)) pools.add("weather");

            for (String pool : pools) {
                for (Map.Entry<UUID, List<Listeners.Candidate>> e : listeners.byPlayer().entrySet()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                    if (player == null || player.level() != level) continue;
                    Listeners.Candidate c = pick(e.getValue());
                    if (c == null) continue;
                    speech.offer(player, c, pool);
                }
            }
        }
    }

    /** Offers the villager-death reaction to every nearby listener. */
    public void onVillagerDeath(Villager villager) {
        broadcast(villager, "reaction.villager_death");
    }

    /** Handles opening or otherwise using a villager, not completion of a trade. */
    public void onVillagerInteraction(Villager villager) {
        Integer until = interactionCooldown.get(villager.getUUID());
        if (until != null && tick < until) return;
        interactionCooldown.put(villager.getUUID(), tick + 20);
        // Keep the existing pool key for configuration compatibility even though the
        // available Fabric callback reports interaction rather than trade completion.
        broadcast(villager, "traded");
    }

    private void broadcast(Villager villager, String pool) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        double r2 = (double) config.settings().hearingRange() * config.settings().hearingRange();
        Listeners.Candidate speaker =
                new Listeners.Candidate(villager, Listeners.professionId(villager));
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(villager) > r2) continue;
            speech.offer(player, speaker, pool);
        }
    }

    private Listeners.Candidate pick(List<Listeners.Candidate> candidates) {
        for (Listeners.Candidate c : candidates) {
            if (c.villager().isSleeping()) continue;
            if (c.villager().level() instanceof ServerLevel level) {
                if (level.isRaided(c.villager().blockPosition())) continue;
            }
            return c;
        }
        return null;
    }

    private record Snapshot(long time, boolean rain, boolean thunder) {}
}
