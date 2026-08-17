package rehome;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * All world-facing cues -- sounds, particles, chat lines. Reuses vanilla's own
 * vocabulary for taming (hearts, villager_yes) rather than inventing new
 * feedback (SPEC.md section 3).
 */
public final class Feedback {

    private Feedback() {}

    public static void giftAccepted(Villager villager, ServerLevel level) {
        hearts(villager, level);
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    public static void giftDeclined(Villager villager, ServerLevel level) {
        smoke(villager, level);
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    public static void settled(Villager villager, ServerLevel level, ServerPlayer player) {
        hearts(villager, level);
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f);
        player.sendSystemMessage(Component.literal(nameOf(villager) + " has made itself at home.")
                .withStyle(ChatFormatting.GREEN));
    }

    public static void notSettled(Villager villager, ServerPlayer player) {
        player.sendSystemMessage(Component.literal(nameOf(villager) + " doesn't see anywhere to sleep here.")
                .withStyle(ChatFormatting.GRAY));
    }

    public static void resumedFollowing(Villager villager, ServerPlayer player) {
        player.sendSystemMessage(Component.literal(nameOf(villager) + " follows you again.")
                .withStyle(ChatFormatting.GRAY));
    }

    public static void stoppedFollowing(Villager villager, ServerPlayer player) {
        player.sendSystemMessage(Component.literal(nameOf(villager) + " will wait here.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** Sent once, at befriend -- the emerald toggle has no other discovery path in-game. */
    public static void befriended(Villager villager, ServerPlayer player) {
        player.sendSystemMessage(Component.literal(nameOf(villager)
                        + " will follow you home. Right-click them with an emerald to have "
                        + "them wait, and again to have them follow again.")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * Gives a befriended villager a visible, green name tag -- the only
     * at-a-glance "this one's mine" signal, since Rehome has no identity
     * system of its own (that's Small Talk's, for residents, not strangers).
     */
    public static void markFriendly(Villager villager) {
        Component base = villager.hasCustomName() && villager.getCustomName() != null
                ? villager.getCustomName()
                : villager.getType().getDescription();
        villager.setCustomName(base.copy().withStyle(ChatFormatting.GREEN));
        villager.setCustomNameVisible(true);
    }

    public static void headroomWarning(ServerLevel level, BlockPos obstruction, ServerPlayer player, Villager villager) {
        level.sendParticles(ParticleTypes.SMOKE,
                obstruction.getX() + 0.5, obstruction.getY() + 0.5, obstruction.getZ() + 0.5,
                8, 0.2, 0.2, 0.2, 0.02);
        player.sendSystemMessage(Component.literal(
                        nameOf(villager) + " eyes the ceiling warily. There's not much room to stretch out.")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void hearts(Villager villager, ServerLevel level) {
        level.sendParticles(ParticleTypes.HEART,
                villager.getX(), villager.getEyeY(), villager.getZ(),
                6, 0.4, 0.4, 0.4, 0.0);
    }

    private static void smoke(Villager villager, ServerLevel level) {
        level.sendParticles(ParticleTypes.SMOKE,
                villager.getX(), villager.getEyeY(), villager.getZ(),
                4, 0.3, 0.3, 0.3, 0.01);
    }

    public static String nameOf(Villager villager) {
        return villager.hasCustomName() && villager.getCustomName() != null
                ? villager.getCustomName().getString()
                : "The villager";
    }
}
