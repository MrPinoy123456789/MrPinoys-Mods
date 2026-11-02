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

/** A rank takes a new name; every rank can be renamed, the top one (King, Queen) and the default one included. */
public record RenameRank(OrgId kingdom, String rank, String name) implements Action {

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
        Optional<Ranks.Rank> found = ctx.state.ranks.rank(kingdom, rank);
        if (found.isEmpty()) return Outcome.refuse(Ranks.NO_RANK);
        Optional<String> clean = Ranks.clean(name);
        if (clean.isEmpty()) return Outcome.refuse(Ranks.BAD_NAME);
        if (clean.get().equals(found.get().name())) return Outcome.refuse(Ranks.UNCHANGED);
        if (ctx.state.ranks.nameTaken(kingdom, clean.get(), rank)) return Outcome.refuse(Ranks.NAME_TAKEN);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        String clean = Ranks.clean(name).orElseThrow();
        Ranks.Ladder ladder = ctx.state.ranks.edit(kingdom);
        int at = ctx.state.ranks.position(kingdom, rank);
        String from = ladder.ranks.get(at).name();
        ladder.ranks.set(at, new Ranks.Rank(rank, clean));
        return List.of(new RankEvents.RankRenamed(ctx.now, kingdom, rank, from, clean));
    }
}
