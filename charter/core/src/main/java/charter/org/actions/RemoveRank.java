package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Grant;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.RankEvents;
import charter.org.Ranks;

import java.util.List;
import java.util.Optional;

/**
 * A rank goes: whoever held it goes back to the default rank and any powers it carried are taken back. The top and
 * default ranks cannot be removed; a rank that carries powers is removed only by the Lord.
 */
public record RemoveRank(OrgId kingdom, String rank) implements Action {

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
        if (ctx.state.ranks.rank(kingdom, rank).isEmpty()) return Outcome.refuse(Ranks.NO_RANK);
        if (rank.equals(Ranks.TOP)) return Outcome.refuse(Ranks.TOP_FIXED);
        if (rank.equals(Ranks.DEFAULT)) return Outcome.refuse(Ranks.DEFAULT_FIXED);
        if (RankRules.powered(ctx, kingdom, rank) && !RankRules.ruler(ctx, kingdom)) return Outcome.refuse(Ranks.POWERED);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Ranks ranks = ctx.state.ranks;
        String name = ranks.rank(kingdom, rank).orElseThrow().name();
        List<PlayerId> holders = ranks.holders(ctx.state, kingdom, rank);
        for (Grant grant : Ranks.rankGrants(ctx.state, kingdom, rank)) grant.revoke(ctx.now);
        ranks.forgetRank(kingdom, rank);
        Ranks.Ladder ladder = ranks.edit(kingdom);
        ladder.ranks.removeIf(r -> r.id().equals(rank));
        return List.of(new RankEvents.RankRemoved(ctx.now, kingdom, rank, name, holders));
    }
}
