package charter.grant;

import charter.id.OrgId;
import charter.id.PlayerId;

/** Who is exercising a power: a player, or an organization by a passed vote. */
public sealed interface Who {

    record AsPlayer(PlayerId player) implements Who {
    }

    record AsOrg(OrgId org) implements Who {
    }

    /** The holders of an office acting by a majority vote of the seats. Reaches only grants to that office. */
    record AsSeats(charter.id.OfficeId office) implements Who {
    }
}
