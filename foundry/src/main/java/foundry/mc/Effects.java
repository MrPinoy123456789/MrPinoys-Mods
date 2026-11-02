package foundry.mc;

import foundry.core.mechanics.Step;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The look and push of weapon hits: knockback and particle shapes (rings, bursts, lines, columns, arcs and spirals) for
 * the {@code knockback} and {@code particle_shape} actions. Everything is vanilla particles and motion, so Bedrock
 * players see and feel it too.
 */
final class Effects {
    private Effects() {}

    /**
     * Pushes {@code target} away from {@code from}, across the ground by {@code strength} and up by {@code lift}; a
     * negative strength pulls it in (an undertow).
     */
    static void knock(ServerPlayer from, LivingEntity target, double strength, double lift) {
        Vec3 away = target.position().subtract(from.position());
        Vec3 flat = new Vec3(away.x, 0, away.z);
        flat = flat.lengthSqr() < 1.0E-4 ? from.getLookAngle().multiply(1, 0, 1) : flat;
        flat = flat.normalize().scale(strength * (1 - target.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)));
        target.push(flat.x, lift, flat.z);
        target.syncVelocity = true;
    }

    /**
     * Draws a shape, centred on the struck entity (or the caster): a {@code ring} on the ground around it, a
     * {@code burst} out from its middle, a {@code line} from the caster's hand to it, a {@code column} rising from it,
     * an {@code arc} sweeping in front of the caster (a blade's swing), or a {@code spiral} climbing around it.
     * {@code colour} draws coloured dust in place of the particle.
     */
    static void shape(ServerLevel level, ServerPlayer caster, LivingEntity struck, Step action) {
        ParticleOptions particle = particle(action);
        if (particle == null) return;
        LivingEntity at = struck != null ? struck : caster;
        String shape = action.param("shape", "ring");
        int count = action.intParam("count", 24);
        double r = action.decimalParam("radius", 1.5);
        double h = action.decimalParam("height", 0.1);
        Vec3 base = at.position().add(0, h, 0);
        switch (shape) {
            case "ring" -> {
                for (int i = 0; i < count; i++) {
                    double a = Math.PI * 2 * i / count;
                    send(level, particle, base.x + Math.cos(a) * r, base.y, base.z + Math.sin(a) * r);
                }
            }
            case "burst" -> {
                Vec3 mid = at.position().add(0, at.getBbHeight() / 2, 0);
                var random = level.getRandom();
                for (int i = 0; i < count; i++) {
                    Vec3 d = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().scale(r * random.nextDouble());
                    send(level, particle, mid.x + d.x, mid.y + d.y, mid.z + d.z);
                }
            }
            case "line" -> {
                Vec3 from = caster.getEyePosition().add(0, -0.3, 0);
                Vec3 to = at == caster ? from.add(caster.getLookAngle().scale(r)) : at.position().add(0, at.getBbHeight() / 2, 0);
                for (int i = 0; i <= count; i++) {
                    Vec3 p = from.lerp(to, (double) i / count);
                    send(level, particle, p.x, p.y, p.z);
                }
            }
            case "column" -> {
                for (int i = 0; i < count; i++) {
                    double y = r * i / Math.max(1, count - 1);
                    send(level, particle, base.x, base.y + y, base.z);
                }
            }
            case "arc" -> {
                // a half circle in front of the caster at chest height, from their right to their left
                double yaw = Math.toRadians(caster.getYRot());
                Vec3 c = caster.position().add(0, 1.1 + h, 0);
                for (int i = 0; i < count; i++) {
                    double a = yaw - Math.PI / 2 + Math.PI * i / Math.max(1, count - 1);
                    send(level, particle, c.x - Math.sin(a) * r, c.y + Math.sin(i * 0.4) * 0.05, c.z + Math.cos(a) * r);
                }
            }
            case "spiral" -> {
                double height = Math.max(1, at.getBbHeight());
                for (int i = 0; i < count; i++) {
                    double t = (double) i / count;
                    double a = t * Math.PI * 6;
                    send(level, particle, base.x + Math.cos(a) * r, base.y + t * height, base.z + Math.sin(a) * r);
                }
            }
            default -> { }
        }
    }

    private static ParticleOptions particle(Step action) {
        String colour = action.param("colour", "");
        if (!colour.isEmpty()) {
            return new DustParticleOptions(Integer.parseInt(colour.substring(1), 16), (float) action.decimalParam("size", 1.0));
        }
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(action.param("particle", "minecraft:end_rod")));
        return type instanceof SimpleParticleType s ? s : ParticleTypes.END_ROD;
    }

    private static void send(ServerLevel level, ParticleOptions particle, double x, double y, double z) {
        level.sendParticles(particle, x, y, z, 1, 0, 0, 0, 0);
    }
}
