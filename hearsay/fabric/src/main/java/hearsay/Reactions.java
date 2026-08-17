package hearsay;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import hearsay.core.Triggers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off reactions to world events, trades, time and weather.
 *
 * <p>Time and weather use the pure {@link Triggers} helpers from core.
 * Trades are detected via the best available entity-interaction event.
 */
public final class Reactions {

    private final HearsayConfig config;
    private final Speech speech;
    private final Map<ServerLevel, Snapshot> prev = new HashMap<>();
    private final Map<UUID, Integer> tradedCooldown = new HashMap<>();
    private int tick;

    public Reactions(HearsayConfig config, Speech speech) {
        this.config = config;
        this.speech = speech;
    }

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

    public void onVillagerDeath(Villager villager) {
        broadcast(villager, "reaction.villager_death");
    }

    public void onTrade(ServerPlayer player, Villager villager) {
        Integer until = tradedCooldown.get(villager.getUUID());
        if (until != null && tick < until) return;
        tradedCooldown.put(villager.getUUID(), tick + 20);
        // The pool is "traded", not "reaction.traded" — asking for the latter
        // resolved to nothing, so completed trades were silent.
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
