package charter.land.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.Core;
import charter.land.LandGraph;
import charter.land.LandReasons;

import java.util.List;
import java.util.Optional;

/**
 * An operator moves a kingdom's keep so its core chunk is {@code center}. The keep keeps its identity, so members, race,
 * treasury, history and outposts stay as they are; only the keep's land moves. Outposts that no longer touch it fall
 * back on the usual grace for land without a core. The caller moves the Nexus block.
 */
public record MoveKeep(OrgId org, ChunkKey center) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    /** The one keep the organization has, if it has one. */
    static Optional<Core> keepOf(ActionContext ctx, OrgId org) {
        return ctx.state.coresOf(org).stream().filter(Core::isKeep).findFirst();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.admin()) return Outcome.refuse(LandReasons.OPERATOR_ONLY);
        if (ctx.state.org(org).isEmpty()) return Outcome.refuse(charter.engine.Reason.NO_SUCH_ORG);
        Optional<Core> keep = keepOf(ctx, org);
        if (keep.isEmpty()) return Outcome.refuse(LandReasons.NO_SUCH_CORE);
        if (keep.get().center.equals(center)) return Outcome.refuse(LandReasons.KEEP_NEEDS_ROOM);
        var problem = new LandGraph(ctx.state, ctx.knobs).keepProblemForMove(org, center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Core keep = keepOf(ctx, org).orElseThrow();
        return new LandGraph(ctx.state, ctx.knobs).moveKeep(org, keep, center, ctx.now);
    }
}
