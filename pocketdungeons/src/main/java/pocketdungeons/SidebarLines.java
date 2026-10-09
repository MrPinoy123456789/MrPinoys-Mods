package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The words of the trip sidebar (design pass 2026-10-09, Q7; PD-187, PD-190), with no Minecraft imports so
 * the lines are testable with plain {@code javac}. {@link SidebarDisplay} turns them into packets.
 *
 * <p>Short on purpose: the sidebar is a narrow column down the right edge, and the player never reads chat
 * live, so the facts he needs mid-floor (where he is, how many lives, how many spawners, what he carries)
 * live here.
 */
final class SidebarLines {

    private SidebarLines() {}

    /** One line of the sidebar: its text and a colour name ({@link SidebarDisplay} maps it to a style). */
    record Line(String text, String colour) {}

    /** Everything the sidebar reads, gathered by the caller so this class stays pure. */
    record Facts(String dungeon, boolean mine, int floor, String floorName, boolean ominous, int lives,
                 int party, boolean onFloor, int spawnersCleared, int spawnersTotal, int spawnersNeeded,
                 boolean finished, int haul, int banked, int compass, int progress, int levelCost,
                 int heard, int heardMax) {

        /** A floor with no sculk room under the player: the shape before the Heard meter. */
        Facts(String dungeon, boolean mine, int floor, String floorName, boolean ominous, int lives, int party,
              boolean onFloor, int spawnersCleared, int spawnersTotal, int spawnersNeeded, boolean finished,
              int haul, int banked, int compass, int progress, int levelCost) {
            this(dungeon, mine, floor, floorName, ominous, lives, party, onFloor, spawnersCleared, spawnersTotal,
                    spawnersNeeded, finished, haul, banked, compass, progress, levelCost, 0, 0);
        }
    }

    /** The title: the dungeon's name, or {@code Endless Mine} on a mine trip, or the mod's name outside a dungeon. */
    static String title(Facts f) {
        if (f.mine()) {
            return "Endless Mine";
        }
        return f.dungeon() == null || f.dungeon().isBlank() ? "Pocket Dungeons" : f.dungeon();
    }

    /** The colour of the lives line: green at four or five, yellow at two or three, red at one. */
    static String livesColour(int lives) {
        return lives >= 4 ? "green" : lives >= 2 ? "yellow" : "red";
    }

    /** {@code "Floor 3: Gear Loft"}, or {@code "Floor 3"} with no name, {@code " (ominous)"} on an ominous floor. */
    static String floorText(int floor, String floorName, boolean ominous) {
        String text = "Floor " + Math.max(1, floor)
                + (floorName == null || floorName.isBlank() ? "" : ": " + floorName);
        return ominous ? text + " (ominous)" : text;
    }

    /** The lines, top to bottom. */
    static List<Line> lines(Facts f) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(floorText(f.floor(), f.floorName(), f.ominous()), f.ominous() ? "dark_purple" : "white"));
        out.add(new Line("Lives " + f.lives(), livesColour(f.lives())));
        if (f.party() > 1) {
            out.add(new Line("Party " + f.party(), "gray"));
        }
        if (f.onFloor() && f.spawnersTotal() > 0 && !f.finished()) {
            boolean done = f.spawnersCleared() >= f.spawnersNeeded();
            out.add(new Line("Spawners " + f.spawnersCleared() + "/" + f.spawnersTotal(), done ? "green" : "white"));
        }
        if (f.heardMax() > 0 && f.onFloor() && !f.finished()) {
            out.add(new Line("Heard " + f.heard() + "/" + f.heardMax(), "dark_aqua"));
        }
        out.add(new Line(" ", "white"));
        // PD-179: once the dungeon is finished the haul is already paid, so the line says what it banked.
        out.add(f.finished()
                ? new Line(IntervalBanking.finishLine(f.banked()), "aqua")
                : new Line("Haul " + IntervalBanking.scrapText(f.haul()), "aqua"));
        out.add(new Line("Compass " + f.compass() + ": " + f.progress() + "/" + f.levelCost(), "yellow"));
        return out;
    }
}
