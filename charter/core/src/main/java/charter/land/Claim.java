package charter.land;

import charter.id.ChunkKey;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.OrgId;

/** One claimed chunk (DESIGN.md §9.1). Every claim belongs to one core, except while contested. */
public final class Claim {

    public final ClaimId id;
    public final ChunkKey chunk;
    public final OrgId owner;
    public final long claimedAt;

    private ClaimKind kind;
    private CoreId core;
    private int hops;
    /** Siege time the orphan grace ends; 0 when the claim is not contested. */
    private long contestedUntil;

    public Claim(ClaimId id, ChunkKey chunk, OrgId owner, ClaimKind kind, CoreId core, int hops, long claimedAt) {
        this.id = id;
        this.chunk = chunk;
        this.owner = owner;
        this.kind = kind;
        this.core = core;
        this.hops = hops;
        this.claimedAt = claimedAt;
    }

    public ClaimKind kind() { return kind; }
    /** The core this claim is assigned to, or null while contested. */
    public CoreId core() { return core; }
    public int hops() { return hops; }
    public long contestedUntil() { return contestedUntil; }
    public boolean contested() { return contestedUntil != 0; }

    /** True for the chunks that make up a core itself: a keep's nine, or a camp's banner chunk. */
    public boolean fixed() {
        return kind == ClaimKind.KEEP_CORE || kind == ClaimKind.KEEP_RING || kind == ClaimKind.CAMP && hops == 0;
    }

    void assign(CoreId newCore, ClaimKind newKind, int newHops) {
        core = newCore;
        kind = newKind;
        hops = newHops;
        contestedUntil = 0;
    }

    void contest(long until) {
        core = null;
        kind = ClaimKind.OUTLYING;
        contestedUntil = until;
    }
}
