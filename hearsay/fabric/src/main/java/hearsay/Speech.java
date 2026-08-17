package hearsay;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import hearsay.core.RateLimit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The chat budget: per-listener rate limit, per-speaker cooldown, raid and
 * damage hush, and channel routing.
 */
public final class Speech {

    private final HearsayConfig config;
    private final Bubbles bubbles;
    private final Scenes scenes;
    private final Mute mute;
    private final Random random = new Random();
    private final Map<UUID, RateLimit> perListener = new HashMap<>();
    private final Map<UUID, RateLimit> perSpeaker = new HashMap<>();
    private final Map<UUID, Integer> damagedUntil = new HashMap<>();
    private final Map<String, RateLimit> greetingCooldown = new HashMap<>();
    private int tick;

    public Speech(HearsayConfig config, Bubbles bubbles, Scenes scenes, Mute mute) {
        this.config = config;
        this.bubbles = bubbles;
        this.scenes = scenes;
        this.mute = mute;
    }

    public void hush(UUID player, int seconds) {
        damagedUntil.put(player, tick + seconds * 20);
    }

    public void tick(MinecraftServer server, Listeners listeners) {
        tick++;
        bubbles.tick();

        Map<UUID, Pick> picks = new HashMap<>();

        for (Map.Entry<UUID, List<Listeners.Candidate>> e : listeners.byPlayer().entrySet()) {
            UUID listenerId = e.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(listenerId);
            if (player == null || player.isRemoved() || !player.isAlive()) continue;

            // Muted players hear nothing ambient.
            if (mute.isMuted(listenerId)) continue;

            // A running two-hander holds the floor.
            if (scenes.isBusyListener(listenerId)) continue;

            // Per-listener budget first.
            RateLimit listenerBudget = perListener.computeIfAbsent(listenerId,
                    u -> new RateLimit(config.settings().quietSeconds() * 20));
            if (!listenerBudget.allow(tick)) continue;

            // Damage hush.
            Integer hush = damagedUntil.get(listenerId);
            if (hush != null && tick < hush) continue;

            Pick chosen = choose(e.getValue());
            if (chosen == null) continue;

            // Per-speaker cooldown is on top.
            RateLimit speakerBudget = perSpeaker.computeIfAbsent(chosen.candidate().villager().getUUID(),
                    u -> new RateLimit(config.settings().speakerCooldownSeconds() * 20));
            if (!speakerBudget.allow(tick)) continue;

            // Raid suppression.
            Villager v = chosen.candidate().villager();
            if (v.level() instanceof ServerLevel level) {
                if (level.isRaided(v.blockPosition())) continue;
            }

            // Sleeping villagers do not speak.
            if (v.isSleeping()) continue;

            picks.put(listenerId, chosen);
        }

        for (Map.Entry<UUID, Pick> e : picks.entrySet()) {
            UUID listenerId = e.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(listenerId);
            if (player == null || player.isRemoved() || !player.isAlive()) continue;
            deliver(player, e.getValue());
        }
    }

    private Pick choose(List<Listeners.Candidate> candidates) {
        List<Listeners.Candidate> available = new ArrayList<>();
        for (Listeners.Candidate c : candidates) {
            Villager v = c.villager();
            if (v.isSleeping() || !v.isAlive() || scenes.isBusy(v.getUUID())) continue;
            if (v.level() instanceof ServerLevel level && level.isRaided(v.blockPosition())) continue;
            available.add(c);
        }
        if (available.isEmpty()) return null;

        // Greeting has priority: the first candidate this listener has not
        // recently been near becomes the chosen line.
        for (Listeners.Candidate c : available) {
            String key = c.villager().getUUID().toString();
            RateLimit seen = greetingCooldown.computeIfAbsent(key,
                    u -> new RateLimit(config.settings().greetingCooldownSeconds() * 20));
            if (seen.allow(tick)) {
                return new Pick(c, "greeting");
            }
        }

        // Otherwise, only ambient lines are eligible and only by chance.
        if (random.nextDouble() >= config.settings().ambientChance()) return null;

        Listeners.Candidate c = available.get(random.nextInt(available.size()));
        return new Pick(c, "ambient");
    }

    public void forceSay(ServerPlayer player, Listeners.Candidate candidate, String pool) {
        deliver(player, new Pick(candidate, pool));
    }

    /**
     * Offer an event-driven line to one listener, subject to the same budget as
     * ambient chatter: mute, an active scene, the damage and raid hushes, and the
     * per-listener rate limit all apply (PLAN.md M4 — "reactions are not exempt").
     *
     * <p>The rate limit is charged last, so a reaction whose pool is empty or whose
     * speaker is unusable does not burn the listener's budget.
     *
     * @return true if a line was actually delivered
     */
    public boolean offer(ServerPlayer player, Listeners.Candidate candidate, String pool) {
        UUID listenerId = player.getUUID();
        if (player.isRemoved() || !player.isAlive()) return false;
        if (mute.isMuted(listenerId)) return false;
        if (scenes.isBusyListener(listenerId)) return false;

        Integer hush = damagedUntil.get(listenerId);
        if (hush != null && tick < hush) return false;

        Villager v = candidate.villager();
        if (v.isSleeping()) return false;
        if (v.level() instanceof ServerLevel level && level.isRaided(v.blockPosition())) return false;

        String line = config.pools().pickFor(candidate.professionId(), pool, random,
                config.settings().professionLineChance());
        if (line.isBlank()) return false;

        // A bubble hangs off the speaker's body, so it needs a living speaker. A chat
        // or actionbar line only needs the profession the line came from — which is
        // what lets the village react to a villager's own death.
        String channel = channelFor(pool);
        if ("bubble".equals(channel) && (v.isRemoved() || !v.isAlive())) return false;

        RateLimit budget = perListener.computeIfAbsent(listenerId,
                u -> new RateLimit(config.settings().quietSeconds() * 20));
        if (!budget.allow(tick)) return false;

        send(player, v, line, channel);
        return true;
    }

    private void deliver(ServerPlayer player, Pick pick) {
        String line = config.pools().pickFor(pick.candidate().professionId(), pick.pool(), random,
                config.settings().professionLineChance());
        if (line.isBlank()) return;
        send(player, pick.candidate().villager(), line, channelFor(pick.pool()));
    }

    private void send(ServerPlayer player, Villager speaker, String line, String channel) {
        if ("bubble".equals(channel)) {
            if (speaker.level() instanceof ServerLevel level) {
                bubbles.say(level, speaker, line);
            }
        } else if ("chat".equals(channel)) {
            player.sendSystemMessage(Component.literal(line)
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY), false);
        } else {
            player.sendSystemMessage(Component.literal(line), true);
        }
    }

    /**
     * The channel for a pool: an exact match first, then the family before the dot,
     * so every {@code reaction.*} pool inherits the single {@code reaction} setting
     * instead of silently falling through to a hardcoded default.
     */
    private String channelFor(String pool) {
        Map<String, String> channels = config.settings().channel();
        String exact = channels.get(pool);
        if (exact != null) return exact;
        int dot = pool.indexOf('.');
        if (dot > 0) {
            String family = channels.get(pool.substring(0, dot));
            if (family != null) return family;
        }
        return "actionbar";
    }

    private record Pick(Listeners.Candidate candidate, String pool) {}
}
