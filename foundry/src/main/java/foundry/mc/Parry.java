package foundry.mc;

import foundry.core.mechanics.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * The {@code parry} action (the Oathblade's Mikiri): for a few ticks the next melee hit or projectile aimed at the player
 * is cancelled, and the weapon answers the attacker with its on_parry mechanics. One hit per parry; poison, falls, fire,
 * explosions and magic are never stopped. The answer runs at the end of the tick, outside the damage call, so it can
 * never set off another mechanic mid hit. Server thread only.
 */
public final class Parry {
    /** An attacker further away than this (an archer across a field) is not answered; the arrow is still stopped. */
    static final double ANSWER_RANGE = 16;

    record Window(String contentId, int index, long until, long refundTicks) {}

    private record Answer(UUID player, Window window, LivingEntity attacker) {}

    private static final Map<UUID, Window> OPEN = new HashMap<>();
    private static final List<Answer> PENDING = new ArrayList<>();

    private Parry() {}

    static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer player && tryParry(player, source)));
    }

    /** Opens a parry for the mechanic at {@code index} of {@code contentId}, whose recharge is {@code cooldownTicks}. */
    static void open(ServerPlayer player, String contentId, int index, int cooldownTicks, Step action) {
        long now = player.level().getServer().getTickCount();
        long refund = Math.round(cooldownTicks * action.decimalParam("refund", 0));
        OPEN.put(player.getUUID(), new Window(contentId, index, now + action.intParam("window", 15), refund));
    }

    /** True when the hit is the kind a blade can turn aside: a melee blow or a projectile. */
    static boolean parryable(DamageSource source) {
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        return source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO) || source.is(DamageTypeTags.IS_PROJECTILE);
    }

    /** Called for every hit on a player: true (cancel the hit) when an open parry takes it. */
    static boolean tryParry(ServerPlayer player, DamageSource source) {
        Window w = OPEN.get(player.getUUID());
        if (w == null) return false;
        long now = player.level().getServer().getTickCount();
        if (now > w.until()) {
            OPEN.remove(player.getUUID());
            return false;
        }
        if (!parryable(source) || source.getEntity() == player) return false;
        OPEN.remove(player.getUUID());
        LivingEntity attacker = source.getEntity() instanceof LivingEntity l && l.isAlive()
                && l.distanceToSqr(player) <= ANSWER_RANGE * ANSWER_RANGE ? l : null;
        PENDING.add(new Answer(player.getUUID(), w, attacker));
        if (player.level() instanceof ServerLevel level) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5f, 1.8f);
            level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.2, player.getZ(), 12, 0.4, 0.3, 0.4, 0.2);
        }
        return true;
    }

    /** Runs the answers of the parries that landed this tick and drops the windows that ran out. */
    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        OPEN.values().removeIf(w -> now > w.until());
        if (PENDING.isEmpty()) return;
        List<Answer> due = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Answer a : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(a.player());
            if (player == null || !player.isAlive()) continue;
            var def = FoundryMod.loaded().items().get(foundry.core.Ident.parse(a.window().contentId()));
            if (def == null) continue;
            LivingEntity attacker = a.attacker() != null && a.attacker().isAlive() ? a.attacker() : null;
            MechanicHost.parried(a.window().contentId(), def.mechanics(), a.window().index(), a.window().refundTicks(),
                    player, attacker);
        }
    }

    /** True while this player has a parry open (for tests). */
    public static boolean open(UUID player) {
        return OPEN.containsKey(player);
    }

    static void forget(UUID player) {
        OPEN.remove(player);
    }

    static void clear() {
        OPEN.clear();
        PENDING.clear();
    }
}
