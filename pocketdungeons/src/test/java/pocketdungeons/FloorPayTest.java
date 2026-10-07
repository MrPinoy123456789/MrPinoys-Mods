package pocketdungeons;

/**
 * Pure-JDK regression for {@link FloorPay}: the at/above floor split, the
 * over-level emerald rate (config 2 and 3) and the party example from plan
 * B. No Minecraft classpath, run from {@code tasks.test}.
 */
public class FloorPayTest {

    public static void main(String[] args) {
        testAtOrBelowPaysScrap();
        testAbovePaysEmeralds();
        testPartyExample();
        testLines();
        System.out.println("FloorPayTest passed");
    }

    private static void testAtOrBelowPaysScrap() {
        check(FloorPay.of(2, 7, 4, 2).scrap(), 2, "below the floor pays the dealt scrap");
        check(FloorPay.of(2, 7, 7, 2).scrap(), 2, "at the floor still pays scrap");
        check(FloorPay.of(2, 7, 7, 2).emeralds(), 0, "at the floor pays no emeralds");
        check(FloorPay.of(0, 7, 4, 2).scrap(), 0, "a zero step pays nothing");
        check(FloorPay.of(-1, 7, 4, 2).scrap(), 0, "a negative step is clamped to nothing");
    }

    private static void testAbovePaysEmeralds() {
        FloorPay.Payout pay = FloorPay.of(2, 7, 9, 2);
        check(pay.scrap(), 0, "above the floor pays no scrap");
        check(pay.emeralds(), 4, "above the floor pays step times the rate");
        check(FloorPay.of(2, 7, 9, 3).emeralds(), 6, "a rate of 3 triples the scrap");
        check(FloorPay.of(2, 7, 9, 0).emeralds(), 0, "a rate of 0 turns the payout off");
        check(FloorPay.of(3, 1, 2, 2).emeralds(), 6, "one level above is enough");
    }

    private static void testPartyExample() {
        // Plan B's example: leader at compass 4 and a friend at 9 clear a level
        // 5 floor dealt +2 (floor level 7): the leader gets 2 scrap, the friend
        // 4 emeralds.
        FloorPay.Payout leader = FloorPay.of(2, 7, 4, 2);
        FloorPay.Payout friend = FloorPay.of(2, 7, 9, 2);
        check(leader.scrap(), 2, "the plan's leader is paid scrap");
        check(friend.emeralds(), 4, "the plan's friend is paid emeralds");
    }

    private static void testLines() {
        check(FloorPay.of(2, 7, 4, 2).line().equals("+2 scrap"), "the scrap line");
        check(FloorPay.of(2, 7, 9, 2).line().equals("+4 emeralds, you are above this floor"),
                "the over-level line");
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " got " + actual);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
