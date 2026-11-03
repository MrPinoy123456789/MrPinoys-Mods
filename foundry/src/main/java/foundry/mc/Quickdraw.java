package foundry.mc;

import foundry.core.mechanics.Step;
import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code dash_strike} action (the Oathblade's Quickdraw): the player is carried along the ground the way they face,
 * stopping at the first wall, and every living thing in the path is cut once. The move is a short teleport along a
 * checked path, so it is the same on Java and Bedrock and can never end inside a block. Server thread only.
 */
public final class Quickdraw {
    /** How finely the path is checked for walls, in blocks. */
    static final double STEP = 0.25;
    /** How far the cut reaches either side of the path. */
    static final double REACH = 0.6;

    private Quickdraw() {}

    /** True when the player can dash: not riding anything and not in water (rain is fine). */
    static boolean onFoot(ServerPlayer player) {
        return !player.isPassenger() && !player.isInWater() && !player.isInLava();
    }

    /** Runs one dash at {@code charge} (0 to 1). Returns how far the player went, in blocks. */
    public static double strike(ServerPlayer player, Step action, double charge) {
        if (!onFoot(player) || !(player.level() instanceof ServerLevel level)) {
            player.sendSystemMessage(Component.literal("No room to draw here"), true);
            return 0;
        }
        double range = foundry.core.Iai.reach(charge, action.decimalParam("min_range", action.decimalParam("range", 8)),
                action.decimalParam("range", 8));
        float amount = (float) foundry.core.Iai.damage(charge, action.decimalParam("amount", 4),
                action.decimalParam("min_damage", 0.5));
        Vec3 dir = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 start = player.position();
        AABB box = player.getBoundingBox();
        double lift = 0;
        double went = 0;
        // Walk the path a quarter block at a time; step up onto one block (a ledge, a slab) as walking would, and stop
        // at anything taller.
        while (went + STEP <= range + 1e-9) {
            Vec3 next = dir.scale(went + STEP);
            AABB at = box.move(next.x, lift, next.z);
            if (level.noCollision(player, at)) {
                went += STEP;
                continue;
            }
            if (lift < 1 && level.noCollision(player, at.move(0, 1 - lift, 0))) {
                lift = 1;
                went += STEP;
                continue;
            }
            break;
        }
        Vec3 end = start.add(dir.scale(went)).add(0, lift, 0);
        AABB swept = box.minmax(box.move(end.subtract(start))).inflate(REACH, 0.25, REACH);
        List<LivingEntity> cut = level.getEntitiesOfClass(LivingEntity.class, swept,
                e -> e != player && e.isAlive() && !e.isSpectator() && MechanicHost.passesFilters(action, e));
        var effect = action.params().containsKey("effect")
                ? BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(action.param("effect", ""))).orElse(null) : null;
        for (LivingEntity e : cut) {
            e.damageCooldownTime = 0;
            e.hurtServer(level, level.damageSources().playerAttack(player), amount);
            if (effect != null) {
                e.addEffect(new MobEffectInstance(effect, action.intParam("duration", 20), action.intParam("amplifier", 0)));
            }
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(), 1, 0, 0, 0, 0);
        }
        player.connection.teleport(end.x, end.y, end.z, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        // The slash hangs in the air along the path for a moment.
        var line = new DustParticleOptions(Integer.parseInt(action.param("colour", "#F2F4F8").substring(1), 16), 1.0f);
        double y = 1.1;
        for (double d = 0; d <= went; d += 0.4) {
            Vec3 p = start.add(dir.scale(d));
            level.sendParticles(line, p.x, p.y + y + lift * d / Math.max(went, 1), p.z, 1, 0, 0, 0, 0);
        }
        level.playSound(null, end.x, end.y, end.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 0.7f);
        if (!cut.isEmpty()) {
            level.playSound(null, end.x, end.y, end.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.2f);
        }
        return went;
    }
}
