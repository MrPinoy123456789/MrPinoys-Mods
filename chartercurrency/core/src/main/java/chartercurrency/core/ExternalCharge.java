package chartercurrency.core;

/**
 * The words of the transaction log line for coin another mod took from a player (Fortune's stake),
 * kept here so a plain test can pin them.
 */
public final class ExternalCharge {

    private ExternalCharge() {}

    /** For example {@code Alex was charged 320 Marks for fortune_stake}. */
    public static String line(String playerName, long marks, String reason) {
        return playerName + " was charged " + marks + " Marks for "
                + (reason == null || reason.isBlank() ? "an outside mod" : reason);
    }
}
