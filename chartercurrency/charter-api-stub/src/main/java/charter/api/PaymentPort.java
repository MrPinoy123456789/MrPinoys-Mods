package charter.api;

/** Moves Marks for an organization. All methods are called on the server thread. */
public interface PaymentPort {
    /** Marks held, or 0 for an unknown organization. */
    long balance(String orgId);

    /** Takes exactly marks or nothing. Returns false (and changes nothing) if the balance is short. */
    boolean charge(String orgId, long marks, String reason);

    /** Adds marks (a fine, a deposit, plunder). */
    void credit(String orgId, long marks, String reason);

    /** Marks a player holds in their own wallet, or 0 when unknown (a copy of Charter's default). */
    default long balanceOfPlayer(java.util.UUID player) {
        return 0;
    }

    /** Takes exactly marks from a player's own wallet or nothing; false if short (a copy of Charter's default). */
    default boolean chargePlayer(java.util.UUID player, long marks, String reason) {
        return false;
    }
}
