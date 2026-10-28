package charter.org;

import charter.id.OrgId;
import charter.id.PlayerId;

/** A player's open request to join a faction. Admitting it is a decision of the faction (MANAGE_MEMBERS). */
public record JoinRequest(OrgId org, PlayerId player, long at) {
}
