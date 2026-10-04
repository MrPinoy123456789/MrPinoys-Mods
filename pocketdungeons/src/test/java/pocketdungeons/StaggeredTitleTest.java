package pocketdungeons;

import java.util.List;

/**
 * The shared big-text reveal (playtest 2026-10-03): subtitle lines stack under
 * the title one per beat, and the beat is the longer 20 ticks.
 *
 * <p>Headless: only the pure subtitle builder runs; the packets are covered by
 * a game test.
 */
public class StaggeredTitleTest {

    public static void main(String[] args) {
        List<String> affixes = List.of("SILENCED", "EXPLOSIVE", "MOLTEN");
        check(StaggeredTitle.subtitleAt(affixes, 0).isEmpty(), "beat 0 shows the title alone");
        check(StaggeredTitle.subtitleAt(affixes, 1).equals("SILENCED"), "beat 1 shows the first line");
        check(StaggeredTitle.subtitleAt(affixes, 2).equals("SILENCED  EXPLOSIVE"), "lines accumulate, two spaces apart");
        check(StaggeredTitle.subtitleAt(affixes, 3).equals("SILENCED  EXPLOSIVE  MOLTEN"), "all lines by the last beat");
        check(StaggeredTitle.subtitleAt(affixes, 9).equals("SILENCED  EXPLOSIVE  MOLTEN"), "past the end is clamped");
        check(StaggeredTitle.subtitleAt(List.of(), 1).isEmpty(), "no lines, no subtitle");
        check(StaggeredTitle.subtitleAt(List.of("GO HOME or DESCEND"), 1).equals("GO HOME or DESCEND"),
                "a single subtitle is one beat after the headline");
        check(StaggeredTitle.BEAT_TICKS == 20, "L14: the beat is 20 ticks");
        System.out.println("StaggeredTitleTest passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
