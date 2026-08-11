package chatdonkey;

import chatdonkey.core.Behaviors;
import chatdonkey.core.DonkeyBehavior;
import chatdonkey.core.EndReason;
import chatdonkey.core.EventDefinition;
import chatdonkey.core.EventPool;
import chatdonkey.core.Gift;
import chatdonkey.core.GiftSettings;
import chatdonkey.core.GiftTable;
import chatdonkey.core.LinePools;
import chatdonkey.core.Treat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.equine.Donkey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The event runner: starts events, ticks them, and ends them exactly once.
 *
 * <p>Holds no persistent state (SPEC.md section 2) -- a restart forgets every
 * running event, and the donkey it forgets stops being immortal and reverts to
 * an ordinary, killable, entirely harmless vanilla donkey.
 */
public final class Events {

    /** At most one event per player. */
    private final Map<UUID, ActiveEvent> active = new HashMap<>();

    private final DonkeyConfig config;
    private final Triggers triggers;
    private final Voice voice;
    private final Random random = new Random();

    public Events(DonkeyConfig config, Triggers triggers, Voice voice) {
        this.config = config;
        this.triggers = triggers;
        this.voice = voice;
    }

    public boolean isActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public int activeCount() {
        return active.size();
    }

    /**
     * The event this entity is the donkey of, or {@code null}.
     *
     * <p>On the damage hot path for every mob on the server, so it
     * short-circuits on the overwhelmingly common case of nothing running.
     */
    public ActiveEvent eventForDonkey(UUID entityId) {
        if (active.isEmpty()) {
            return null;
        }
        for (ActiveEvent event : active.values()) {
            if (event.donkey().getUUID().equals(entityId)) {
                return event;
            }
        }
        return null;
    }

    public boolean isEventDonkey(UUID entityId) {
        return eventForDonkey(entityId) != null;
    }

    /**
     * Starts a weighted-random event from {@code events.json}.
     *
     * @return false if nowhere suitable was found, in which case the caller
     *         silently re-rolls (SPEC.md section 6)
     */
    public boolean start(ServerPlayer player) {
        EventDefinition chosen = config.events().pick(random);
        if (chosen == null) {
            return false;
        }
        DonkeyBehavior behavior = Behaviors.byId(chosen.behavior());
        if (behavior == null) {
            return false;
        }
        return start(player, behavior, EventPool.durationTicks(chosen, random));
    }

    /** Starts a specific behavior at its own default duration range. */
    public boolean start(ServerPlayer player, DonkeyBehavior behavior) {
        return start(player, behavior, durationFor(behavior));
    }

    /**
     * The duration an explicitly-triggered behavior runs for: whatever
     * {@code events.json} says, falling back to the behavior's own range if the
     * operator removed that entry.
     */
    private int durationFor(DonkeyBehavior behavior) {
        for (EventDefinition entry : config.events().entries()) {
            if (entry.behavior().equals(behavior.id())) {
                return EventPool.durationTicks(entry, random);
            }
        }
        return Behaviors.rollDurationTicks(behavior, random);
    }

    /** Starts a specific behavior -- the {@code /donkey trigger [event]} path. */
    public boolean start(ServerPlayer player, DonkeyBehavior behavior, int duration) {
        if (active.containsKey(player.getUUID())) {
            return false;
        }

        LinePools lines = config.lines();
        String name = lines.has("names") ? lines.pick("names", random) : "Duncan";

        Donkey donkey = DonkeySpawn.spawnNear(player, name, random);
        if (donkey == null) {
            ChatDonkeyMod.LOG.debug("No spawnable spot near {}, skipping the event",
                    player.getName().getString());
            return false;
        }

        ActiveEvent event = new ActiveEvent(player, donkey, behavior, lines, name, duration,
                random, voice);
        active.put(player.getUUID(), event);
        triggers.state(player).markEventStarted();

        behavior.start(event);
        ChatDonkeyMod.LOG.info("{} is being bothered by {} ({}) for {}s",
                player.getName().getString(), name, behavior.id(), duration / 20);
        return true;
    }

    /** Called once per server tick. */
    public void tickAll() {
        if (active.isEmpty()) {
            return;
        }
        // The reason is decided where the ending is detected -- deriving it
        // afterwards loses the difference between "ran out of time" and "the
        // behavior got what it wanted".
        List<Map.Entry<ActiveEvent, EndReason>> finished = new ArrayList<>();

        for (ActiveEvent event : active.values()) {
            // The donkey was removed by something outside this mod, the player
            // vanished from under it, or the player left the dimension -- a
            // portal strands the donkey behind, and every distance the behavior
            // computes from there is meaningless because the coordinates are in
            // two different worlds.
            if (event.donkeyGone() || event.player().isRemoved() || event.separated()) {
                finished.add(Map.entry(event, EndReason.ABORTED));
                continue;
            }

            event.advanceTick();
            event.behavior().tick(event);

            if (event.behavior().wantsEarlyEnd(event)) {
                finished.add(Map.entry(event, EndReason.SATISFIED));
            } else if (event.expired()) {
                finished.add(Map.entry(event, EndReason.WAITED));
            }
        }

        for (Map.Entry<ActiveEvent, EndReason> entry : finished) {
            end(entry.getKey(), entry.getValue());
        }
    }

    /** Ends a player's event if they have one. Used by logout, death, and admin commands. */
    public void endFor(UUID playerId, EndReason reason) {
        ActiveEvent event = active.get(playerId);
        if (event != null) {
            end(event, reason);
        }
    }

    private void end(ActiveEvent event, EndReason reason) {
        active.remove(event.playerId());

        ServerPlayer player = event.player();
        boolean playerStillHere = !player.isRemoved();

        if (!playerStillHere || reason == EndReason.ABORTED) {
            // Nobody is left to hear the rest of the sentence.
            voice.silence(event.donkey().getUUID());
        }

        if (playerStillHere) {
            event.behavior().end(event, reason);

            if (reason.givesGift()) {
                Gift gift = giftFor(event, reason);
                if (!gift.isEmpty()) {
                    Rewards.giveGift(player, gift);
                    Chime.gift(player);
                }
            }
        }

        if (!event.donkeyGone()) {
            event.poof();
            event.bray();
            event.donkey().discard();
        }

        // An aborted event levies no cooldown penalty (SPEC.md section 6).
        if (reason.startsCooldown()) {
            triggers.state(player).markEventEnded(System.currentTimeMillis());
        } else {
            triggers.clearEventFlag(player.getUUID());
        }
    }

    /**
     * The gift this event earned.
     *
     * <p>A fed Food Critic pays by treat rather than by roll, so a golden carrot
     * reliably earns the golden tier instead of hoping for a 2% upgrade.
     */
    private Gift giftFor(ActiveEvent event, EndReason reason) {
        GiftSettings gifts = config.settings().giftsOrDefault();
        Treat fed = event.fedTreat();
        if (reason == EndReason.SATISFIED && fed != null) {
            return GiftTable.selectFed(fed, event.hitCount(), random, gifts);
        }
        return GiftTable.select(reason, event.hitCount(), random, gifts);
    }

    /** Ends everything, e.g. on shutdown, leaving no immortal donkeys behind. */
    public void endAll() {
        for (ActiveEvent event : new ArrayList<>(active.values())) {
            end(event, EndReason.ABORTED);
        }
    }
}
