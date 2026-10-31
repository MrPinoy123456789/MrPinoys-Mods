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
}
