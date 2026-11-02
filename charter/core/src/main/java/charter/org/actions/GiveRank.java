package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.RankEvents;
import charter.org.Ranks;

import java.util.List;
import java.util.Optional;

/**
 * A member of the kingdom takes a rank; they hold exactly one. The top rank is never given (it follows the Lord's
 * seat), the Lord's own rank never changes, and a rank that carries powers is given or taken away only by the Lord.
 */
public record GiveRank(OrgId kingdom, PlayerId player, String rank) implements Action {

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
        if (!Ranks.kingdomOf(ctx.state, player).map(kingdom::equals).orElse(false)) return Outcome.refuse(Ranks.NOT_IN_KINGDOM);
        if (Ranks.isRuler(ctx.state, player, kingdom)) return Outcome.refuse(Ranks.RULER);
        String from = ctx.state.ranks.rankOf(ctx.state, player).map(Ranks.Rank::id).orElse(Ranks.DEFAULT);
        if (from.equals(rank)) return Outcome.refuse(Ranks.UNCHANGED);
        boolean powered = RankRules.powered(ctx, kingdom, rank) || RankRules.powered(ctx, kingdom, from);
        if (powered && !RankRules.ruler(ctx, kingdom)) return Outcome.refuse(Ranks.POWERED);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Ranks ranks = ctx.state.ranks;
        String from = ranks.rankOf(ctx.state, player).map(Ranks.Rank::name).orElse("");
        ranks.edit(kingdom);
        ranks.give(player, kingdom, rank);
        String name = ranks.rank(kingdom, rank).orElseThrow().name();
        return List.of(new RankEvents.RankGiven(ctx.now, kingdom, player, rank, name, from));
    }
}
