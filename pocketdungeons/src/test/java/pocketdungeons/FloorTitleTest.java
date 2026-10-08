package pocketdungeons;

/**
 * PD-173: the floor-start title names the floor, not just the dungeon, and the first floor of a
 * trip says which dungeon it belongs to under it.
 */
public class FloorTitleTest {

    public static void main(String[] args) {
        eq(FloorStartTitle.titleFor("MINESHAFT", "Mine Mouth"), "MINE MOUTH");
        eq(FloorStartTitle.titleFor("MINESHAFT", null), "MINESHAFT");
        eq(FloorStartTitle.titleFor("MINESHAFT", " "), "MINESHAFT");
        eq(FloorStartTitle.titleFor("HOME", "Mine Mouth"), "HOME");

        eq(FloorStartTitle.dungeonLine("MINESHAFT", "Mine Mouth", 1), "MINESHAFT");
        check(FloorStartTitle.dungeonLine("MINESHAFT", "Mine Mouth", 2) == null, "later floors add no dungeon line");
        check(FloorStartTitle.dungeonLine("MINESHAFT", null, 1) == null, "no floor name, no dungeon line");
        check(FloorStartTitle.dungeonLine("HOME", "Mine Mouth", 1) == null, "home has none");
        check(FloorStartTitle.dungeonLine("MINESHAFT", "Mineshaft", 1) == null, "a name that repeats the dungeon adds nothing");
        System.out.println("FloorTitleTest passed");
    }

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected [" + expected + "] but was [" + actual + "]");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
