package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;

/**
 * The Copper Works furnace countdown (roadmap T32). A dungeon wide clock: the moment the trip
 * commits to a Copper Works door the furnace starts counting down ({@code copperClockMinutes},
 * 20 by default) and every member inside sees it on the action bar as "The furnace: 12:40",
 * red in the last two minutes. Each trial spawner the party clears buys
 * {@code copperClockBonusSeconds} back. At zero the furnace blows: a soft failure, never a
 * kill, which {@link CopperFinale} answers with the Foreman's wave arriving early and the
 * vents running faster.
 *
 * <p>The state is {@link IntervalState#copperClock}: one interval is one trip, so the clock is
 * born with the trip, keeps counting through floor transitions and the pad phases between
 * them, and is dropped by construction when the trip ends. In memory only, like the record:
 * a restart forgives the run anyway, so there is nothing to save.
 *
 * <p>Ticked from the same watch tick as the other floor systems ({@code Instances.onTick}),
 * but outside the floor-in-progress guard: the countdown is the trip's, not the floor's.
 */
final class CopperClock {

    /** The only dungeon with a clock: {@code pocketdungeons:copper_works}. */
    static final String DUNGEON = "pocketdungeons:copper_works";

    /**
     * One trip's clock, living on {@link IntervalState}. {@code deadline} is 0 until the trip
     * first enters the dungeon; {@code blown} latches at zero and never unsets: a blown
     * furnace does not cool down.
     */
    static final class Clock {
        long deadline;
        boolean shown;
        boolean blown;
        long floorSeed = Long.MIN_VALUE;
        int clearedSeen;
    }

    private CopperClock() {}

    /** One watch tick of the clock for a record, from {@code Instances.onTick}. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        if (record == null || record.layout == null || !DUNGEON.equals(record.interval.dungeonId)
                || record.phase == RunSession.Phase.HOME || record.phase == RunSession.Phase.SAFE_RETURN) {
            return;
        }
        Clock clock = record.interval.copperClock;
        long now = server.getTickCount();
        if (clock.deadline == 0) {
            clock.deadline = now + PocketDungeonsConfig.copperClockMinutes() * 1200L;
        }
        List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
        if (!clock.shown && !members.isEmpty()) {
            clock.shown = true;
            for (ServerPlayer member : members) {
                StaggeredTitle.show(server, member.getUUID(),
                        Component.literal("The furnace is counting down.").withStyle(ChatFormatting.GOLD),
                        List.of("Every cleared spawner buys it time."), ChatFormatting.GRAY);
            }
        }
        long left = clock.deadline - now;
        if (!clock.blown && left <= 0) {
            clock.blown = true;
            for (ServerPlayer member : members) {
                StaggeredTitle.show(server, member.getUUID(),
                        Component.literal("The furnace blows.").withStyle(ChatFormatting.RED),
                        List.of("The Foreman does not wait."), ChatFormatting.GRAY);
            }
        }
        if (!clock.blown) {
            credit(server, record, clock);
            left = clock.deadline - now;
        }
        String text = clock.blown ? "The furnace blew."
                : "The furnace: " + CopperClockRules.format(left);
        ChatFormatting colour = clock.blown || CopperClockRules.isRed(left)
                ? ChatFormatting.RED : ChatFormatting.GRAY;
        for (ServerPlayer member : members) {
            member.sendOverlayMessage(Component.literal(text).withStyle(colour));
        }
    }

    /**
     * Each cleared trial spawner buys {@code copperClockBonusSeconds} back. The floor's set is
     * re-read every tick and only the growth since last time pays, so nothing double counts;
     * a new floor (a new layout seed) starts the count again.
     */
    private static void credit(MinecraftServer server, InstanceRecord record, Clock clock) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        if (clock.floorSeed != record.layout.seed()) {
            clock.floorSeed = record.layout.seed();
            clock.clearedSeen = 0;
        }
        Set<BlockPos> spawners = TrialContent.activeSpawners(record.layout, level);
        int cleared = TrialContent.countCleared(level, spawners);
        if (cleared > clock.clearedSeen) {
            clock.deadline = CopperClockRules.deadlineWithBonus(clock.deadline,
                    cleared - clock.clearedSeen, PocketDungeonsConfig.copperClockBonusSeconds());
            clock.clearedSeen = cleared;
        }
    }

    /**
     * Whether the trip's clock has run out. {@link CopperFinale} reads this twice: to wake the
     * wave without cleared spawners, and to run the vent pulses faster.
     */
    static boolean expired(InstanceRecord record) {
        return record != null && record.interval.copperClock.blown;
    }
}
