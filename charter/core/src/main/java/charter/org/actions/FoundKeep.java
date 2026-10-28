package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.LandGraph;
import charter.land.LandReasons;
import charter.org.Organization;
import charter.org.OrgReasons;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A player founds a Lordship and raises its keep in one act, paying for the keep
 * with the diamonds they carried (the Charter book's Nexus). A refusal leaves
 * nothing behind: every check runs before anything changes. A player's own act,
 * like {@link Found}.
 */
public record FoundKeep(PlayerId founder, String name, String banner, ChunkKey center, long paidDiamonds) implements Action {

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
        if (!ctx.admin() && !founder.equals(ctx.player())) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (ctx.state.membership(founder).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        Outcome named = Found.checkName(ctx, name);
        if (named.refused()) return named;
        Outcome cooled = Found.cooldownProblem(ctx, founder);
        if (cooled.refused()) return cooled;
        var problem = new LandGraph(ctx.state, ctx.knobs).keepProblemForNew(center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        if (paidDiamonds < ctx.knobs.keepPriceDiamonds) {
            return Outcome.refuse(LandReasons.NO_FUNDS.with("A keep costs " + ctx.knobs.keepPriceDiamonds + " diamonds and you carry fewer."));
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>(new Found(founder, name, Preset.LORDSHIP).apply(ctx));
        Organization org = ctx.state.orgNamed(name.strip()).orElseThrow();
        org.people.banner = banner;
        org.people.name = name.strip();
        org.ledger.add(Currency.DIAMOND, paidDiamonds);
        events.add(new TreasuryEvents.Deposited(ctx.now, org.id, Currency.DIAMOND, paidDiamonds, founder));
        org.ledger.take(Currency.DIAMOND, ctx.knobs.keepPriceDiamonds);
        events.addAll(new LandGraph(ctx.state, ctx.knobs).placeKeep(org.id, center, ctx.now));
        return events;
    }
}
