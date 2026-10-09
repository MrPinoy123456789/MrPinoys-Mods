package pocketdungeons;

import java.util.List;

/** Q7: the sidebar's lines for a trip, a finished dungeon, a party, an ominous floor and the Endless Mine. */
public class SidebarLinesTest {

    public static void main(String[] args) {
        SidebarLines.Facts mid = new SidebarLines.Facts("Copper Works", false, 3, "Gear Loft", false, 3, 1,
                true, 2, 3, 2, false, 7, 0, 10, 4, 5);
        eq(SidebarLines.title(mid), "Copper Works");
        eq(texts(SidebarLines.lines(mid)), List.of("Floor 3: Gear Loft", "Lives 3", "Spawners 2/3", " ",
                "Haul 7 scrap", "Compass 10: 4/5"));
        eq(SidebarLines.lines(mid).get(1).colour(), "yellow");
        eq(SidebarLines.lines(mid).get(2).colour(), "green");   // 2 of the 2 needed

        SidebarLines.Facts low = new SidebarLines.Facts("Copper Works", false, 1, "", false, 1, 1,
                true, 0, 4, 3, false, 0, 0, 1, 0, 5);
        eq(texts(SidebarLines.lines(low)).get(0), "Floor 1");
        eq(SidebarLines.lines(low).get(1).colour(), "red");
        eq(SidebarLines.lines(low).get(2).colour(), "white");

        SidebarLines.Facts done = new SidebarLines.Facts("Infestation", false, 4, "The Queen's Nest", false, 4, 2,
                false, 4, 4, 3, true, 0, 9, 5, 2, 5);
        eq(texts(SidebarLines.lines(done)), List.of("Floor 4: The Queen's Nest", "Lives 4", "Party 2", " ",
                "Banked 9 scrap", "Compass 5: 2/5"));

        SidebarLines.Facts dark = new SidebarLines.Facts("Frostworks", false, 2, "Glaze Furnaces", true, 5, 1,
                true, 0, 2, 2, false, 3, 0, 12, 1, 5);
        eq(SidebarLines.lines(dark).get(0).text(), "Floor 2: Glaze Furnaces (ominous)");
        eq(SidebarLines.lines(dark).get(0).colour(), "dark_purple");

        SidebarLines.Facts mine = new SidebarLines.Facts("", true, 6, "", false, 5, 1, true, 1, 2, 2, false, 2, 0,
                3, 0, 5);
        eq(SidebarLines.title(mine), "Endless Mine");
        eq(SidebarLines.title(new SidebarLines.Facts("", false, 1, "", false, 5, 1, false, 0, 0, 0, false, 0, 0,
                1, 0, 5)), "Pocket Dungeons");

        // In a sculk room the meter shows under the spawners (design pass 2026-10-09, Q3).
        SidebarLines.Facts heard = new SidebarLines.Facts("Deepslate", false, 2, "Sculk Shallows", false, 4, 1,
                true, 0, 1, 1, false, 2, 0, 8, 1, 6, 2, 4);
        eq(texts(SidebarLines.lines(heard)), List.of("Floor 2: Sculk Shallows", "Lives 4", "Spawners 0/1", "Heard 2/4", " ",
                "Haul 2 scrap", "Compass 8: 1/6"));
        eq(SidebarLines.lines(heard).get(3).colour(), "dark_aqua");

        for (SidebarLines.Facts f : List.of(mid, low, done, dark, mine, heard)) {
            for (SidebarLines.Line line : SidebarLines.lines(f)) {
                if (line.text().contains("--") || line.text().contains("\u2014")) {
                    throw new AssertionError("dash punctuation: " + line.text());
                }
                if (line.text().length() > 40) {
                    throw new AssertionError("too wide for a sidebar: " + line.text());
                }
            }
        }
        System.out.println("SidebarLinesTest passed");
    }

    private static List<String> texts(List<SidebarLines.Line> lines) {
        return lines.stream().map(SidebarLines.Line::text).toList();
    }

    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
