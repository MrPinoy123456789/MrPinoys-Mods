package cobbleeconomy.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rankings by banked balance. One implementation, used by {@code /baltop}, by
 * {@code /balance}, and by the login snapshot -- so those three can never disagree.
 *
 * <p><b>Banked balances only.</b> This never looks at an inventory, a chest, or a
 * shulker box, and it could not if it wanted to: it is in a module that cannot see
 * Minecraft. That is not an accident of layering, it is the rule from the spec made
 * structural. Wealth becomes visible by being banked.
 *
 * <p><b>Sorting is computed on demand.</b> Every call sorts the account map. For the
 * hundreds-to-thousands of accounts a Minecraft server actually has this is
 * microseconds, and it is always correct with no cache to invalidate on every
 * deposit. If a server ever grows past that, the fix is a cache behind this same
 * interface, and nothing that calls it has to change.
 */
public final class LeaderboardService {

    private final EconomyService economy;
    private final Function<UUID, String> nameLookup;

    /**
     * @param nameLookup last-seen username for a UUID, used only for the tie-break
     *                   and never for identity
     */
    public LeaderboardService(EconomyService economy, Function<UUID, String> nameLookup) {
        this.economy = economy;
        this.nameLookup = nameLookup;
    }

    /** One ranked row. {@code rank} is 1-based. */
    public record Rank(int rank, UUID player, long balance) {}

    /** The top {@code limit} players in a currency, highest first. */
    public List<Rank> top(Currency currency, int limit) {
        return top(currency, limit, 0);
    }

    /**
     * A page of the ranking. Players with a zero balance never appear -- the economy
     * does not store them, so there is nothing to filter.
     */
    public List<Rank> top(Currency currency, int limit, int offset) {
        if (limit <= 0 || offset < 0) return List.of();
        List<Rank> ranked = ranked(currency);
        if (offset >= ranked.size()) return List.of();
        return List.copyOf(ranked.subList(offset, Math.min(offset + limit, ranked.size())));
    }

    /**
     * A player's 1-based position in a currency, or 0 if they hold none of it.
     *
     * <p>Answers the question {@code /baltop} cannot: whether 50,000 cobblestone makes
     * you fourth or four hundredth.
     */
    public int rankOf(UUID player, Currency currency) {
        if (player == null) return 0;
        List<Rank> ranked = ranked(currency);
        for (Rank row : ranked) {
            if (row.player().equals(player)) return row.rank();
        }
        return 0;
    }

    /** How many players hold any of this currency. */
    public int participants(Currency currency) {
        return economy.balances(currency).size();
    }

    /**
     * The full ordering. Highest balance first; ties broken alphabetically by current
     * username, then by UUID.
     *
     * <p>The tie-break matters more than it looks. Without a total order, two players
     * on the same balance can swap places between two calls, which shows up as a name
     * appearing on two pages of {@code /baltop} while another vanishes. The UUID
     * fallback is there because usernames are not unique in the cache's view of a
     * server that has seen a rename.
     */
    private List<Rank> ranked(Currency currency) {
        Map<UUID, Long> balances = economy.balances(currency);
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(balances.entrySet());
        entries.sort(Comparator
                .comparingLong((Map.Entry<UUID, Long> e) -> e.getValue()).reversed()
                .thenComparing(e -> displayName(e.getKey()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.getKey().toString()));

        List<Rank> out = new ArrayList<>(entries.size());
        int position = 1;
        for (Map.Entry<UUID, Long> e : entries) {
            out.add(new Rank(position++, e.getKey(), e.getValue()));
        }
        return out;
    }

    private String displayName(UUID player) {
        String name = nameLookup.apply(player);
        return name == null ? "" : name;
    }
}
