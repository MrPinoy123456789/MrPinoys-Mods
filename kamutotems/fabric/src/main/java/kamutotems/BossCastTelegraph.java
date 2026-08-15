package kamutotems;

import kamutotems.core.Kamu;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * The three-beat telegraph for a boss cast: particles accumulate during the
 * wind-up, a sound fires at payoff, then EffectsMc lands. The pause is the
 * animation: the caller is expected to freeze the mob during the wind-up.
 *
 * <p>Some {@link ParticleTypes} and {@link SoundEvents} constants below are not
 * proven in the suite's existing files; they are marked per the instruction in
 * PLAN_COMBAT §5 and listed in the agent report.
 */
public final class BossCastTelegraph {

    private BossCastTelegraph() {}

    /**
     * Accumulates particles at the boss for one wind-up tick.
     */
    public static void tick(ServerLevel level, Entity source, Kamu kamu,
                            int progress, int windup) {
        if (source == null || kamu == null || level == null) {
            return;
        }

        double ratio = (double) progress / Math.max(1, windup);
        int count = Math.max(1, 2 + (int) (8 * ratio));
        double speed = 0.01 + 0.06 * ratio;
        double radius = 0.4 + 1.2 * ratio;
        double y = source.getY() + source.getEyeHeight() * 0.5;
        ParticleOptions p = particleFor(kamu.id());

        for (int i = 0; i < count; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            double x = source.getX() + Math.cos(angle) * radius;
            double z = source.getZ() + Math.sin(angle) * radius;
            // ⚠ UNVERIFIED: these ParticleTypes constants are common vanilla but
            // not duplicated in the suite's verified call sites.
            level.sendParticles(p, x, y, z, 1, 0.0, 0.05, 0.0, speed);
        }
    }

    /**
     * The distinctive sound that fires the moment the cast resolves.
     */
    public static void castSound(ServerLevel level, Entity source, Kamu kamu) {
        if (source == null || kamu == null || level == null) {
            return;
        }
        Cue cue = soundFor(kamu.id());
        double y = source.getY() + source.getEyeHeight();
        // playSound with a Holder<SoundEvent> is copied from the suite's
        // SmelterMenu / chatdonkey ActiveEvent call sites.
        level.playSound(null, source.getX(), y, source.getZ(),
                cue.sound, SoundSource.HOSTILE, 0.9f, cue.pitch);
    }

    private static Cue soundFor(String id) {
        return switch (id) {
            // ⚠ UNVERIFIED: SoundEvents selections below are not duplicated in
            // the suite's proven call sites; they are read from SoundEvents.
            case "fire" -> new Cue(SoundEvents.NOTE_BLOCK_BASS, 0.7f);
            case "ice" -> new Cue(SoundEvents.NOTE_BLOCK_CHIME, 1.3f);
            case "poison" -> new Cue(SoundEvents.NOTE_BLOCK_DIDGERIDOO, 0.8f);
            case "wither" -> new Cue(SoundEvents.NOTE_BLOCK_BASS, 0.5f);
            case "lightning" -> new Cue(SoundEvents.NOTE_BLOCK_BELL, 1.9f);
            case "heal" -> new Cue(SoundEvents.NOTE_BLOCK_HARP, 1.4f);
            case "absorption" -> new Cue(SoundEvents.NOTE_BLOCK_PLING, 1.2f);
            default -> new Cue(SoundEvents.NOTE_BLOCK_HAT, 1.0f);
        };
    }

    private static ParticleOptions particleFor(String id) {
        return switch (id) {
            // ⚠ UNVERIFIED: these vanilla ParticleTypes constants are not
            // duplicated in the suite's verified call sites.
            case "fire" -> ParticleTypes.FLAME;
            case "ice" -> ParticleTypes.SNOWFLAKE;
            case "poison" -> ParticleTypes.WITCH;
            case "wither" -> ParticleTypes.SMOKE;
            case "lightning" -> ParticleTypes.ELECTRIC_SPARK;
            case "heal" -> ParticleTypes.HEART;
            case "absorption" -> ParticleTypes.TOTEM_OF_UNDYING;
            default -> ParticleTypes.POOF;
        };
    }

    private record Cue(Holder<SoundEvent> sound, float pitch) {}
}
