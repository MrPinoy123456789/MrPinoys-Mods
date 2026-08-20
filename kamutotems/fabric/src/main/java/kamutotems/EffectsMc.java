package kamutotems;

import kamutotems.core.Effect;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Translates resolved core {@link Effect}s into actual Minecraft behaviour.
 *
 * <p>This is the boundary: {@code kamutotems.core} decides <em>what</em>
 * happens and in what order, and knows nothing about Minecraft. This class
 * decides <em>how</em>, and contains no rules.
 *
 * <p>Tier scaling is already applied by the core resolver. Do not scale a
 * second time here.
 *
 * <p>Every effect id emitted by the new catalog and by the reaction table must
 * have a case below. An unknown id is logged once and skipped, never thrown.
 */
public final class EffectsMc {

    private EffectsMc() {}

    public static void apply(List<Effect> effects, ServerLevel level,
                             LivingEntity source, Entity target) {
        if (effects == null || level == null || source == null) {
            return;
        }
        for (Effect e : effects) {
            if (e == null || e.effectId() == null) {
                continue;
            }
            try {
                applyOne(e, level, source, target);
            } catch (RuntimeException ex) {
                // One bad effect must not abort the rest of the resolution.
                KamuTotemsMod.LOG.error("Effect '{}' failed", e.effectId(), ex);
            }
        }
    }

    private static void applyOne(Effect e, ServerLevel level,
                                 LivingEntity source, Entity target) {
        LivingEntity living = (target instanceof LivingEntity l) ? l : null;
        Map<String, Double> p = e.parameters();
        boolean aura = num(p, "aura", 0.0) > 0.0;
        boolean boon = num(p, "boon", 0.0) > 0.0;
        double power = num(p, "power", KamuTotemsConfig.d("combat", "base_damage", 2.0));

        switch (e.effectId()) {

            // ---- Elements -------------------------------------------------
            // All five elements are BANE only (KamuCatalog): no resistances,
            // no immunities, so every case fires unconditionally -- a
            // construct hit, or the bane target of a Bloom pulse/Rebuke.
            case "fire" -> {
                if (living != null) {
                    living.igniteForSeconds((float) num(p, "seconds",
                            KamuTotemsConfig.d("combat", "fire_seconds", 4.0)));
                    level.playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.GENERIC_BURN, living.getSoundSource(),
                            0.5f, 1.0f);
                }
            }
            case "ice" -> {
                if (living != null) {
                    living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                            (int) num(p, "ticks", KamuTotemsConfig.i("combat", "ice_ticks", 120)),
                            (int) num(p, "amplifier", 0), true, false));
                    level.playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.PLAYER_HURT_FREEZE, living.getSoundSource(),
                            0.5f, 1.0f);
                }
            }
            case "poison" -> {
                if (living != null) {
                    living.addEffect(new MobEffectInstance(MobEffects.POISON,
                            (int) num(p, "ticks", KamuTotemsConfig.i("combat", "poison_ticks", 200)),
                            (int) num(p, "amplifier", 0), true, false));
                    level.playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.SPLASH_POTION_BREAK, living.getSoundSource(),
                            0.4f, 1.4f);
                }
            }
            case "wither" -> {
                if (living != null) {
                    living.addEffect(new MobEffectInstance(MobEffects.WITHER,
                            (int) num(p, "ticks", KamuTotemsConfig.i("combat", "wither_ticks", 160)),
                            (int) num(p, "amplifier", 0), true, false));
                    level.playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.SOUL_ESCAPE, living.getSoundSource(),
                            0.6f, 0.8f);
                }
            }
            case "lightning" -> {
                if (living != null) {
                    hurt(living, source, power);
                    jolt(living, source, num(p, "jolt",
                            KamuTotemsConfig.d("combat", "lightning_jolt", 0.8)));
                    // Impact crack instead of the long rumbling thunder --
                    // a hit proc needs a snap, not several seconds of boom.
                    level.playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.LIGHTNING_BOLT_IMPACT, living.getSoundSource(),
                            0.5f, 1.15f);
                }
            }

            // ---- Behaviours -----------------------------------------------
            case "heal" -> {
                // construct: lifesteal, always to the source.
                // aura: health-over-time; the recipient is the target.
                LivingEntity recipient = aura ? (living != null ? living : source) : source;
                boolean instant = num(p, "instant", 0.0) > 0.0;
                if (instant) {
                    recipient.heal((float) power);
                } else if (aura) {
                    recipient.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
                            (int) num(p, "ticks", KamuTotemsConfig.i("combat", "heal_aura_ticks", 60)),
                            (int) num(p, "amplifier", 0), true, false));
                } else {
                    recipient.heal((float) power);
                }
            }
            case "absorption" -> {
                LivingEntity recipient = aura ? (living != null ? living : source) : source;
                float add = (float) power;
                float cap = (float) num(p, "cap",
                        KamuTotemsConfig.d("combat", "absorption_cap", 10.0));
                float current = recipient.getAbsorptionAmount();
                recipient.setAbsorptionAmount(Math.min(cap, current + add));
            }

            // ---- Reaction outcomes ----------------------------------------
            case "thermal_shock" -> {
                if (living != null) {
                    living.clearFire();
                    hurt(living, source, power);
                }
            }
            case "melt" -> {
                if (living != null) {
                    living.clearFire();
                    living.removeEffect(MobEffects.SLOWNESS);
                }
            }
            case "conduct" -> {
                double radius = num(p, "radius", KamuTotemsConfig.d("combat", "conduct_radius", 5.0));
                for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class,
                        boxAround(target != null ? target : source, radius))) {
                    if (near == source || !near.isAlive()) {
                        continue;
                    }
                    hurt(near, source, power);
                }
            }
            case "shatter" -> {
                if (living != null) {
                    hurt(living, source, power);
                    jolt(living, source, num(p, "jolt",
                            KamuTotemsConfig.d("combat", "shatter_jolt", 0.6)));
                }
            }
            case "toxic_flame" -> {
                if (living != null) {
                    living.addEffect(new MobEffectInstance(MobEffects.POISON,
                            (int) (num(p, "ticks", KamuTotemsConfig.i("combat", "poison_ticks", 200)) / 2.0),
                            (int) num(p, "amplifier", 0) + 1, true, false));
                    living.igniteForSeconds((float) num(p, "seconds",
                            KamuTotemsConfig.d("combat", "toxic_flame_seconds", 2.0)));
                }
            }
            case "tainted" -> {
                if (living != null) {
                    hurt(living, source, power);
                    double ratio = num(p, "ratio", 0.35) * 0.5;
                    source.heal((float) (power * ratio));
                }
            }

            // ---- Defaults -------------------------------------------------
            case "hit", "plain" -> { }

            default -> KamuTotemsMod.LOG.warn(
                    "No Minecraft translation for effect id '{}'", e.effectId());
        }
    }

    private static void hurt(LivingEntity victim, LivingEntity source, double amount) {
        if (victim == null || amount <= 0) {
            return;
        }
        // ⚠ UNVERIFIED: whether mobAttack(LivingEntity) survives 26.2's rename
        // pass and remains the right source for non-player casters.
        victim.hurtServer(
                (ServerLevel) victim.level(),
                source != null
                        ? victim.damageSources().mobAttack(source)
                        : victim.damageSources().magic(),
                (float) amount);
    }

    private static void jolt(LivingEntity victim, LivingEntity source, double strength) {
        if (victim == null || source == null || strength <= 0) {
            return;
        }
        Vec3 away = victim.position().subtract(source.position());
        double len = away.horizontalDistance();
        if (len < 0.001) {
            away = new Vec3(0, 0, 1);
        } else {
            away = away.scale(1.0 / len);
        }
        away = away.scale(strength);
        victim.push(away.x, 0.35, away.z);
        victim.hurtMarked = true;
    }

    private static AABB boxAround(Entity centre, double r) {
        return centre.getBoundingBox().inflate(r);
    }

    private static double num(Map<String, Double> p, String key, double fallback) {
        if (p == null) {
            return fallback;
        }
        Double v = p.get(key);
        return v == null ? fallback : v;
    }
}
