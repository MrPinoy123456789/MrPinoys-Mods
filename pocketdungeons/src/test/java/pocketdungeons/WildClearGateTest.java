package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owner playtest 2026-11-02: every spawner of a wild dungeon was done and no exit or rewards appeared. The watcher
 * only asked the clear gate when {@code gameTime % 10 == 0}, but the watch runs every 20 ticks counted from server
 * start, so on a restart whose boot offset is not a multiple of 10 the two never lined up. The gate must be asked on
 * every watch tick and never be tied to the world clock.
 */
public class WildClearGateTest {

    public static void main(String[] args) throws Exception {
        String src = Files.readString(Path.of("src/main/java/pocketdungeons/WildClear.java"));
        check(!src.contains("gameTime %"), "the clear gate is not tied to the world clock's phase");
        check(!src.contains("CHECK_EVERY"), "no separate check period is left to drift against the watch");
        check(src.contains("RunLifecycle.padRefusal(server, record, level) == null"), "the gate itself still decides the clear");
        System.out.println("WildClearGateTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
