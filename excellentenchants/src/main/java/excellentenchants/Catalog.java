package excellentenchants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * What players read in {@code /enchants}: every enchantment, what it goes on, what it does and how to get one.
 * Pure text built from {@link Numbers}, so a change to a number shows up here too and the tests need no world.
 */
public final class Catalog {

    /** Enchantments shown on one page of {@code /enchants}. */
    public static final int PER_PAGE = 5;

    /**
     * One enchantment. {@code table} is true when the enchanting table can roll it (the data pack's non_treasure
     * tag); the rest come only from loot and librarian trades. {@code perLevel} describes one level, or is null
     * when the enchantment has a single level and {@code effect} says it all.
     */
    public record Entry(String id, String name, int maxLevel, String goesOn, String effect, boolean table,
                        IntFunction<String> perLevel, String note) {
    }

    private static final String SWORDS = "swords and axes, and the Oathblade, War Pick and Tide Trident";

    public static final List<Entry> ALL = List.of(
            new Entry("thunder", "Thunder", 5, SWORDS,
                    "a chance to call lightning on the target for bonus damage, under open sky only.", true,
                    l -> pct(Numbers.thunderChance(l)) + " chance, " + num(Numbers.thunderDamage(l)) + " bonus damage", null),
            new Entry("venom", "Venom", 2, SWORDS, "a chance to poison the target.", true,
                    l -> pct(Numbers.venomChance(l)) + " chance, Poison " + roman(Numbers.amplifier(l) + 1)
                            + " for " + Numbers.venomSeconds(l) + "s", null),
            new Entry("wither", "Wither", 2, SWORDS, "a chance to wither the target.", true,
                    l -> pct(Numbers.witherChance(l)) + " chance, Wither " + roman(Numbers.amplifier(l) + 1)
                            + " for " + Numbers.witherSeconds(l) + "s", null),
            new Entry("ice_aspect", "Ice Aspect", 3, SWORDS, "every hit slows and freezes the target.", true,
                    l -> "Slowness " + roman(Numbers.amplifier(l) + 1) + " for " + Numbers.iceAspectSeconds(l) + "s", null),
            new Entry("wisdom", "Wisdom", 3, SWORDS, "kills drop more experience.", true,
                    l -> "x" + num(Numbers.wisdomMultiplier(l)) + " experience", null),
            new Entry("decapitator", "Decapitator", 2, SWORDS,
                    "a chance that a kill drops the victim's head, players included.", false,
                    l -> pct(Numbers.decapitatorChance(l)) + " chance", null),
            new Entry("thrifty", "Thrifty", 3, SWORDS, "a small chance that a kill drops the mob's spawn egg.", false,
                    l -> pct(Numbers.thriftyChance(l)) + " chance", "Never the Wither or the Ender Dragon."),
            new Entry("dragon_heart", "Dragon Heart", 5, "chestplates and elytra", "extra hearts while you wear it.", true,
                    l -> "+" + 2 * (Numbers.amplifier(l) + 1) + " hearts (Health Boost " + roman(Numbers.amplifier(l) + 1) + ")", null),
            new Entry("flame_walker", "Flame Walker", 2, "boots",
                    "walk on lava: it turns to magma under you for a while, and magma does not burn you.", false,
                    l -> "radius " + Numbers.flameWalkerRadius(l) + ", magma lasts up to " + Numbers.flameWalkerDecaySeconds(l) + "s",
                    "Cannot go with Frost Walker."),
            new Entry("tunnel", "Tunnel", 3, "pickaxes, axes, shovels and hoes",
                    "each swing breaks an area of blocks. Sneak to mine one block.", true,
                    l -> Numbers.tunnelBlocks(l) + " blocks a swing", null),
            new Entry("telekinesis", "Telekinesis", 1, "pickaxes, axes, shovels, hoes and shears",
                    "what you mine goes straight into your inventory.", false, null, null),
            new Entry("silk_spawner", "Silk Spawner", 1, "pickaxes, axes, shovels and hoes",
                    "a " + pct(Numbers.silkSpawnerChance(1)) + " chance that a spawner you break drops as an item that "
                            + "keeps its mob. Otherwise it breaks as normal.", false, null, null),
            new Entry("ghast", "Ghast", 1, "bows and crossbows",
                    "shoots fireballs instead of arrows (small fireballs with Multishot).", false, null,
                    "Cannot go with Power, Punch, Flame or Ender Bow."),
            new Entry("ender_bow", "Ender Bow", 1, "bows", "shoots ender pearls instead of arrows.", false, null,
                    "Cannot go with Power, Punch, Flame or Ghast."));

    private Catalog() {
    }

    public static int pages() {
        return (ALL.size() + PER_PAGE - 1) / PER_PAGE;
    }

    /** The entries on a page, counting from 1; a page out of range is moved to the nearest real one. */
    public static List<Entry> page(int page) {
        int p = clampPage(page);
        int from = (p - 1) * PER_PAGE;
        return ALL.subList(from, Math.min(ALL.size(), from + PER_PAGE));
    }

    public static int clampPage(int page) {
        return Math.max(1, Math.min(pages(), page));
    }

    /** Looks an enchantment up by id ("ice_aspect") or by name ("Ice Aspect", "ice aspect"). */
    public static Optional<Entry> find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (q.startsWith("excellentenchants:")) q = q.substring("excellentenchants:".length());
        for (Entry e : ALL) if (e.id().equals(q)) return Optional.of(e);
        return Optional.empty();
    }

    /** The detail lines for one enchantment, in reading order. */
    public static List<String> details(Entry e) {
        List<String> lines = new ArrayList<>();
        lines.add(e.name() + " (max " + roman(e.maxLevel()) + "): " + e.effect());
        lines.add("Goes on: " + e.goesOn() + ".");
        if (e.perLevel() != null) {
            for (int l = 1; l <= e.maxLevel(); l++) lines.add("  " + roman(l) + ": " + e.perLevel().apply(l));
        }
        if (e.note() != null) lines.add(e.note());
        lines.add(e.table()
                ? "How to get it: the enchanting table, loot chests or librarian trades."
                : "How to get it: loot chests or librarian trades only (librarians charge double).");
        return lines;
    }

    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(n);
        };
    }

    private static String pct(double v) {
        return num(v) + "%";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }
}
