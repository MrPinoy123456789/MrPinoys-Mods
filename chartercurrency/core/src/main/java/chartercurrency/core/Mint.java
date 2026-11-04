package chartercurrency.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Mint's arithmetic: the daily soft cap, its falloff and the kingdom tax, with no
 * inventory and no clock. The fabric side removes the ingots, gives the coins and saves
 * the state; it asks this class what should happen.
 *
 * <p>A player's day is counted in ingots spent (owner, 2026-11-03). Coins are struck
 * {@link #COINS_PER_STRIKE} at a time. Up to the soft cap a strike costs one ingot; past
 * it the cost doubles every {@link Rules#falloffEvery} ingots, up to
 * {@link #MAX_INGOTS_PER_STRIKE}. There is no hard stop and no carry-over. The tax is
 * the owner's fixed rule (SPEC 8.3): every fifth coin struck goes to the kingdom, and it
 * is not configurable.
 */
public final class Mint {

    /** Every fifth coin is taxed: 2 in 10. Fixed in code on purpose. */
    public static final int TAX_EVERY = 5;

    /** Coins one strike makes: one gold ingot is 10 Sovereigns at full rate (owner, 2026-11-03). */
    public static final int COINS_PER_STRIKE = 10;

    /** The most a strike ever costs, however far past the soft cap. */
    public static final int MAX_INGOTS_PER_STRIKE = 64;

    /**
     * The one denomination the Mint strikes (owner, 2026-11-03). Copper and iron come out of mob farms without limit
     * (drowned, iron golems), so they are not money at the Mint; Copper Marks and Iron Crowns still circulate as
     * change, from withdrawals and payouts.
     */
    public static final String STRUCK = "gold";

    private Mint() {}

    /** Whether the Mint strikes coin of this denomination from its metal. */
    public static boolean strikes(String denominationId) {
        return STRUCK.equals(denominationId);
    }

    /**
     * The numbers that live in settings.json under {@code mint}.
     *
     * @param softCapIngots ingots a player can strike at one ingot per strike each day
     * @param falloffEvery  past the soft cap, the cost per strike doubles after every this many ingots
     */
    public record Rules(int softCapIngots, int falloffEvery) {

        public static Rules defaults() {
            return new Rules(320, 320);
        }
    }

    /**
     * Ingots the next strike of {@link #COINS_PER_STRIKE} coins costs after {@code spentToday}
     * ingots have been spent today.
     */
    public static int ingotsPerStrike(long spentToday, Rules rules) {
        if (spentToday < rules.softCapIngots()) return 1;
        long steps = 1 + (spentToday - rules.softCapIngots()) / Math.max(1, rules.falloffEvery());
        if (steps >= 6) return MAX_INGOTS_PER_STRIKE;
        return (int) Math.min(MAX_INGOTS_PER_STRIKE, 1L << steps);
    }

    /**
     * Ingots still to spend today before the cost per strike next rises, or -1 once it is at
     * {@link #MAX_INGOTS_PER_STRIKE} and will not rise again.
     */
    public static long ingotsUntilFalloff(long spentToday, Rules rules) {
        if (ingotsPerStrike(spentToday, rules) >= MAX_INGOTS_PER_STRIKE) return -1;
        if (spentToday < rules.softCapIngots()) return rules.softCapIngots() - spentToday;
        long every = Math.max(1, rules.falloffEvery());
        long past = spentToday - rules.softCapIngots();
        return every - past % every;
    }

    /** Why a strike produced no coin, when it did not. */
    public enum Shortfall {
        /** Coins were struck. */
        NONE,
        /** The ingots offered do not cover one strike at the current rate. */
        NEED_MORE_INGOTS,
        /** Nothing was offered. */
        NOTHING_OFFERED
    }

    /**
     * What a strike does.
     *
     * @param ingotsUsed      ingots to remove; ingots short of the next strike's cost stay
     * @param coinsStruck     every coin struck, taxed or not (their ingots count against the day)
     * @param coinsFull       of those, struck at one ingot per strike
     * @param coinsReduced    of those, struck past the soft cap at more than one ingot per strike
     * @param coinsToPlayer   coins handed to the striker
     * @param coinsToKingdom  coins whose face value goes to the kingdom (or is destroyed)
     * @param marksStruck     face value of every coin struck
     * @param marksToKingdom  face value of the taxed coins
     * @param shortfall       why nothing was struck, if nothing was
     * @param after           the state once the strike is applied
     */
    public record Plan(int ingotsUsed, int coinsStruck, int coinsFull, int coinsReduced,
                       int coinsToPlayer, int coinsToKingdom, long marksStruck,
                       long marksToKingdom, Shortfall shortfall, State after) {
        public boolean struck() {
            return coinsStruck > 0;
        }
    }

    /**
     * One player's mint state. A plain mutable class with public fields so the fabric
     * side can store it as JSON without this module knowing about Gson.
     */
    public static final class State {
        /** The day {@code ingotsToday} was counted on. */
        public long day;
        /** Ingots spent at the Mint on {@code day}, taxed coins included. */
        public long ingotsToday;
        /** Coins struck since the last taxed coin, per denomination id. 0 to 4. */
        public Map<String, Integer> taxCounters = new LinkedHashMap<>();

        public State() {}

        public State copy() {
            State s = new State();
            s.day = day;
            s.ingotsToday = ingotsToday;
            s.taxCounters = new LinkedHashMap<>(taxCounters);
            return s;
        }

        /** A new player's state: nothing spent today. */
        public static State fresh(long today) {
            State s = new State();
            s.day = today;
            return s;
        }
    }

    /**
     * The state as it stands on {@code today}: a new day starts from nothing spent. Tax
     * counters are kept. A day in the past changes nothing.
     */
    public static State rollTo(State state, long today) {
        State out = state.copy();
        if (today <= state.day) return out;
        out.ingotsToday = 0;
        out.day = today;
        return out;
    }

    /** {@link #plan(State, Rules, String, int, int)} keying the tax counter by face value. */
    public static Plan plan(State state, Rules rules, int faceValue, int ingotsOffered) {
        return plan(state, rules, "v" + faceValue, faceValue, ingotsOffered);
    }

    /**
     * Plan a strike of one denomination.
     *
     * <p>Strikes of {@link #COINS_PER_STRIKE} coins are made one at a time at the cost the
     * day has reached: one ingot each up to the soft cap, then the falloff. A strike that
     * starts below a step is made at the lower cost. Ingots short of the next strike's cost
     * are left with the player. The tax counter for the denomination advances by every coin
     * struck, and every fifth coin is the kingdom's.
     *
     * @param state          the state already rolled to today
     * @param denominationId keys the tax counter
     * @param faceValue      Marks per coin; positive
     * @param ingotsOffered  ingots the player is willing to spend; never exceeded
     */
    public static Plan plan(State state, Rules rules, String denominationId, int faceValue, int ingotsOffered) {
        State after = state.copy();
        if (faceValue <= 0 || ingotsOffered <= 0) {
            return new Plan(0, 0, 0, 0, 0, 0, 0, 0, Shortfall.NOTHING_OFFERED, after);
        }

        long spent = state.ingotsToday;
        int left = ingotsOffered;
        int coinsFull = 0;
        int coinsReduced = 0;
        while (true) {
            int cost = ingotsPerStrike(spent, rules);
            if (cost > left) break;
            left -= cost;
            spent += cost;
            if (cost == 1) coinsFull += COINS_PER_STRIKE;
            else coinsReduced += COINS_PER_STRIKE;
        }

        int coins = coinsFull + coinsReduced;
        if (coins == 0) {
            return new Plan(0, 0, 0, 0, 0, 0, 0, 0, Shortfall.NEED_MORE_INGOTS, after);
        }
        after.ingotsToday = spent;

        int counter = state.taxCounters.getOrDefault(denominationId, 0);
        long running = (long) counter + coins;
        int taxed = (int) (running / TAX_EVERY);
        after.taxCounters.put(denominationId, (int) (running % TAX_EVERY));

        return new Plan(ingotsOffered - left, coins, coinsFull, coinsReduced,
                coins - taxed, taxed, (long) coins * faceValue, (long) taxed * faceValue,
                Shortfall.NONE, after);
    }
}
