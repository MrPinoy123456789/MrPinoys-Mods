package foundry.mc;

import foundry.core.mechanics.Step;
import foundry.core.mechanics.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side bolts: a point stepped along the caster's look direction each tick, drawn with particles and tested
 * against blocks and living entities. There is no entity and nothing on the client. A bolt never hits its caster,
 * passes through entities that fail its permission filters, and stops at the first block. When it lands it runs the
 * owning item's on_bolt_hit mechanics with the entity as the {@code hit} target. Server thread only.
 */
public final class Bolts {
    /** Live bolts allowed server wide and per caster, so no definition can flood the tick. */
    static final int MAX_TOTAL = 256;
    static final int MAX_PER_CASTER = 8;
    /** Distance covered per collision step; small enough that a thin target cannot be skipped. */
    private static final double STEP = 0.4;

    private static final class Bolt {
        final ServerLevel level;
        final UUID owner;
        final String contentId;
        final String tag;
        final Step spec;
        final SimpleParticleType particle;
        final double speed;
        final double hitRadius;
        Vec3 pos;
        final Vec3 dir;
        double remaining;

        Bolt(ServerLevel level, ServerPlayer owner, String contentId, Step spec, SimpleParticleType particle) {
            this.level = level;
            this.owner = owner.getUUID();
            this.contentId = contentId;
            this.tag = spec.param("tag", "");
            this.spec = spec;
            this.particle = particle;
            this.speed = spec.decimalParam("speed", 1.0);
            this.hitRadius = spec.decimalParam("radius", 0.4);
            this.remaining = spec.decimalParam("range", 10);
            this.dir = owner.getLookAngle().normalize();
            this.pos = owner.getEyePosition().add(dir.scale(0.5)).add(0, -0.15, 0);
        }
    }

    private static final List<Bolt> LIVE = new ArrayList<>();

    private Bolts() {}

    public static int liveFor(java.util.UUID owner) {
        return (int) LIVE.stream().filter(b -> b.owner.equals(owner)).count();
    }

    public static int live() {
        return LIVE.size();
    }

    public static void clear() {
        LIVE.clear();
    }

    /** Starts a bolt from the player's eye along their look direction. Refused quietly at the caps. */
    static void launch(ServerPlayer player, String contentId, Step spec) {
        if (LIVE.size() >= MAX_TOTAL || LIVE.stream().filter(b -> b.owner.equals(player.getUUID())).count() >= MAX_PER_CASTER) {
            FoundryMod.LOG.warn("Foundry: bolt from {} refused at the live bolt cap.", player.getName().getString());
            return;
        }
        Identifier id = Identifier.parse(spec.param("particle", "minecraft:end_rod"));
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(id);
        SimpleParticleType particle = type instanceof SimpleParticleType s ? s : ParticleTypes.END_ROD;
        LIVE.add(new Bolt(player.level(), player, contentId, spec, particle));
    }

    static void tick(MinecraftServer server) {
        var it = LIVE.iterator();
        while (it.hasNext()) {
            Bolt b = it.next();
            ServerPlayer owner = b.level.getPlayerByUUID(b.owner) instanceof ServerPlayer p ? p : null;
            if (owner == null || !owner.isAlive() || !advance(b, owner)) it.remove();
        }
    }

    /** Moves one tick of travel. Returns false when the bolt is finished. */
    private static boolean advance(Bolt b, ServerPlayer owner) {
        double travel = Math.min(b.speed, b.remaining);
        double done = 0;
        while (done < travel) {
            double step = Math.min(STEP, travel - done);
            Vec3 next = b.pos.add(b.dir.scale(step));
            LivingEntity struck = firstEntity(b, owner, b.pos, next);
            var block = b.level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            if (struck != null) {
                landed(b, owner, struck);
                return false;
            }
            if (block.getType() != HitResult.Type.MISS) {
                b.level.sendParticles(ParticleTypes.POOF, block.getLocation().x, block.getLocation().y, block.getLocation().z,
                        2, 0.05, 0.05, 0.05, 0.01);
                return false;
            }
            b.pos = next;
            done += step;
            b.level.sendParticles(b.particle, next.x, next.y, next.z, 1, 0, 0, 0, 0);
        }
        b.remaining -= travel;
        return b.remaining > 0;
    }

    private static LivingEntity firstEntity(Bolt b, ServerPlayer owner, Vec3 from, Vec3 to) {
        AABB sweep = new AABB(from, to).inflate(b.hitRadius);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var e : b.level.getEntities(owner, sweep, x -> x instanceof LivingEntity l && l.isAlive() && !l.isSpectator())) {
            LivingEntity living = (LivingEntity) e;
            if (!MechanicHost.passesFilters(b.spec, living)) continue;
            Optional<Vec3> at = living.getBoundingBox().inflate(b.hitRadius).clip(from, to);
            if (at.isPresent() && from.distanceToSqr(at.get()) < bestDistance) {
                bestDistance = from.distanceToSqr(at.get());
                best = living;
            }
        }
        return best;
    }

    private static void landed(Bolt b, ServerPlayer owner, LivingEntity struck) {
        var ident = foundry.core.Ident.parse(b.contentId);
        var def = ident == null ? null : FoundryMod.loaded().items().get(ident);
        if (def == null) return;
        MechanicHost.fire(Trigger.ON_BOLT_HIT, b.tag, b.contentId, def.mechanics(), owner, struck);
    }
}
