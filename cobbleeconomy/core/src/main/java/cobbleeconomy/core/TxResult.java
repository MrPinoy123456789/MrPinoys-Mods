package cobbleeconomy.core;

/**
 * The outcome of an economy operation, carrying the currency it applied to and the
 * balances as they stand afterwards.
 *
 * <p>On failure the balances are the unchanged current values, which is what an error
 * message wants: "you have 300 cobblestone, you asked for 500".
 *
 * @param status        why it did or did not happen
 * @param currency      the currency involved, or null for operations spanning several
 * @param amount        the amount requested
 * @param actorBalance  the acting player's balance in {@code currency} afterwards
 * @param targetBalance the recipient's balance; equals {@code actorBalance} when there
 *                      is only one party
 */
public record TxResult(TxStatus status, Currency currency, long amount,
                       long actorBalance, long targetBalance) {

    public boolean ok() {
        return status.ok();
    }

    static TxResult ok(Currency currency, long amount, long actorBalance) {
        return new TxResult(TxStatus.OK, currency, amount, actorBalance, actorBalance);
    }

    static TxResult ok(Currency currency, long amount, long actorBalance, long targetBalance) {
        return new TxResult(TxStatus.OK, currency, amount, actorBalance, targetBalance);
    }

    static TxResult fail(TxStatus status, Currency currency, long amount, long actorBalance) {
        return new TxResult(status, currency, amount, actorBalance, actorBalance);
    }

    static TxResult fail(TxStatus status, Currency currency, long amount,
                         long actorBalance, long targetBalance) {
        return new TxResult(status, currency, amount, actorBalance, targetBalance);
    }
}
