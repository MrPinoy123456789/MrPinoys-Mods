package charter.api;

/**
 * Moves Marks for an organization. All methods are called on the server thread. The organization id is
 * the string form of Charter's {@code OrgId} ({@code "org:" + uuid}); every organization of the tree
 * (kingdom, sub-faction, outpost owner) may hold a balance.
 */
public interface PaymentPort {
    /** Marks held, or 0 for an unknown organization. */
    long balance(String orgId);

    /** Takes exactly marks or nothing. Returns false (and changes nothing) if the balance is short. */
    boolean charge(String orgId, long marks, String reason);

    /** Adds marks (a fine, a deposit, plunder). */
    void credit(String orgId, long marks, String reason);

    /**
     * Marks a player holds in their own wallet, or 0 when unknown. A provider that keeps player wallets overrides this;
     * the default says there are none, so Charter treats the player as having no coin (a custody fine is then refused).
     */
    default long balanceOfPlayer(java.util.UUID player) {
        return 0;
    }

    /**
     * Takes exactly marks from a player's own wallet or nothing; false (and nothing changes) if short or if the provider
     * keeps no player wallets, which is the default. Used for custody fines, which Charter then credits to the fining
     * faction with {@link #credit}.
     */
    default boolean chargePlayer(java.util.UUID player, long marks, String reason) {
        return false;
    }
}
