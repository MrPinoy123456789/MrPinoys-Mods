package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A marked area on the floor that lands later (owner ask 2026-10-19): the boss attack the party can
 * read and step out of. An attack names its {@link AoEShapes.Shape} -- a circle, a cone or a line --
 * its origin tile and what it does; this class marks every tile of the shape with particles for the
 * warning time, then does it to whatever is still inside.
 *
 * <p>Two attack kinds:
 * <ul>
 *   <li>{@link #detonate}: the tiles mark for {@code warnTicks}, flare in the last
 *       {@link #FLARE_TICKS}, and the effect lands once on everything still inside -- a burst of
 *       damage, a poison splash, fire, a freeze hit.</li>
 *   <li>{@link #zone}: the tiles mark for {@code warnTicks} and then the area stays live for
 *       {@code durationTicks}, applying the effect every {@code intervalTicks} to whoever is in it --
 *       freezing that lets go the moment a member steps out, or a slow ticking damage that pushes the
 *       party off the ground it holds.</li>
 * </ul>
 *
 * <p>Inside is answered per tile, not per shape: an entity counts only when it stands within
 * {@link #STAND_TOLERANCE} of a marked tile's surface, so a member one story above the mark or one
 * step past its edge is safe. The marked tiles are found once when the attack arms (the top face of
 * the first solid block under each tile); a tile with no floor under it marks nothing and holds no
 * one.
 *
 * <p>Cleanup: an attack dies when it resolves or ends, when its cell ends ({@link #clear(BlockPos)},
 * wired into {@link InstanceTeardown#forgetCellState}), when the run it was armed under tears down
 * or is replaced, and at server stop ({@link SessionState#clearedAtStop}). Nothing is placed in the
 * world, so a telegraph can never strand a block or an entity.
 */
final class AoETelegraph {

    /** How often a marked tile sends its warning particle. */
    static final int RENDER_TICKS = 4;
    /** Ticks before a detonation when the mark flares, the "get out now" beat. */
    static final int FLARE_TICKS = 15;
    /** How far above or below a marked tile's surface an entity may stand and still be inside. */
    private static final double STAND_TOLERANCE = 2.0;
    /** The scan for the floor under a tile: up two (a step), down a story and a half. */
    private static final int SCAN_UP = 2;
    private static final int SCAN_DOWN = 12;
    /** The default mark: a flat red spark on the tile, the warning colour of every dungeon fight. */
    private static final ParticleOptions DEFAULT_MARKER = DustParticleOptions.REDSTONE;

    private AoETelegraph() {}

    /** What lands on whoever is still inside when the attack resolves. */
    @FunctionalInterface
    interface Effect {
        void apply(ServerLevel level, LivingEntity entity);
    }

    /** Plain damage against the generic source (difficulty-free, the haunt strike's source). */
    static Effect damage(float amount) {
        return (level, entity) -> entity.hurtServer(level, level.damageSources().generic(), amount);
    }

    /** Sets the entity alight for {@code seconds}. */
    static Effect ignite(float seconds) {
        return (level, entity) -> entity.igniteForSeconds(seconds);
    }

    /**
     * Adds {@code ticks} of freeze, capped a breath past the point freeze damage starts. A zone that
     * applies it every interval is the "freezing until you step out" the ask names; vanilla thaw
     * takes over the moment the entity is out.
     */
    static Effect freeze(int ticks) {
        return (level, entity) -> entity.setTicksFrozen(
                Math.min(entity.getTicksRequiredToFreeze() + 60, entity.getTicksFrozen() + ticks));
    }

    /** A mob effect (poison, slowness, weakness), made fresh per entity it lands on. */
    static Effect potion(Supplier<MobEffectInstance> effect) {
        return (level, entity) -> entity.addEffect(effect.get());
    }

    /** Several effects as one. */
    static Effect all(Effect... effects) {
        return (level, entity) -> {
            for (Effect effect : effects) {
                effect.apply(level, entity);
            }
        };
    }

    // ---- the attack ---------------------------------------------------------------------------------

    /** One armed attack: where it lands, when, and what it does. */
    private static final class Active {
        final ServerLevel level;
        /** The run the attack belongs to, or {@code null} when none claims it; a torn down or replaced record kills it. */
        final InstanceRecord record;
        /** The chunk column of the origin tile, for {@link #clear(BlockPos)}. */
        final long cellKey;
        final BlockPos origin;
        final AoEShapes.Shape shape;
        /** The marked tiles' surfaces (feet level), for the particles. */
        final List<BlockPos> marks;
        /** Packed {@code dx,dz} tile -> marked surface y, for the inside test. */
        final Map<Long, Integer> tileY;
        final Effect effect;
        final ParticleOptions marker;
        final boolean standing;
        final long armedAt;
        final long resolveAt;
        final long endAt;
        final int warnTicks;
        final int intervalTicks;
        long nextApply = Long.MIN_VALUE;
        long nextRender = Long.MIN_VALUE;

        Active(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
               List<BlockPos> marks, Map<Long, Integer> tileY, Effect effect, ParticleOptions marker,
               boolean standing, long armedAt, long resolveAt, long endAt, int warnTicks, int intervalTicks) {
            this.level = level;
            this.record = record;
            this.cellKey = cellKey(origin);
            this.origin = origin.immutable();
            this.shape = shape;
            this.marks = marks;
            this.tileY = tileY;
            this.effect = effect;
            this.marker = marker;
            this.standing = standing;
            this.armedAt = armedAt;
            this.resolveAt = resolveAt;
            this.endAt = endAt;
            this.warnTicks = warnTicks;
            this.intervalTicks = intervalTicks;
        }
    }

    private static final List<Active> ACTIVE = SessionState.clearedAtStop(new ArrayList<>());
    private static boolean registered;

    /** Wires the tick. Called once from {@code PocketDungeonsMod#onInitialize}. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(AoETelegraph::poll);
    }

    /**
     * A burst telegraph: marks {@code shape}'s tiles for {@code warnTicks}, then applies
     * {@code effect} to everything still inside and is done. {@code record} is the run the attack
     * belongs to ({@code ctx.record()} from a finale, a guardian's record), or {@code null} for one
     * nobody claims -- either way the cell's end drops it.
     */
    static void detonate(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
                         int warnTicks, Effect effect) {
        detonate(level, record, origin, shape, warnTicks, effect, DEFAULT_MARKER);
    }

    static void detonate(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
                         int warnTicks, Effect effect, ParticleOptions marker) {
        arm(level, record, origin, shape, Math.max(1, warnTicks), effect, marker, false, 0, 0);
    }

    /**
     * A standing zone: marks for {@code warnTicks}, then stays live for {@code durationTicks} and
     * applies {@code effect} every {@code intervalTicks} to whoever is inside. Stepping out ends it
     * for that member.
     */
    static void zone(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
                     int warnTicks, int durationTicks, int intervalTicks, Effect effect) {
        zone(level, record, origin, shape, warnTicks, durationTicks, intervalTicks, effect, DEFAULT_MARKER);
    }

    static void zone(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
                     int warnTicks, int durationTicks, int intervalTicks, Effect effect, ParticleOptions marker) {
        arm(level, record, origin, shape, Math.max(1, warnTicks), effect, marker, true,
                Math.max(1, durationTicks), Math.max(1, intervalTicks));
    }

    /** The attacks still pending or live in {@code cellOrigin}'s cell (a test seam). */
    static int liveCount(BlockPos cellOrigin) {
        long key = cellKey(cellOrigin);
        int live = 0;
        for (Active active : ACTIVE) {
            if (active.cellKey == key) {
                live++;
            }
        }
        return live;
    }

    /**
     * The cell at {@code cellOrigin} is ending: its attacks die unspent. Wired into
     * {@link InstanceTeardown#forgetCellState}; the cell is one chunk column, so the origin tile's
     * column decides.
     */
    static void clear(BlockPos cellOrigin) {
        long key = cellKey(cellOrigin);
        ACTIVE.removeIf(active -> active.cellKey == key);
    }

    // ---- arming and ticking ---------------------------------------------------------------------------

    private static void arm(ServerLevel level, InstanceRecord record, BlockPos origin, AoEShapes.Shape shape,
                            int warnTicks, Effect effect, ParticleOptions marker, boolean standing,
                            int durationTicks, int intervalTicks) {
        List<BlockPos> marks = new ArrayList<>();
        Map<Long, Integer> tileY = new HashMap<>();
        for (int[] tile : shape.tiles()) {
            int y = surfaceY(level, origin.getX() + tile[0], origin.getY(), origin.getZ() + tile[1]);
            if (y == Integer.MIN_VALUE) {
                continue; // no floor under this tile: nothing to mark and nothing to stand on
            }
            marks.add(new BlockPos(origin.getX() + tile[0], y, origin.getZ() + tile[1]));
            tileY.put(pack(tile[0], tile[1]), y);
        }
        long now = level.getServer().getTickCount();
        ACTIVE.add(new Active(level, record, origin, shape, marks, tileY, effect, marker, standing, now,
                now + warnTicks, now + warnTicks + durationTicks, warnTicks, intervalTicks));
        level.playSound(null, origin, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    private static void poll(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        for (Active active : new ArrayList<>(ACTIVE)) {
            if (gone(active)) {
                ACTIVE.remove(active);
                continue;
            }
            if (!active.standing && now >= active.resolveAt) {
                resolve(active);
                ACTIVE.remove(active);
                continue;
            }
            if (active.standing && now >= active.endAt) {
                ACTIVE.remove(active);
                continue;
            }
            if (now >= active.nextRender) {
                active.nextRender = now + RENDER_TICKS;
                render(active, now);
            }
            if (active.standing && now >= active.armedAt + active.warnTicks && now >= active.nextApply) {
                active.nextApply = now + active.intervalTicks;
                apply(active);
            }
        }
    }

    /** Whether the attack's owner is gone: the run torn down, or its slot taken by another run. */
    private static boolean gone(Active active) {
        return active.record != null
                && (active.record.tearingDown || InstanceRegistry.bySlot.get(active.record.slot) != active.record);
    }

    /** One render pass: a flat spark on every marked tile, flaring as a detonation closes. */
    private static void render(Active active, long now) {
        boolean flaring = !active.standing && active.resolveAt - now <= FLARE_TICKS;
        for (BlockPos mark : active.marks) {
            active.level.sendParticles(active.marker, mark.getX() + 0.5, mark.getY() + 0.08, mark.getZ() + 0.5,
                    flaring ? 3 : 1, 0.05, 0.02, 0.05, 0.0);
            if (flaring) {
                active.level.sendParticles(ParticleTypes.FLAME, mark.getX() + 0.5, mark.getY() + 0.1,
                        mark.getZ() + 0.5, 1, 0.1, 0.02, 0.1, 0.0);
            }
        }
    }

    /** The warning is over: the effect lands on everything still inside, with the burst to sell it. */
    private static void resolve(Active active) {
        ServerLevel level = active.level;
        apply(active);
        for (BlockPos mark : active.marks) {
            level.sendParticles(ParticleTypes.FLAME, mark.getX() + 0.5, mark.getY() + 0.2, mark.getZ() + 0.5,
                    3, 0.2, 0.1, 0.2, 0.02);
        }
        level.playSound(null, active.origin, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.7f, 1.2f);
    }

    /** Applies the effect to every living entity that stands inside the marked area right now. */
    private static void apply(Active active) {
        AABB bounds = bounds(active);
        for (LivingEntity entity : active.level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            if (inside(active, entity)) {
                active.effect.apply(active.level, entity);
            }
        }
    }

    /**
     * Whether {@code entity} stands inside: inside the shape, and within {@link #STAND_TOLERANCE} of
     * the surface of the marked tile under it. A tile with no mark (no floor found at arm) holds no
     * one, so a member hovering over a gap is safe.
     */
    private static boolean inside(Active active, LivingEntity entity) {
        if (!entity.isAlive() || entity.isInvulnerableTo(active.level, active.level.damageSources().generic())
                || entity instanceof ServerPlayer player && (player.isSpectator() || player.isCreative())) {
            return false;
        }
        Integer y = active.tileY.get(pack((int) Math.floor(entity.getX()) - active.origin.getX(),
                (int) Math.floor(entity.getZ()) - active.origin.getZ()));
        return y != null && Math.abs(entity.getY() - y) <= STAND_TOLERANCE;
    }

    /** The box the entity sweep reads: the marked tiles, padded an arm's length up and down. */
    private static AABB bounds(Active active) {
        if (active.marks.isEmpty()) {
            return new AABB(active.origin).inflate(0.5);
        }
        AABB out = new AABB(active.marks.get(0));
        for (BlockPos mark : active.marks) {
            out = out.minmax(new AABB(mark));
        }
        return out.inflate(1.0, STAND_TOLERANCE + 1.0, 1.0);
    }

    /** The y of the first tile a member could stand on at {@code x,z}, or {@code MIN_VALUE} for none. */
    private static int surfaceY(ServerLevel level, int x, int originY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, originY + SCAN_UP, z);
        for (int y = originY + SCAN_UP; y >= originY - SCAN_DOWN; y--) {
            cursor.setY(y);
            if (!level.isLoaded(cursor)) {
                return Integer.MIN_VALUE;
            }
            if (level.getBlockState(cursor).isSolid() && !level.getBlockState(cursor.above()).isSolid()) {
                return y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The cell is one chunk column; its key is the packed chunk coordinates of any tile in it. */
    private static long cellKey(BlockPos pos) {
        return ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
    }

    /** A tile offset as one map key; offsets never leave the few hundred a shape can reach. */
    private static long pack(int dx, int dz) {
        return ((long) dx << 32) | (dz & 0xFFFFFFFFL);
    }
}
