package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regression for {@link AdventureGraph#pick}, the M11 replacement for
 * {@code ThemeOfferMath.pick} this file used to test.
 */
public class KeystoneOfferTest {

    public static void main(String[] args) {
        // M42.6: deepslate carries three distinct transitions, not two. pick
        // now dedupes by theme before taking the top three (no node offers
        // the same theme on two doors), which collapses the output space to
        // a single fixed permutation when only two distinct themes are
        // available; the "different owners should differ" check below needs
        // a third to stay a meaningful property rather than a coin flip.
        AdventureGraph graph = AdventureGraph.of(Map.of(
                "deepslate", new AdventureGraph.Node("deepslate", AdventureGraph.Kind.ENTRY, List.of(
                        new AdventureGraph.Transition("prismarine", 3),
                        new AdventureGraph.Transition("blackstone", 1),
                        new AdventureGraph.Transition("drowned_vault", 1))),
                "prismarine", new AdventureGraph.Node("prismarine", AdventureGraph.Kind.ENTRY, List.of(
                        new AdventureGraph.Transition("deepslate", 1))),
                "blackstone", new AdventureGraph.Node("blackstone", AdventureGraph.Kind.DESCENT, List.of(
                        new AdventureGraph.Transition("drowned_vault", 1))),
                "drowned_vault", new AdventureGraph.Node("drowned_vault", AdventureGraph.Kind.BOSS, List.of())));

        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<String> expected = graph.pick(owner, "deepslate", 1);
        for (int i = 0; i < 100; i++) {
            check(graph.pick(owner, "deepslate", 1), expected, "stable repeat " + i);
        }
        check(expected.size(), 3, "always three offers");

        UUID different = UUID.fromString("10000000-0000-0000-0000-000000000001");
        if (graph.pick(different, "deepslate", 1).equals(expected)) {
            throw new AssertionError("different owners should differ");
        }

        // A different depth re-seeds the pick even for the same node.
        boolean depthMatters = false;
        for (int depth = 1; depth <= 20; depth++) {
            if (!graph.pick(owner, "deepslate", depth).equals(expected)) {
                depthMatters = true;
                break;
            }
        }
        if (!depthMatters) {
            throw new AssertionError("depth never changed the pick across 20 tries");
        }

        // A theme with no node (unknown, or not yet loaded) falls back to the
        // entry pool rather than throwing.
        List<String> fallback = graph.pick(owner, "not_a_real_theme", 0);
        check(fallback.size(), 3, "fallback still returns three offers");
        for (String theme : fallback) {
            if (!theme.equals("deepslate") && !theme.equals("prismarine")) {
                throw new AssertionError("fallback picked a non-entry theme: " + theme);
            }
        }

        // The empty graph never throws either.
        check(AdventureGraph.EMPTY.pick(owner, "", 0), List.of("", "", ""), "empty graph");

        System.out.println("KeystoneOfferTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
