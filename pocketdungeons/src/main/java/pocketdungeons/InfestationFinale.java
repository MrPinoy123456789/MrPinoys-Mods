package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The Infestation's finale (roadmap T24): in place of the data wave, a giant silverfish, The Queen. She spawns
 * in like the other bosses (named, boss bar) at {@code infestationBossScale} times normal size, fights, and after
 * {@code infestationSurfaceSeconds} rears up (a tell), then burrows: invisible, invulnerable and under the floor
 * in a puff of dust. The floor cracks (a second tell) and the adds phase begins: mossy bricks are planted around
 * the arena and ripen into silverfish through {@link CreepingBricks}. When every brick is gone, or the phase
 * runs out, the ground heaves (a third tell) and she rises again; the cycle repeats. The fight ends when she is
 * killed, which cannot happen while she is burrowed. The schedule is {@link InfestationRules}.
 *
 * <p>Her silverfish count toward the wave, so the pad stays shut until all are dead; the alive cap is
 * {@code infestationSilverfishCap}. Everything placed is given back in {@link #onFloorEnded}, and when she dies
 * the bricks and the rest of her brood go with her.
 */
final class InfestationFinale implements FinaleScript {

    static final String DUNGEON_ID = "pocketdungeons:infestation";
    static final String BOSS_NAME = "The Queen";

    /** How far under her feet the burrowed boss is kept, out of reach. */
    private static final int BURROW_DEPTH = 4;
    /** Planting tries per brick before the spot is given up. */
    private static final int PLANT_TRIES = 24;
    /** Failed brick placements in a row after which the phase stops waiting for the rest. */
    private static final int MAX_FAILS = 4;
    /** A brick is never planted this close to a member or the exit pad. */
    private static final double KEEP_CLEAR = 2.5;

    /** One run's state, kept in {@link FinaleContext#scriptState}. */
    static final class Run {
        InfestationRules.Phase phase = InfestationRules.Phase.SURFACE;
        long phaseStart;
        CreepingBricks.Field field;
        UUID boss;
        boolean bossDead;
        int planted;
        int total;
        int fails;
        long nextPlant;
        Vec3 burrowAt;
        Vec3 emergeAt;
    }

    @Override
    public boolean spawnWave(FinaleContext ctx) {
        DungeonDef.Elite elite = new DungeonDef.Elite("minecraft:silverfish", BOSS_NAME,
                PocketDungeonsConfig.infestationBossHealth(), "");
        Mob boss = ctx.spawnElite(elite);
        if (boss == null) {
            return false; // no member to spawn near: the data wave stands in
        }
        boss.getAttribute(Attributes.SCALE).setBaseValue(PocketDungeonsConfig.infestationBossScale());
        boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(PocketDungeonsConfig.infestationBossDamage());
        Run run = ctx.scriptState(Run.class, Run::new);
        run.boss = boss.getUUID();
        run.phaseStart = ctx.tickCount();
        run.field = CreepingBricks.open(ctx.level(), ctx.record().origin, PocketDungeonsConfig.infestationArenaBrickCap(),
                true);
        return true;
    }

    @Override
    public void onMobDied(FinaleContext ctx, UUID mob) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (mob.equals(run.boss)) {
            run.bossDead = true;
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (run.field == null) {
            return;
        }
        for (Mob released : run.field.collect()) {
            ctx.addMob(released);
        }
        if (run.bossDead) {
            endFight(ctx, run);
            return;
        }
        Mob boss = bossOf(ctx, run);
        int aliveAdds = 0;
        for (Mob mob : ctx.liveMobs()) {
            if (mob == boss) {
                continue;
            }
            if (mob.getType() == EntityTypes.SILVERFISH) {
                aliveAdds++;
            }
            keepTarget(ctx, mob);
        }
        if (boss != null && !run.phase.burrowed()) {
            keepTarget(ctx, boss); // a silverfish with no target may merge into the stone and vanish
        }
        run.field.releaseBudget =InfestationRules.releaseBudget(aliveAdds,
                PocketDungeonsConfig.infestationSilverfishCap());
        long now = ctx.tickCount();
        InfestationRules.Timing timing = timing();
        boolean addsDone = run.phase == InfestationRules.Phase.ADDS
                && InfestationRules.addsDone(run.planted, run.total, run.field.active());
        InfestationRules.Phase next = InfestationRules.next(run.phase, now - run.phaseStart, timing, addsDone);
        if (next != run.phase) {
            run.phase = next;
            run.phaseStart = now;
            enter(ctx, run, boss, next);
        } else {
            during(ctx, run, boss, now);
        }
    }

    @Override
    public void onWon(FinaleContext ctx) {
        release(ctx);
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        release(ctx);
    }

    private static InfestationRules.Timing timing() {
        return InfestationRules.timing(PocketDungeonsConfig.infestationSurfaceSeconds(),
                PocketDungeonsConfig.infestationBurrowTellSeconds(), PocketDungeonsConfig.infestationAddsTellSeconds(),
                PocketDungeonsConfig.infestationAddsMaxSeconds(), PocketDungeonsConfig.infestationEmergeTellSeconds());
    }

    // ---- phases -----------------------------------------------------------------------------------------

    private void enter(FinaleContext ctx, Run run, Mob boss, InfestationRules.Phase phase) {
        ServerLevel level = ctx.level();
        switch (phase) {
            case BURROW_TELL -> {
                say(ctx, "The Queen rears up.");
                if (boss != null) {
                    level.playSound(null, boss.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2f, 0.5f);
                    dust(level, boss.position(), 40);
                }
            }
            case ADDS_TELL -> {
                if (boss != null) {
                    burrow(level, run, boss);
                }
                say(ctx, "The floor cracks.");
            }
            case ADDS -> {
                run.total = InfestationRules.bricksFor(ctx.party(), PocketDungeonsConfig.infestationBricksPerPhase(),
                        PocketDungeonsConfig.infestationBricksPerExtraMember());
                run.planted = 0;
                run.fails = 0;
                run.nextPlant = ctx.tickCount();
            }
            case EMERGE_TELL -> {
                BlockPos spot = ctx.freeSpotNearParty();
                run.emergeAt = spot != null ? Vec3.atBottomCenterOf(spot) : run.burrowAt;
                say(ctx, "Something rises.");
            }
            case SURFACE -> {
                if (boss != null) {
                    emerge(ctx, run, boss);
                }
            }
            default -> { }
        }
    }

    private void during(FinaleContext ctx, Run run, Mob boss, long now) {
        ServerLevel level = ctx.level();
        switch (run.phase) {
            case BURROW_TELL -> {
                if (boss != null) {
                    dust(level, boss.position(), 12);
                    level.playSound(null, boss.blockPosition(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.0f, 0.6f);
                }
            }
            case ADDS_TELL -> {
                for (int i = 0; i < 3; i++) {
                    BlockPos spot = arenaSpot(ctx, level.getRandom());
                    if (spot != null) {
                        CreepingBricks.crack(level, spot, Blocks.STONE_BRICKS.defaultBlockState(), 8);
                        level.playSound(null, spot, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8f, 0.6f);
                    }
                }
            }
            case ADDS -> plant(ctx, run, now);
            case EMERGE_TELL -> {
                if (run.emergeAt != null) {
                    dust(level, run.emergeAt, 16);
                    level.playSound(null, BlockPos.containing(run.emergeAt), SoundEvents.ROOTED_DIRT_BREAK,
                            SoundSource.HOSTILE, 1.2f, 0.5f);
                }
            }
            default -> { }
        }
    }

    /** Plants one brick when the interval is up and the arena and floor caps allow. */
    private void plant(FinaleContext ctx, Run run, long now) {
        if (now < run.nextPlant || run.planted >= run.total) {
            return;
        }
        int slots = InfestationRules.plantNow(run.total - run.planted, run.field.active(),
                PocketDungeonsConfig.infestationArenaBrickCap(), CreepingBricks.floorActive(run.field.floorKey),
                PocketDungeonsConfig.creepingFloorCap());
        if (slots <= 0) {
            return;
        }
        run.nextPlant = now + PocketDungeonsConfig.infestationPlantSeconds() * 20L;
        BlockPos spot = null;
        for (int attempt = 0; attempt < PLANT_TRIES && spot == null; attempt++) {
            BlockPos candidate = arenaSpot(ctx, ctx.level().getRandom());
            if (candidate != null && !run.field.bricks.containsKey(candidate)) {
                spot = candidate;
            }
        }
        if (spot != null && run.field.plant(spot, now)) {
            run.planted++;
            run.fails = 0;
        } else if (++run.fails >= MAX_FAILS) {
            run.total = run.planted; // no room to grow more: the phase ends with what stands
        }
    }

    /** A free floor spot in the arena (the final cell), clear of the members and the pad; {@code null} if none. */
    private BlockPos arenaSpot(FinaleContext ctx, net.minecraft.util.RandomSource random) {
        InstanceLayout layout = ctx.record().layout;
        BlockPos corner = layout == null ? null : layout.terminal();
        if (corner == null) {
            return null;
        }
        ServerLevel level = ctx.level();
        for (int attempt = 0; attempt < PLANT_TRIES; attempt++) {
            int x = 1 + random.nextInt(RoomGeometry.CELL - 2);
            int z = 1 + random.nextInt(RoomGeometry.CELL - 2);
            if (CreepingRules.inDoorLane(x, z)) {
                continue;
            }
            BlockPos at = corner.offset(x, 1, z);
            if (!level.isLoaded(at) || !level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()
                    || !level.getBlockState(at.below()).isSolid()) {
                continue;
            }
            Vec3 centre = Vec3.atBottomCenterOf(at);
            if (tooClose(ctx, centre)) {
                continue;
            }
            return at;
        }
        return null;
    }

    private static boolean tooClose(FinaleContext ctx, Vec3 centre) {
        for (ServerPlayer member : ctx.members()) {
            if (member.position().distanceTo(centre) < KEEP_CLEAR) {
                return true;
            }
        }
        BlockPos pad = ctx.record().layout == null ? null : ctx.record().layout.exitPad();
        return pad != null && Vec3.atCenterOf(pad).distanceTo(centre) < KEEP_CLEAR;
    }

    // ---- the boss goes down and comes up ---------------------------------------------------------------

    private static void burrow(ServerLevel level, Run run, Mob boss) {
        run.burrowAt = boss.position();
        dust(level, boss.position(), 60);
        level.playSound(null, boss.blockPosition(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.5f, 0.4f);
        boss.setTarget(null);
        boss.setNoAi(true);
        boss.setNoGravity(true);
        boss.setInvulnerable(true);
        boss.setInvisible(true);
        boss.setSilent(true);
        boss.setCustomNameVisible(false);
        boss.removeEffect(MobEffects.GLOWING);
        boss.setPos(run.burrowAt.x, run.burrowAt.y - BURROW_DEPTH, run.burrowAt.z);
    }

    private static void emerge(FinaleContext ctx, Run run, Mob boss) {
        ServerLevel level = ctx.level();
        Vec3 at = run.emergeAt != null ? run.emergeAt : run.burrowAt;
        if (at != null) {
            boss.setPos(at.x, at.y, at.z);
        }
        boss.setNoAi(false);
        boss.setNoGravity(false);
        boss.setInvulnerable(false);
        boss.setInvisible(false);
        boss.setSilent(false);
        boss.setCustomNameVisible(true);
        boss.fallDistance = 0;
        dust(level, boss.position(), 60);
        level.playSound(null, boss.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.4f, 0.6f);
        keepTarget(ctx, boss);
    }

    /** Gives back whatever a burrow changed, for a boss that is still alive when the finale ends. */
    private static void unburrowFlags(Mob boss) {
        boss.setNoAi(false);
        boss.setNoGravity(false);
        boss.setInvulnerable(false);
        boss.setInvisible(false);
        boss.setSilent(false);
    }

    // ---- endings ----------------------------------------------------------------------------------------

    /** The boss is dead: her bricks are put back and the rest of her brood goes with her. */
    private void endFight(FinaleContext ctx, Run run) {
        run.field.close();
        for (Mob mob : ctx.liveMobs()) {
            dust(ctx.level(), mob.position(), 10);
            mob.discard();
        }
    }

    private void release(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (run.field != null) {
            run.field.close();
        }
        Mob boss = bossOf(ctx, run);
        if (boss != null && run.phase.burrowed()) {
            Vec3 at = run.burrowAt != null ? run.burrowAt : boss.position();
            boss.setPos(at.x, at.y, at.z);
            unburrowFlags(boss);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static Mob bossOf(FinaleContext ctx, Run run) {
        if (run.boss == null) {
            return null;
        }
        Entity entity = ctx.level().getEntity(run.boss);
        return entity instanceof Mob mob && mob.isAlive() ? mob : null;
    }

    /** Points a mob with no target at the nearest member, so a silverfish fights instead of hiding in the stone. */
    private static void keepTarget(FinaleContext ctx, Mob mob) {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            return;
        }
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer member : ctx.members()) {
            double d = member.distanceToSqr(mob);
            if (d < best) {
                best = d;
                nearest = member;
            }
        }
        if (nearest != null) {
            mob.setTarget(nearest);
        }
    }

    private static void say(FinaleContext ctx, String text) {
        for (ServerPlayer member : ctx.members()) {
            member.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
        }
    }

    private static void dust(ServerLevel level, Vec3 at, int count) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE_BRICKS.defaultBlockState()),
                at.x, at.y + 0.3, at.z, count, 0.8, 0.4, 0.8, 0.1);
    }
}
