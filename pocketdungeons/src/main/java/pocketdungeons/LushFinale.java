package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Lush Caves finale (roadmap T23; boss workshop 2026-10-11, row 2): the data wave of drowned and slimes
 * gives way to one giant slime. On its first split the boss becomes double the usual number of children
 * (vanilla gives 2 to 4; this tops the count up to double); every later split is vanilla. Every slime counts
 * toward the wave, so the pad stays shut until all are dead, and the number alive at once is capped
 * ({@code lushSlimeMaxAlive}) so the crowd can never lag a server. The potion drip speeds up for the fight
 * ({@link PotionDrip#setBoss}), and on her own clock she sheds a slime pool under a member: the tiles are
 * marked through {@link AoETelegraph} first, then the pool stands for a while, slowing and dissolving
 * whoever stays in it.
 *
 * <p>Vanilla splits a slime as the body is removed, about a second after it dies, so a slime that has just
 * died is not alive but its children do not exist yet. To keep the pad shut across that gap a dead splitter
 * puts a guard in the wave (an invisible, invulnerable bat) until its children are found and counted.
 */
final class LushFinale implements FinaleScript {

    /** The boss's name on its bar. */
    static final String BOSS_NAME = "The Great Drip";

    /** Tag on the guard that holds the wave open while a split is pending. */
    static final String GUARD_TAG = PocketDungeonsMod.MOD_ID + ".lush_guard";

    private record Track(BlockPos pos, int size, boolean boss) {}

    private static final class Pending {
        final UUID parent;
        final BlockPos pos;
        final int size;
        final boolean first;
        final long deadline;

        Pending(UUID parent, BlockPos pos, int size, boolean first, long deadline) {
            this.parent = parent;
            this.pos = pos;
            this.size = size;
            this.first = first;
            this.deadline = deadline;
        }
    }

    /** Per run state: what each slime last was (size and place), the splits waiting for children, the guard. */
    private static final class State {
        final Map<UUID, Track> tracked = new HashMap<>();
        final List<Pending> pending = new ArrayList<>();
        UUID guard;
        boolean firstSplitDone;
        long nextPool = Long.MAX_VALUE;
    }

    /** A tick in a shed pool: slowed and dissolved. Only the party reads a pool -- slimes swim in it. */
    private static final AoETelegraph.Effect POOL_EFFECT = (level, entity) -> {
        if (entity instanceof ServerPlayer player) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, LushFinaleRules.POOL_SLOW_TICKS, 0,
                    false, true));
            player.hurtServer(level, level.damageSources().generic(), LushFinaleRules.POOL_DAMAGE);
        }
    };

    // ---- the boss -----------------------------------------------------------------------------------

    @Override
    public boolean spawnWave(FinaleContext ctx) {
        int party = ctx.party();
        int health = FinaleRules.eliteHealth(PocketDungeonsConfig.lushSlimeHealth(), party,
                PocketDungeonsConfig.finaleEliteHealthPercentPerMember());
        DungeonDef.Elite elite = new DungeonDef.Elite("minecraft:slime", BOSS_NAME, health, "");
        Mob mob = ctx.spawnElite(elite);
        if (!(mob instanceof Slime boss)) {
            if (mob != null) {
                mob.discard();
            }
            return false;
        }
        int size = PocketDungeonsConfig.lushSlimeSize();
        boss.setSize(size, true);
        while (!placed(ctx, boss) && LushFinaleRules.splits(size) && size > 2) {
            size = LushFinaleRules.smallerSize(size);
            boss.setSize(size, true);
        }
        if (boss.getAttribute(Attributes.MAX_HEALTH) != null) {
            boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        }
        boss.setHealth(boss.getMaxHealth());
        ctx.scriptState(State.class, State::new).tracked.put(boss.getUUID(),
                new Track(boss.blockPosition(), boss.getSize(), true));
        return true;
    }

    /** Moves the boss to a spot near the party where its body fits; true when it stands clear. */
    private static boolean placed(FinaleContext ctx, Slime boss) {
        ServerLevel level = ctx.level();
        if (level.noCollision(boss)) {
            return true;
        }
        for (int attempt = 0; attempt < 30; attempt++) {
            BlockPos spot = ctx.freeSpotNearParty();
            if (spot == null) {
                continue;
            }
            boss.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            if (level.noCollision(boss)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        PotionDrip.setBoss(ctx.record(), true);
        ctx.scriptState(State.class, State::new).nextPool = ctx.tickCount() + LushFinaleRules.POOL_FIRST_TICKS;
    }

    // ---- the splits ---------------------------------------------------------------------------------

    @Override
    public void onMobDied(FinaleContext ctx, UUID mob) {
        State state = ctx.scriptState(State.class, State::new);
        Track track = state.tracked.get(mob);
        if (track == null || !LushFinaleRules.splits(track.size())) {
            return;
        }
        Entity body = ctx.level().getEntity(mob);
        BlockPos at = body != null ? body.blockPosition() : track.pos();
        boolean first = track.boss() && !state.firstSplitDone;
        if (first) {
            state.firstSplitDone = true;
        }
        state.pending.add(new Pending(mob, at, track.size(), first,
                ctx.tickCount() + LushFinaleRules.SPLIT_WINDOW_TICKS));
        if (state.guard == null || !(ctx.level().getEntity(state.guard) instanceof Mob guard) || !guard.isAlive()) {
            state.guard = spawnGuard(ctx, at);
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        State state = ctx.scriptState(State.class, State::new);
        ServerLevel level = ctx.level();
        for (Iterator<Pending> it = state.pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            Entity body = level.getEntity(p.parent);
            boolean bodyGone = body == null || body.isRemoved();
            if (!bodyGone && ctx.tickCount() < p.deadline) {
                continue;
            }
            it.remove();
            resolve(ctx, state, p);
        }
        // Remember where each live slime is, for the day it dies.
        for (Mob mob : ctx.liveMobs()) {
            if (mob instanceof Slime slime) {
                Track old = state.tracked.get(slime.getUUID());
                state.tracked.put(slime.getUUID(), new Track(slime.blockPosition(), slime.getSize(),
                        old != null && old.boss()));
            }
        }
        if (state.pending.isEmpty() && state.guard != null) {
            Entity guard = level.getEntity(state.guard);
            if (guard != null) {
                guard.discard();
            }
            state.guard = null;
        }
        long now = ctx.tickCount();
        if (now >= state.nextPool) {
            state.nextPool = now + LushFinaleRules.POOL_INTERVAL_TICKS;
            shed(ctx);
        }
    }

    /** The slime sheds a standing pool under one member: marked first, then live for a while. */
    private void shed(FinaleContext ctx) {
        List<ServerPlayer> members = ctx.members();
        if (members.isEmpty()) {
            return;
        }
        ServerPlayer target = members.get(ctx.level().getRandom().nextInt(members.size()));
        AoETelegraph.zone(ctx.level(), ctx.record(), target.blockPosition(),
                new AoEShapes.Circle(LushFinaleRules.POOL_RADIUS), LushFinaleRules.POOL_WARN_TICKS,
                LushFinaleRules.POOL_LIVE_TICKS, LushFinaleRules.POOL_TICK, POOL_EFFECT);
    }

    /** Finds the children a dead slime left, counts them toward the wave, and on the first split doubles them. */
    private void resolve(FinaleContext ctx, State state, Pending p) {
        ServerLevel level = ctx.level();
        int childSize = LushFinaleRules.childSize(p.size);
        double reach = 0.26 * p.size + 1.5;
        AABB around = new AABB(p.pos).inflate(reach);
        List<UUID> known = ctx.mobIds();
        List<Slime> found = new ArrayList<>();
        for (Slime slime : level.getEntitiesOfClass(Slime.class, around)) {
            // Children are brand new; a slime that has stood a while is not one (a spawner's, or a stray).
            if (slime.isAlive() && slime.getSize() == childSize && slime.tickCount <= LushFinaleRules.SPLIT_WINDOW_TICKS
                    && !known.contains(slime.getUUID())) {
                found.add(slime);
            }
        }
        int cap = PocketDungeonsConfig.lushSlimeMaxAlive();
        int alive = ctx.alive();
        int kept = LushFinaleRules.keep(found.size(), alive, cap);
        ServerPlayer target = ctx.members().isEmpty() ? null : ctx.members().get(0);
        for (int i = 0; i < found.size(); i++) {
            Slime child = found.get(i);
            if (i >= kept) {
                child.discard();
                continue;
            }
            claim(ctx, state, child, target);
        }
        if (p.first) {
            int extra = LushFinaleRules.topUp(found.size(), kept, alive, cap);
            RandomSource random = level.getRandom();
            for (int i = 0; i < extra; i++) {
                Entity spawned = FinaleWave.spawn(level, "minecraft:slime", p.pos, random);
                if (spawned instanceof Slime child) {
                    child.setSize(childSize, true);
                    child.setPos(p.pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 2.0, p.pos.getY(),
                            p.pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 2.0);
                    claim(ctx, state, child, target);
                }
            }
            PocketDungeonsMod.LOG.info("Lush finale of slot {}: first split gave {} vanilla, {} extra",
                    ctx.record().slot, found.size(), extra);
        }
    }

    private static void claim(FinaleContext ctx, State state, Slime child, ServerPlayer target) {
        ctx.addMob(child);
        if (target != null) {
            FinaleWave.provoke(child, target);
        }
        state.tracked.put(child.getUUID(), new Track(child.blockPosition(), child.getSize(), false));
    }

    /** The guard: a bat nobody can hurt, see or hear, that keeps the wave open for a pending split. */
    private static UUID spawnGuard(FinaleContext ctx, BlockPos at) {
        Mob bat = FinaleWave.spawn(ctx.level(), "minecraft:bat", at, ctx.level().getRandom());
        if (bat == null) {
            return null;
        }
        bat.setInvulnerable(true);
        bat.setInvisible(true);
        bat.setSilent(true);
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.addTag(GUARD_TAG);
        bat.setTarget(null);
        ctx.addMob(bat);
        return bat.getUUID();
    }

    // ---- the end ------------------------------------------------------------------------------------

    @Override
    public void onWon(FinaleContext ctx) {
        PotionDrip.setBoss(ctx.record(), false);
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        PotionDrip.setBoss(ctx.record(), false);
    }
}
