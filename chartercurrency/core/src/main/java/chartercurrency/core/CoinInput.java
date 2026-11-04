package chartercurrency.core;

/**
 * What a typed coin amount means (owner, 2026-11-04). A bare number is whole Gold
 * Sovereigns, the coin's largest denomination, since that is the only unit a player ever
 * reads: {@code /pay Bob 5} pays 5 Sovereigns (320 Marks). A word names its own coin and
 * still works: {@code /pay Bob 3 marks} pays 3 Marks, {@code 3 crowns} 24 Marks. The
 * word {@code coin} names the currency, not a coin, so it reads as a bare number.
 *
 * <p>Player commands use this: {@code /bank}, {@code /withdraw}, {@code /pay} and
 * {@code /treasury deposit|withdraw}. The operator commands other mods script
 * ({@code /chartercurrency set|add|remove}, {@code /chartercurrency treasury
 * add|remove|pay|give}) stay in Marks, so nothing that calls them changes.
 */
public final class CoinInput {

    private CoinInput() {}

    /** Marks per typed unit: the named coin's face value, else the largest coin's. */
    public static long unit(Currency coin, Denomination named) {
        if (named != null) return named.value();
        return coin.hasDenominations() ? coin.largestFirst().get(0).value() : 1;
    }

    /** The typed amount in Marks, or -1 when it would overflow. */
    public static long marks(long typed, Currency coin, Denomination named) {
        try {
            return Math.multiplyExact(typed, unit(coin, named));
        } catch (ArithmeticException e) {
            return -1;
        }
    }
}
