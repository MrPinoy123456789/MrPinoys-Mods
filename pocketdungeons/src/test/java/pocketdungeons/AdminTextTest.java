package pocketdungeons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The operator screens' pure parts (2026-11-04): directions, distances, paging, filters and the help catalogue. */
public class AdminTextTest {

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    public static void main(String[] args) {
        check(AdminText.compass(0, -100).equals("N"), "north is -z");
        check(AdminText.compass(0, 100).equals("S"), "south is +z");
        check(AdminText.compass(100, 0).equals("E"), "east is +x");
        check(AdminText.compass(-100, 0).equals("W"), "west is -x");
        check(AdminText.compass(100, -100).equals("NE"), "north east");
        check(AdminText.compass(-100, 100).equals("SW"), "south west");
        check(AdminText.compass(0, 0).equals("here"), "on the spot");
        check(AdminText.distance(80.4).equals("80m") && AdminText.distance(1234).equals("1.2km"), "distances");

        check(AdminText.pages(0) == 1 && AdminText.pages(10) == 1 && AdminText.pages(11) == 2, "pages");
        check(AdminText.clampPage(9, 25) == 3 && AdminText.clampPage(0, 25) == 1, "page clamp");
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(i);
        }
        check(AdminText.page(rows, 3).equals(List.of(20, 21, 22, 23, 24)), "the last page");
        check(AdminText.page(List.of(), 1).isEmpty(), "an empty list");

        WildState.Lodestone l = new WildState.Lodestone("w1", "minecraft:overworld", 0, 70, 0, "x", 1, 0L, 100L);
        check(AdminText.Filter.READY.matches(l) && !AdminText.Filter.COOLING.matches(l), "ready");
        l.markCleared(10L, 7200L);
        check(AdminText.Filter.COOLING.matches(l) && AdminText.state(l, 10L).equals("Cooling, 2h"), "cooling");
        l.ominous = true;
        check(AdminText.Filter.OMINOUS.matches(l) && AdminText.Filter.ALL.matches(l), "ominous");

        Set<String> usages = new HashSet<>();
        for (AdminText.Entry e : AdminText.CATALOGUE) {
            check(usages.add(e.usage()), "listed once: " + e.usage());
            check(AdminText.SECTIONS.contains(e.section()), "a known section: " + e);
            check(e.what().length() <= 72 && e.what().endsWith("."), "a short sentence: " + e.what());
            check(!e.what().contains(EM_DASH) && !e.what().contains(" " + "-" + "- "), "no dashes: " + e.what());
            check(e.stem().startsWith("/dungeon admin ") && !e.stem().contains("<") && !e.stem().contains("["),
                    "the click fills the command up to its arguments: " + e.stem());
        }
        check(new AdminText.Entry("x", "wild info <id>", "x.", AdminText.Mode.ANY).stem()
                .equals("/dungeon admin wild info "), "stem stops at the first argument");
        check(new AdminText.Entry("x", "manifest reload|list", "x.", AdminText.Mode.ANY).stem()
                .equals("/dungeon admin manifest "), "stem stops before a choice");
        check(new AdminText.Entry("x", "coverage", "x.", AdminText.Mode.ANY).stem()
                .equals("/dungeon admin coverage"), "a bare command is whole");
        for (String s : AdminText.SECTIONS) {
            check(AdminText.sectionNamed(AdminText.helpWord(s)).equals(s), "help word " + AdminText.helpWord(s));
        }
        check(AdminText.section(AdminText.PLAYERS, true).isEmpty(), "a wild server has no campaign player tools");
        check(!AdminText.section(AdminText.WILD, true).isEmpty() && AdminText.section(AdminText.WILD, false).isEmpty(),
                "the wild commands show on a wild server only");
        System.out.println("AdminTextTest passed (" + AdminText.CATALOGUE.size() + " commands)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
