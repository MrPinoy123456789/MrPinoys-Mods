package charter.grant;

import charter.treasury.Currency;

import java.util.EnumMap;
import java.util.Map;

/**
 * The limits on a grant (DESIGN.md §7.2). For now that is a spending limit per action in Marks;
 * no limit means unlimited. A derived grant must sit within its parent's scope.
 *
 * <p>{@code spendLimitPerAction} is the old per currency limit (cobblestone and diamonds) from before treasuries
 * moved to the payment port. It is still saved and loaded as it was and is never written for a new grant; the only
 * thing read from it is a grant saved before {@code spendLimitMarks} existed, which keeps its diamond limit at the
 * reference rate of {@link #MARKS_PER_DIAMOND}.
 */
public record GrantScope(Map<Currency, Long> spendLimitPerAction, Long spendLimitMarks) {

    /** The reference rate used only to carry an old diamond limit over; never an exchange rate. */
    public static final long MARKS_PER_DIAMOND = 32;

    public static final GrantScope ANY = new GrantScope(Map.of(), null);

    public GrantScope {
        EnumMap<Currency, Long> ordered = new EnumMap<>(Currency.class);
        if (spendLimitPerAction != null) ordered.putAll(spendLimitPerAction);
        spendLimitPerAction = java.util.Collections.unmodifiableMap(ordered);
    }

    public static GrantScope spendUpTo(long marks) {
        return new GrantScope(Map.of(), marks);
    }

    /** The most Marks one action may spend; {@link Long#MAX_VALUE} when there is no limit. */
    public long limit() {
        if (spendLimitMarks != null) return spendLimitMarks;
        Long diamonds = spendLimitPerAction.get(Currency.DIAMOND);
        return diamonds == null ? Long.MAX_VALUE : diamonds * MARKS_PER_DIAMOND;
    }

    public boolean allows(ScopeNeed need) {
        return need.marks() <= 0 || need.marks() <= limit();
    }

    /** True if the limit here is at least as tight as the parent's. */
    public boolean within(GrantScope parent) {
        return limit() <= parent.limit();
    }
}
