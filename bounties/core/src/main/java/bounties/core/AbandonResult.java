package bounties.core;

/**
 * Result of attempting to abandon a held bounty.
 */
public record AbandonResult(boolean ok, PlayerBounties state, BountyDefinition abandoned, String message) {
}
