package charter.mc.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One operator scenario (PLAN.md §12.3), read from {@code config/charter/scenarios/<name>.json}.
 * Every place is a chunk offset {@code [dx, dz]} from the chunk the scenario runs at.
 * Roles are the people in it: the operator names real players for them in order, and
 * a role nobody was named for is played by an offline stand-in.
 *
 * <p>Plain fields for Gson; anything missing from the file reads as empty.
 */
public final class Scenario {

    public String name = "";
    /** What it sets up, for {@code scenario list}. */
    public String setup = "";
    /** What the session tries with it, told to the operator after it runs. */
    public String tries = "";
    /** Knobs set before anything is placed, by their field name in {@code charter.json}. */
    public Map<String, Double> knobs = new LinkedHashMap<>();
    public List<Faction> factions = new ArrayList<>();
    /** Roles that belong to no faction: trespassers, sellswords. */
    public List<String> outsiders = new ArrayList<>();
    public List<Keep> keeps = new ArrayList<>();
    public List<Farm> farms = new ArrayList<>();
    public List<Camp> camps = new ArrayList<>();
    /** Finishes raising every camp at once, which moves the siege clock on by the raising time. */
    public boolean raiseCamps;
    public List<Chest> chests = new ArrayList<>();
    public List<Rails> rails = new ArrayList<>();
    public List<Give> give = new ArrayList<>();
    public List<Stand> stand = new ArrayList<>();

    public static final class Faction {
        /** The short id other steps use, such as "A". */
        public String id = "";
        public String name = "";
        /** Its people; the first is the Lord. */
        public List<String> roles = new ArrayList<>();
        public List<Office> offices = new ArrayList<>();
        public long diamonds = 400;
        public long cobblestone = 5000;
    }

    public static final class Office {
        public String name = "";
        public String holder = "";
        /** Power names, as in {@code charter.grant.Power}. */
        public List<String> powers = new ArrayList<>();
    }

    public static final class Keep {
        public String faction = "";
        public int[] at = {0, 0};
        /** How full the granary starts, 0 to 1 of its storage limit. */
        public double granary = 1;
        /** A cell to build and mark in the keep, by name; empty for none. */
        public String cell = "";
        /** The ring chunk the cell is built in, as an offset from the keep. */
        public int[] cellAt = {1, 0};
    }

    public static final class Farm {
        public String faction = "";
        public List<int[]> chunks = new ArrayList<>();
        public boolean crops;
        public boolean animals;
    }

    public static final class Camp {
        public String faction = "";
        public int[] at = {0, 0};
        /** Chunks the camp's faction claims beside it, so it holds more sides. */
        public List<int[]> claims = new ArrayList<>();
        /** Food put in the camp's store, item id to count. */
        public Map<String, Integer> store = new LinkedHashMap<>();
    }

    public static final class Chest {
        public int[] at = {0, 0};
        public Map<String, Integer> items = new LinkedHashMap<>();
    }

    public static final class Rails {
        public int[] from = {0, 0};
        public int[] to = {0, 0};
        /** A chest minecart at the start of the line. */
        public boolean cart;
    }

    public static final class Give {
        public String role = "";
        public Map<String, Integer> items = new LinkedHashMap<>();
    }

    public static final class Stand {
        public String role = "";
        public int[] at = {0, 0};
    }

    /** Every role in the order players are named for them: faction members first, then outsiders. */
    public List<String> roles() {
        List<String> out = new ArrayList<>();
        for (Faction f : factions) out.addAll(f.roles);
        out.addAll(outsiders);
        return out;
    }
}
