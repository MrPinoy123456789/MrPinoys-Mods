package bounties.core;

/**
 * Result of a kill event that finished one held bounty.
 */
public record Completed(BountyDefinition definition, int rewardDiamonds) {
}
