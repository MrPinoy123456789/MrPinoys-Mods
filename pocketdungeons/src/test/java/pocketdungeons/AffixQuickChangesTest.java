package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

/** T10: the affix workshop's five quick changes (2026-10-11). */
public class AffixQuickChangesTest {

    public static void main(String[] args) throws Exception {
        mineFuse();
        silencedCount();
        cookedLine();
        System.out.println("AffixQuickChangesTest passed");
    }

    /** Explosive: the mine fuse is 25 ticks (a quarter longer than the old 20). */
    private static void mineFuse() throws Exception {
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/RoomContent.java"));
        check(src.contains("MINE_FUSE_TICKS = 25;"), "the mine fuse is 25 ticks");
        check(!src.contains("MINE_FUSE_TICKS = 20;"), "the old 20 tick fuse is gone");
    }

    /** Silenced: the action bar count reads "Silenced: n/3" and the wave is sent on the third use. */
    private static void silencedCount() throws Exception {
        check(OmenBarText.silencedCount(1, 3).equals("Silenced: 1/3"), "first use reads 1/3");
        check(OmenBarText.silencedCount(3, 3).equals("Silenced: 3/3"), "third use reads 3/3");
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/PressureSources.java"));
        check(src.contains("OmenBarText.silencedCount(Math.min(uses, per), per)"), "silencedUse shows the count");
        check(src.contains("silencedConsumablesPerOmen()"), "the every-third-use rule still reads the config");
    }

    /** Cooked: the floor clear lines carry the bonus line only on a Cooked floor. */
    private static void cookedLine() {
        check(OmenBarText.clearedLines(false, false, false).equals(java.util.List.of("GO HOME or DESCEND")),
                "no Cooked line on a plain floor");
        check(OmenBarText.clearedLines(true, false, false).equals(java.util.List.of("LEAVE")),
                "no Cooked line on a plain final floor");
        java.util.List<String> cooked = OmenBarText.clearedLines(false, true, true);
        check(cooked.size() == 2 && cooked.get(0).equals(OmenBarText.clearedSubtitle(false, true))
                && cooked.get(1).equals(OmenBarText.COOKED_BONUS_LINE), "a Cooked floor adds one line after the next step");
        check(OmenBarText.COOKED_BONUS_LINE.length() <= 25, "the line fits a title");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
