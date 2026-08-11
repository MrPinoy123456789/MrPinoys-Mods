package bounties.core;

import java.util.List;
import java.util.UUID;

/**
 * Dependency-free test suite for the bounty rules.
 *
 * <p>Run with: javac -d build $(find core/src -name '*.java') && java -cp build bounties.core.BountiesTest
 */
public final class BountiesTest {

    private static int tests = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        rotationIsDeterministic();
        boardChangesEveryHalfHour();
        boundariesAreOffsetByFifteenMinutes();
        acceptRespectsMaxThree();
        abandonFreesASlot();
        progressIncrementsAndCompletesOnce();
        twoPlayersAreIndependent();

        System.out.println(tests + " tests, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void rotationIsDeterministic() {
        BountyPool pool = samplePool();
        long now = System.currentTimeMillis();

        Board first = BountyMath.boardAt(pool, now);
        Board second = BountyMath.boardAt(pool, now);

        assertSame("rotation determinism slot1", first.slot1(), second.slot1());
        assertSame("rotation determinism slot2", first.slot2(), second.slot2());
    }

    private static void boardChangesEveryHalfHour() {
        BountyPool pool = samplePool();
        long base = 1_000_000_000_000L; // arbitrary fixed point

        long w = BountyMath.windowIndex(base);
        Board b0 = BountyMath.boardAtWindow(pool, w);
        Board b1 = BountyMath.boardAtWindow(pool, w + 1);
        Board b2 = BountyMath.boardAtWindow(pool, w + 2);

        assertTrue("slot1 changes over windows",
                b0.slot1() != b1.slot1() || b1.slot1() != b2.slot1());
        assertTrue("slot2 changes over windows",
                b0.slot2() != b1.slot2() || b1.slot2() != b2.slot2());
    }

    private static void boundariesAreOffsetByFifteenMinutes() {
        // Trivia on the hour/half-hour, bounties at :15 and :45.
        long hour = 60L * 60L * 1000L;
        long fifteen = 15L * 60L * 1000L;

        BountyPool pool = samplePool();

        // Just before the :15 boundary, the board is the same as at :00.
        Board atZero = BountyMath.boardAt(pool, 0L);
        Board atFourteenFiftyNine = BountyMath.boardAt(pool, 14L * 60L * 1000L + 59L * 1000L);
        assertSame(":00 and :14:59 have the same board", atZero.slot1(), atFourteenFiftyNine.slot1());

        // At exactly :15 slot 2 rotates (slot 1 stays until :45).
        Board atFifteen = BountyMath.boardAt(pool, fifteen);
        assertSame("slot 1 unchanged at :15", atZero.slot1(), atFifteen.slot1());
        assertTrue("slot 2 changes at :15", atZero.slot2() != atFifteen.slot2());

        // Same for :45 inside the hour: slot 1 rotates.
        Board atThirty = BountyMath.boardAt(pool, 30L * 60L * 1000L);
        Board beforeFortyFive = BountyMath.boardAt(pool, 44L * 60L * 1000L + 59L * 1000L);
        Board atFortyFive = BountyMath.boardAt(pool, 45L * 60L * 1000L);
        assertSame(":30 and :44:59 have the same slot 2", atThirty.slot2(), beforeFortyFive.slot2());
        assertSame("slot 2 unchanged at :45", atThirty.slot2(), atFortyFive.slot2());
        assertTrue("slot 1 changes at :45", beforeFortyFive.slot1() != atFortyFive.slot1());
    }

    private static void acceptRespectsMaxThree() {
        PlayerBounties player = new PlayerBounties();
        BountyPool pool = samplePool();
        Board board = BountyMath.boardAtWindow(pool, 1234L);

        AcceptResult r1 = player.accept(board.slot1(), 0L);
        assertTrue("first accept ok", r1.ok());
        AcceptResult r2 = r1.state().accept(board.slot2(), 0L);
        assertTrue("second accept ok", r2.ok());
        AcceptResult r3 = r2.state().accept(board.slot1(), 0L);
        assertTrue("third accept ok", r3.ok());
        AcceptResult r4 = r3.state().accept(board.slot2(), 0L);
        assertFalse("fourth accept rejected", r4.ok());
        assertTrue("message mentions limit", r4.message().contains("3"));
    }

    private static void abandonFreesASlot() {
        BountyPool pool = samplePool();
        PlayerBounties player = new PlayerBounties()
                .accept(pool.at(0), 0L).state()
                .accept(pool.at(1), 0L).state();

        AbandonResult abandoned = player.abandon(2);
        assertTrue("abandon ok", abandoned.ok());
        assertSame("abandoned definition returned", pool.at(1), abandoned.abandoned());
        assertEquals("one slot free after abandon", 1, abandoned.state().heldCount());

        AcceptResult readded = abandoned.state().accept(pool.at(2), 0L);
        assertTrue("can accept after abandoning", readded.ok());
    }

    private static void progressIncrementsAndCompletesOnce() {
        BountyDefinition zombie = new BountyDefinition("minecraft:zombie", 3, 1,
                "Zombie", "Kill 3 zombies");
        PlayerBounties player = new PlayerBounties()
                .accept(zombie, 0L).state();

        ProgressResult p1 = player.progress("minecraft:zombie");
        assertEquals("progress 1/3", 1, p1.state().held().get(0).progress());
        assertFalse("not completed on first kill", p1.anyCompleted());

        ProgressResult p2 = p1.state().progress("minecraft:zombie");
        assertEquals("progress 2/3", 2, p2.state().held().get(0).progress());
        assertFalse("not completed on second kill", p2.anyCompleted());

        ProgressResult p3 = p2.state().progress("minecraft:zombie");
        assertTrue("completed on third kill", p3.anyCompleted());
        assertEquals("pays configured diamonds", 1, p3.completed().get(0).rewardDiamonds());
        assertEquals("bounty removed after completion", 0, p3.state().heldCount());

        ProgressResult p4 = p3.state().progress("minecraft:zombie");
        assertFalse("no further completion without bounty", p4.anyCompleted());
    }

    private static void twoPlayersAreIndependent() {
        BountyDefinition blaze = new BountyDefinition("minecraft:blaze", 2, 3,
                "Blaze", "Kill 2 blazes");
        PlayerBounties a = new PlayerBounties().accept(blaze, 0L).state();
        PlayerBounties b = new PlayerBounties().accept(blaze, 0L).state();

        ProgressResult pa = a.progress("minecraft:blaze");
        ProgressResult pb = b.progress("minecraft:blaze");

        assertEquals("player A progress 1/2", 1, pa.state().held().get(0).progress());
        assertEquals("player B progress 1/2", 1, pb.state().held().get(0).progress());
        assertFalse("player A not complete", pa.anyCompleted());
        assertFalse("player B not complete", pb.anyCompleted());
    }

    private static BountyPool samplePool() {
        return BountyPool.of(
                new BountyDefinition("minecraft:zombie", 5, 1, "Zombie", "Kill 5 zombies"),
                new BountyDefinition("minecraft:skeleton", 5, 1, "Skeleton", "Kill 5 skeletons"),
                new BountyDefinition("minecraft:spider", 5, 2, "Spider", "Kill 5 spiders"),
                new BountyDefinition("minecraft:creeper", 4, 3, "Creeper", "Kill 4 creepers"),
                new BountyDefinition("minecraft:blaze", 3, 3, "Blaze", "Kill 3 blazes"),
                new BountyDefinition("minecraft:ender_dragon", 1, 10, "Ender Dragon", "Kill the dragon"),
                new BountyDefinition("minecraft:wither", 1, 10, "Wither", "Kill the wither"),
                new BountyDefinition("minecraft:witch", 4, 2, "Witch", "Kill 4 witches"));
    }

    // ---- tiny assertion helpers ------------------------------------------------

    private static void assertTrue(String name, boolean condition) {
        tests++;
        if (!condition) {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void assertFalse(String name, boolean condition) {
        assertTrue(name, !condition);
    }

    private static void assertSame(String name, Object a, Object b) {
        assertTrue(name, a == b);
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + expected + " actual=" + actual);
        }
    }
}
