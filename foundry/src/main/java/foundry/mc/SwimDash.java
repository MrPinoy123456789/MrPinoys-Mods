package foundry.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The swim dash as a race trait: a race whose look has {@code swim_dash} (the Merfolk of Azurius, by their race:azurius
 * node) darts forward the way they are looking when they press jump while swimming (sprint swimming, or holding sprint
 * in the water), on a short cooldown. Plain jump in the water still rises as usual; only sprinting makes it a dash.
 *
 * <p>As with the double jump, the client moves the player, so the dash is the server telling the player's own client its
 * new motion; it works on vanilla and Bedrock clients alike. A trail of bubbles and a splash mark it.
 */
public final class SwimDash {
    /** How fast the dash throws a Merfolk, in blocks per tick; the water slows it within a second or so. */
    static final double SPEED = 1.15;
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static final Map<UUID, foundry.core.SwimDash> STATE = new HashMap<>();

    private SwimDash() {}

    /** The swim dash cooldown for this player's race in ticks, or 0 when the race cannot dash. */
    public static int cooldownOf(UUID player) {
        return COOLDOWN.getOrDefault(player, 0);
    }

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % WornDisplays.SCAN_EVERY == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                int cooldown = 0;
                if (player.isAlive()) {
                    for (var race : WornDisplays.table().races().entrySet()) {
                        for (var look : race.getValue()) {
                            if (look.swimDash() > 0 && Gate.hasNode(player, race.getKey())) cooldown = look.swimDash();
                        }
                    }
                }
                if (cooldown > 0) COOLDOWN.put(player.getUUID(), cooldown);
                else forget(player.getUUID());
            }
        }
        if (COOLDOWN.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Integer cooldown = COOLDOWN.get(player.getUUID());
            if (cooldown == null) continue;
            var state = STATE.computeIfAbsent(player.getUUID(), id -> new foundry.core.SwimDash());
            var input = player.getLastClientInput();
            boolean swimming = player.isInWater() && player.isAlive() && !player.isPassenger() && !player.isSpectator()
                    && (player.isSwimming() || input.sprint());
            if (state.tick(input.jump(), swimming, now, cooldown)) dash(player);
        }
    }

    /** Throws the player the way they look and tells their client, with bubbles and a splash; public so a test can drive it. */
    public static void dash(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        player.setDeltaMovement(look.scale(SPEED));
        player.syncVelocity = true;
        player.resetFallDistance();
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.BUBBLE, player.getX(), player.getY() + 0.5, player.getZ(), 24, 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, player.getX(), player.getY() + 0.3, player.getZ(), 8, 0.2, 0.2,
                    0.2, 0.05);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DOLPHIN_SWIM, SoundSource.PLAYERS,
                    1.0f, 1.2f);
        }
    }

    static void forget(UUID player) {
        COOLDOWN.remove(player);
        STATE.remove(player);
    }

    static void clear() {
        COOLDOWN.clear();
        STATE.clear();
    }
}
