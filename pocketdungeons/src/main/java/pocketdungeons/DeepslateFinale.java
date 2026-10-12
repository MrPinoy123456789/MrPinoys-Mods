package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Silent Deep finale (roadmap T31, boss workshop 2026-10-11 row 9): the Hush. The room is full of sculk
 * sensors and one shrieker, and the finale runs under the room's Heard meter, the same meter
 * {@link PressureSources} keeps for every sculk room. For the length of the finale this script owns it
 * ({@link PressureSources#handOver}): noise fills it, in the amounts {@link DeepslateFinaleRules} and the
 * {@code hush*} knobs give, and a full meter answers with a tell, then darkness and a Warden whelp
 * ({@link Whelp}). Never the Warden: that is the Ancient City's alone, and this class spawns no Warden except
 * through {@link Whelp}.
 *
 * <p>What fills it: a sensor pulse, a bow or crossbow shot, a hit a member takes, a finale mob dying, a second
 * of sprinting, and, slowly, simply fighting on. Sneaking and melee make none of the loud ones, so a quiet
 * party rises slowly. The data wave and the Deep Sentry are untouched.
 *
 * <p>Fairness: the tell is a heartbeat and a line, {@link DeepslateFinaleRules#TELL_TICKS} (3 seconds) before
 * the dark and the whelp; a gentler line comes at three quarters. The meter is deaf while a whelp is out, so
 * answers never chain. The whelps leave when the finale is won and with the floor, and nothing is placed in the
 * world, so the pad is never covered. Noise is sampled once per watch tick (a second by default), so a
 * second with two hits counts as one.
 *
 * <p>If the final floor drew no Silent Deep room (it is weighted, not guaranteed) the script does nothing and
 * the finale is the plain data wave.
 */
final class DeepslateFinale implements FinaleScript {

    private static final float TELL_VOLUME = 2.0f;
    /** The tell clock when no tell is running (a real tick can be any small number in a test). */
    static final long NO_TELL = Long.MIN_VALUE;

    /** One run's state (package-visible so the gametest can read the story). */
    static final class Run {
        /** The origin of the listening room, or null when the floor has none. */
        BlockPos room;
        int ceiling;
        /** The tick the wave stood, and the last whole second of the fight that was counted. */
        long waveTick = -1;
        long lastSecond;
        /** The tick the tell began, or NO_TELL when the room is listening. */
        long tellTick = NO_TELL;
        boolean warned;
        int answers;
        /** Per member: health, and the bow and crossbow shots on their statistics, at the last look. */
        final Map<UUID, Float> health = new HashMap<>();
        final Map<UUID, Integer> shots = new HashMap<>();
        /** Whelps beyond the first (the first is {@link Whelp}'s own). */
        final List<UUID> extras = new ArrayList<>();
    }

    @Override
    public void onTriggered(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        BlockPos room = PressureSources.listeningRoom(ctx.record(), DeepslateFinaleRules.ROOM_TEMPLATE);
        if (room == null) {
            return;
        }
        run.ceiling = PocketDungeonsConfig.hushCeiling();
        if (PressureSources.handOver(room, run.ceiling)) {
            run.room = room;
        }
    }

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (run.room == null) {
            return;
        }
        // The fight starts with an empty meter and fresh baselines: noise from the countdown is forgiven.
        PressureSources.resetHeard(run.room);
        PressureSources.takePulses(run.room);
        run.waveTick = ctx.tickCount();
        run.lastSecond = 0;
        look(ctx, run);
    }

    @Override
    public void onMobDied(FinaleContext ctx, UUID mob) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (listening(ctx, run)) {
            PressureSources.addHeard(run.room, rises().of(DeepslateFinaleRules.Noise.DEATH, 1));
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        if (run.room == null) {
            return;
        }
        tendExtras(ctx, run);
        if (run.tellTick != NO_TELL) {
            look(ctx, run);
            PressureSources.takePulses(run.room);
            if (DeepslateFinaleRules.answerDue(ctx.tickCount(), DeepslateFinaleRules.answerTick(run.tellTick))) {
                answer(ctx, run);
            } else {
                tellBeat(ctx);
            }
            return;
        }
        if (!listening(ctx, run)) {
            // A whelp is out: the room is deaf, so nothing is counted and nothing is owed afterwards.
            look(ctx, run);
            PressureSources.takePulses(run.room);
            return;
        }
        int units = rises().of(DeepslateFinaleRules.Noise.PULSE, PressureSources.takePulses(run.room))
                + look(ctx, run);
        long second = (ctx.tickCount() - run.waveTick) / 20;
        for (long s = run.lastSecond + 1; s <= second; s++) {
            units += DeepslateFinaleRules.quietRise(s, PocketDungeonsConfig.hushQuietSeconds());
        }
        run.lastSecond = Math.max(run.lastSecond, second);
        int heard = units > 0 ? PressureSources.addHeard(run.room, units) : PressureSources.heardOf(run.room);
        if (DeepslateFinaleRules.full(heard, run.ceiling)) {
            run.tellTick = ctx.tickCount();
            run.warned = true;
            tellBeat(ctx);
        } else if (!run.warned && DeepslateFinaleRules.nearlyFull(heard, run.ceiling)) {
            run.warned = true;
            overlay(ctx, "The sculk stirs.", ChatFormatting.GRAY);
        }
    }

    @Override
    public void onWon(FinaleContext ctx) {
        // The whelps go at once; the room stays held by the script, so the walk to the pad is not answered.
        sendAway(ctx, ctx.scriptState(Run.class, Run::new));
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        Run run = ctx.scriptState(Run.class, Run::new);
        sendAway(ctx, run);
        if (run.room != null) {
            PressureSources.release(run.room);
        }
    }

    // ---- the meter ---------------------------------------------------------------------------------

    /** The knobs as the rules class reads them. */
    private static DeepslateFinaleRules.Rises rises() {
        return new DeepslateFinaleRules.Rises(PocketDungeonsConfig.hushPulseRise(), PocketDungeonsConfig.hushBowRise(),
                PocketDungeonsConfig.hushHitRise(), PocketDungeonsConfig.hushDeathRise(),
                PocketDungeonsConfig.hushSprintRise());
    }

    /** Whether the room is listening: its script holds the meter, no tell is running, no whelp is out. */
    private static boolean listening(FinaleContext ctx, Run run) {
        return run.room != null && run.waveTick >= 0 && run.tellTick == NO_TELL && !whelpOut(ctx, run);
    }

    private static boolean whelpOut(FinaleContext ctx, Run run) {
        return Whelp.active(ctx.level(), ctx.record()) || !run.extras.isEmpty();
    }

    /**
     * Looks at every member: the shots they fired, the hits they took and whether they sprinted since the last
     * look. Returns the meter units those are worth and moves the baselines on, so a look while the room is
     * deaf simply forgets them.
     */
    private static int look(FinaleContext ctx, Run run) {
        DeepslateFinaleRules.Rises rises = rises();
        int bows = 0;
        int hits = 0;
        int sprints = 0;
        for (ServerPlayer member : ctx.members()) {
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

    // ---- the tell and the answer ---------------------------------------------------------------------

    /** One beat of the tell: a heartbeat for every member and the line on the action bar. */
    private static void tellBeat(FinaleContext ctx) {
        for (ServerPlayer member : ctx.members()) {
            ctx.level().playSound(null, member.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE,
                    TELL_VOLUME, 0.8f);
        }
        overlay(ctx, "The deep heard you.", ChatFormatting.DARK_AQUA);
    }

    /** The tell has run: the shrieker screams, the dark settles and the whelps come. */
    private static void answer(FinaleContext ctx, Run run) {
        run.tellTick = NO_TELL;
        run.warned = false;
        run.answers++;
        PressureSources.resetHeard(run.room);
        PressureSources.soundAnswer(ctx.level(), ctx.record(), run.room);
        ServerPlayer hearer = nearestMember(ctx, run.room);
        if (hearer == null) {
            return;
        }
        Whelp.spawn(ctx.level(), ctx.record(), hearer);
        Random random = new Random(ctx.tickCount());
        for (int i = 1; i < DeepslateFinaleRules.whelps(PocketDungeonsConfig.hushWhelps()); i++) {
            Vec3 at = Instances.randomSpawnNear(hearer, ctx.level(), random, 5, 10);
            Warden extra = at == null ? null : Whelp.create(ctx.level(), at, hearer);
            if (extra != null) {
                run.extras.add(extra.getUUID());
            }
        }
    }

    private static ServerPlayer nearestMember(FinaleContext ctx, BlockPos room) {
        AABB cell = CellGeometry.cellBounds(room);
        Vec3 centre = cell.getCenter();
        ServerPlayer best = null;
        for (ServerPlayer member : ctx.members()) {
            if (member.isAlive() && (best == null || member.position().distanceToSqr(centre)
                    < best.position().distanceToSqr(centre))) {
                best = member;
            }
        }
        return best;
    }

    /** Keeps the extra whelps on the party and sends them away when their time is up, as {@link Whelp#tick} does. */
    private static void tendExtras(FinaleContext ctx, Run run) {
        if (run.extras.isEmpty()) {
            return;
        }
        List<UUID> gone = new ArrayList<>();
        for (UUID id : run.extras) {
            Entity entity = ctx.level().getEntity(id);
            if (!(entity instanceof Warden whelp) || !whelp.isAlive()) {
                gone.add(id);
            } else if (whelp.tickCount >= PocketDungeonsConfig.whelpSeconds() * 20) {
                whelp.discard();
                gone.add(id);
            } else {
                ServerPlayer nearest = null;
                for (ServerPlayer member : ctx.members()) {
                    if (member.isAlive() && (nearest == null
                            || member.distanceToSqr(whelp) < nearest.distanceToSqr(whelp))) {
                        nearest = member;
                    }
                }
                if (nearest != null) {
                    Whelp.point(whelp, nearest);
                }
            }
        }
        run.extras.removeAll(gone);
    }

    /** The whelps go: the extras here, and the first through {@link Whelp}. */
    private static void sendAway(FinaleContext ctx, Run run) {
        for (UUID id : run.extras) {
            Entity entity = ctx.level().getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        run.extras.clear();
        if (run.room != null) {
            Whelp.clear(ctx.level(), ctx.record().slot);
        }
    }

    private static void overlay(FinaleContext ctx, String text, ChatFormatting colour) {
        for (ServerPlayer member : ctx.members()) {
            member.sendOverlayMessage(Component.literal(text).withStyle(colour));
        }
    }
}
