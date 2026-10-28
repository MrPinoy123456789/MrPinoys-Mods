package charter.grant;

import charter.gov.Office;
import charter.id.OrgId;
import charter.state.CharterState;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Decides at the moment of use whether someone may exercise a power over an
 * organization (PLAN.md §3, step 2).
 *
 * <p>An organization acting by its own vote is the source of all its powers.
 * Anyone else needs a grant over that organization, for that power, whose scope
 * covers the need, that is valid now, and that reaches them:
 * <ul>
 * <li>a grant to a player reaches that player;</li>
 * <li>a grant to an office reaches whoever holds it now (and is a member);</li>
 * <li>a grant to an organization reaches that organization's own vote, and any
 * player who holds the same power within that organization.</li>
 * </ul>
 *
 * <p>A grant is valid only while every link above it is: not revoked, not
 * expired, its parent passable on, its scope within its parent's, and its grantor
 * the parent's recipient. Nothing is cached, so a revoke ends everything below it
 * at once (test F).
 */
public final class AuthorityResolver {

    private static final int MAX_DEPTH = 32;

    private final CharterState state;
    private final long now;

    public AuthorityResolver(CharterState state, long now) {
        this.state = state;
        this.now = now;
    }

    public Authority resolve(Who who, OrgId subject, Power power, ScopeNeed need, boolean redelegable) {
        return resolve(who, subject, power, need, redelegable, new HashSet<>(), 0);
    }

    private Authority resolve(Who who, OrgId subject, Power power, ScopeNeed need, boolean redelegable,
                              Set<String> visiting, int depth) {
        if (state.org(subject).isEmpty()) return Authority.NONE;
        if (who instanceof Who.AsOrg(OrgId org) && org.equals(subject)) return Authority.SOURCE;
        if (depth > MAX_DEPTH) return Authority.NONE;
        String key = who + "|" + subject + "|" + power;
        if (!visiting.add(key)) return Authority.NONE;
        try {
            for (Grant grant : state.grants()) {
                if (!grant.subject.equals(subject) || grant.power != power) continue;
                if (redelegable && !grant.redelegable) continue;
                if (!grant.scope.allows(need)) continue;
                if (!valid(grant)) continue;
                if (reaches(who, grant.recipient, power, need, visiting, depth)) return Authority.via(grant);
            }
            return Authority.NONE;
        } finally {
            visiting.remove(key);
        }
    }

    private boolean reaches(Who who, Holder recipient, Power power, ScopeNeed need, Set<String> visiting, int depth) {
        return switch (recipient) {
            case Holder.OfPlayer(var player) -> who instanceof Who.AsPlayer(var p) && p.equals(player);
            case Holder.OfOffice(var officeId) -> {
                if (who instanceof Who.AsSeats(var seats)) yield seats.equals(officeId);
                if (!(who instanceof Who.AsPlayer(var p))) yield false;
                Optional<Office> office = state.office(officeId);
                yield office.isPresent() && office.get().holds(p) && state.isMember(p, office.get().org);
            }
            case Holder.OfOrg(var org) -> {
                if (who instanceof Who.AsOrg(var o)) yield o.equals(org);
                // A player uses an organization's grant with the same power they hold within it.
                yield resolve(who, org, power, need, false, visiting, depth + 1).ok();
            }
        };
    }

    /** True if the grant and every link above it holds now. */
    public boolean valid(Grant grant) {
        return valid(grant, 0);
    }

    private boolean valid(Grant grant, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (grant.revoked()) return false;
        if (grant.expiresAt != 0 && now >= grant.expiresAt) return false;
        if (state.org(grant.subject).isEmpty()) return false;
        if (!recipientExists(grant.recipient)) return false;
        if (grant.isRoot()) {
            return grant.grantor.equals(new Holder.OfOrg(grant.subject));
        }
        Optional<Grant> parent = state.grant(grant.parent);
        if (parent.isEmpty()) return false;
        Grant p = parent.get();
        return p.redelegable
                && p.power == grant.power
                && p.subject.equals(grant.subject)
                && grant.grantor.equals(p.recipient)
                && grant.scope.within(p.scope)
                && valid(p, depth + 1);
    }

    private boolean recipientExists(Holder holder) {
        return switch (holder) {
            case Holder.OfPlayer p -> true;
            case Holder.OfOffice o -> state.office(o.office()).isPresent();
            case Holder.OfOrg o -> state.org(o.org()).isPresent();
        };
    }

    /** True if {@code ancestor} is this grant or above it in its chain. */
    public boolean inChain(Grant grant, Grant ancestor) {
        Grant current = grant;
        for (int depth = 0; current != null && depth <= MAX_DEPTH; depth++) {
            if (current.id.equals(ancestor.id)) return true;
            current = current.parent == null ? null : state.grant(current.parent).orElse(null);
        }
        return false;
    }
}
