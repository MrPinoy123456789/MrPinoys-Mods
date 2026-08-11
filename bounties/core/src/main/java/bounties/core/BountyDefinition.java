package bounties.core;

/**
 * One entry in the bounty pool: kill a specific mob type a configured number of
 * times for a configured diamond payout.
 */
public record BountyDefinition(
        String mobId,
        int requiredKills,
        int rewardDiamonds,
        String label,
        String description
) {
    public BountyDefinition {
        if (mobId == null || mobId.isBlank()) {
            throw new IllegalArgumentException("mobId must be non-blank");
        }
        if (requiredKills <= 0) {
            throw new IllegalArgumentException("requiredKills must be positive");
        }
        if (rewardDiamonds < 0) {
            throw new IllegalArgumentException("rewardDiamonds must be non-negative");
        }
        if (label == null || label.isBlank()) {
            label = mobId;
        }
        if (description == null) {
            description = "";
        }
    }

    public String displayDescription() {
        return description.isBlank()
                ? label + " — kill " + requiredKills
                : description;
    }
}
