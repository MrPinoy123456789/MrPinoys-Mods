package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Ossuary finale (roadmap T21, boss workshop row 5): coffins. The Last Rest holds sealed sarcophagi; the
 * wave lies in them and rises one at a time as the party crosses the hall. A coffin cracks when a member comes
 * within {@link OssuaryRules#WAKE_RADIUS} blocks, or when {@link OssuaryRules#SKIP_DELAY_TICKS} have passed
 * since the last one cracked, so a coffin the party walked past always opens behind them. Each coffin gives a
 * tell (a stone crack sound and dust, {@link OssuaryRules#CRACK_TICKS} ticks) before its lid breaks, and no more
 * than {@link OssuaryRules#AWAKE_CAP} coffin mobs stand awake at once.
 *
 * <p>A coffin is the 1 x 3 x 2 block pattern the room stamps ({@link FinalFloorEarlySpecs}): polished blackstone
 * body and lid with a chiseled polished blackstone cap at the head. The script finds them by scanning the
 * floor's cells for that pattern, never by authored coordinates, so a rotated room works. The occupants are
 * spawned up front, dormant (no AI, invulnerable, invisible, silent) inside the body block, so they count
 * toward {@code alive} and the pad stays shut until the last is dead; opening wakes them. A floor with no
 * coffins runs the data wave instead. Every mob is added through the context, so it carries the finale tag
 * and the Restless affix never raises it again.
 */
final class OssuaryFinale implements FinaleScript {

    private enum Phase { SEALED, CRACKING, OPEN }

    /** One coffin: the cap cell at the head, the two lid cells (middle and foot) that break, and who lies in it. */
    private static final class Coffin {
        final BlockPos head;
        final BlockPos mid;
        final BlockPos foot;
        final List<UUID> occupants = new ArrayList<>();
        Phase phase = Phase.SEALED;
        long crackStart;

        Coffin(BlockPos head, BlockPos mid, BlockPos foot) {
            this.head = head;
            this.mid = mid;
            this.foot = foot;
        }
    }

    /** The per run state: the coffins in use and when one last began to crack. */
    private static final class Run {
        final List<Coffin> coffins = new ArrayList<>();
        long lastCrack;
    }

    private static BlockState lid() {
        return Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    }

    // ---- the wave ------------------------------------------------------------------------------------

    @Override
    public boolean spawnWave(FinaleContext ctx) {
        DungeonDef.Finale finale = ctx.finale();
        List<Coffin> found = scan(ctx.level(), ctx.record());
        if (found.isEmpty() || finale == null || finale.mobs().isEmpty()) {
            return false;
        }
        int[] bases = new int[finale.mobs().size()];
        for (int i = 0; i < bases.length; i++) {
            bases[i] = finale.mobs().get(i).count();
        }
        int[] counts = OssuaryRules.waveCounts(bases, ctx.party(), finale.perMemberPercent());
        int[] sequence = OssuaryRules.sequence(counts);
        int used = OssuaryRules.coffinsUsed(found.size(), sequence.length);
        int[] occupants = OssuaryRules.occupants(sequence.length, used);
        Run run = ctx.scriptState(Run.class, Run::new);
        run.coffins.clear();
        for (int c = 0; c < used; c++) {
            Coffin coffin = found.get(c);
            for (int group : OssuaryRules.slice(sequence, occupants, c)) {
                Mob mob = spawnDormant(ctx, finale.mobs().get(group).type(), coffin);
                if (mob != null) {
                    coffin.occupants.add(mob.getUUID());
                }
            }
            if (!coffin.occupants.isEmpty()) {
                run.coffins.add(coffin);
            }
        }
        if (finale.elite() != null) {
            ctx.spawnElite(finale.elite());
        }
        run.lastCrack = ctx.tickCount();
        return ctx.total() > 0;
    }

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        if (!ctx.scriptState(Run.class, Run::new).coffins.isEmpty()) {
            ctx.tell("The lids shift.", ChatFormatting.GRAY);
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (run.coffins.isEmpty() || ctx.members().isEmpty()) {
            return;
        }
        ServerLevel level = ctx.level();
        long now = ctx.tickCount();
        for (Coffin coffin : run.coffins) {
            if (coffin.phase == Phase.SEALED && !lidIntact(level, coffin)) {
                open(ctx, coffin, false);
            } else if (coffin.phase == Phase.CRACKING) {
                if (OssuaryRules.crackDone(now - coffin.crackStart)) {
                    open(ctx, coffin, true);
                } else {
                    crackTell(level, coffin, now - coffin.crackStart);
                }
            }
        }
        int awake = 0;
        int cracking = 0;
        double[] distSq = new double[run.coffins.size()];
        boolean[] sealed = new boolean[run.coffins.size()];
        for (int i = 0; i < distSq.length; i++) {
            Coffin coffin = run.coffins.get(i);
            if (coffin.phase == Phase.OPEN) {
                awake += liveCount(level, coffin);
            } else if (coffin.phase == Phase.CRACKING) {
                cracking += coffin.occupants.size();
            }
            sealed[i] = coffin.phase == Phase.SEALED;
            distSq[i] = nearestMemberSq(ctx.members(), coffin);
        }
        long since = now - run.lastCrack;
        int next = OssuaryRules.choose(distSq, sealed, since);
        if (next >= 0 && OssuaryRules.mayStart(awake, cracking, run.coffins.get(next).occupants.size(), since)) {
            Coffin coffin = run.coffins.get(next);
            coffin.phase = Phase.CRACKING;
            coffin.crackStart = now;
            run.lastCrack = now;
            crackTell(level, coffin, 0);
        }
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        ServerLevel level = ctx.level();
        for (Coffin coffin : ctx.scriptState(Run.class, Run::new).coffins) {
            if (level.hasChunkAt(coffin.mid)) {
                for (BlockPos cell : List.of(coffin.mid, coffin.foot)) {
                    if (!level.getBlockState(cell).is(Blocks.POLISHED_BLACKSTONE)) {
                        level.setBlock(cell, lid(), 3);
                    }
                }
            }
        }
    }

    // ---- one coffin ----------------------------------------------------------------------------------

    private static boolean lidIntact(ServerLevel level, Coffin coffin) {
        return level.getBlockState(coffin.mid).is(Blocks.POLISHED_BLACKSTONE)
                && level.getBlockState(coffin.foot).is(Blocks.POLISHED_BLACKSTONE);
    }

    /** The cracking tell: a stone crack that climbs in pitch every half second, and dust from the lid, every other tick. */
    private static void crackTell(ServerLevel level, Coffin coffin, long elapsed) {
        double x = coffin.mid.getX() + 0.5;
        double y = coffin.mid.getY() + 1.0;
        double z = coffin.mid.getZ() + 0.5;
        if (elapsed % 10 == 0) {
            float pitch = 0.5f + 0.5f * Math.min(1.0f, (float) elapsed / OssuaryRules.CRACK_TICKS);
            level.playSound(null, coffin.mid, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.0f, pitch);
        }
        if (elapsed % 2 == 0) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, lid()), x, y, z, 6, 0.5, 0.2, 0.5, 0.02);
            level.sendParticles(ParticleTypes.POOF, x, y, z, 2, 0.5, 0.2, 0.5, 0.01);
        }
    }

    /** The lid breaks (unless a player already broke it) and every occupant wakes, stands up and turns on the nearest member. */
    private static void open(FinaleContext ctx, Coffin coffin, boolean breakLid) {
        ServerLevel level = ctx.level();
        if (breakLid) {
            level.destroyBlock(coffin.mid, false);
            level.destroyBlock(coffin.foot, false);
        }
        level.playSound(null, coffin.mid, SoundEvents.SKELETON_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.6f);
        BlockPos[] spots = {coffin.mid, coffin.foot};
        int n = 0;
        for (UUID id : coffin.occupants) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            BlockPos spot = spots[n++ % spots.length];
            mob.setNoAi(false);
            mob.setInvulnerable(false);
            mob.removeEffect(MobEffects.INVISIBILITY);
            mob.setInvisible(false);
            mob.setSilent(false);
            mob.setNoGravity(false);
            mob.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, mob.getYRot(), 0.0f);
            mob.setDeltaMovement(0.0, 0.0, 0.0);
            ServerPlayer target = nearestMember(ctx.members(), spot);
            if (target != null) {
                FinaleWave.provoke(mob, target);
            }
        }
        coffin.phase = Phase.OPEN;
    }

    /** A mob of {@code typeId} asleep inside the coffin's body block: no AI, no damage, unseen, silent, unmoving. */
    private static Mob spawnDormant(FinaleContext ctx, String typeId, Coffin coffin) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(typeId)).orElse(null);
        if (type == null) {
            PocketDungeonsMod.LOG.warn("The Ossuary finale names an unknown mob type: {}", typeId);
            return null;
        }
        Entity entity = type.spawn(ctx.level(), coffin.mid.below(), EntitySpawnReason.TRIGGERED);
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        // The bare invisible flag is recomputed from active effects every tick, so a sleeper must wear the effect.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        mob.setInvisible(true);
        mob.setSilent(true);
        mob.setNoGravity(true);
        return ctx.addMob(mob);
    }

    private static int liveCount(ServerLevel level, Coffin coffin) {
        int alive = 0;
        for (UUID id : coffin.occupants) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    private static double nearestMemberSq(List<ServerPlayer> members, Coffin coffin) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer member : members) {
            best = Math.min(best, member.distanceToSqr(coffin.mid.getX() + 0.5, coffin.mid.getY(),
                    coffin.mid.getZ() + 0.5));
        }
        return best;
    }

    private static ServerPlayer nearestMember(List<ServerPlayer> members, BlockPos at) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer member : members) {
            double sq = member.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            if (sq < bestSq) {
                bestSq = sq;
                best = member;
            }
        }
        return best;
    }

    // ---- finding the coffins -------------------------------------------------------------------------

    /**
     * Every coffin standing in the floor's cells, found by pattern: a chiseled polished blackstone cap at the
     * lid level (y 2) over a polished blackstone body, with two more body and lid pairs running away from it
     * along one compass direction. One scan per finale; it reads 256 cells per room cell and skips unloaded chunks.
     */
    private static List<Coffin> scan(ServerLevel level, InstanceRecord record) {
        List<Coffin> out = new ArrayList<>();
        if (record == null || record.layout == null) {
            return out;
        }
        for (BlockPos origin : record.layout.geometry().cellOrigins()) {
            if (!level.hasChunkAt(origin)) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos cap = origin.offset(x, 2, z);
                    if (!level.getBlockState(cap).is(Blocks.CHISELED_POLISHED_BLACKSTONE)
                            || !level.getBlockState(cap.below()).is(Blocks.POLISHED_BLACKSTONE)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos mid = cap.relative(d);
                        BlockPos foot = cap.relative(d, 2);
                        if (isBodyAndLid(level, mid) && isBodyAndLid(level, foot)) {
                            out.add(new Coffin(cap, mid, foot));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean isBodyAndLid(ServerLevel level, BlockPos lidCell) {
        return level.getBlockState(lidCell).is(Blocks.POLISHED_BLACKSTONE)
                && level.getBlockState(lidCell.below()).is(Blocks.POLISHED_BLACKSTONE);
    }
}
