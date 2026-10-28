package charter.org;

import charter.contract.Contract;
import charter.grant.Grant;
import charter.grant.GrantEvents;
import charter.grant.Holder;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A sub-faction becomes a top-level faction of its own (DESIGN 16.1). It keeps its members, its
 * sub-factions, its treasury and any keeps it owns; the grants and contracts that tied it to its old
 * faction end. Everything is recorded.
 */
public final class Secession {

    private Secession() {
    }

    public static List<HistoryEvent> complete(CharterState state, OrgId org, long now) {
        List<HistoryEvent> events = new ArrayList<>();
        Optional<Organization> found = state.org(org);
        if (found.isEmpty() || found.get().parent() == null) return events;
        OrgId from = found.get().parent();
        Set<OrgId> inside = new java.util.LinkedHashSet<>(state.descendants(org));
        inside.add(org);

        state.setParent(org, null);
        events.add(new OrgEvents.Seceded(now, org, from));

        for (Grant grant : state.grants()) {
            if (grant.revoked()) continue;
            OrgId holderOrg = recipientOrg(state, grant.recipient);
            if (holderOrg == null) continue;
            if (inside.contains(grant.subject) != inside.contains(holderOrg)) {
                grant.revoke(now);
                events.add(new GrantEvents.Revoked(now, grant.id, grant.subject, grant.power));
            }
        }
        for (Contract contract : state.contracts.live()) {
            OrgId a = contract.imposer.org();
            OrgId b = contract.subject.org();
            if (a != null && b != null && inside.contains(a) != inside.contains(b)) contract.end(now, "secession");
        }
        for (OrgId o : inside) {
            for (MemberRecord m : state.members(o)) state.player(m.player()).lastFoundedAt = now;
        }
        return events;
    }

    private static OrgId recipientOrg(CharterState state, Holder holder) {
        return switch (holder) {
            case Holder.OfOrg o -> o.org();
            case Holder.OfOffice o -> state.office(o.office()).map(x -> x.org).orElse(null);
            case Holder.OfPlayer p -> null;
        };
    }
}
