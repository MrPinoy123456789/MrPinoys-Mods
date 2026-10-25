package charter.contract;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;

/**
 * A player caught on a faction's land and held until sentenced (DESIGN.md §15.5).
 * It lapses {@code custodyLapseMinutes} after the arrest if no sentence follows.
 * The fabric side checks the lead; core checks where the player stood and keeps
 * the custody and its lapse.
 */
public record Custody(PlayerId prisoner, OrgId faction, PlayerId officer, CoreId keep, long since) {
}
