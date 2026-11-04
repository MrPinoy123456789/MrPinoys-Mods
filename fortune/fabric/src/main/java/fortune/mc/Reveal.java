package fortune.mc;

import fortune.core.Claims;
import fortune.core.Line;
import fortune.core.Machine;
import fortune.core.Settings;
import fortune.core.Stake;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Paying and telling people about a spin, once its reels have stopped (or the window closed early): the payout, then
 * the title and subtitle (both clients show them), a chat line, sounds, sparks, and the server lines for big wins and
 * milestones. Nothing about the outcome is said or paid before this runs.
 */
final class Reveal {

    private Reveal() {
    }

    static void show(FortuneService service, ServerPlayer player, FortuneService.Pull.Spun pulled, BlockPos machineAt) {
        if (player.hasDisconnected()) return;
        // Paid now, not at the pull: Charter Currency's own "+N Valor" line and the items arrive with the reveal.
        service.deliver(player);
        Machine.Spin spin = pulled.spin();
        Settings settings = service.settings();
        String result = service.resultName(spin);
        String payout = payoutText(service, spin.reward());

        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(result).withStyle(ChatFormatting.GOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(payout).withStyle(ChatFormatting.YELLOW)));
        String stakeNote = spin.stake() == null ? "" : " (" + spin.stake().name + " +" + spin.stake().bonusPercent + "%)";
        player.sendSystemMessage(Messages.info("Fantasy's Fortune: " + result + ". " + payout + stakeNote + "."));

        Line line = spin.outcome().line();
        boolean big = spin.outcome().row().jackpot() || spin.plan().kind() == Claims.Kind.GOLDEN;
        if (big) {
            sound(player, BuiltInSound.wrap(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 0.7f, 1.0f);
            sparks(player.level(), player.getX(), player.getY() + 1.5, player.getZ());
        } else if (line != Line.NO_MATCH) {
            sound(player, BuiltInSound.wrap(SoundEvents.PLAYER_LEVELUP), 0.6f, 1.0f);
        }
        if (machineAt != null && line.isTriple()) {
            ServerLevel level = player.level();
            level.playSound(null, machineAt, big ? SoundEvents.FIREWORK_ROCKET_LARGE_BLAST : SoundEvents.FIREWORK_ROCKET_TWINKLE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
            sparks(level, machineAt.getX() + 0.5, machineAt.getY() + 1.2, machineAt.getZ() + 0.5);
        }

        if (spin.plan().kind() == Claims.Kind.WELCOME_BACK) {
            player.sendSystemMessage(Messages.info("The kingdom kept your seat warm."));
            if (spin.plan().previousBest() > 1) {
                player.sendSystemMessage(Messages.plain("Your best streak was " + spin.plan().previousBest() + " days. Chase it."));
            }
        }
        if (pulled.stakeFailed()) {
            player.sendSystemMessage(Messages.plain("Your stake did not go through, so this spin was unstaked and nothing was charged."));
        }

        if (spin.outcome().row().jackpot()) {
            service.server().getPlayerList().broadcastSystemMessage(
                    Messages.info("Jackpot! " + player.getPlainTextName() + " pulled " + result + " at Fantasy's Fortune."), false);
        } else if (spin.outcome().row().broadcast()) {
            service.server().getPlayerList().broadcastSystemMessage(
                    Messages.info(player.getPlainTextName() + " pulled " + result + " at Fantasy's Fortune."), false);
        }
        if (pulled.milestone() > 0) {
            for (Settings.Milestone m : settings.milestones) {
                if (m.claims != pulled.milestone()) continue;
                service.server().getPlayerList().broadcastSystemMessage(Messages.info(player.getPlainTextName()
                        + " has spun Fantasy's Fortune on " + m.claims + " days: " + player.getPlainTextName() + " " + m.title + "."), false);
            }
        }
    }

    /** "+24 Valor and a diamond", "+6 Valor", or the items alone when Charter Currency is absent. */
    static String payoutText(FortuneService service, Stake.Reward reward) {
        List<String> parts = new ArrayList<>();
        for (Settings.Extra extra : reward.extras()) {
            ItemStack stack = ItemIds.stack(extra.item, 1);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            parts.add(extra.count == 1 ? "a " + name.toLowerCase(java.util.Locale.ROOT)
                    : extra.count + " " + name.toLowerCase(java.util.Locale.ROOT) + (name.endsWith("s") ? "" : "s"));
        }
        String items = String.join(" and ", parts);
        if (FortuneService.currencyLoaded()) {
            String valor = "+" + Stake.valor(reward.level(), service.settings()) + " Valor";
            return items.isEmpty() ? valor : valor + " and " + items;
        }
        return items.isEmpty() ? "Better luck tomorrow" : "+" + items;
    }

    static void sound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    /**
     * A burst of firework sparks. Not a real rocket: an exploding rocket hurts players standing near it, and the
     * machine is pulled with people around.
     */
    static void sparks(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 40, 0.6, 0.6, 0.6, 0.15);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 20, 0.5, 0.5, 0.5, 0.3);
    }

    /** Sounds that are plain events rather than registry holders, wrapped for the sound packet. */
    static final class BuiltInSound {
        private BuiltInSound() {
        }

        static Holder<SoundEvent> wrap(SoundEvent event) {
            return Holder.direct(event);
        }
    }
}
