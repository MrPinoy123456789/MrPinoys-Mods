package wayfarers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import wayfarers.core.RateLimit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Per-encounter dialogue delivery: open lines on spawn, periodic during lines,
 * and optional gossip. Speech routes to floating bubbles, narration to chat,
 * and combat suppresses the whole encounter (SPEC.md §5.3).
 */
public final class Dialogue {

    private static final int BETWEEN_LINES = 80;
    private static final int RATE_LIMIT_TICKS = 40;
    private static final int DAMAGE_HUSH = 60;
    private static final double SPEECH_RANGE = 16.0;

    private final WayfarersConfig config;
    private final Bubbles bubbles;
    private final Random random = new Random();
    private final Map<UUID, RateLimit> perListener = new HashMap<>();
    private final Map<UUID, Integer> damagedUntil = new HashMap<>();
    private int tick;

    public Dialogue(WayfarersConfig config, Bubbles bubbles) {
        this.config = config;
        this.bubbles = bubbles;
    }

    public void tick(Encounters encounters) {
        tick++;
        bubbles.tick();
        Collection<Encounters.Active> actives = encounters.actives();
        for (Encounters.Active active : actives) {
            if (active.entity.isRemoved()) continue;
            if (combatNearby(active)) continue;

            if (active.lastLineTick == 0) {
                String moment = "open";
                if ("hostile".equals(active.def.template())) {
                    moment = isWearingGold(active.player) ? "placated" : "open";
                }
                speak(active, moment, false);
                active.lastLineTick = tick;
                continue;
            }

            if (tick - active.lastLineTick < BETWEEN_LINES) continue;

            boolean gossip = random.nextDouble() < active.def.gossipChance();
            if (gossip) {
                speak(active, "gossip", false);
            } else {
                speak(active, "during", false);
            }
            active.lastLineTick = tick;
        }
    }

    private boolean combatNearby(Encounters.Active active) {
        if (!(active.entity.level() instanceof ServerLevel level)) return false;
        double r2 = SPEECH_RANGE * SPEECH_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(active.entity) > r2) continue;
            Integer hush = damagedUntil.get(player.getUUID());
            if (hush != null && tick < hush) return true;
        }
        return false;
    }

    void speak(Encounters.Active active, String moment, boolean narrate) {
        String pool = active.def.dialogue().isBlank() ? active.def.id() : active.def.dialogue();
        String line = config.lines().pickFor(pool, moment, random);
        if (line.isBlank() && !"gossip".equals(moment)) {
            return;
        }
        if (line.isBlank()) {
            line = config.lines().pickFor(pool, "during", random);
            if (line.isBlank()) return;
        }

        if (narrate) {
            narrate(active, line);
        } else {
            say(active, line);
        }
    }

    private void say(Encounters.Active active, String line) {
        if (!(active.entity.level() instanceof ServerLevel level)) return;
        bubbles.say(level, active.entity, line);
    }

    private void narrate(Encounters.Active active, String line) {
        if (!(active.entity.level() instanceof ServerLevel level)) return;
        Component message = Component.literal(line).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
        double r2 = SPEECH_RANGE * SPEECH_RANGE;
        for (ServerPlayer listener : level.players()) {
            if (listener.level() != level) continue;
            if (listener.distanceToSqr(active.entity) > r2) continue;

            RateLimit rl = perListener.computeIfAbsent(listener.getUUID(), u -> new RateLimit(RATE_LIMIT_TICKS));
            if (!rl.allow(tick)) continue;

            Integer hush = damagedUntil.get(listener.getUUID());
            if (hush != null && tick < hush) continue;

            listener.sendSystemMessage(message);
        }
    }

    /** Speak a death line when an encounter body is killed. */
    public void onDeath(Encounters.Active active) {
        if (active == null || active.entity.isRemoved()) return;
        String pool = active.def.dialogue().isBlank() ? active.def.id() : active.def.dialogue();
        String line = config.lines().pickFor(pool, "attack", random);
        if (line.isBlank()) {
            line = config.lines().pickFor(pool, "deny", random);
        }
        if (!line.isBlank()) {
            narrate(active, line);
        }
    }

    private static boolean isWearingGold(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.is(Items.GOLDEN_HELMET)
                    || stack.is(Items.GOLDEN_CHESTPLATE)
                    || stack.is(Items.GOLDEN_LEGGINGS)
                    || stack.is(Items.GOLDEN_BOOTS)) {
                return true;
            }
        }
        return false;
    }

    /** Silence a player for a short time after they take damage. */
    public void hush(UUID player, int ticks) {
        damagedUntil.put(player, tick + ticks);
    }

    public void hush(ServerPlayer player) {
        hush(player.getUUID(), DAMAGE_HUSH);
    }
}
