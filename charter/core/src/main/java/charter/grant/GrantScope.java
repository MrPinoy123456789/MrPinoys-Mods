package charter.grant;

import charter.treasury.Currency;

import java.util.EnumMap;
import java.util.Map;

/**
 * The limits on a grant (DESIGN.md §7.2). For now that is a spending limit per
 * action in each currency; a currency with no entry is unlimited. A derived
 * grant must sit within its parent's scope.
 */
public record GrantScope(Map<Currency, Long> spendLimitPerAction) {

    public static final GrantScope ANY = new GrantScope(Map.of());

    public GrantScope {
        EnumMap<Currency, Long> ordered = new EnumMap<>(Currency.class);
        ordered.putAll(spendLimitPerAction);
        spendLimitPerAction = java.util.Collections.unmodifiableMap(ordered);
    }

    public static GrantScope spendUpTo(long cobblestone, long diamonds) {
        EnumMap<Currency, Long> limits = new EnumMap<>(Currency.class);
        limits.put(Currency.COBBLESTONE, cobblestone);
        limits.put(Currency.DIAMOND, diamonds);
        return new GrantScope(limits);
    }

    public long limit(Currency currency) {
        return spendLimitPerAction.getOrDefault(currency, Long.MAX_VALUE);
    }

    public boolean allows(ScopeNeed need) {
        return need.currency() == null || need.amount() <= limit(need.currency());
    }

    /** True if every limit here is at least as tight as the parent's. */
    public boolean within(GrantScope parent) {
        for (Currency currency : Currency.values()) {
            if (limit(currency) > parent.limit(currency)) return false;
        }
        return true;
    }
}
