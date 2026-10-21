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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Act 1 Endless Mine's first guardian (roadmap T41), a miniature of the Infestation's Queen. The
 * young queen spawns already burrowed under the terminal cell: the floor cracks and dust rises (a tell
 * of {@link MineGuardianRules#tellTicks} ticks, never under two seconds, whose tiles are marked through
 * {@link AoETelegraph} -- whoever still stands on them when she surfaces is slammed and tossed), then she
 * surfaces fighting at a fraction of the real Queen's size. Around her, {@link CreepingBricks} bricks hatch her brood on the
 * field's own clock; every silverfish a brick releases is tagged as a guardian mob, so the pad stays
 * shut until she and her brood are dead.
 *
 * <p>Per floor state lives in {@link #RUNS}, keyed by the terminal cell origin: it is dropped and its
 * pieces undone by {@link #clear(BlockPos)} when the cell ends ({@code InstanceTeardown.forgetCellState})
 * and at server stop ({@link SessionState#clearedAtStop}).
 */
final class MineGuardianQueen implements MineGuardians.Guardian {

    /** Her name, a small Queen for a small fight. */
    static final String NAME = "The Princess";

    /** How often the shared poll runs; the tell and the collect keep to second boundaries. */
    private static final int POLL_TICKS = 5;

    /** One floor's queen: her field, the bricks planted, and the burrow she is in until {@code emergeAt}. */
    private static final class Run {
        final ServerLevel level;
        final BlockPos terminal;
        final CreepingBricks.Field field;
        final UUID boss;
        final long emergeAt;
        int total;
        int planted;
        boolean emerged;

        Run(ServerLevel level, BlockPos terminal, CreepingBricks.Field field, UUID boss, long emergeAt, int total) {
            this.level = level;
            this.terminal = terminal;
            this.field = field;
            this.boss = boss;
            this.emergeAt = emergeAt;
            this.total = total;
        }
    }

    private static final Map<BlockPos, Run> RUNS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    // ---- the spawn -----------------------------------------------------------------------------------

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        long now = level.getServer().getTickCount();
        int cap = PocketDungeonsConfig.mineGuardianMobCap();
        int bricks = MineGuardianRules.capped(MineGuardianRules.bricksFor(spawn.hardness()), Math.max(0, cap - 1));

        Mob queen = EntityTypes.SILVERFISH.spawn(level, spawn.centre().below(), EntitySpawnReason.TRIGGERED);
        if (queen == null) {
            PocketDungeonsMod.LOG.warn("The mine queen failed to spawn at {}", spawn.centre().toShortString());
            return;
        }
        MineGuardians.tag(queen, spawn.terminal());
        MineGuardians.scale(queen, spawn.floorLevel(), spawn.hardness());
        AttributeInstance scale = queen.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(MineGuardianRules.queenScale(spawn.hardness()));
        }
        queen.setCustomName(Component.literal(NAME).withStyle(ChatFormatting.RED));
        bury(queen);
        dust(level, Vec3.atBottomCenterOf(spawn.centre()), 30);
        level.playSound(null, spawn.centre(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.2f, 0.5f);

        CreepingBricks.Field field = CreepingBricks.open(level, spawn.terminal(), bricks, true);
        int tell = MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS, spawn.hardness());
        Run run = new Run(level, spawn.terminal(), field, queen.getUUID(), now + tell, bricks);
        RUNS.put(spawn.terminal(), run);
        // The crack tell is also the strike's marked warning: it lands as she surfaces.
        AoETelegraph.detonate(level, null, spawn.centre(),
                new AoEShapes.Circle(MineGuardianRules.EMERGE_RADIUS), tell, emergeSlam(spawn.hardness()));
        plantBricks(spawn, run, now);
    }

    /** Plants the queen's bricks on free spots around the cell centre, one burst of planting at spawn. */
    private static void plantBricks(MineGuardians.Spawn spawn, Run run, long now) {
        if (run.total <= 0) {
            return;
        }
        Set<BlockPos> taken = new HashSet<>();
        taken.add(spawn.centre());
        net.minecraft.util.RandomSource random = spawn.level().getRandom();
        for (int i = 0; i < run.total; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(spawn.level(), spawn.terminal(), taken, random);
            if (at == null || !run.field.plant(at, now)) {
                continue;
            }
            run.planted++;
        }
        run.total = run.planted; // planting is one shot: this is the count the poll waits out
        if (run.planted == 0) {
            run.field.close();
        }
    }

    /** What the young queen's rise does to whoever still stands on the heaving floor. */
    private static AoETelegraph.Effect emergeSlam(double hardness) {
        float damage = MineGuardianRules.emergeDamage(hardness);
        return (level, entity) -> {
            if (entity instanceof ServerPlayer player) {
                player.hurtServer(level, level.damageSources().generic(), damage);
                player.push(0.0, 0.7, 0.0);
                player.hurtMarked = true;
            }
        };
    }

    /** The dormant queen under the floor: hidden, untouchable, silent, still. */
    private static void bury(Mob queen) {
        queen.setNoAi(true);
        queen.setNoGravity(true);
        queen.setInvulnerable(true);
        queen.setInvisible(true);
        queen.setSilent(true);
    }

    // ---- the poll: crack tell, emerge, tag the brood ---------------------------------------------------

    /** Wires the shared tick. Called once from {@link MineGuardiansAct1#registerAll()}. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % POLL_TICKS != 0 || RUNS.isEmpty()) {
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
        for (Map.Entry<BlockPos, Run> entry : new ArrayList<>(RUNS.entrySet())) {
            Run run = entry.getValue();
            for (Mob released : run.field.collect()) {
                MineGuardians.tag(released, run.terminal);
            }
            if (!run.emerged) {
                if (now >= run.emergeAt) {
                    emerge(run);
                    run.emerged = true;
                } else if ((run.emergeAt - now) % 20 == 0) {
                    dust(run.level, Vec3.atBottomCenterOf(run.terminal.offset(
                            RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2)), 12);
                    run.level.playSound(null, BlockPos.containing(Vec3.atBottomCenterOf(run.terminal.offset(
                            RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2))),
                            SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 0.9f, 0.6f);
                }
            }
            if (run.emerged && run.planted >= run.total && run.field.active() == 0) {
                run.field.close();
                RUNS.remove(entry.getKey());
            }
        }
    }

    /** The floor gives way and the young queen comes up fighting. */
    private static void emerge(Run run) {
        Entity entity = run.level.getEntity(run.boss);
        if (!(entity instanceof Mob queen) || !queen.isAlive()) {
            return;
        }
        BlockPos centre = run.terminal.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        queen.setPos(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5);
        queen.setNoAi(false);
        queen.setNoGravity(false);
        queen.setInvulnerable(false);
        queen.setInvisible(false);
        queen.setSilent(false);
        queen.setCustomNameVisible(true);
        queen.fallDistance = 0;
        dust(run.level, queen.position(), 50);
        run.level.playSound(null, centre, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2f, 0.6f);
        ServerPlayer nearest = nearestMember(run.level, centre);
        if (nearest != null) {
            queen.setTarget(nearest);
        }
    }

    private static ServerPlayer nearestMember(ServerLevel level, BlockPos centre) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double d = player.distanceToSqr(Vec3.atCenterOf(centre));
            if (player.isAlive() && !player.isSpectator() && d < bestSq) {
                best = player;
                bestSq = d;
            }
        }
        return best;
    }

    // ---- cleanup -------------------------------------------------------------------------------------

    /**
     * The floor's cell is ending: give the standing bricks back, and discard the queen if she is still
     * buried (a dormant, invisible, invulnerable mob must never outlive its cell). Awake mobs are the
     * teardown's to sweep.
     */
    static void clear(BlockPos cellOrigin) {
        Run run = RUNS.remove(cellOrigin);
        if (run == null) {
            return;
        }
        run.field.close();
        Entity entity = run.level.getEntity(run.boss);
        if (entity instanceof Mob queen && queen.isAlive() && !run.emerged) {
            queen.discard();
        }
    }

    /** The bricks still standing in the cell's field, for tests. */
    static int standingBricks(BlockPos cellOrigin) {
        Run run = RUNS.get(cellOrigin);
        return run == null ? 0 : run.field.active();
    }

    /** Whether the cell's queen has surfaced, for tests. */
    static boolean surfaced(BlockPos cellOrigin) {
        Run run = RUNS.get(cellOrigin);
        return run == null || run.emerged;
    }

    private static void dust(ServerLevel level, Vec3 at, int count) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState()),
                at.x, at.y + 0.3, at.z, count, 0.8, 0.4, 0.8, 0.1);
    }
}
