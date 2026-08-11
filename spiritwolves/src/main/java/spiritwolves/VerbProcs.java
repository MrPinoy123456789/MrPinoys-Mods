package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The combat hooks for equipped verbs (SPEC.md sections 18.2 and 18.4): all
 * passive procs on the wolf's own attacks/kills, no keybind. On-hit effects
 * (Emberfang/Venomfang/Bonechill/Witherbite/Ravenous-hit) key off {@code
 * ServerLivingEntityEvents.AFTER_DAMAGE}; Ravenous' on-kill heal is called
 * from {@link WolfKill}; Blinkstrike is positional and polled from
 * {@link Tracker}. Effects applied to the wolf or its target are transient
 * combat state -- nothing here writes into {@code wolfTag}.
 */
final class VerbProcs {

    private static final int BLINK_COOLDOWN_TICKS = 100;
    private static final int BLINK_COOLDOWN_TICKS_TIER2 = 50;
    private static final double BLINK_RANGE = 8.0;
    private static final double FIRE_AURA_RADIUS = 5.0;

    /** Wolf UUID -> tick at which Blinkstrike is next available. */
    private static final Map<UUID, Integer> blinkCooldown = new HashMap<>();

    private VerbProcs() {}

    static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, baseDamageTaken, newDamageTaken, blocked) -> {
            if (newDamageTaken <= 0.0f || !(source.getEntity() instanceof Wolf wolf) || !wolf.isTame()) {
                return;
            }
            if (!(wolf.level() instanceof ServerLevel level)) {
                return;
            }
            ServerPlayer owner = Tracker.findOwner(level, wolf.getUUID());
            if (owner == null) {
                return;
            }
            WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
            if (record == null || !record.summoned) {
                return;
            }

            Assists.mark(wolf, owner, victim);
            onHit(wolf, owner, record, victim, level);
        });
    }

    private static void onHit(Wolf wolf, ServerPlayer owner, WolfRecord record, LivingEntity victim, ServerLevel level) {
        int emberfang = tierOf(record, Verbs.EMBERFANG);
        if (emberfang >= 1) {
            victim.setRemainingFireTicks(Math.max(victim.getRemainingFireTicks(), emberfang == 1 ? 40 : emberfang == 2 ? 80 : 120));
            if (emberfang >= 2 && owner.distanceTo(wolf) <= FIRE_AURA_RADIUS) {
                owner.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 60, 0, true, false));
            }
        }

        int venomfang = tierOf(record, Verbs.VENOMFANG);
        if (venomfang >= 1) {
            int seconds = venomfang == 1 ? 3 : venomfang == 2 ? 5 : 8;
            int amplifier = venomfang >= 2 ? 1 : 0;
            addEffect(victim, MobEffects.POISON, seconds * 20, amplifier);
            if (venomfang >= 3) {
                addEffect(victim, MobEffects.SLOWNESS, seconds * 20, 0);
            }
        }

        int bonechill = tierOf(record, Verbs.BONECHILL);
        if (bonechill >= 1) {
            int seconds = bonechill == 1 ? 2 : 4;
            int amplifier = bonechill >= 2 ? 1 : 0;
            addEffect(victim, MobEffects.SLOWNESS, seconds * 20, amplifier);
            if (bonechill >= 3) {
                addEffect(victim, MobEffects.WEAKNESS, seconds * 20, 0);
            }
        }

        int witherbite = tierOf(record, Verbs.WITHERBITE);
        if (witherbite >= 1) {
            int seconds = witherbite == 1 ? 2 : witherbite == 2 ? 4 : 6;
            int amplifier = witherbite >= 3 ? 1 : 0;
            addEffect(victim, MobEffects.WITHER, seconds * 20, amplifier);
        }

        int ravenous = tierOf(record, Verbs.RAVENOUS);
        if (ravenous >= 2) {
            healOrOverheal(wolf, ravenous, 1.0f);
        }
    }

    /** Ravenous tier I: heal on kill. Called from {@link WolfKill}. */
    static void onKill(Wolf wolf, ServerPlayer owner, WolfRecord record, Entity killed) {
        int ravenous = tierOf(record, Verbs.RAVENOUS);
        if (ravenous >= 1) {
            healOrOverheal(wolf, ravenous, 2.0f);
        }

        int predator = tierOf(record, Verbs.PREDATOR);
        if (predator >= 1 && Verbs.PREDATOR.family.contains(killed.getType())) {
            wolf.heal(predator * 2.0f);
        }
    }

    private static void healOrOverheal(Wolf wolf, int ravenousTier, float amount) {
        float before = wolf.getHealth();
        wolf.heal(amount);
        if (ravenousTier < 3) {
            return;
        }
        float overheal = amount - (wolf.getHealth() - before);
        if (overheal > 0.0f) {
            wolf.setAbsorptionAmount(Math.min(4.0f, wolf.getAbsorptionAmount() + overheal));
        }
    }

    /**
     * Blinkstrike: polled from {@link Tracker} for every summoned wolf, since
     * the trigger is positional rather than a hit/kill event.
     */
    static void tickSummonedWolf(ServerPlayer owner, WolfRecord record, Wolf wolf) {
        int emberfang = tierOf(record, Verbs.EMBERFANG);
        if (emberfang >= 3) {
            // Tier 3 Emberfang makes the wolf immune to fire. Re-apply a long
            // Fire Resistance effect in the periodic tick; it is cleared on recall
            // by WolfCapture.restore(), so this only runs while the wolf is out.
            wolf.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600, 0, true, false));
        }

        int light = tierOf(record, Verbs.LIGHT);
        if (light >= 1) {
            wolf.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, true, false));
        }
        if (light >= 2) {
            double nightVisionRadius = light >= 3 ? 16.0 : 8.0;
            if (owner.distanceTo(wolf) <= nightVisionRadius) {
                owner.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 300, 0, true, false));
            }
        }

        int blinkTier = tierOf(record, Verbs.BLINKSTRIKE);
        if (blinkTier <= 0) {
            return;
        }

        LivingEntity target = wolf.getTarget();
        if (target == null && blinkTier >= 3) {
            target = nearestMonster(wolf);
        }
        if (target == null || wolf.distanceTo(target) <= BLINK_RANGE) {
            return;
        }

        int now = wolf.level().getServer() == null ? 0 : wolf.level().getServer().getTickCount();
        Integer readyAt = blinkCooldown.get(wolf.getUUID());
        if (readyAt != null && now < readyAt) {
            return;
        }

        wolf.snapTo(target.getX(), target.getY(), target.getZ(), wolf.getYRot(), wolf.getXRot());
        blinkCooldown.put(wolf.getUUID(), now + (blinkTier >= 2 ? BLINK_COOLDOWN_TICKS_TIER2 : BLINK_COOLDOWN_TICKS));
    }

    private static LivingEntity nearestMonster(Wolf wolf) {
        Monster nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (Monster candidate : wolf.level().getEntitiesOfClass(Monster.class,
                wolf.getBoundingBox().inflate(16.0), Monster::isAlive)) {
            double distSqr = candidate.distanceToSqr(wolf);
            if (distSqr < nearestDistSqr) {
                nearestDistSqr = distSqr;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** Clears any transient Blinkstrike cooldown for a wolf that is no longer out. */
    static void forget(UUID wolfUuid) {
        blinkCooldown.remove(wolfUuid);
    }

    private static int tierOf(WolfRecord record, Verbs.Verb verb) {
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        return vr == null || !vr.equipped ? 0 : vr.tier;
    }

    private static void addEffect(LivingEntity target, Holder<MobEffect> effect, int durationTicks, int amplifier) {
        target.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
    }
}
