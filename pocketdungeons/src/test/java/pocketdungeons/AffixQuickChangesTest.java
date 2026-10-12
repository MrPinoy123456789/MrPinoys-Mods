package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

/** T10: the affix workshop's five quick changes (2026-10-11). */
public class AffixQuickChangesTest {

    public static void main(String[] args) throws Exception {
        mineFuse();
        System.out.println("AffixQuickChangesTest passed");
    }

    /** Explosive: the mine fuse is 25 ticks (a quarter longer than the old 20). */
    private static void mineFuse() throws Exception {
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/RoomContent.java"));
        check(src.contains("MINE_FUSE_TICKS = 25;"), "the mine fuse is 25 ticks");
        check(!src.contains("MINE_FUSE_TICKS = 20;"), "the old 20 tick fuse is gone");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
