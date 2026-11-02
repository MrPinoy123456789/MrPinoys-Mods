package foundry.mc;

import foundry.core.AirJump;
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
 * The double jump as a race trait: a race whose look has {@code double_jump} (the Fae of Faeloria, by their race:faeloria
 * node) can press jump again in the air for a second hop, once per time off the ground, on a short cooldown. It replaces
 * gliding for the Fae, so they and the Fairies are told apart by how they move as well as how they look.
 *
 * <p>The client moves the player, so the hop is the server telling the player's own client its new motion (the same
 * packet a knockback sends); it works on vanilla and Bedrock clients alike. The fall distance is reset, so the hop never
 * adds fall damage. A burst of falling leaves and a rustle mark it for everyone nearby.
 */
public final class DoubleJump {
    /** Each Fae's cooldown in ticks, refreshed every {@value WornDisplays#SCAN_EVERY} ticks; absent for other races. */
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static final Map<UUID, AirJump> STATE = new HashMap<>();

    private DoubleJump() {}

    /** The double jump cooldown for this player's race in ticks, or 0 when the race cannot double jump. */
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
                            if (look.doubleJump() > 0 && Gate.hasNode(player, race.getKey())) cooldown = look.doubleJump();
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
            AirJump state = STATE.computeIfAbsent(player.getUUID(), id -> new AirJump());
            var input = player.getLastClientInput();
            boolean grounded = player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()
                    || player.getAbilities().flying || player.isFallFlying() || player.onClimbable() || !player.isAlive()
                    || player.isSpectator();
            if (state.tick(input.jump(), grounded, now, cooldown)) {
                hop(player, AirJump.push(player.getYRot(), input.forward(), input.backward(), input.left(), input.right()));
            }
        }
    }

    /** Lifts the player and tells their client, with leaves and a rustle; public so a test can drive it. */
    public static void hop(ServerPlayer player, double[] push) {
        Vec3 now = player.getDeltaMovement();
        // keep a little of the motion they already had, so a hop while running carries on
        player.setDeltaMovement(now.x * 0.4 + push[0], push[1], now.z * 0.4 + push[2]);
        player.syncVelocity = true;
        player.resetFallDistance();
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, player.getX(), player.getY() + 0.2, player.getZ(), 10, 0.4, 0.1,
                    0.4, 0.02);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 0.1, player.getZ(), 4, 0.3, 0.05,
                    0.3, 0.0);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AZALEA_LEAVES_BREAK,
                    SoundSource.PLAYERS, 0.8f, 1.4f);
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
