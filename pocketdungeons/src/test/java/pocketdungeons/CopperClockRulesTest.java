package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Roadmap T32: the Copper Works furnace clock's text, its red window, the spawner bonus, the
 * vent cycle once the furnace blew and the fixed serpentine sweep the vents pulse in.
 */
public class CopperClockRulesTest {

    public static void main(String[] args) {
        // The countdown text: mm:ss, a part second rounding up so the last tick reads 0:01.
        check(CopperClockRules.format(20L * 60 * 12 + 20L * 40).equals("12:40"), "12:40 shows as 12:40");
        check(CopperClockRules.format(20L * 60).equals("1:00"), "one minute shows 1:00");
        check(CopperClockRules.format(20L * 60 * 20).equals("20:00"), "the default reads 20:00");
        check(CopperClockRules.format(1).equals("0:01"), "one tick still reads 0:01");
        check(CopperClockRules.format(19).equals("0:01"), "a part second rounds up");
        check(CopperClockRules.format(0).equals("0:00"), "zero reads 0:00");
        check(CopperClockRules.format(-200).equals("0:00"), "never negative");

        // Red: the last two minutes, inclusive of the boundary tick.
        check(CopperClockRules.isRed(2400), "2:00 exactly is red");
        check(CopperClockRules.isRed(2399), "under two minutes is red");
        check(!CopperClockRules.isRed(2401), "over two minutes is not red");
        check(CopperClockRules.RED_TICKS == 2400L, "the red window is two minutes");

        // The spawner bonus: each clear buys the knob's seconds back, and never rewinds.
        check(CopperClockRules.deadlineWithBonus(10_000, 3, 60) == 10_000 + 3 * 60 * 20,
                "three clears buy three minutes");
        check(CopperClockRules.deadlineWithBonus(10_000, 0, 60) == 10_000, "no clear, no time");
        check(CopperClockRules.deadlineWithBonus(10_000, -2, 60) == 10_000, "a bad count never rewinds the clock");
        check(CopperClockRules.deadlineWithBonus(10_000, 2, 0) == 10_000, "a zero knob pays nothing");

        // The vent cycle: the knob seconds, faster once blown, never under the tell plus burn.
        check(CopperClockRules.ventPeriodTicks(8, false, 50) == 160, "eight seconds is 160 ticks");
        check(CopperClockRules.ventPeriodTicks(8, true, 50) == 80, "blown at 50 percent halves it");
        check(CopperClockRules.ventPeriodTicks(8, true, 100) == 160, "100 percent is no change");
        check(CopperClockRules.ventPeriodTicks(8, true, 10) == 60, "10 percent hits the floor");
        check(CopperClockRules.ventPeriodTicks(8, true, 150) == 160, "over 100 percent clamps to no change");
        check(CopperClockRules.ventPeriodTicks(3, true, 50) == 60, "the cycle never outruns the tell");
        check(CopperClockRules.ventPeriodTicks(0, false, 50) == 60,
                "even a zero knob leaves the full tell");
        check(CopperClockRules.MIN_PERIOD_TICKS == CopperClockRules.TELL_TICKS + CopperClockRules.BURN_TICKS,
                "the floor is exactly the tell plus the burn");

        // The sweep: rows north to south, walking west to east then east to west.
        List<BlockPos> vents = List.of(
                new BlockPos(11, 0, 9), new BlockPos(4, 0, 6), new BlockPos(7, 0, 9),
                new BlockPos(11, 0, 6), new BlockPos(7, 0, 6), new BlockPos(4, 0, 9));
        List<BlockPos> order = CopperClockRules.ventOrder(vents);
        check(order.size() == 6, "every vent is in the sweep");
        check(order.get(0).equals(new BlockPos(4, 0, 6)), "the north row starts west");
        check(order.get(1).equals(new BlockPos(7, 0, 6)) && order.get(2).equals(new BlockPos(11, 0, 6)),
                "the north row walks east");
        check(order.get(3).equals(new BlockPos(11, 0, 9)), "the south row takes up where it left off");
        check(order.get(4).equals(new BlockPos(7, 0, 9)) && order.get(5).equals(new BlockPos(4, 0, 9)),
                "the south row walks back west");
        check(CopperClockRules.ventOrder(List.of(new BlockPos(5, 0, 5))).size() == 1,
                "a lone vent is a one step sweep");
        check(CopperClockRules.ventOrder(List.of()).isEmpty(), "no vents, no sweep");

        System.out.println("CopperClockRulesTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
