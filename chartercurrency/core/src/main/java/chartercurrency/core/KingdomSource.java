package chartercurrency.core;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Every question this mod asks about kingdoms, behind one interface so only one class
 * knows where the answers come from.
 *
 * <p>In production the answers come from Charter ({@code CharterKingdomSource});
 * without Charter they come from {@link ManualKingdomSource}. The mod never keeps its
 * own copy of membership: it asks every time it needs to know.
 *
 * <p>"Kingdom" here is the top level organization. Treasuries are keyed by an
 * organization id and may belong to any organization; Renown and the Mint tax follow
 * {@link #kingdomOf}.
 */
public interface KingdomSource {

    /** The kingdom id the player belongs to, empty if none. */
    Optional<String> kingdomOf(UUID player);

    /** The Lord of an organization, empty if the office is vacant or the id is unknown. With two Lords, the first seated. */
    Optional<UUID> lordOf(String kingdomId);

    /** Every Lord of an organization: none, one, or two when a kingdom has two Lords. */
    default java.util.List<UUID> lordsOf(String kingdomId) {
        return lordOf(kingdomId).map(java.util.List::of).orElse(java.util.List.of());
    }

    /**
     * True when another system (Charter) decides who may spend a treasury, so this mod must not
     * run a second, ungoverned door to it. Ops still may. Default false (the manual stand-in).
     */
    default boolean governsSpending() {
        return false;
    }

    /**
     * The most Marks one action may take from the organization's treasury for this player, or
     * empty when the player has no spend right. The default is the Lord rule with no limit; a
     * source that knows a spend limit (Charter's {@code lordSpendLimitMarks}) overrides it.
     */
    default java.util.OptionalLong spendLimit(String kingdomId, UUID player) {
        return lordsOf(kingdomId).contains(player)
                ? java.util.OptionalLong.of(Long.MAX_VALUE) : java.util.OptionalLong.empty();
    }

    /**
     * May this player take {@code marks} of coin from the organization's treasury in one action, by the rules of
     * whoever governs spending. The default reads {@link #spendLimit}; Charter answers from its grants. This is
     * the question the Bank menu asks before it shows a Take button; the withdrawal itself still goes through
     * the governing door ({@link Treasury#mayWithdrawCoin}, or Charter's Withdraw when Charter governs).
     */
    default boolean maySpend(String kingdomId, UUID player, long marks) {
        if (marks <= 0) return false;
        java.util.OptionalLong limit = spendLimit(kingdomId, player);
        return limit.isPresent() && marks <= limit.getAsLong();
    }

    /** A name for chat; falls back to the id for an unknown organization. */
    String displayName(String kingdomId);

    /** Every organization id this source knows. */
    Collection<String> kingdomIds();

    /** Whether this source knows who holds land, so {@link #landOwner} means something. */
    default boolean knowsLand() {
        return false;
    }

    /** The kingdom id holding a chunk, empty when nobody does or land is unknown. */
    default Optional<String> landOwner(String dimension, int chunkX, int chunkZ) {
        return Optional.empty();
    }
}
