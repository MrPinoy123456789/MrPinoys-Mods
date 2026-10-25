package charter.org;

import charter.id.OrgId;
import charter.id.PlayerId;

import java.util.Set;

/**
 * A player's direct membership. In v0 a player has at most one (DESIGN.md §6.2).
 * Roles are labels and grant nothing.
 */
public record MemberRecord(PlayerId player, OrgId org, long joinedAt, Set<String> roles) {
    public MemberRecord {
        roles = Set.copyOf(roles);
    }

    public static MemberRecord of(PlayerId player, OrgId org, long joinedAt) {
        return new MemberRecord(player, org, joinedAt, Set.of());
    }
}
