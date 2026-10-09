package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * PD-178 (owner ruling 2026-10-08) and the design pass of 2026-10-09 (Q10e): the effects that make a player
 * "hide for 30 seconds" never last past a cap inside a dungeon. Witch potions, cave spiders, wither
 * skeletons and strays all apply their effects through the vanilla effect, so each cap is a clamp on the
 * effect a player carries: a poll finds a longer one and replaces it with the same amplifier and the capped
 * duration. A fresh hit lands at its full length for at most one poll, then clamps again. Darkness is left
 * alone: the sculk shrieker's darkness is the omen's own signal.
 */
final class EffectCaps {

    /** Ticks between polls. */
    private static final int PERIOD = 5;

    /** One capped effect and the knob that holds its cap in seconds. */
    record Cap(String name, Holder<MobEffect> effect, IntSupplier seconds) {}

    /** The capped effects, in the order they are checked. A method, so the pure tests never load the effect registry. */
    static List<Cap> caps() {
        return List.of(
            new Cap("poison", MobEffects.POISON, PocketDungeonsConfig::poisonMaxSeconds),
            new Cap("wither", MobEffects.WITHER, PocketDungeonsConfig::witherMaxSeconds),
            new Cap("slowness", MobEffects.SLOWNESS, PocketDungeonsConfig::slownessMaxSeconds),
            new Cap("mining_fatigue", MobEffects.MINING_FATIGUE, PocketDungeonsConfig::miningFatigueMaxSeconds));
    }

    private EffectCaps() {}

    /** Wires the poll. Call once from {@code onInitialize}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            for (ServerPlayer player : level.players()) {
                for (Cap cap : caps()) {
                    clamp(player, cap.effect(), cap.seconds().getAsInt());
                }
            }
        });
    }

    /** The duration in ticks an effect of {@code duration} ticks is held to; infinite ({@code -1}) is held too. */
    static int cappedTicks(int duration, int maxSeconds) {
        if (maxSeconds <= 0) {
            return duration;
        }
        int cap = maxSeconds * 20;
        return duration < 0 || duration > cap ? cap : duration;
    }

    /** Replaces {@code player}'s {@code effect} with a capped copy when it runs longer than {@code maxSeconds}. */
    static void clamp(ServerPlayer player, Holder<MobEffect> effect, int maxSeconds) {
        MobEffectInstance current = player.getEffect(effect);
        if (current == null) {
            return;
        }
        int capped = cappedTicks(current.getDuration(), maxSeconds);
        if (capped == current.getDuration()) {
            return;
        }
        player.removeEffect(effect);
        player.addEffect(new MobEffectInstance(effect, capped, current.getAmplifier(),
                current.isAmbient(), current.isVisible(), current.showIcon()));
    }
}
