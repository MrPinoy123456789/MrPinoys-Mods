package charter.id;

import java.util.UUID;

/**
 * Hands out new ids from a counter that is saved with the state, so ids are
 * deterministic in tests and never repeat across restarts. Player ids are the
 * players' own UUIDs and never come from here.
 */
public final class IdSource {

    private static final long PREFIX = 0x4348_4152_5445_5200L; // "CHARTER\0"

    private long next = 1;

    private UUID nextUuid() {
        return new UUID(PREFIX, next++);
    }

    public OrgId org() { return new OrgId(nextUuid()); }
    public OfficeId office() { return new OfficeId(nextUuid()); }
    public GrantId grant() { return new GrantId(nextUuid()); }
    public VoteId vote() { return new VoteId(nextUuid()); }
    public CoreId core() { return new CoreId(nextUuid()); }
    public ClaimId claim() { return new ClaimId(nextUuid()); }
    public ContractId contract() { return new ContractId(nextUuid()); }

    /** The next counter value, for saving. */
    public long peek() {
        return next;
    }

    /** Restores the counter after loading. Never moves it backwards. */
    public void restore(long value) {
        next = Math.max(next, value);
    }
}
