package foundry.mc;

import foundry.core.RaceTraits;
import foundry.core.WingFlight;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Powered flight for the Fairies (race_traits.json {@code flight}; rules in {@link WingFlight}): a takeoff leap from
 * the ground (sneak and jump) that springs them up and starts the glide at the top, and a few wingbeats while gliding
 * (jump) that lift them and push them on. Like the double jump, each is the server telling the player's own client its
 * new motion, so it needs no client mod; it does not touch the glide itself ({@link Wings} and the LivingEntityGlide
 * mixin). Beats left show on the action bar after each beat.
 */
public final class FairyFlight {
    private static final Map<UUID, WingFlight> STATE = new HashMap<>();
    /** Players who leapt and should start gliding at the top of the leap. */
    private static final Set<UUID> RISING = new HashSet<>();

    private FairyFlight() {}

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RaceTraits.Flight f = RaceTraitsRuntime.of(player.getUUID()).flight();
            if (f == null || !player.isAlive() || player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
                if (f == null) forget(player.getUUID());
                continue;
            }
            WingFlight state = STATE.computeIfAbsent(player.getUUID(), id -> new WingFlight());
            var input = player.getLastClientInput();
            boolean onGround = player.onGround() && !player.isInWater();
            switch (state.tick(input.jump(), input.shift(), onGround, player.isFallFlying(), now, f)) {
                case LEAP -> leap(player, f);
                case BEAT -> beat(player, f, state.charges());
                case NONE -> { }
            }
            // glide from the top of the leap
            if (RISING.contains(player.getUUID()) && !player.onGround() && player.getDeltaMovement().y <= 0.05) {
                RISING.remove(player.getUUID());
                if (!player.isFallFlying() && !player.isInWater()) player.startFallFlying();
            } else if (RISING.contains(player.getUUID()) && player.onGround() && state.leapWait(now) < f.leapCooldown() - 10) {
                RISING.remove(player.getUUID());
            }
        }
    }

    /** Springs a Fairy up from the ground; they start gliding at the top. Public so a test can drive it. */
    public static void leap(ServerPlayer player, RaceTraits.Flight f) {
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x, f.leap(), v.z);
        player.syncVelocity = true;
        player.resetFallDistance();
        RISING.add(player.getUUID());
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 0.2, player.getZ(), 12, 0.4, 0.1, 0.4, 0.05);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS,
                    0.6f, 1.6f);
        }
    }

    /** Beats a gliding Fairy's wings: a lift and a push the way they look. Public so a test can drive it. */
    public static void beat(ServerPlayer player, RaceTraits.Flight f, int left) {
        Vec3 v = player.getDeltaMovement();
        Vec3 look = player.getLookAngle();
        double[] after = WingFlight.beat(v.x, v.y, v.z, look.x, look.z, f, f.maxSpeed());
        player.setDeltaMovement(after[0], after[1], after[2]);
        player.syncVelocity = true;
        player.resetFallDistance();
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 0.8, player.getZ(), 8, 0.5, 0.2, 0.5, 0.03);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS,
                    0.7f, 1.8f);
        }
        player.sendSystemMessage(Component.literal("Wingbeats " + "●".repeat(left) + "○".repeat(Math.max(0, f.beats() - left))), true);
    }

    static void forget(UUID player) {
        STATE.remove(player);
        RISING.remove(player);
    }

    static void clear() {
        STATE.clear();
        RISING.clear();
    }
}
