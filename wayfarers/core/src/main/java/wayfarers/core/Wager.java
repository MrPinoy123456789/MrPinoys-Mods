package wayfarers.core;

/**
 * A stake, odds and consequence; defined now but unused in v1 (SPEC.md §6.7).
 */
public record Wager(int stake, double odds, int prize, String consequence) {

    public int payout() {
        if (odds <= 0 || stake <= 0) return 0;
        return stake + (int) Math.floor(stake * (odds - 1.0));
    }
}
