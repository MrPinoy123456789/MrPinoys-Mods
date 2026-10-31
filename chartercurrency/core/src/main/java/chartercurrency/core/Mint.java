package chartercurrency.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Mint's arithmetic: daily allowance, bands, carry-over and the kingdom tax, with
 * no inventory and no clock. The fabric side removes the ingots, gives the coins and
 * saves the state; it asks this class what should happen.
 *
 * <p>Allowance is counted in Marks of face value. The full band yields a coin for one
 * ingot, the half band for two. The tax is the owner's fixed rule (SPEC 8.3): every
 * fifth coin struck goes to the kingdom, and it is not configurable.
 */
public final class Mint {

    /** Every fifth coin is taxed: 2 in 10. Fixed in code on purpose. */
    public static final int TAX_EVERY = 5;

    /** Ingots a coin costs in the half band. */
    public static final int HALF_BAND_INGOTS = 2;

    private Mint() {}

    /** The numbers that live in settings.json under {@code mint}. */
    public record Rules(int fullBandPerDay, int halfBandPerDay, int carryOverDays) {

        public static Rules defaults() {
            return new Rules(192, 192, 2);
        }

        /** The most full band a player can bank: a day's worth plus the carry-over. */
        public long fullCap() {
            return (long) fullBandPerDay * (1 + Math.max(0, carryOverDays));
        }
    }

    /** Why a strike produced no coin, when it did not. */
    public enum Shortfall {
        /** Coins were struck. */
        NONE,
        /** No band has room left for even the smallest coin of this kind today. */
        EXHAUSTED,
        /** There is room, but the ingots offered do not cover one coin at the current rate. */
        NEED_MORE_INGOTS,
        /** Nothing was offered. */
        NOTHING_OFFERED
    }

    /**
     * What a strike does.
     *
     * @param ingotsUsed      ingots to remove; an odd ingot left in the half band stays
     * @param coinsStruck     every coin struck, taxed or not (all count against the allowance)
     * @param coinsFull       of those, struck at one ingot each
     * @param coinsHalf       of those, struck at two ingots each
     * @param coinsToPlayer   coins handed to the striker
     * @param coinsToKingdom  coins whose face value goes to the kingdom (or is destroyed)
     * @param marksStruck     face value of every coin struck
     * @param marksToKingdom  face value of the taxed coins
     * @param shortfall       why nothing was struck, if nothing was
     * @param after           the state once the strike is applied
     */
    public record Plan(int ingotsUsed, int coinsStruck, int coinsFull, int coinsHalf,
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
        /** The day number {@code fullRemaining} and {@code halfRemaining} were last rolled to. */
        public long day;
        public long fullRemaining;
        public long halfRemaining;
        /** Coins struck since the last taxed coin, per denomination id. 0 to 4. */
        public Map<String, Integer> taxCounters = new LinkedHashMap<>();

        public State() {}

        public State copy() {
            State s = new State();
            s.day = day;
            s.fullRemaining = fullRemaining;
            s.halfRemaining = halfRemaining;
            s.taxCounters = new LinkedHashMap<>(taxCounters);
            return s;
        }

        /** A new player's state: a full day's allowance, no carry-over yet. */
        public static State fresh(long today, Rules rules) {
            State s = new State();
            s.day = today;
            s.fullRemaining = rules.fullBandPerDay();
            s.halfRemaining = rules.halfBandPerDay();
            return s;
        }
    }

    /**
     * The state as it stands on {@code today}: the full band is refilled by a day's
     * worth for every day that has passed (capped at the carry-over limit), and the
     * half band is reset. Tax counters are kept. A day in the past changes nothing.
     */
    public static State rollTo(State state, long today, Rules rules) {
        State out = state.copy();
        long days = today - state.day;
        if (days <= 0) return out;
        long refill = Math.min(days, Math.max(1, rules.carryOverDays() + 1L)) * rules.fullBandPerDay();
        out.fullRemaining = Math.min(rules.fullCap(), state.fullRemaining + refill);
        out.halfRemaining = rules.halfBandPerDay();
        out.day = today;
        return out;
    }

    /** {@link #plan(State, String, int, int)} keying the tax counter by face value. */
    public static Plan plan(State state, int faceValue, int ingotsOffered) {
        return plan(state, "v" + faceValue, faceValue, ingotsOffered);
    }

    /**
     * Plan a strike of one denomination.
     *
     * <p>The full band is used first. A coin is never split across bands: when the
     * full band cannot cover another whole coin, the rest come from the half band at
     * two ingots each, or not at all. The tax counter for the denomination advances by
     * every coin struck, and every fifth coin is the kingdom's.
     *
     * @param state          the state already rolled to today
     * @param denominationId keys the tax counter
     * @param faceValue      Marks per coin; positive
     * @param ingotsOffered  ingots the player is willing to spend; never exceeded
     */
    public static Plan plan(State state, String denominationId, int faceValue, int ingotsOffered) {
        State after = state.copy();
        if (faceValue <= 0 || ingotsOffered <= 0) {
            return new Plan(0, 0, 0, 0, 0, 0, 0, 0,
                    ingotsOffered <= 0 ? Shortfall.NOTHING_OFFERED : Shortfall.EXHAUSTED, after);
        }

        long fullRoom = state.fullRemaining / faceValue;
        long halfRoom = state.halfRemaining / faceValue;

        int coinsFull = (int) Math.min(ingotsOffered, fullRoom);
        int ingotsLeft = ingotsOffered - coinsFull;
        int coinsHalf = (int) Math.min(ingotsLeft / HALF_BAND_INGOTS, halfRoom);

        int coins = coinsFull + coinsHalf;
        if (coins == 0) {
            Shortfall why = fullRoom + halfRoom == 0 ? Shortfall.EXHAUSTED : Shortfall.NEED_MORE_INGOTS;
            return new Plan(0, 0, 0, 0, 0, 0, 0, 0, why, after);
        }

        after.fullRemaining -= (long) coinsFull * faceValue;
        after.halfRemaining -= (long) coinsHalf * faceValue;

        int counter = state.taxCounters.getOrDefault(denominationId, 0);
        long running = (long) counter + coins;
        int taxed = (int) (running / TAX_EVERY);
        after.taxCounters.put(denominationId, (int) (running % TAX_EVERY));

        return new Plan(coinsFull + HALF_BAND_INGOTS * coinsHalf, coins, coinsFull, coinsHalf,
                coins - taxed, taxed, (long) coins * faceValue, (long) taxed * faceValue,
                Shortfall.NONE, after);
    }
}
