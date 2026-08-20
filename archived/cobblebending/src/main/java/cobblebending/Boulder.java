package cobblebending;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A manually raycasted BlockDisplay projectile.
 */
public final class Boulder {

    private static final List<Boulder> ACTIVE = new ArrayList<>();

    private final ServerLevel level;
    private final ServerPlayer caster;
    private final Display.BlockDisplay display;
    private final Vec3 start;
    private Vec3 position;
    private Vec3 velocity;
    private final float gravity;
    private final float damage;
    private final boolean heavy;
    private final int slownessDuration;
    private final int maxTicks;
    private final int cooldown;
    private int ticks;
    private boolean removed;

    private Boulder(ServerPlayer caster, Vec3 start, Vec3 velocity, float scale, float gravity,
                    float damage, boolean heavy, int slownessDuration, int cooldown) {
        this.caster = caster;
        this.level = caster.level();
        this.start = start;
        this.position = start;
        this.velocity = velocity;
        this.gravity = gravity;
        this.damage = damage;
        this.heavy = heavy;
        this.slownessDuration = slownessDuration;
        this.cooldown = cooldown;
        this.maxTicks = 60;

        this.display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (this.display != null) {
            this.display.setBlockState(Blocks.COBBLESTONE.defaultBlockState());
            this.display.setTransformation(new Transformation(
                    new Vector3f(-scale / 2, -scale / 2, -scale / 2),
                    new Quaternionf(),
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()
            ));
            this.display.setPos(start);
            this.display.setNoGravity(true);
            level.addFreshEntity(this.display);
        }
    }

    /** Creates a display-backed projectile using the player's current eye position and aim. */
    public static void spawn(ServerPlayer player, float speed, float gravity, float scale, float damage,
                             int cooldown, int slownessDuration, boolean heavy) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 vel = look.scale(speed);
        Boulder b = new Boulder(player, eye, vel, scale, gravity, damage, heavy, slownessDuration, cooldown);
        ACTIVE.add(b);
    }

    /** Advances every active projectile and removes those that hit or expire. */
    public static void tickAll() {
        ACTIVE.removeIf(b -> b.tick());
    }

    private boolean tick() {
        if (removed || display == null || display.isRemoved()) {
            return true;
        }
        ticks++;
        if (ticks > maxTicks || position.distanceToSqr(start) > 64 * 64) {
            remove();
            return true;
        }

        Vec3 next = position.add(velocity);

        // Block raycast.
        ClipContext ctx = new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster);
        BlockHitResult blockHit = level.clip(ctx);
        if (blockHit.getType() != HitResult.Type.MISS) {
            impactBlock(blockHit.getBlockPos());
            remove();
            return true;
        }

        // Entity raycast.
        AABB search = AABB.ofSize(position, 1.0, 1.0, 1.0).expandTowards(velocity).inflate(0.5);
        Predicate<Entity> filter = e -> e instanceof LivingEntity && e != caster && e != display
                && (CobbleBendingMod.config().data().global().pvpDamage() || !(e instanceof ServerPlayer));
        EntityHitResult entHit = ProjectileUtil.getEntityHitResult(level, display, position, next, search, filter, 0.5F);
        if (entHit != null) {
            impactEntity((LivingEntity) entHit.getEntity());
            remove();
            return true;
        }

        position = next;
        velocity = velocity.add(0.0, -gravity, 0.0);
        display.setPos(position);
        return false;
    }

    private void impactBlock(BlockPos pos) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                position.x, position.y, position.z, 8, 0.2, 0.2, 0.2, 0.15);
        level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.5F, 0.8F);
        Chime.boulderImpact(caster);
    }

    private void impactEntity(LivingEntity target) {
        DamageSource src = caster.damageSources().playerAttack(caster);
        target.hurtServer(level, src, damage);
        if (heavy) {
            target.knockback(0.8, -velocity.x, -velocity.z, src, 0.8F);
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, slownessDuration, 2));
        }
        Chime.boulderImpact(caster);
    }

    private void remove() {
        if (display != null) {
            display.discard();
        }
        removed = true;
    }
}
