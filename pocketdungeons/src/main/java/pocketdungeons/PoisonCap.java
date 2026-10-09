package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * PD-178 (owner ruling 2026-10-08): poison never lasts more than {@code poisonMaxSeconds} on a player
 * inside a dungeon. Witch potions, cave spiders and the rest all apply poison through the vanilla
 * effect, so the cap is a clamp on the effect a player carries: a poll finds a longer one and
 * replaces it with the same amplifier and the capped duration. A fresh hit lands at its full length
 * for at most one poll, then clamps again.
 */
final class PoisonCap {

    /** Ticks between polls. */
    private static final int PERIOD = 5;

    private PoisonCap() {}

    /** Wires the poll. Call once from {@code onInitialize}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0) {
                return;
            }
            int max = PocketDungeonsConfig.poisonMaxSeconds();
            if (max <= 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            for (ServerPlayer player : level.players()) {
                clamp(player, max);
            }
        });
    }

    /** The duration in ticks a poison of {@code duration} ticks is held to; infinite ({@code -1}) is held too. */
    static int cappedTicks(int duration, int maxSeconds) {
        if (maxSeconds <= 0) {
            return duration;
        }
        int cap = maxSeconds * 20;
        return duration < 0 || duration > cap ? cap : duration;
    }

    /** Replaces {@code player}'s poison with a capped copy when it runs longer than {@code maxSeconds}. */
    static void clamp(ServerPlayer player, int maxSeconds) {
        MobEffectInstance poison = player.getEffect(MobEffects.POISON);
        if (poison == null) {
            return;
        }
        int capped = cappedTicks(poison.getDuration(), maxSeconds);
        if (capped == poison.getDuration()) {
            return;
        }
        player.removeEffect(MobEffects.POISON);
        player.addEffect(new MobEffectInstance(MobEffects.POISON, capped, poison.getAmplifier(),
                poison.isAmbient(), poison.isVisible(), poison.showIcon()));
    }
}
