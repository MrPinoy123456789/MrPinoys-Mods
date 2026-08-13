package bounties.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a player's held bounties.
 *
 * <p>All state transitions are pure functions returning new objects. The engine
 * has no concept of time limits once a bounty is accepted; it lives until it is
 * abandoned or completed.
 */
public final class PlayerBounties {

    public static final int DEFAULT_MAX_HELD = 3;

    private final List<AcceptedBounty> held;

    public PlayerBounties() {
        this.held = List.of();
    }

    public PlayerBounties(List<AcceptedBounty> held) {
        this.held = List.copyOf(held);
    }

    public List<AcceptedBounty> held() {
        return held;
    }

    public int heldCount() {
        return held.size();
    }

    public boolean isFull(int maxHeld) {
        return held.size() >= maxHeld;
    }

    public AcceptResult accept(BountyDefinition definition, long acceptedAt) {
        return accept(definition, acceptedAt, DEFAULT_MAX_HELD);
    }

    public AcceptResult accept(BountyDefinition definition, long acceptedAt, int maxHeld) {
        if (isFull(maxHeld)) {
            return new AcceptResult(false, this,
                    "You already hold " + maxHeld + " bounties. Abandon one first.");
        }
        List<AcceptedBounty> next = new ArrayList<>(held);
        next.add(new AcceptedBounty(definition, 0, acceptedAt));
        return new AcceptResult(true, new PlayerBounties(next),
                "Accepted bounty: " + definition.displayDescription());
    }

    public AbandonResult abandon(int oneBasedIndex) {
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= held.size()) {
            return new AbandonResult(false, this, null,
                    "Invalid bounty number. You are holding " + held.size() + ".");
        }
        List<AcceptedBounty> next = new ArrayList<>(held);
        BountyDefinition removed = next.remove(index).definition();
        return new AbandonResult(true, new PlayerBounties(next), removed,
                "Abandoned bounty: " + removed.displayDescription());
    }

    public ProgressResult progress(String mobId) {
        if (held.isEmpty()) {
            return new ProgressResult(this, List.of());
        }

        List<AcceptedBounty> next = new ArrayList<>(held.size());
        List<Completed> completed = new ArrayList<>();

        for (AcceptedBounty current : held) {
            if (!mobId.equals(current.definition().mobId())) {
                next.add(current);
                continue;
            }

            int newProgress = current.progress() + 1;
            if (newProgress >= current.definition().requiredKills()) {
                completed.add(new Completed(current.definition(),
                        current.definition().rewardDiamonds()));
            } else {
                next.add(new AcceptedBounty(current.definition(), newProgress,
                        current.acceptedAt()));
            }
        }

        return new ProgressResult(new PlayerBounties(next),
                Collections.unmodifiableList(completed));
    }
}
