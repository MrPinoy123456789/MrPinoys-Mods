package charter.grant;

import charter.id.OrgId;
import charter.id.PlayerId;

/** Who is exercising a power: a player, or an organization by a passed vote. */
public sealed interface Who {

    record AsPlayer(PlayerId player) implements Who {
    }

    record AsOrg(OrgId org) implements Who {
    }
}
