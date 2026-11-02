package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.RankEvents;
import charter.org.Ranks;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** A rank moves one place up or down the ladder. The top rank stays first and the default rank stays last. */
public record MoveRank(OrgId kingdom, String rank, boolean up) implements Action {

    @Override
    public OrgId actingFor() {
        return kingdom;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.MANAGE_RANKS);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Outcome k = RankRules.kingdom(ctx, kingdom);
        if (k.refused()) return k;
        int at = ctx.state.ranks.position(kingdom, rank);
        if (at < 0) return Outcome.refuse(Ranks.NO_RANK);
        if (rank.equals(Ranks.TOP)) return Outcome.refuse(Ranks.TOP_FIXED);
        if (rank.equals(Ranks.DEFAULT)) return Outcome.refuse(Ranks.DEFAULT_FIXED);
        int size = ctx.state.ranks.ladder(kingdom).size();
        int to = up ? at - 1 : at + 1;
        if (to < 1 || to > size - 2) return Outcome.refuse(Ranks.CANNOT_MOVE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Ranks.Ladder ladder = ctx.state.ranks.edit(kingdom);
        int at = ctx.state.ranks.position(kingdom, rank);
        int to = up ? at - 1 : at + 1;
        String name = ladder.ranks.get(at).name();
        Collections.swap(ladder.ranks, at, to);
        return List.of(new RankEvents.RankMoved(ctx.now, kingdom, rank, name, at + 1, to + 1));
    }
}
