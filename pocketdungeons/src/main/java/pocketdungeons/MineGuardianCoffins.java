package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Act 1 Endless Mine's third guardian (roadmap T41), a miniature of the Ossuary's coffins. The wave
 * lies sealed in upright coffins stood around the terminal cell, one sleeper to a coffin. They rise one
 * at a time: a coffin cracks (a tell of {@link MineGuardianRules#tellTicks} ticks, never under two
 * seconds), its sleeper stands up, and only then may the next begin to crack. A coffin whose lid a
 * member broke opens at once. Every sleeper is tagged as a guardian mob at spawn, so the pad stays shut
 * until the last is dead.
 *
 * <p>The coffins are smaller than the Ossuary's: two polished blackstone blocks standing on the floor
 * with the sleeper dormant inside the lower one. Hardness scales their number
 * ({@link MineGuardianRules#coffinsFor}), never past {@code mineGuardianMobCap}, and shortens the crack
 * tell, never under two seconds. Per floor state lives in {@link #SETS}, keyed by the terminal cell
 * origin, and is dropped by {@link #clear(BlockPos)} when the cell ends.
 */
final class MineGuardianCoffins implements MineGuardians.Guardian {

    /** How often the shared poll runs. */
    private static final int POLL_TICKS = 5;

    private enum Phase { SEALED, CRACKING, OPEN }

    /** One coffin: the lower block the sleeper lies in, the upper block, and who is inside. */
    private static final class Coffin {
        final BlockPos base;
        final BlockPos top;
        final UUID sleeper;
        Phase phase = Phase.SEALED;
        long crackStart;

        Coffin(BlockPos base, UUID sleeper) {
            this.base = base;
            this.top = base.above();
            this.sleeper = sleeper;
        }
    }

    /** One floor's coffins and the clock that spaces their cracking. */
    private static final class Set {
        final ServerLevel level;
        final BlockPos terminal;
        final List<Coffin> coffins = new ArrayList<>();
        final int tellTicks;
        long nextCrackAt;

        Set(ServerLevel level, BlockPos terminal, int tellTicks, long nextCrackAt) {
            this.level = level;
            this.terminal = terminal;
            this.tellTicks = tellTicks;
            this.nextCrackAt = nextCrackAt;
        }
    }

    private static final Map<BlockPos, Set> SETS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    private static BlockState block() {
        return Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    }

    // ---- the spawn -----------------------------------------------------------------------------------

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        long now = level.getServer().getTickCount();
        int count = Math.max(1, MineGuardianRules.capped(MineGuardianRules.coffinsFor(spawn.hardness()),
                PocketDungeonsConfig.mineGuardianMobCap()));
        Set set = new Set(level, spawn.terminal(),
                MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS, spawn.hardness()),
                now + MineGuardianRules.COFFIN_GAP_TICKS);
        java.util.Set<BlockPos> taken = new HashSet<>();
        taken.add(spawn.centre());
        for (int i = 0; i < count; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(level, spawn.terminal(), taken, level.getRandom());
            if (at == null) {
                continue;
            }
            Mob sleeper = spawnSleeper(level, at, spawn);
            if (sleeper == null) {
                continue;
            }
            level.setBlock(at, block(), 3);
            level.setBlock(at.above(), block(), 3);
            set.coffins.add(new Coffin(at, sleeper.getUUID()));
        }
        if (set.coffins.isEmpty()) {
            return;
        }
        SETS.put(spawn.terminal(), set);
        tellNear(set, "Stone lids shift in the dark.");
    }

    /** A skeleton asleep inside the coffin's lower block: no AI, no damage, unseen, silent, unmoving. */
    private static Mob spawnSleeper(ServerLevel level, BlockPos base, MineGuardians.Spawn spawn) {
        Mob sleeper = EntityTypes.SKELETON.spawn(level, base, EntitySpawnReason.TRIGGERED);
        if (sleeper == null) {
            return null;
        }
        MineGuardians.tag(sleeper);
        MineGuardians.scale(sleeper, spawn.floorLevel(), spawn.hardness());
        sleeper.setNoAi(true);
        sleeper.setInvulnerable(true);
        // The bare invisible flag is recomputed from active effects every tick, so a sleeper must wear the effect.
        sleeper.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION,
                0, false, false, false));
        sleeper.setInvisible(true);
        sleeper.setSilent(true);
        sleeper.setNoGravity(true);
        return sleeper;
    }

    // ---- the poll: crack, open, next -----------------------------------------------------------------

    /** Wires the shared tick. Called once from {@link MineGuardiansAct1#registerAll()}. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % POLL_TICKS != 0 || SETS.isEmpty()) {
                return;
            }
            pollAll(server.getTickCount());
        });
    }

    /** Test seam: runs the poll once at {@code now}. */
    static void pollForTest(long now) {
        pollAll(now);
    }

    private static void pollAll(long now) {
        for (Map.Entry<BlockPos, Set> entry : new ArrayList<>(SETS.entrySet())) {
            if (tick(entry.getValue(), now)) {
                SETS.remove(entry.getKey());
            }
        }
    }

    /** Advances one floor's coffins; {@code true} when every coffin stands open and the set is spent. */
    private static boolean tick(Set set, long now) {
        Coffin next = null;
        for (Coffin coffin : set.coffins) {
            if (coffin.phase == Phase.SEALED && !intact(set.level, coffin)) {
                open(set, coffin, now); // a member broke the lid: it opens on the spot
            } else if (coffin.phase == Phase.CRACKING) {
                if (!intact(set.level, coffin) || now - coffin.crackStart >= set.tellTicks) {
                    open(set, coffin, now);
                } else {
                    crackTell(set.level, coffin, now - coffin.crackStart);
                }
            }
            if (next == null && coffin.phase == Phase.SEALED) {
                next = coffin;
            }
        }
        if (next != null && now >= set.nextCrackAt && noneCracking(set)) {
            next.phase = Phase.CRACKING;
            next.crackStart = now;
            crackTell(set.level, next, 0);
        }
        for (Coffin coffin : set.coffins) {
            if (coffin.phase != Phase.OPEN) {
                return false;
            }
        }
        return true;
    }

    private static boolean noneCracking(Set set) {
        for (Coffin coffin : set.coffins) {
            if (coffin.phase == Phase.CRACKING) {
                return false;
            }
        }
        return true;
    }

    private static boolean intact(ServerLevel level, Coffin coffin) {
        return level.getBlockState(coffin.base).is(Blocks.POLISHED_BLACKSTONE)
                && level.getBlockState(coffin.top).is(Blocks.POLISHED_BLACKSTONE);
    }

    /** The cracking tell: a stone crack that climbs in pitch, and dust off the lid. */
    private static void crackTell(ServerLevel level, Coffin coffin, long elapsed) {
        double x = coffin.base.getX() + 0.5;
        double y = coffin.base.getY() + 1.0;
        double z = coffin.base.getZ() + 0.5;
        if (elapsed % 20 == 0) {
            level.playSound(null, coffin.base, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.0f, 0.6f);
        }
        if (elapsed % 4 == 0) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block()), x, y, z, 5,
                    0.3, 0.4, 0.3, 0.02);
        }
    }

    /** The lid gives way and the sleeper stands up, turning on the nearest member. */
    private static void open(Set set, Coffin coffin, long now) {
        ServerLevel level = set.level;
        level.destroyBlock(coffin.base, false);
        level.destroyBlock(coffin.top, false);
        level.playSound(null, coffin.base, SoundEvents.SKELETON_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.6f);
        coffin.phase = Phase.OPEN;
        set.nextCrackAt = Math.max(set.nextCrackAt, now + MineGuardianRules.COFFIN_GAP_TICKS);
        Entity entity = level.getEntity(coffin.sleeper);
        if (!(entity instanceof Mob mob) || !mob.isAlive()) {
            return;
        }
        mob.setNoAi(false);
        mob.setInvulnerable(false);
        mob.removeEffect(MobEffects.INVISIBILITY);
        mob.setInvisible(false);
        mob.setSilent(false);
        mob.setNoGravity(false);
        mob.snapTo(coffin.base.getX() + 0.5, coffin.base.getY(), coffin.base.getZ() + 0.5, mob.getYRot(), 0.0f);
        mob.setDeltaMovement(0.0, 0.0, 0.0);
        ServerPlayer nearest = nearestMember(level, coffin.base);
        if (nearest != null) {
            mob.setTarget(nearest);
        }
    }

    private static ServerPlayer nearestMember(ServerLevel level, BlockPos at) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double d = player.distanceToSqr(Vec3.atCenterOf(at));
            if (player.isAlive() && !player.isSpectator() && d < bestSq) {
                best = player;
                bestSq = d;
            }
        }
        return best;
    }

    private static void tellNear(Set set, String text) {
        BlockPos centre = set.terminal.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        for (ServerPlayer player : set.level.players()) {
            if (player.position().distanceToSqr(Vec3.atCenterOf(centre)) < 30.0 * 30.0) {
                player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    // ---- cleanup -------------------------------------------------------------------------------------

    /**
     * The floor's cell is ending: drop the clock and discard every sleeper still sealed. A sealed
     * sleeper is invisible and invulnerable, so it must never outlive its cell; sleepers already
     * standing are ordinary mobs, the teardown's to sweep.
     */
    static void clear(BlockPos cellOrigin) {
        Set set = SETS.remove(cellOrigin);
        if (set == null) {
            return;
        }
        for (Coffin coffin : set.coffins) {
            if (coffin.phase != Phase.OPEN) {
                Entity entity = set.level.getEntity(coffin.sleeper);
                if (entity instanceof Mob mob && mob.isAlive()) {
                    mob.discard();
                }
            }
        }
    }

    /** The phases of the cell's coffins, for tests; empty once the set is spent or cleared. */
    static List<String> phases(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        List<String> out = new ArrayList<>();
        if (set != null) {
            for (Coffin coffin : set.coffins) {
                out.add(coffin.phase.name());
            }
        }
        return out;
    }
}
