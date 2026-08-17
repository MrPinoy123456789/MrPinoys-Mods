package smalltalk.social;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * World-facing cues for the gift verb (SPEC.md section 6.2). Reuses
 * vanilla's own vocabulary for reactions rather than inventing new
 * feedback.
 */
public final class Feedback {

    private Feedback() {}

    public static void giftReaction(Villager villager, ServerLevel level, GiftReaction reaction) {
        switch (reaction) {
            case LOVED -> {
                hearts(villager, level, 6);
                level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                        SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
            case NEUTRAL -> level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                    SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1.0f, 1.0f);
            case DISLIKED -> {
                smoke(villager, level);
                level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                        SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
        }
    }

    private static void hearts(Villager villager, ServerLevel level, int count) {
        level.sendParticles(ParticleTypes.HEART,
                villager.getX(), villager.getEyeY(), villager.getZ(),
                count, 0.4, 0.4, 0.4, 0.0);
    }

    private static void smoke(Villager villager, ServerLevel level) {
        level.sendParticles(ParticleTypes.SMOKE,
                villager.getX(), villager.getEyeY(), villager.getZ(),
                4, 0.3, 0.3, 0.3, 0.01);
    }
}
