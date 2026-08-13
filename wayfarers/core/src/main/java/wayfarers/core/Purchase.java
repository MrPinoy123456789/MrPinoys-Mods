package wayfarers.core;

/**
 * Count-first purchase arithmetic.
 *
 * <p>Returns a plan rather than mutating the wallet, so a refused purchase
 * leaves every state object untouched.
 */
public final class Purchase {

    public enum Outcome {
        OK,
        NO_STOCK,
        INSUFFICIENT_PAYMENT,
        COIN_EXHAUSTED,
        INVALID_CURRENCY,
        INVALID_LISTING
    }

    /**
     * A plan from the player's perspective: a positive {@code cost} means the
     * player pays; a negative {@code cost} means the player is paid.
     */
    public record Plan(Currencies currency, int cost, Listing listing, boolean isBuy, int quantity) {}

    public record Result(Outcome outcome, Plan plan) {}

    /**
     * The player is buying from the trader. The player pays {@code price}
     * and receives {@code quantity} of {@code item}.
     */
    public static Result buy(Listing listing, Wallet player) {
        Currencies c = listing.currencyType();
        if (c == null) return new Result(Outcome.INVALID_CURRENCY, null);
        if (listing.stock() <= 0) return new Result(Outcome.NO_STOCK, null);
        if (listing.quantity() <= 0 || listing.price() < 0) return new Result(Outcome.INVALID_LISTING, null);
        if (!player.canAfford(c, listing.price())) return new Result(Outcome.INSUFFICIENT_PAYMENT, null);

        Plan p = new Plan(c, listing.price(), listing.withStock(listing.stock() - 1), false, listing.quantity());
        return new Result(Outcome.OK, p);
    }

    /**
     * The player is selling to the trader. The trader pays {@code price}
     * from its {@code coin} budget; the player receives the money.
     */
    public static Result sell(Listing listing, Wallet traderCoin, Wallet player) {
        Currencies c = listing.currencyType();
        if (c == null) return new Result(Outcome.INVALID_CURRENCY, null);
        if (listing.stock() <= 0) return new Result(Outcome.NO_STOCK, null);
        if (listing.quantity() <= 0 || listing.price() < 0) return new Result(Outcome.INVALID_LISTING, null);
        if (!traderCoin.canAfford(c, listing.price())) return new Result(Outcome.COIN_EXHAUSTED, null);

        Plan p = new Plan(c, -listing.price(), listing.withStock(listing.stock() - 1), true, listing.quantity());
        return new Result(Outcome.OK, p);
    }
}
