package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.RankEvents;
import charter.org.Ranks;

import java.util.List;
import java.util.Optional;

/** A new rank, placed just above the default rank. It carries no powers until the Lord gives it some. */
public record AddRank(OrgId kingdom, String name) implements Action {

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
        Optional<String> clean = Ranks.clean(name);
        if (clean.isEmpty()) return Outcome.refuse(Ranks.BAD_NAME);
        if (ctx.state.ranks.ladder(kingdom).size() >= Ranks.MAX_RANKS) return Outcome.refuse(Ranks.TOO_MANY);
        if (ctx.state.ranks.nameTaken(kingdom, clean.get(), null)) return Outcome.refuse(Ranks.NAME_TAKEN);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        String clean = Ranks.clean(name).orElseThrow();
        Ranks.Ladder ladder = ctx.state.ranks.edit(kingdom);
        Ranks.Rank rank = new Ranks.Rank("r" + ladder.nextId++, clean);
        ladder.ranks.add(ladder.ranks.size() - 1, rank);
        return List.of(new RankEvents.RankAdded(ctx.now, kingdom, rank.id(), rank.name()));
    }
}
