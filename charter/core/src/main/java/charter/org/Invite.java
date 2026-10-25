package charter.org;

import charter.id.OrgId;
import charter.id.PlayerId;

/** An open invitation to join an organization. Accepting it is the player's own act. */
public record Invite(OrgId org, PlayerId player, long at) {
}
