package foundry.mc;

import foundry.core.CrouchSlide;
import foundry.core.RaceTraits;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The crouch slide as a race trait: a race with {@code slide} in race_traits.json (the Humans of Aurelia) who presses
 * sneak while sprinting on the ground slides forward the way they run, on a short cooldown. The client already crouches
 * on the press, so it looks like a slide; dust from the ground and a scrape mark it for everyone nearby.
 *
 * <p>As with the double jump and the swim dash, the client moves the player, so the slide is the server telling the
 * player's own client its new motion; it works on vanilla and Bedrock clients alike, and the client's own collision
 * stops it at a wall.
 */
public final class HumanSlide {
    private static final Map<UUID, CrouchSlide> STATE = new HashMap<>();

    private HumanSlide() {}

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RaceTraits.Slide slide = RaceTraitsRuntime.of(player.getUUID()).slide();
            if (slide == null) {
                STATE.remove(player.getUUID());
                continue;
            }
            CrouchSlide state = STATE.computeIfAbsent(player.getUUID(), id -> new CrouchSlide());
            boolean grounded = player.onGround() && !player.isInWater() && !player.isInLava() && !player.isPassenger()
                    && !player.isFallFlying() && !player.onClimbable() && !player.getAbilities().flying && player.isAlive()
                    && !player.isSpectator();
            if (state.tick(player.getLastClientInput().shift(), player.isSprinting(), grounded, now, slide.cooldown())) {
                slide(player, slide);
            } else if (state.refused()) {
                AbilityBar.notYet(player, "Slide", state.waiting(now),
                        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.GRAVEL_STEP));
            }
        }
    }

    /** Slides the player forward the way they face and tells their client, with dust and a scrape; public so a test can drive it. */
    public static void slide(ServerPlayer player, RaceTraits.Slide slide) {
        double[] push = CrouchSlide.push(player.getYRot(), slide.speed());
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(push[0], Math.max(0, v.y), push[1]);
        player.syncVelocity = true;
        if (player.level() instanceof ServerLevel level) {
            var below = level.getBlockState(player.blockPosition().below());
            if (!below.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, below), player.getX(), player.getY() + 0.1,
                        player.getZ(), 16, 0.3, 0.05, 0.3, 0.15);
            }
            level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 4, 0.2, 0.02, 0.2, 0.02);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS,
                    0.6f, 1.4f);
        }
    }

    /** True while this player has a slide tracked (for tests). */
    public static boolean tracking(UUID player) {
        return STATE.containsKey(player);
    }

    static void forget(UUID player) {
        STATE.remove(player);
    }

    static void clear() {
        STATE.clear();
    }
}
