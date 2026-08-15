package kamutotems;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;

import kamutotems.core.AuraOutcome;

/**
 * Wind-up particles and sound before a Bloom pulse lands.
 *
 * <p>This is deliberately one-shot: {@link AuraHost} calls it once at the
 * start of the telegraph. The pulse itself is applied separately by
 * {@link AuraHost#applyPulse}.
 */
public final class AuraTelegraph {

    private AuraTelegraph() {}

    public static void telegraph(ServerLevel level, LivingEntity bearer,
                                 AuraOutcome outcome, double radius) {
        if (level == null || bearer == null || outcome == null) {
            return;
        }

        String id = outcome.effectId();
        ParticleOptions particle = switch (id) {
            case "fire" -> ParticleTypes.FLAME;
            case "ice" -> ParticleTypes.SNOWFLAKE;
            case "poison" -> ParticleTypes.WITCH;
            case "wither" -> ParticleTypes.SMOKE;
            case "lightning" -> ParticleTypes.ELECTRIC_SPARK;
            case "heal" -> ParticleTypes.HEART;
            case "absorption" -> ParticleTypes.HAPPY_VILLAGER;
            default -> ParticleTypes.END_ROD;
        };

        int count = KamuTotemsConfig.i("combat", "telegraph_particles", 20);
        double cx = bearer.getX();
        double cy = bearer.getY() + bearer.getBbHeight() * 0.5;
        double cz = bearer.getZ();

        // Particles spiral up from the bearer to the pulse radius.
        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * Math.PI * 2.0;
            double px = cx + Math.cos(angle) * radius * 0.5;
            double pz = cz + Math.sin(angle) * radius * 0.5;
            double py = cy + (i / (double) count) * 1.5;
            // ⚠ UNVERIFIED: ServerLevel.sendParticles overload used by spiritwolves/Tricks
            level.sendParticles(particle, true, true,
                    px, py, pz, 1, 0.1, 0.1, 0.1, 0.0);
        }

        // ⚠ UNVERIFIED: SoundEvents.BEACON_POWER_SELECT in 26.2
        level.playSound(null, cx, cy, cz,
                SoundEvents.BEACON_POWER_SELECT, bearer.getSoundSource(),
                0.8f, 1.0f);
    }
}
