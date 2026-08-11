package ballot;

/**
 * The result of trying to do something to a poll.
 *
 * <p>A refusal is a return value, not an exception. A player clicking a ballot box they
 * have already used is completely ordinary, and the message they see is the whole
 * point — so the failure path carries the same weight as the success path.
 *
 * @param value an entry id where one is relevant, otherwise -1
 */
public record Outcome(boolean ok, String message, int value) {

    public static Outcome ok(String message) {
        return new Outcome(true, message, -1);
    }

    public static Outcome ok(String message, int value) {
        return new Outcome(true, message, value);
    }

    public static Outcome no(String message) {
        return new Outcome(false, message, -1);
    }
}
