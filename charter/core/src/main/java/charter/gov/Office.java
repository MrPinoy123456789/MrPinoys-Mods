package charter.gov;

import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;

/**
 * A named position in an organization. Grants to an office follow whoever holds
 * it at the moment they are used, so removing the holder ends their powers at once.
 *
 * <p>A reserved office (the Lord) can only be filled or emptied by a vote of the
 * members. An office marked {@code vacateWhenAbsent} is emptied when its holder
 * has not been seen for the absent Lord period, and a succession election opens.
 */
public final class Office {

    public final OfficeId id;
    public final OrgId org;
    public final String name;
    public final boolean reserved;
    public final boolean vacateWhenAbsent;

    private PlayerId holder;
    private long heldSince;

    public Office(OfficeId id, OrgId org, String name, boolean reserved, boolean vacateWhenAbsent) {
        this.id = id;
        this.org = org;
        this.name = name;
        this.reserved = reserved;
        this.vacateWhenAbsent = vacateWhenAbsent;
    }

    public PlayerId holder() { return holder; }
    public long heldSince() { return heldSince; }
    public boolean vacant() { return holder == null; }

    public void fill(PlayerId player, long now) {
        holder = player;
        heldSince = now;
    }

    public void vacate() {
        holder = null;
        heldSince = 0;
    }
}
