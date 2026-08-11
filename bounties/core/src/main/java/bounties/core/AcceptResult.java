package bounties.core;

/**
 * Result of attempting to accept a new bounty.
 */
public record AcceptResult(boolean ok, PlayerBounties state, String message) {
}
