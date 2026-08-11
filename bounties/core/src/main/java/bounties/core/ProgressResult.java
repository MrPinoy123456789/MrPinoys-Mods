package bounties.core;

import java.util.List;

/**
 * Result of reporting a kill toward held bounties.
 */
public record ProgressResult(PlayerBounties state, List<Completed> completed) {

    public boolean anyCompleted() {
        return !completed.isEmpty();
    }
}
