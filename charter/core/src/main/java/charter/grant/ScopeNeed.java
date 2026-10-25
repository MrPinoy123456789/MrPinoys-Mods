package charter.grant;

import charter.treasury.Currency;

/** What an action needs from a grant's scope: an amount of a currency, or nothing. */
public record ScopeNeed(Currency currency, long amount) {

    public static final ScopeNeed NONE = new ScopeNeed(null, 0);

    public static ScopeNeed spend(Currency currency, long amount) {
        return new ScopeNeed(currency, amount);
    }
}
