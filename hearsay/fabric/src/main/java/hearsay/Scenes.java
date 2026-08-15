package hearsay;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import hearsay.core.Scene;
import hearsay.core.Script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Two-hander scene scheduler. Finds matching villager pairs, claims them and the
 * listener for the duration of a scene, and drives the script one step per tick.
 */
public final class Scenes {

    private final HearsayConfig config;
    private final Bubbles bubbles;
    private final Random random = new Random();
    private final Map<UUID, Active> active = new HashMap<>();
    private final Set<UUID> busyListeners = new HashSet<>();
    private final Set<UUID> busySpeakers = new HashSet<>();
    private int tick;

    public Scenes(HearsayConfig config, Bubbles bubbles) {
        this.config = config;
        this.bubbles = bubbles;
    }

    public boolean isBusyListener(UUID listener) {
        return busyListeners.contains(listener);
    }

    public boolean isBusy(UUID villager) {
        return busySpeakers.contains(villager);
    }

    public Set<UUID> busyListeners() {
        return Collections.unmodifiableSet(busyListeners);
    }

    public Set<UUID> busySpeakers() {
        return Collections.unmodifiableSet(busySpeakers);
    }

    public void tick(MinecraftServer server, Listeners listeners) {
        tick++;

        // Advance running scenes.
        Map<UUID, Active> still = new HashMap<>();
        for (Map.Entry<UUID, Active> e : active.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Active a = e.getValue();
            if (player == null || player.isRemoved() || !player.isAlive()
                    || !a.isValid()) {
                busySpeakers.remove(a.speaker0.villager().getUUID());
                busySpeakers.remove(a.speaker1.villager().getUUID());
                busyListeners.remove(e.getKey());
                bubbles.clear(a.speaker0.villager().getUUID());
                bubbles.clear(a.speaker1.villager().getUUID());
                continue;
            }

            if (tick >= a.nextTick) {
                if (a.stepIndex >= a.script.steps().size()) {
                    busySpeakers.remove(a.speaker0.villager().getUUID());
                    busySpeakers.remove(a.speaker1.villager().getUUID());
                    busyListeners.remove(e.getKey());
                    continue;
                }
                Script.Step step = a.script.steps().get(a.stepIndex);
                a.stepIndex++;

                int wait = execute(player, a, step);
                a.nextTick = tick + wait;
            }
            still.put(e.getKey(), a);
        }
        active.clear();
        active.putAll(still);

        // Try to start new scenes.
        if (active.size() >= config.settings().maxSimultaneousScenes()) return;

        for (Map.Entry<UUID, List<Listeners.Candidate>> e : listeners.byPlayer().entrySet()) {
            UUID listenerId = e.getKey();
            if (busyListeners.contains(listenerId)) continue;
            if (random.nextDouble() >= config.settings().sceneChance()) continue;

            List<Listeners.Candidate> available = new ArrayList<>();
            for (Listeners.Candidate c : e.getValue()) {
                Villager v = c.villager();
                if (v.isSleeping() || !v.isAlive() || busySpeakers.contains(v.getUUID())) continue;
                if (v.level() instanceof ServerLevel level && level.isRaided(v.blockPosition())) continue;
                available.add(c);
            }

            Optional<Found> found = findScene(available);
            if (found.isEmpty()) continue;

            Found f = found.get();
            Active a = new Active(f.scene, f.speaker0, f.speaker1);
            active.put(listenerId, a);
            busyListeners.add(listenerId);
            busySpeakers.add(f.speaker0.villager().getUUID());
            busySpeakers.add(f.speaker1.villager().getUUID());
            if (active.size() >= config.settings().maxSimultaneousScenes()) return;
        }
    }

    public boolean forceStart(UUID listenerId, List<Listeners.Candidate> candidates) {
        if (busyListeners.contains(listenerId)) return false;

        List<Listeners.Candidate> available = new ArrayList<>();
        for (Listeners.Candidate c : candidates) {
            Villager v = c.villager();
            if (v.isSleeping() || !v.isAlive() || busySpeakers.contains(v.getUUID())) continue;
            if (v.level() instanceof ServerLevel level && level.isRaided(v.blockPosition())) continue;
            available.add(c);
        }

        Optional<Found> found = findScene(available);
        if (found.isEmpty()) return false;

        Found f = found.get();
        Active a = new Active(f.scene, f.speaker0, f.speaker1);
        active.put(listenerId, a);
        busyListeners.add(listenerId);
        busySpeakers.add(f.speaker0.villager().getUUID());
        busySpeakers.add(f.speaker1.villager().getUUID());
        return true;
    }

    private Optional<Found> findScene(List<Listeners.Candidate> available) {
        List<Found> all = new ArrayList<>();
        for (int i = 0; i < available.size(); i++) {
            for (int j = i + 1; j < available.size(); j++) {
                Listeners.Candidate a = available.get(i);
                Listeners.Candidate b = available.get(j);
                for (Scene scene : config.scenes()) {
                    Optional<Scene.Assignment> match = scene.match(a.professionId(), b.professionId());
                    if (match.isPresent()) {
                        Listeners.Candidate s0 = match.get().swapped() ? b : a;
                        Listeners.Candidate s1 = match.get().swapped() ? a : b;
                        all.add(new Found(scene, s0, s1));
                    }
                }
            }
        }
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(random.nextInt(all.size())));
    }

    private int execute(ServerPlayer player, Active a, Script.Step step) {
        switch (step.kind()) {
            case SAY -> {
                Listeners.Candidate speaker = step.speaker() == 0 ? a.speaker0 : a.speaker1;
                if (speaker.villager().level() instanceof ServerLevel level) {
                    bubbles.say(level, speaker.villager(), step.text());
                }
                return 0;
            }
            case NARRATE -> {
                boolean actionbar = !"chat".equals(a.scene.channel());
                player.sendSystemMessage(Component.literal(step.text())
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY), actionbar);
                return 0;
            }
            case WAIT -> {
                return step.waitTicks();
            }
            case ACTION -> {
                player.sendSystemMessage(Component.literal(step.action()), true);
                return 0;
            }
        }
        return 0;
    }

    private record Found(Scene scene, Listeners.Candidate speaker0, Listeners.Candidate speaker1) {}

    private static final class Active {
        final Scene scene;
        final Listeners.Candidate speaker0;
        final Listeners.Candidate speaker1;
        final String channel;
        final Script script;
        int stepIndex;
        int nextTick;

        Active(Scene scene, Listeners.Candidate speaker0, Listeners.Candidate speaker1) {
            this.scene = scene;
            this.speaker0 = speaker0;
            this.speaker1 = speaker1;
            this.channel = scene.channel();
            this.script = scene.script();
            this.stepIndex = 0;
            this.nextTick = 0;
        }

        boolean isValid() {
            return speaker0.villager().isAlive() && speaker1.villager().isAlive();
        }
    }
}
