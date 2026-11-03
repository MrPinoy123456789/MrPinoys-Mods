package fortune.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * One paytable: result lines with weights in basis points (a full table sums to 10000). The roll picks a line from
 * here first, then symbols that make that line ({@link Roller}). A floored table ({@link #atLeast}) drops the lines
 * below the floor and renormalises the rest, so the Odds screen and the roll read the same numbers.
 */
public final class Paytable {

    /** A full table's weight: 100 percent in basis points. */
    public static final int FULL = 10_000;

    /**
     * Items that are, or look like, Charter Currency's Coin. The reels never pay them: Coin enters the world only at
     * a Mint, and the stake must never pay Coin back.
     */
    public static final Set<String> COIN_ITEMS = Set.of(
            "minecraft:copper_nugget", "minecraft:iron_nugget", "minecraft:gold_nugget", "kingdoms:gold_coin");

    public record Row(Line line, int weight, int level, List<Settings.Extra> extras, boolean broadcast, boolean jackpot) {
        public Row {
            extras = List.copyOf(extras);
        }
    }

    private final List<Row> rows;
    private final int total;

    private Paytable(List<Row> rows) {
        this.rows = List.copyOf(rows);
        int sum = 0;
        for (Row row : rows) sum += row.weight();
        this.total = sum;
    }

    /** Validates a config table. Throws with a plain reason if it does not sum to 100 percent or pays Coin. */
    public static Paytable of(String name, List<Settings.PayRow> config) {
        if (config == null || config.isEmpty()) throw new IllegalArgumentException(name + " has no lines");
        List<Row> rows = new ArrayList<>();
        Set<Line> seen = EnumSet.noneOf(Line.class);
        for (Settings.PayRow row : config) {
            Line line;
            try {
                line = Line.parse(row.line);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(name + " has an unknown line: " + row.line);
            }
            if (!seen.add(line)) throw new IllegalArgumentException(name + " lists " + line + " twice");
            if (row.weight <= 0) throw new IllegalArgumentException(name + ": " + line + " needs a weight above 0");
            if (row.level < 1) throw new IllegalArgumentException(name + ": " + line + " needs a level of at least 1");
            List<Settings.Extra> extras = row.extras == null ? List.of() : row.extras;
            for (Settings.Extra extra : extras) {
                if (extra.item == null || extra.count < 1) {
                    throw new IllegalArgumentException(name + ": " + line + " has an empty extra");
                }
                if (COIN_ITEMS.contains(extra.item)) {
                    throw new IllegalArgumentException(name + ": " + line + " pays " + extra.item + ", and the reels never pay Coin");
                }
            }
            rows.add(new Row(line, row.weight, row.level, extras, row.broadcast, row.jackpot));
        }
        rows.sort((a, b) -> a.line().compareTo(b.line()));
        Paytable table = new Paytable(rows);
        if (table.total != FULL) {
            throw new IllegalArgumentException(name + " sums to " + table.total + " basis points, not " + FULL + " (100%)");
        }
        return table;
    }

    /** This table with every line below {@code floor} removed and the rest renormalised. */
    public Paytable atLeast(Line floor) {
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) if (row.line().atLeast(floor)) kept.add(row);
        if (kept.isEmpty()) throw new IllegalArgumentException("No line at or above " + floor);
        return kept.size() == rows.size() ? this : new Paytable(kept);
    }

    public Row pick(RandomGenerator random) {
        int roll = random.nextInt(total);
        for (Row row : rows) {
            roll -= row.weight();
            if (roll < 0) return row;
        }
        return rows.get(rows.size() - 1);
    }

    public List<Row> rows() {
        return rows;
    }

    public int total() {
        return total;
    }

    /** The chance of a line in percent, as shown on the Odds screen. */
    public double percent(Row row) {
        return row.weight() * 100.0 / total;
    }

    public Row row(Line line) {
        for (Row row : rows) if (row.line() == line) return row;
        return null;
    }
}
