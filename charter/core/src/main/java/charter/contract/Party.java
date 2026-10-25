package charter.contract;

import charter.id.OrgId;
import charter.id.PlayerId;

/** One side of a contract: an organization or a single player. */
public record Party(OrgId org, PlayerId player) {

    public Party {
        if ((org == null) == (player == null)) throw new IllegalArgumentException("a party is an organization or a player");
    }

    public static Party of(OrgId org) {
        return new Party(org, null);
    }

    public static Party of(PlayerId player) {
        return new Party(null, player);
    }

    public boolean isPlayer() {
        return player != null;
    }
}
