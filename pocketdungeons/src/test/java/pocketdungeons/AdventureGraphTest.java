package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-JDK regression for {@link AdventureGraph}: node validation, the
 * boss-reset pool, and {@link AdventureGraph#pick}'s watcher-stability. The
 * weighted pick itself is covered by {@link KeystoneOfferTest}.
 */
public class AdventureGraphTest {

    public static void main(String[] args) {
        testNodeValidation();
        testResetTheme();
        testEntryThemesSorted();
        System.out.println("AdventureGraphTest: all checks passed");
    }

    private static void testNodeValidation() {
        // A boss node must not declare transitions: reaching one ends the
        // adventure, so a "next" list would never be read but would still be
        // a lie about what the node does.
        expectThrows(() -> new AdventureGraph.Node("drowned_vault", AdventureGraph.Kind.BOSS,
                List.of(new AdventureGraph.Transition("deepslate", 1))));

        // A non-boss node must declare at least one transition, or a door
        // could be offered into a dead end with nothing to pick from.
        expectThrows(() -> new AdventureGraph.Node("deepslate", AdventureGraph.Kind.ENTRY, List.of()));

        // A blank theme or a non-positive weight is never valid.
        expectThrows(() -> new AdventureGraph.Transition("", 1));
        expectThrows(() -> new AdventureGraph.Transition("deepslate", 0));
    }

    private static void testResetTheme() {
        AdventureGraph graph = AdventureGraph.of(Map.of(
                "deepslate", new AdventureGraph.Node("deepslate", AdventureGraph.Kind.ENTRY,
                        List.of(new AdventureGraph.Transition("blackstone", 1))),
                "blackstone", new AdventureGraph.Node("blackstone", AdventureGraph.Kind.DESCENT,
                        List.of(new AdventureGraph.Transition("deepslate", 1)))));

        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String reset = graph.resetTheme(owner, 3);
        check(reset, "deepslate", "the only entry theme is always the reset target");

        // Stable across repeat calls at the same depth, the same watcher-stability
        // rule every seeded pick in this mod follows.
        for (int i = 0; i < 20; i++) {
            check(graph.resetTheme(owner, 3), reset, "reset drifted on repeat call " + i);
        }

        // A graph with no entry theme at all (should never ship, per
        // AdventureGraphs' load-time rejection) degrades to null rather than
        // throwing.
        AdventureGraph noEntry = AdventureGraph.of(Map.of());
        check(noEntry.resetTheme(owner, 0), null, "no entry theme resets to null, not an exception");
    }

    private static void testEntryThemesSorted() {
        AdventureGraph graph = AdventureGraph.of(Map.of(
                "prismarine", new AdventureGraph.Node("prismarine", AdventureGraph.Kind.ENTRY,
                        List.of(new AdventureGraph.Transition("deepslate", 1))),
                "deepslate", new AdventureGraph.Node("deepslate", AdventureGraph.Kind.ENTRY,
                        List.of(new AdventureGraph.Transition("prismarine", 1))),
                "blackstone", new AdventureGraph.Node("blackstone", AdventureGraph.Kind.DESCENT,
                        List.of(new AdventureGraph.Transition("deepslate", 1)))));
        check(graph.entryThemes(), List.of("deepslate", "prismarine"),
                "only ENTRY-kind themes, sorted, DESCENT excluded");
    }

    private static void expectThrows(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalArgumentException");
    }

    private static void check(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
