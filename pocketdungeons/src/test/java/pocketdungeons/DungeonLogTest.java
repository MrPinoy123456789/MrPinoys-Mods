package pocketdungeons;

import java.util.UUID;

/**
 * Regression for {@link DungeonLog}'s {@code currentTheme}/{@code depth}
 * plumbing (M11), the replacement for the {@code ThemeHistory} window this
 * file used to test. No server is available in this headless test, so
 * {@code AdventureGraphs.current()} is always the empty graph and
 * {@link DungeonLog#recordTheme} always takes its "no node for this theme"
 * branch; the graph's own kind-based branching is covered separately in
 * {@link KeystoneOfferTest}.
 */
public class DungeonLogTest {

    public static void main(String[] args) {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

        DungeonLog log = new DungeonLog();
        check(log.get(player).currentTheme(), "", "unset player has no current theme");
        check(log.get(player).depth(), 0, "unset player is at depth 0");

        DungeonLog.Entry first = log.recordTheme(player, "deepslate");
        check(first.currentTheme(), "deepslate", "first completion sets currentTheme");
        check(first.depth(), 1, "first completion starts depth at 1");
        check(first.completedThemes().get("deepslate"), Integer.valueOf(1), "theme counted");

        DungeonLog.Entry second = log.recordTheme(player, "prismarine");
        check(second.currentTheme(), "prismarine", "second completion advances currentTheme");
        check(second.depth(), 2, "second completion advances depth");

        // A blank or null theme is a no-op, same as ThemeHistory.push's old contract.
        check(log.recordTheme(player, "").currentTheme(), "prismarine", "blank theme ignored");
        check(log.recordTheme(player, null).depth(), 2, "null theme ignored");

        // currentTheme/depth survive every other mutation untouched.
        log.setKeystone(player, 5, java.util.EnumSet.noneOf(Affix.class));
        check(log.get(player).currentTheme(), "prismarine", "setKeystone preserves currentTheme");
        log.recordCompletion(player, 7, 5);
        check(log.get(player).depth(), 2, "recordCompletion preserves depth");
        log.setPendingOffer(player, 5);
        log.clearPendingOffer(player);
        check(log.get(player).currentTheme(), "prismarine", "pending-offer round trip preserves currentTheme");

        System.out.println("DungeonLogTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
