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
                 int heard, int heardMax, boolean campaign, int maxLives) {

        /** An endgame trip (the shape before the campaign split, 2026-10-18): the haul and compass lines show. */
        Facts(String dungeon, boolean mine, int floor, String floorName, boolean ominous, int lives, int party,
              boolean onFloor, int spawnersCleared, int spawnersTotal, int spawnersNeeded, boolean finished,
              int haul, int banked, int compass, int progress, int levelCost, int heard, int heardMax) {
            this(dungeon, mine, floor, floorName, ominous, lives, party, onFloor, spawnersCleared, spawnersTotal,
                    spawnersNeeded, finished, haul, banked, compass, progress, levelCost, heard, heardMax, false,
                    Omen.LIVES);
        }

        /** A floor with no sculk room under the player: the shape before the Heard meter. */
        Facts(String dungeon, boolean mine, int floor, String floorName, boolean ominous, int lives, int party,
              boolean onFloor, int spawnersCleared, int spawnersTotal, int spawnersNeeded, boolean finished,
              int haul, int banked, int compass, int progress, int levelCost) {
            this(dungeon, mine, floor, floorName, ominous, lives, party, onFloor, spawnersCleared, spawnersTotal,
                    spawnersNeeded, finished, haul, banked, compass, progress, levelCost, 0, 0, false, Omen.LIVES);
        }

        /** The same facts for a campaign trip: no haul line and no compass line (the campaign pays no scrap). */
        Facts asCampaign() {
            return new Facts(dungeon, mine, floor, floorName, ominous, lives, party, onFloor, spawnersCleared,
                    spawnersTotal, spawnersNeeded, finished, haul, banked, compass, progress, levelCost, heard,
                    heardMax, true, maxLives);
        }
    }

    /** The title: the dungeon's name, or {@code Endless Mine} on a mine trip, or the mod's name outside a dungeon. */
    static String title(Facts f) {
        if (f.mine()) {
            return "Endless Mine";
        }
        return f.dungeon() == null || f.dungeon().isBlank() ? "Pocket Dungeons" : f.dungeon();
    }

    /** The colour of the lives line by the share of the trip's {@code max} lives left: green above 3/5, yellow above 1/5, red below. */
    static String livesColour(int lives, int max) {
        return switch (Omen.livesColourIndex(lives, max)) {
            case 0 -> "green";
            case 1 -> "yellow";
            default -> "red";
        };
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
        out.add(new Line("Lives " + f.lives(), livesColour(f.lives(), f.maxLives())));
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
        if (f.campaign()) {
            // Campaign and endgame (2026-10-18): the campaign pays items, not scrap, and the compass is a quest
            // line on the item, so the sidebar keeps the floor, lives and spawners only.
            return out;
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
