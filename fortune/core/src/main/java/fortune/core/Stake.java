package fortune.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The coin stake. A player may spend Marks before a pull to raise the spin's rewards. The bonus multiplies the whole
 * payout: the Valor (paid as extra levels, rounded to the nearest level so it still goes through {@code earn} and its
 * soft cap) and the counts of the item extras. It never changes the odds and it never pays Coin back: a
 * {@link Reward} has no Coin in it, and {@link Paytable} refuses Coin items as extras.
 */
public final class Stake {

    /** What a spin hands over. Valor is a level for the payout command; items are vanilla item ids. No Coin. */
    public record Reward(int level, List<Settings.Extra> extras) {
        public Reward {
            extras = List.copyOf(extras);
        }
    }

    private Stake() {
    }

    /** Valor for a level at the mirrored earn.json numbers (base + perLevel times level). */
    public static long valor(int level, Settings settings) {
        return settings.valorBase + (long) settings.valorPerLevel * level;
    }

    /**
     * The level that pays closest to {@code valor(level) * (100 + bonus) / 100}, rounding halves up, never below the
     * unstaked level. With the defaults (4 + 2 per level) a level 1 spin (6 Valor) staked +25% targets 7.5 Valor and
     * pays level 2 (8); +100% targets 12 and pays level 4.
     */
    public static int scaledLevel(int level, int bonusPercent, Settings settings) {
        if (bonusPercent <= 0) return level;
        if (settings.valorPerLevel <= 0) {
            return (int) Math.max(level, roundHalfUp((long) level * (100 + bonusPercent), 100));
        }
        long target100 = valor(level, settings) * (100 + bonusPercent);
        long over = target100 - settings.valorBase * 100L;
        long scaled = roundHalfUp(over, settings.valorPerLevel * 100L);
        return (int) Math.max(level, scaled);
    }

    /** An item count with the bonus applied, rounding halves up, never below the unstaked count. */
    public static int scaledCount(int count, int bonusPercent) {
        if (bonusPercent <= 0) return count;
        return (int) Math.max(count, roundHalfUp((long) count * (100 + bonusPercent), 100));
    }

    /** The reward for a paytable row at a stake bonus (0 for no stake). */
    public static Reward reward(Paytable.Row row, int bonusPercent, Settings settings) {
        List<Settings.Extra> extras = new ArrayList<>();
        for (Settings.Extra e : row.extras()) extras.add(new Settings.Extra(e.item, scaledCount(e.count, bonusPercent)));
        return new Reward(scaledLevel(row.level(), bonusPercent, settings), extras);
    }

    /**
     * Which stake actually applies, given the chosen tier and whether the charge went through. A failed charge means
     * no stake: the spin goes ahead unstaked. Returns null for no stake.
     */
    public static Settings.StakeTier applied(Settings.StakeTier chosen, boolean charged) {
        return chosen != null && charged ? chosen : null;
    }

    /** True when a tier can be offered to a player with this many Marks banked. */
    public static boolean affordable(Settings.StakeTier tier, long balance) {
        return tier.cost > 0 && balance >= tier.cost;
    }

    /** A tier by id from the settings, or null for "none" or an unknown id. */
    public static Settings.StakeTier tier(Settings settings, String id) {
        if (id == null) return null;
        for (Settings.StakeTier t : settings.stakeTiers) if (t.id.equals(id)) return t;
        return null;
    }

    private static long roundHalfUp(long numerator, long denominator) {
        return Math.floorDiv(numerator * 2 + denominator, denominator * 2);
    }
}
