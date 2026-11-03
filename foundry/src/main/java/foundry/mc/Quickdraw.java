package foundry.mc;

import foundry.core.mechanics.Step;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code dash_strike} action (the Oathblade's Quickdraw): the player is driven forward along the ground the way they
 * face for a few ticks, as fast as {@code speed} blocks a tick, and every living thing they pass is cut once. It is a
 * push, not a teleport: the client moves the player, so walls stop it and mobs do not (a player never collides with a
 * mob). The number of pushes is fixed from the range at the start, so the distance does not depend on ping. Java and
 * Bedrock both take a pushed player (the slide and Wing Dash move the same way). Server thread only.
 */
public final class Quickdraw {
    /** How far the cut reaches either side of the player's path. */
    static final double REACH = 0.7;
    /** Default push speed, blocks a tick. */
    static final double SPEED = 1.25;
    /** What is left of the push when it ends, so the player skids to a stop instead of halting dead. */
    static final double SKID = 0.2;
    /** Ticks after the last push in which the cut still looks for what was passed (the server sees the client late). */
    static final int TRAIL = 3;

    private record Dash(Vec3 dir, double speed, int pushes, int left, float amount, Holder<MobEffect> effect, int duration,
            int amplifier, Step action, Set<UUID> cut, Vec3[] last) {}

    private static final Map<UUID, Dash> DASHES = new HashMap<>();

    private Quickdraw() {}

    /** True when the player can dash: not riding anything and not in water (rain is fine). */
    static boolean onFoot(ServerPlayer player) {
        return !player.isPassenger() && !player.isInWater() && !player.isInLava();
    }

    /** Starts one dash at {@code charge} (0 to 1). Returns how far it is planned to go, in blocks. */
    public static double strike(ServerPlayer player, Step action, double charge) {
        if (!onFoot(player) || !(player.level() instanceof ServerLevel level)) {
            player.sendSystemMessage(Component.literal("No room to draw here"), true);
            return 0;
        }
        double range = foundry.core.Iai.reach(charge, action.decimalParam("min_range", action.decimalParam("range", 8)),
                action.decimalParam("range", 8));
        float amount = (float) foundry.core.Iai.damage(charge, action.decimalParam("amount", 4),
                action.decimalParam("min_damage", 0.5));
        double speed = action.decimalParam("speed", SPEED);
        int pushes = Math.max(1, (int) Math.round(range / speed));
        var effect = action.params().containsKey("effect")
                ? BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(action.param("effect", ""))).orElse(null) : null;
        Vec3 dir = Vec3.directionFromRotation(0, player.getYRot());
        DASHES.put(player.getUUID(), new Dash(dir, range / pushes, pushes, pushes + TRAIL, amount, effect,
                action.intParam("duration", 20), action.intParam("amplifier", 0), action, new HashSet<>(),
                new Vec3[] {player.position()}));
        // the pushes all come from tick(), which runs later in this same tick: a push sent here as well would reach the
        // client in the same breath as the next one and be lost
        player.resetFallDistance();
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS,
                1.0f, 0.7f);
        return range;
    }

    private static void push(ServerPlayer player, Vec3 v) {
        player.setDeltaMovement(v.x, 0, v.z);
        player.syncVelocity = true;
        player.resetFallDistance();
    }

    /** Pushes every dash under way one more tick and cuts what each passed since the last. */
    static void tick(MinecraftServer server) {
        if (DASHES.isEmpty()) return;
        var it = DASHES.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Dash d = e.getValue();
            if (player == null || !player.isAlive() || !(player.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            cut(level, player, d);
            int done = d.pushes() + TRAIL - d.left();   // pushes already sent
            if (done < d.pushes() && onFoot(player)) {
                push(player, d.dir().scale(d.speed()));
            } else if (done == d.pushes()) {
                push(player, d.dir().scale(SKID));
            }
            Dash next = new Dash(d.dir(), d.speed(), d.pushes(), d.left() - 1, d.amount(), d.effect(), d.duration(),
                    d.amplifier(), d.action(), d.cut(), d.last());
            if (next.left() <= 0) it.remove();
            else e.setValue(next);
        }
    }

    /** Cuts every living thing between where the player was last tick and where they are now, once per dash. */
    private static void cut(ServerLevel level, ServerPlayer player, Dash d) {
        Vec3 from = d.last()[0], to = player.position();
        d.last()[0] = to;
        AABB box = player.getBoundingBox();
        AABB swept = box.move(from.subtract(to)).minmax(box).inflate(REACH, 0.25, REACH);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, swept,
                t -> t != player && t.isAlive() && !t.isSpectator() && !d.cut().contains(t.getUUID())
                        && MechanicHost.passesFilters(d.action(), t))) {
            d.cut().add(target.getUUID());
            target.damageCooldownTime = 0;
            target.hurtServer(level, level.damageSources().playerAttack(player), d.amount());
            if (d.effect() != null) target.addEffect(new MobEffectInstance(d.effect(), d.duration(), d.amplifier()));
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + target.getBbHeight() * 0.6,
                    target.getZ(), 1, 0, 0, 0, 0);
            level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_CRIT,
                    SoundSource.PLAYERS, 1.0f, 1.2f);
        }
        // the slash hangs in the air along the path for a moment
        var line = new DustParticleOptions(Integer.parseInt(d.action().param("colour", "#F2F4F8").substring(1), 16), 1.0f);
        double length = to.distanceTo(from);
        for (double t = 0; t <= length; t += 0.4) {
            Vec3 p = from.lerp(to, length == 0 ? 0 : t / length);
            level.sendParticles(line, p.x, p.y + 1.1, p.z, 1, 0, 0, 0, 0);
        }
    }

    /** True while this player is dashing (for tests). */
    public static boolean dashing(UUID player) {
        return DASHES.containsKey(player);
    }

    static void forget(UUID player) {
        DASHES.remove(player);
    }

    static void clear() {
        DASHES.clear();
    }
}
