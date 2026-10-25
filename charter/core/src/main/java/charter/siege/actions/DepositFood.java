package charter.siege.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.siege.Supply;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A player puts food into a granary or camp store. Members of the owning faction
 * may; anyone else needs the deposit power by grant (sellswords and allies, D7). Under
 * siege only what the carrier brought in through an open side is accepted; the
 * accepted items are in the {@code FoodDeposited} event, and the fabric side
 * moves exactly those.
 */
public record DepositFood(OrgId owner, CoreId site, String carrier, Map<String, Integer> offered) implements Action {

    public static final Reason NO_SITE = new Reason("no_such_store", "There is no granary or camp store there.");
    public static final Reason NOTHING_IN = new Reason("not_carried_in",
            "Under siege, food goes in only if it was carried in through an open side.");

    @Override
    public OrgId actingFor() {
        return owner;
    }

    public static final Reason NOT_YOURS = new Reason("not_your_store",
            "Only members, or someone they have granted the right, can put food in here.");

    /** Members deposit by right; anyone else needs the deposit power, so the engine does not require it. */
    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (ctx.state.core(site).map(c -> !c.owner.equals(owner)).orElse(true)) return Outcome.refuse(NO_SITE);
        PlayerId player = ctx.player();
        if (player != null) {
            boolean member = ctx.state.membership(player)
                    .map(m -> ctx.state.root(m.org()).equals(ctx.state.root(owner))).orElse(false);
            boolean granted = ctx.resolver().resolve(new Who.AsPlayer(player), owner, Power.GRANARY_DEPOSIT,
                    ScopeNeed.NONE, false).ok();
            if (!member && !granted) return Outcome.refuse(NOT_YOURS);
        }
        if (supply(ctx).preview(carrier, site, offered).isEmpty()) return Outcome.refuse(NOTHING_IN);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        supply(ctx).deposit(carrier, site, offered, events);
        return events;
    }

    private static Supply supply(ActionContext ctx) {
        return new Supply(ctx.engine, ctx.state.siege.food);
    }
}
