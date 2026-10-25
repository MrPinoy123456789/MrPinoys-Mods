package charter.grant;

import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;

/**
 * Who a grant is given to, or who gave it. A grant to an office follows whoever
 * holds the office at the moment it is used; a grant to an organization can be
 * used by anyone who holds the same power within that organization.
 */
public sealed interface Holder {

    record OfPlayer(PlayerId player) implements Holder {
    }

    record OfOffice(OfficeId office) implements Holder {
    }

    record OfOrg(OrgId org) implements Holder {
    }
}
