package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Breakaway;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.Organization;
import charter.time.Durations;

import java.util.List;
import java.util.Optional;

/**
 * A member of a faction with no sub-factions starts to break away, or joins the group that already
 * has. No vote: at least two leave together, and the starter places a named Nexus within
 * {@code breakawayHours}. A player's own act.
 */
public record BreakAway(PlayerId player) implements Action {

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
        if (!ctx.admin() && !player.equals(ctx.player())) return Outcome.refuse(OrgReasons.NOT_YOU);
        OrgId org = ctx.state.membership(player).map(m -> m.org()).orElse(null);
        if (org == null) return Outcome.refuse(OrgReasons.NOT_A_MEMBER);
        Organization o = ctx.state.org(org).orElseThrow();
        if (o.parent() != null) return Outcome.refuse(OrgReasons.NOT_A_SUBFACTION.with("A sub-faction leaves as a whole, by a vote of its members."));
        if (!ctx.state.children(org).isEmpty()) return Outcome.refuse(OrgReasons.HAS_SUBFACTIONS);
        if (charter.gov.Deciders.of(ctx.state, org).map(d -> d.holds(player)).orElse(false)) return Outcome.refuse(OrgReasons.RULER_STAYS);
        Outcome cooled = Found.cooldownProblem(ctx, player);
        if (cooled.refused()) return cooled;
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId org = ctx.state.membership(player).orElseThrow().org();
        Optional<Breakaway> open = ctx.state.groupLeaving(org);
        if (open.isPresent()) {
            ctx.state.replaceBreakaway(open.get(), open.get().with(player));
            return List.of();
        }
        long deadline = ctx.now + Durations.hours(ctx.knobs.breakawayHours);
        ctx.state.addBreakaway(new Breakaway(Breakaway.Kind.GROUP, org, player, List.of(player), ctx.now, deadline));
        return List.of(new OrgEvents.BreakawayBegan(ctx.now, org, player, deadline));
    }
}
