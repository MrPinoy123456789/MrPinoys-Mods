package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Act 2 Endless Mine's first guardian (roadmap T42), a miniature of the Silent Deep's Hush. The
 * Deep Sentry is a bow skeleton waiting in a listening room: sculk sensors stand around the cell for
 * the look, and the fight runs under its own Heard meter, handled the way {@link DeepslateFinale}
 * handles it. Bow shots, hits taken, sprinting, a guardian mob dying and, slowly, simply fighting fill
 * the meter; a full meter answers with the heartbeat tell, then darkness and a tagged Warden whelp,
 * never the Warden. The meter is deaf while a whelp is out, and it never answers without the sentry:
 * the fight is the skeleton plus what it hears.
 *
 * <p>Differences from the finale: the meter is the guardian's own (the mine cell has no armed sculk
 * room, so there are no sensor pulses in it and the sensors are decor only), the ceiling falls as
 * hardness climbs ({@link MineGuardianRules#sentryCeiling}), and a meter that fills while the wave is
 * at {@code mineGuardianMobCap} waits instead of answering. The tell keeps the finale's own length,
 * {@link DeepslateFinaleRules#TELL_TICKS}, comfortably over the two second floor.
 *
 * <p>Per floor state lives in {@link #RUNS}, keyed by the terminal cell origin, dropped by
 * {@link #clear(BlockPos)} when the cell ends, which also takes the decor sensors back.
 */
final class MineGuardianSentry implements MineGuardians.Guardian {

    /** His name, the finale's own. */
    static final String NAME = "The Deep Sentry";

    /** The meter samples a second at a time, the way the finale's watch tick does. */
    private static final int POLL_TICKS = 20;

    /** One floor's meter, baselines, decor and the whelp it last sent. */
    private static final class Run {
        final ServerLevel level;
        final BlockPos terminal;
        final UUID sentry;
        final int ceiling;
        final List<BlockPos> placed = new ArrayList<>();
        final Map<UUID, Float> health = new HashMap<>();
        final Map<UUID, Integer> shots = new HashMap<>();
        long waveTick;
        long lastSecond;
        long tellTick = DeepslateFinale.NO_TELL;
        boolean warned;
        int heard;
        UUID whelp;
        int lastAlive = -1;

        Run(ServerLevel level, BlockPos terminal, UUID sentry, int ceiling, long waveTick) {
            this.level = level;
            this.terminal = terminal;
            this.sentry = sentry;
            this.ceiling = ceiling;
            this.waveTick = waveTick;
        }
    }

    private static final Map<BlockPos, Run> RUNS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    // ---- the spawn -----------------------------------------------------------------------------------

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        Mob sentry = EntityTypes.SKELETON.spawn(level, spawn.centre(), EntitySpawnReason.TRIGGERED);
        if (sentry == null) {
            PocketDungeonsMod.LOG.warn("The deep sentry failed to spawn at {}", spawn.centre().toShortString());
            return;
        }
        MineGuardians.tag(sentry);
        MineGuardians.scale(sentry, spawn.floorLevel(), spawn.hardness());
        sentry.setCustomName(Component.literal(NAME).withStyle(ChatFormatting.DARK_AQUA));
        sentry.setCustomNameVisible(true);

        Run run = new Run(level, spawn.terminal(), sentry.getUUID(),
                MineGuardianRules.sentryCeiling(spawn.hardness(), PocketDungeonsConfig.hushCeiling()),
                level.getServer().getTickCount());
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        taken.add(spawn.centre());
        for (int i = 0; i < MineGuardianRules.SENSORS; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(level, spawn.terminal(), taken, level.getRandom());
            if (at == null) {
                continue;
            }
            level.setBlock(at, Blocks.SCULK_SENSOR.defaultBlockState(), 3);
            run.placed.add(at);
        }
        RUNS.put(spawn.terminal(), run);
    }

    // ---- the poll: the meter, the tell, the answer ----------------------------------------------------

    /** Wires the shared tick. Called once from {@link MineGuardiansAct2#registerAll()}. */
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
            tick(entry.getValue(), now);
            if (done(entry.getValue())) {
                RUNS.remove(entry.getKey());
            }
        }
    }

    /** Whether the floor's guardian wave is gone: no sentry and no whelp the meter sent. */
    private static boolean done(Run run) {
        return !alive(run.level, run.sentry) && !alive(run.level, run.whelp);
    }

    private static boolean alive(ServerLevel level, UUID id) {
        Entity entity = id == null ? null : level.getEntity(id);
        return entity instanceof Mob mob && mob.isAlive();
    }

    private static void tick(Run run, long now) {
        // A guardian mob dying is noise: count the wave shrink since the last look.
        int aliveNow = taggedInCell(run);
        if (run.lastAlive >= 0 && aliveNow < run.lastAlive) {
            run.heard = SculkOmen.heardAfter(run.heard,
                    rises().of(DeepslateFinaleRules.Noise.DEATH, run.lastAlive - aliveNow));
        }
        run.lastAlive = aliveNow;

        boolean sentryUp = alive(run.level, run.sentry);
        if (run.tellTick != DeepslateFinale.NO_TELL) {
            look(run); // the baselines move on; noise during the tell is forgiven
            if (DeepslateFinaleRules.answerDue(now, DeepslateFinaleRules.answerTick(run.tellTick))) {
                answer(run);
            } else {
                tellBeat(run);
            }
            return;
        }
        if (!sentryUp || whelpOut(run) || atCap(run)) {
            look(run); // the room is deaf: forget what it heard
            return;
        }
        int units = look(run);
        long second = (now - run.waveTick) / 20;
        for (long s = run.lastSecond + 1; s <= second; s++) {
            units += DeepslateFinaleRules.quietRise(s, PocketDungeonsConfig.hushQuietSeconds());
        }
        run.lastSecond = Math.max(run.lastSecond, second);
        if (units > 0) {
            run.heard = SculkOmen.heardAfter(run.heard, units);
        }
        if (DeepslateFinaleRules.full(run.heard, run.ceiling)) {
            run.tellTick = now;
            run.warned = true;
            tellBeat(run);
        } else if (!run.warned && DeepslateFinaleRules.nearlyFull(run.heard, run.ceiling)) {
            run.warned = true;
            overlay(run, "The sculk stirs.");
        }
    }

    private static DeepslateFinaleRules.Rises rises() {
        return new DeepslateFinaleRules.Rises(PocketDungeonsConfig.hushPulseRise(),
                PocketDungeonsConfig.hushBowRise(), PocketDungeonsConfig.hushHitRise(),
                PocketDungeonsConfig.hushDeathRise(), PocketDungeonsConfig.hushSprintRise());
    }

    /**
     * The members inside the sentry's cell: the shots they fired, the hits they took and the sprints
     * since the last look, worth meter units the way {@link DeepslateFinale} counts them. Baselines
     * always move, so a deaf second is simply forgotten.
     */
    private static int look(Run run) {
        DeepslateFinaleRules.Rises rises = rises();
        int bows = 0;
        int hits = 0;
        int sprints = 0;
        for (ServerPlayer member : membersInCell(run)) {
            if (!member.isAlive()) {
                continue;
            }
            UUID id = member.getUUID();
            int shot = member.getStats().getValue(Stats.ITEM_USED.get(Items.BOW))
                    + member.getStats().getValue(Stats.ITEM_USED.get(Items.CROSSBOW));
            Integer before = run.shots.put(id, shot);
            if (before != null && shot > before) {
                bows += shot - before;
            }
            float hp = member.getHealth();
            Float last = run.health.put(id, hp);
            if (last != null && hp < last - 0.01f) {
                hits++;
            }
            if (member.isSprinting()) {
                sprints++;
            }
        }
        return rises.of(DeepslateFinaleRules.Noise.BOW, bows) + rises.of(DeepslateFinaleRules.Noise.HIT, hits)
                + rises.of(DeepslateFinaleRules.Noise.SPRINT, sprints);
    }

    /** The players fighting in the cell: inside its bounds, generously read. */
    private static List<ServerPlayer> membersInCell(Run run) {
        AABB bounds = CellGeometry.cellBounds(run.terminal).inflate(4.0);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : run.level.players()) {
            if (!player.isSpectator() && bounds.contains(player.getX(), player.getY(), player.getZ())) {
                out.add(player);
            }
        }
        return out;
    }

    /** How many guardian mobs of this floor still stand, the wave and whatever the meter sent. */
    private static int taggedInCell(Run run) {
        AABB bounds = CellGeometry.cellBounds(run.terminal).inflate(RoomGeometry.CELL * 2.0);
        int count = 0;
        for (Mob mob : run.level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(MineGuardians.GUARDIAN_TAG)) {
                count++;
            }
        }
        return count;
    }

    private static boolean whelpOut(Run run) {
        return alive(run.level, run.whelp);
    }

    /** Whether the wave already fills the mob cap: a full meter waits instead of answering over it. */
    private static boolean atCap(Run run) {
        return taggedInCell(run) >= PocketDungeonsConfig.mineGuardianMobCap();
    }

    /** One beat of the tell: a heartbeat for every member in the cell and the line on the action bar. */
    private static void tellBeat(Run run) {
        for (ServerPlayer member : membersInCell(run)) {
            run.level.playSound(null, member.blockPosition(), SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 2.0f, 0.8f);
            member.sendOverlayMessage(Component.literal("The deep heard you.")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** The tell has run: the dark settles and a tagged whelp comes for the nearest member. */
    private static void answer(Run run) {
        run.tellTick = DeepslateFinale.NO_TELL;
        run.warned = false;
        run.heard = 0;
        ServerPlayer hearer = nearestMember(run);
        for (ServerPlayer member : membersInCell(run)) {
            member.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 160, 0, false, true));
        }
        if (hearer == null) {
            return;
        }
        Vec3 at = Instances.randomSpawnNear(hearer, run.level,
                new Random(run.level.getServer().getTickCount()), 5, 10);
        if (at == null) {
            // No open ring around the hearer: the whelp comes up anywhere the cell gives it room.
            BlockPos spot = MineGuardians.freeSpotInCell(run.level, run.terminal,
                    new java.util.HashSet<>(), run.level.getRandom());
            if (spot != null) {
                at = Vec3.atBottomCenterOf(spot);
            }
        }
        Warden whelp = at == null ? null : Whelp.create(run.level, at, hearer);
        if (whelp != null) {
            MineGuardians.tag(whelp);
            run.whelp = whelp.getUUID();
        }
    }

    private static ServerPlayer nearestMember(Run run) {
        Vec3 centre = CellGeometry.cellBounds(run.terminal).getCenter();
        ServerPlayer best = null;
        for (ServerPlayer member : membersInCell(run)) {
            if (member.isAlive() && (best == null || member.position().distanceToSqr(centre)
                    < best.position().distanceToSqr(centre))) {
                best = member;
            }
        }
        return best;
    }

    private static void overlay(Run run, String text) {
        for (ServerPlayer member : membersInCell(run)) {
            member.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
        }
    }

    // ---- cleanup and seams ----------------------------------------------------------------------------

    /**
     * The floor's cell is ending: drop the meter and take the decor sensors back where they still stand.
     * The whelp, if one is out, is an ordinary live mob, the teardown's to sweep.
     */
    static void clear(BlockPos cellOrigin) {
        Run run = RUNS.remove(cellOrigin);
        if (run == null) {
            return;
        }
        for (BlockPos at : run.placed) {
            if (run.level.getBlockState(at).is(Blocks.SCULK_SENSOR)) {
                run.level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** The cell's meter reading, for tests; -1 when no sentry listens there. */
    static int heardOf(BlockPos cellOrigin) {
        Run run = RUNS.get(cellOrigin);
        return run == null ? -1 : run.heard;
    }

    /** The cell's meter ceiling, for tests; -1 when no sentry listens there. */
    static int ceilingOf(BlockPos cellOrigin) {
        Run run = RUNS.get(cellOrigin);
        return run == null ? -1 : run.ceiling;
    }

    /** The sensors stood in the cell, for tests. */
    static int sensorsOf(BlockPos cellOrigin) {
        Run run = RUNS.get(cellOrigin);
        return run == null ? 0 : run.placed.size();
    }

    /** Test seam: adds meter units as if the cell heard them. */
    static void addHeardForTest(BlockPos cellOrigin, int units) {
        Run run = RUNS.get(cellOrigin);
        if (run != null) {
            run.heard = SculkOmen.heardAfter(run.heard, Math.max(0, units));
        }
    }
}
