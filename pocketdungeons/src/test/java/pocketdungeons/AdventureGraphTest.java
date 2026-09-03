package pocketdungeons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        testUnresolvedTransitionsCascade();
        testPickNoDuplicatesWithEnoughTransitions();
        testPickRepeatsWhenFewerThanThreeTransitions();
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

    /**
     * PD-22: {@code a} transitions to {@code b}, and {@code b} transitions to
     * a theme nobody declared a node for. Whichever of {@code a} or {@code b}
     * a single validation pass happened to visit first used to decide whether
     * this cascade was caught in one load. The fixpoint property that matters
     * is the outcome, not the order it takes to get there: both must end up
     * removed regardless of which order the map iterates in, and {@code c},
     * a self-contained valid node, must survive untouched.
     */
    private static void testUnresolvedTransitionsCascade() {
        Map<String, AdventureGraph.Node> nodes = new HashMap<>(Map.of(
                "a", new AdventureGraph.Node("a", AdventureGraph.Kind.DESCENT,
                        List.of(new AdventureGraph.Transition("b", 1))),
                "b", new AdventureGraph.Node("b", AdventureGraph.Kind.DESCENT,
                        List.of(new AdventureGraph.Transition("nonexistent", 1))),
                "c", new AdventureGraph.Node("c", AdventureGraph.Kind.ENTRY,
                        List.of(new AdventureGraph.Transition("c", 1)))));
        List<String> rejections = new ArrayList<>();

        AdventureGraphs.removeUnresolvedTransitions(nodes, rejections,
                id -> id.equals("a") || id.equals("b") || id.equals("c"));

        check(nodes.containsKey("a"), false, "a depends on b, which is invalid, so a must cascade out too");
        check(nodes.containsKey("b"), false, "b's own transition target does not exist");
        check(nodes.containsKey("c"), true, "c is self-contained and valid, must survive");
    }

    /**
     * M42.6: a node with four distinct transitions must never offer the same
     * theme on two of its three doors. Swept across many owners and depths,
     * since {@code pick}'s seed depends on both.
     */
    private static void testPickNoDuplicatesWithEnoughTransitions() {
        AdventureGraph graph = AdventureGraph.of(Map.of(
                "hub", new AdventureGraph.Node("hub", AdventureGraph.Kind.DESCENT, List.of(
                        new AdventureGraph.Transition("a", 3),
                        new AdventureGraph.Transition("b", 2),
                        new AdventureGraph.Transition("c", 2),
                        new AdventureGraph.Transition("d", 1)))));
        for (int i = 0; i < 200; i++) {
            UUID owner = UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", i));
            for (int depth = 0; depth < 5; depth++) {
                List<String> offers = graph.pick(owner, "hub", depth);
                check(offers.size(), 3, "pick always offers exactly three");
                check(Set.copyOf(offers).size(), 3,
                        "owner " + owner + " depth " + depth + " offered a duplicate: " + offers);
            }
        }
    }

    /**
     * A node with only one real transition legitimately has nothing else to
     * offer, so all three doors show it rather than the dedupe leaving a
     * door with no offer at all.
     */
    private static void testPickRepeatsWhenFewerThanThreeTransitions() {
        AdventureGraph graph = AdventureGraph.of(Map.of(
                "lonely", new AdventureGraph.Node("lonely", AdventureGraph.Kind.DESCENT,
                        List.of(new AdventureGraph.Transition("only", 1)))));
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000009");
        check(graph.pick(owner, "lonely", 0), List.of("only", "only", "only"),
                "a single transition repeats across all three doors");
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
