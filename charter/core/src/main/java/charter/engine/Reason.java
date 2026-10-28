package charter.engine;

/**
 * Why an action was refused: a stable key and one plain sentence for the player.
 * The keys are fixed so the player-text pass can reword every refusal in one
 * place; each feature keeps its own keys beside its actions.
 */
public record Reason(String key, String text) {

    public static final Reason NO_SUCH_ORG = new Reason("no_such_org", "That organization no longer exists.");
    public static final Reason NO_AUTHORITY = new Reason("no_authority", "You do not hold that power.");
    public static final Reason NEEDS_VOTE = new Reason("needs_vote", "That needs a vote of the members. Propose it instead.");
    public static final Reason RESERVED = new Reason("reserved", "Only a vote of the members can do that.");
    public static final Reason NEEDS_SEATS = new Reason("needs_seats", "The council decides this together. Propose it to them.");
    public static final Reason NOT_ELIGIBLE = new Reason("not_eligible", "You cannot vote in this organization yet.");

    /** The same key with a more specific sentence. */
    public Reason with(String specificText) {
        return new Reason(key, specificText);
    }
}
