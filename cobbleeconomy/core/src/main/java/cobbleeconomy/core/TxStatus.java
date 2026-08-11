package cobbleeconomy.core;

/** Why a transaction did or did not happen. */
public enum TxStatus {

    OK,

    /** Amount was zero, negative, or otherwise not a positive quantity. */
    INVALID_AMOUNT,

    /** The account did not hold enough of the currency to cover the request. */
    INSUFFICIENT_FUNDS,

    /** A player tried to pay themselves. */
    SELF_TRANSFER,

    /**
     * The resulting balance would exceed {@link Long#MAX_VALUE}. Unreachable through
     * mining, reachable through {@code /cobbleeconomy add}.
     */
    OVERFLOW,

    /** The currency named does not exist on this server. */
    UNKNOWN_CURRENCY;

    public boolean ok() {
        return this == OK;
    }
}
