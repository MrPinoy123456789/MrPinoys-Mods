package bounties.core;

/**
 * A single bounty that a player has accepted, plus how many kills they have
 * accumulated toward it.
 */
public record AcceptedBounty(
        BountyDefinition definition,
        int progress,
        long acceptedAt
) {
    public AcceptedBounty {
        if (definition == null) {
            throw new IllegalArgumentException("definition is required");
        }
        if (progress < 0) {
            throw new IllegalArgumentException("progress must be non-negative");
        }
    }

    public int remaining() {
        return Math.max(0, definition.requiredKills() - progress);
    }
}
