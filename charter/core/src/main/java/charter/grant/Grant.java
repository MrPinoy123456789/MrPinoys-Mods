package charter.grant;

import charter.id.GrantId;
import charter.id.OrgId;

/**
 * One grant of a power over an organization (DESIGN.md §18.1).
 *
 * <p>{@code subject} is the organization whose power this is. A root grant has
 * no parent and its grantor is the subject itself; it only comes from a vote of
 * the subject (or from a preset at founding). A derived grant names its parent,
 * its grantor is the parent's recipient, and its scope sits within the parent's.
 *
 * <p>Nothing about a grant is ever copied onto its recipient. Whether it works
 * is decided when it is used, by walking the chain (AuthorityResolver).
 */
public final class Grant {

    public final GrantId id;
    public final OrgId subject;
    public final Power power;
    public final Holder recipient;
    public final Holder grantor;
    public final GrantId parent;
    public final GrantScope scope;
    public final boolean redelegable;
    public final long createdAt;
    /** Wall time after which the grant no longer works; 0 for never. */
    public final long expiresAt;

    private boolean revoked;
    private long revokedAt;

    public Grant(GrantId id, OrgId subject, Power power, Holder recipient, Holder grantor, GrantId parent,
                 GrantScope scope, boolean redelegable, long createdAt, long expiresAt) {
        this.id = id;
        this.subject = subject;
        this.power = power;
        this.recipient = recipient;
        this.grantor = grantor;
        this.parent = parent;
        this.scope = scope;
        this.redelegable = redelegable;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean revoked() {
        return revoked;
    }

    public long revokedAt() {
        return revokedAt;
    }

    public void revoke(long now) {
        if (!revoked) {
            revoked = true;
            revokedAt = now;
        }
    }

    public boolean isRoot() {
        return parent == null;
    }
}
