package charter.contract;

/**
 * Where a contract is in its life. Breach exists only for signed contracts;
 * resistance only for imposed ones, and it leaves no mark (DESIGN.md §15.4).
 */
public enum ContractState {
    /** A signed contract waiting for the other party. */
    PROPOSED,
    ACTIVE,
    /** It ran its course. */
    FULFILLED,
    /** Ended early by agreement, a pardon, a release, or the fall of the keep it depended on. */
    ENDED,
    /** A signed contract broken by one party: a mark on their record. */
    BREACHED,
    /** An imposed contract escaped, rebelled against or starved out: no mark. */
    RESISTED;

    public boolean live() {
        return this == ACTIVE;
    }
}
