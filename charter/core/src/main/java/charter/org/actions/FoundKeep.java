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
import charter.org.Breakaway;
import charter.org.OrgEvents;
import charter.org.OrgStatus;
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
        // A member may found a keep only as the starter of a group breaking away from a faction with no sub-factions.
        Optional<Breakaway> group = ctx.state.groupStartedBy(founder);
        if (ctx.state.membership(founder).isPresent() && group.isEmpty()) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        if (group.isPresent() && movers(ctx, group.get()).size() < 2) return Outcome.refuse(OrgReasons.NEEDS_GROUP);
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

    /** The group's members who are still in the faction they gather to leave. */
    private List<PlayerId> movers(ActionContext ctx, Breakaway group) {
        return group.members().stream().filter(p -> ctx.state.isMember(p, group.org())).toList();
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Optional<Breakaway> group = ctx.state.groupStartedBy(founder);
        List<PlayerId> others = group.isEmpty() ? List.of()
                : movers(ctx, group.get()).stream().filter(p -> !p.equals(founder)).toList();
        if (group.isPresent()) ctx.state.leave(founder);
        List<HistoryEvent> events = new ArrayList<>(new Found(founder, name, Preset.LORDSHIP).apply(ctx));
        Organization org = ctx.state.orgNamed(name.strip()).orElseThrow();
        org.people.banner = banner;
        org.people.name = name.strip();
        org.ledger.add(Currency.DIAMOND, paidDiamonds);
        events.add(new TreasuryEvents.Deposited(ctx.now, org.id, Currency.DIAMOND, paidDiamonds, founder));
        org.ledger.take(Currency.DIAMOND, ctx.knobs.keepPriceDiamonds);
        events.addAll(new LandGraph(ctx.state, ctx.knobs).placeKeep(org.id, center, ctx.now));
        if (group.isPresent()) {
            // Those who came along move into the new Lordship.
            ctx.state.removeBreakaway(group.get());
            for (PlayerId p : others) {
                ctx.state.leave(p);
                ctx.state.join(p, org.id, ctx.now);
                ctx.state.player(p).lastFoundedAt = ctx.now;
                events.add(new OrgEvents.Joined(ctx.now, org.id, p, group.get().org()));
            }
            ctx.state.setStatus(org.id, OrgStatus.ACTIVE);
            events.add(new OrgEvents.Activated(ctx.now, org.id));
            events.add(new OrgEvents.GroupBrokeAway(ctx.now, org.id, group.get().org(), others.size() + 1));
        }
        return events;
    }
}
