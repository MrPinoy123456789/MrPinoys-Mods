package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.Breakaway;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.Organization;
import charter.org.Secession;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A sub-faction secedes as a whole. The only vote the members of a Lordship or a council get: it passes
 * when more than half of ALL members eligible to vote say yes, and not voting or abstaining counts as no.
 * If the sub-faction owns a keep it is a faction of its own at once; if not, it has
 * {@code breakawayHours} to place a Nexus at its own cost, or nothing changes.
 */
public record Secede(OrgId org) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.AMEND);
    }

    @Override
    public boolean reserved(ActionContext ctx) {
        return true;
    }

    @Override
    public boolean allEligibleMajority() {
        return true;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Organization o = ctx.state.org(org).orElse(null);
        if (o == null || o.parent() == null) return Outcome.refuse(OrgReasons.NOT_A_SUBFACTION);
        if (!ctx.admin() && !ctx.knobs.playerFounding) return Outcome.refuse(OrgReasons.FOUNDING_CLOSED);
        if (ctx.state.pendingSecession(org).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_BREAKING);
        if (ctx.player() != null) {
            Outcome cooled = Found.cooldownProblem(ctx, ctx.player());
            if (cooled.refused()) return cooled;
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        if (ctx.state.coresOf(org).stream().anyMatch(c -> c.isKeep())) {
            events.addAll(Secession.complete(ctx.state, org, ctx.now));
        } else {
            long deadline = ctx.now + Durations.hours(ctx.knobs.breakawayHours);
            ctx.state.addBreakaway(new Breakaway(Breakaway.Kind.SUBFACTION, org, null, List.of(), ctx.now, deadline));
            events.add(new OrgEvents.BreakawayBegan(ctx.now, org, null, deadline));
        }
        return events;
    }
}
