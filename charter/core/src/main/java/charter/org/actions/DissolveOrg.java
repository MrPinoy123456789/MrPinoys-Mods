package charter.org.actions;

import charter.contract.Contract;
import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.GovEvents;
import charter.gov.Vote;
import charter.grant.Grant;
import charter.grant.GrantEvents;
import charter.grant.Holder;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.Core;
import charter.land.LandGraph;
import charter.land.LandReasons;
import charter.org.MemberRecord;
import charter.org.OrgEvents;
import charter.org.OrgStatus;
import charter.org.Organization;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An operator dissolves a faction and everything below it: members are set free, keeps and land are
 * gone, open votes and requests are closed, and grants and contracts that touched it end, each with
 * the reason recorded. The organization stays in the state as {@code DISSOLVED} so history can still
 * name it. Operators only; nothing in the rules does this.
 */
public record DissolveOrg(OrgId org) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.admin()) return Outcome.refuse(LandReasons.OPERATOR_ONLY);
        if (ctx.state.org(org).isEmpty()) return Outcome.refuse(charter.engine.Reason.NO_SUCH_ORG);
        return Outcome.OK;
    }

    /** The organization and every sub-faction below it, the deepest first. */
    public static List<OrgId> subtree(CharterState state, OrgId org) {
        List<OrgId> out = new ArrayList<>(state.descendants(org));
        java.util.Collections.reverse(out);
        out.add(org);
        return out;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        CharterState state = ctx.state;
        List<OrgId> doomed = subtree(state, org);
        Set<OrgId> all = new LinkedHashSet<>(doomed);
        List<HistoryEvent> events = new ArrayList<>();

        for (OrgId id : doomed) {
            Organization o = state.org(id).orElse(null);
            if (o == null) continue;
            int keeps = 0;
            for (Core core : List.copyOf(state.coresOf(id))) {
                if (core.isKeep()) keeps++;
                events.addAll(new LandGraph(state, ctx.knobs).loseCore(core.id, ctx.now));
            }
            for (Claim claim : List.copyOf(state.claimsOf(id))) state.removeClaim(claim.id);
            int members = 0;
            for (MemberRecord m : List.copyOf(state.members(id))) {
                state.leave(m.player());
                members++;
            }
            for (Vote vote : state.votes()) {
                if (vote.open() && vote.org.equals(id)) {
                    vote.settle(Vote.State.FAILED, "organization dissolved");
                    events.add(new GovEvents.VoteFailed(ctx.now, vote.id, id, "organization dissolved"));
                }
            }
            for (var invite : List.copyOf(state.invites())) {
                if (invite.org().equals(id)) state.removeInvite(id, invite.player());
            }
            state.setStatus(id, OrgStatus.DISSOLVED);
            events.add(new OrgEvents.Dissolved(ctx.now, id, members, keeps));
        }
        for (Grant grant : state.grants()) {
            if (grant.revoked()) continue;
            if (all.contains(grant.subject) || all.contains(recipientOrg(state, grant.recipient))) {
                grant.revoke(ctx.now);
                events.add(new GrantEvents.Revoked(ctx.now, grant.id, grant.subject, grant.power));
            }
        }
        for (Contract contract : state.contracts.live()) {
            OrgId a = contract.imposer.org();
            OrgId b = contract.subject.org();
            if ((a != null && all.contains(a)) || (b != null && all.contains(b))) contract.end(ctx.now, "organization dissolved");
        }
        return events;
    }

    private static OrgId recipientOrg(CharterState state, Holder holder) {
        return switch (holder) {
            case Holder.OfOrg o -> o.org();
            case Holder.OfOffice o -> state.allOffices().stream().filter(x -> x.id.equals(o.office())).findFirst().map(x -> x.org).orElse(null);
            case Holder.OfPlayer p -> null;
        };
    }
}
