package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The pure rules of the Copper Works furnace clock and its vent floor (roadmap T32):
 * the countdown's text, when it shows red, what a cleared spawner buys, the vent
 * cycle once the furnace blew and the fixed order the vents sweep in. No world
 * access, so every decision here is checked by {@code CopperClockRulesTest}.
 */
final class CopperClockRules {

    private CopperClockRules() {}

    /** The last two minutes of the countdown show red on the action bar. */
    static final long RED_TICKS = 2400L;

    /** The tell before a vent fires: a hiss and rising smoke, the 2 seconds the design asks for. */
    static final int TELL_TICKS = 40;

    /** How long a vent's flame runs once it fires. */
    static final int BURN_TICKS = 20;

    /** The shortest vent cycle: the tell plus the burn, so the tell always lands first. */
    static final int MIN_PERIOD_TICKS = TELL_TICKS + BURN_TICKS;

    /** mm:ss of the remaining ticks, a part second rounding up so 1 tick still reads 0:01. */
    static String format(long ticksLeft) {
        long seconds = Math.max(0L, (ticksLeft + 19L) / 20L);
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return minutes + ":" + (rest < 10L ? "0" : "") + rest;
    }

    /** Whether the countdown shows red: the last two minutes. */
    static boolean isRed(long ticksLeft) {
        return ticksLeft <= RED_TICKS;
    }

    /** The deadline after {@code clears} more spawners fell, each worth {@code bonusSeconds} back. */
    static long deadlineWithBonus(long deadline, int clears, int bonusSeconds) {
        return deadline + Math.max(0, clears) * bonusSeconds * 20L;
    }

    /**
     * One vent's cycle in ticks: {@code baseSeconds} a vent, {@code blownPercent} of that once
     * the clock ran out, and never under {@link #MIN_PERIOD_TICKS} so the tell always lands
     * before the burn no matter how the knobs are set.
     */
    static int ventPeriodTicks(int baseSeconds, boolean blown, int blownPercent) {
        long period = Math.max(0, baseSeconds) * 20L;
        if (blown) {
            period = period * Math.max(0, Math.min(100, blownPercent)) / 100L;
        }
        return (int) Math.max(MIN_PERIOD_TICKS, period);
    }

    /**
     * The fixed sweep the vents pulse in: rows north to south, and inside each row the walk
     * alternates west to east then east to west (a serpentine), so the burn visibly crosses
     * the room and a member can read where it goes next.
     */
    static List<BlockPos> ventOrder(List<BlockPos> vents) {
        List<BlockPos> sorted = new ArrayList<>(vents);
        sorted.sort(Comparator.comparingInt((BlockPos pos) -> pos.getZ())
                .thenComparingInt((BlockPos pos) -> pos.getX()));
        List<BlockPos> order = new ArrayList<>(sorted.size());
        List<BlockPos> row = new ArrayList<>();
        int rowZ = Integer.MIN_VALUE;
        int rows = 0;
        for (BlockPos pos : sorted) {
            if (pos.getZ() != rowZ && !row.isEmpty()) {
                if (rows % 2 == 1) {
                    Collections.reverse(row);
                }
                order.addAll(row);
                row = new ArrayList<>();
                rows++;
            }
            rowZ = pos.getZ();
            row.add(pos);
        }
        if (rows % 2 == 1) {
            Collections.reverse(row);
        }
        order.addAll(row);
        return order;
    }
}
