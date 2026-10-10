package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The home vendor's offer plan (J5a), pure data so a headless test can drive
 * it: which gear table each offer rolls and what it costs. The Minecraft
 * half (rolling the tables into real items, the Mending book, the half-rate
 * buy offers) is {@link LibrarianNPC#buildOffers}.
 */
final class VendorStock {

    /** The three gear categories the vendor sells, in screen order. */
    static final List<String> CATEGORIES = List.of("armour", "weapon", "tool");

    /** One sell line: the gear table to roll, its tier, and the emerald price. */
    record GearLine(String table, int tier, int emeralds) {}

    private VendorStock() {}

    /**
     * One offer per category and tier up to {@code tierCap} (from
     * {@link VendorMath#tierCap}), category-major so the screen groups what
     * it sells: all armour, then all weapons, then all tools. Nine lines at
     * cap 3, six at cap 2.
     */
    static List<GearLine> gearLines(int tierCap) {
        List<GearLine> lines = new ArrayList<>();
        for (String category : CATEGORIES) {
            for (int tier = 1; tier <= tierCap; tier++) {
                lines.add(new GearLine(LootTables.gearTable(category, tier), tier,
                        VendorMath.price(category, tier)));
            }
        }
        return List.copyOf(lines);
    }
}
