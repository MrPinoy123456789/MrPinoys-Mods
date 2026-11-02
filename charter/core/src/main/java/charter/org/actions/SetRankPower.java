package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Authority;
import charter.grant.AuthorityResolver;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.RankEvents;
import charter.org.Ranks;

import java.util.List;
import java.util.Optional;

/**
 * The Lord gives a rank a power, or takes it away. Giving makes a grant to the rank, derived from the Lord's own
 * passable grant, so a rank never carries more than the Lord could pass on and loses it the moment the Lord does;
 * the grant is never passable itself. Taking away revokes every grant of that power to the rank.
 *
 * <p>To give, the engine checks the power itself, passable: whoever sets it must hold it to pass on. On top of that only the
 * kingdom's ruler (the Lord, the kingdom's own vote, or an operator) may do it, never someone given the power to
 * manage ranks; the top rank carries nothing extra, and {@link Ranks#FORBIDDEN} powers are never given to a rank.
 */
public record SetRankPower(OrgId kingdom, String rank, Power granted, boolean on) implements Action {

    @Override
    public OrgId actingFor() {
        return kingdom;
    }

    /** Giving needs the power itself, passable; taking away needs only the power to manage ranks (and the Lord). */
    @Override
    public Optional<Power> power() {
        return Optional.of(on ? granted : Power.MANAGE_RANKS);
    }

    @Override
    public boolean needsRedelegable() {
        return on;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Outcome k = RankRules.kingdom(ctx, kingdom);
        if (k.refused()) return k;
        if (ctx.state.ranks.rank(kingdom, rank).isEmpty()) return Outcome.refuse(Ranks.NO_RANK);
        if (rank.equals(Ranks.TOP)) return Outcome.refuse(Ranks.TOP_FIXED);
        if (Ranks.FORBIDDEN.contains(granted)) return Outcome.refuse(Ranks.FORBIDDEN_POWER);
        if (!RankRules.ruler(ctx, kingdom)) return Outcome.refuse(Ranks.NOT_LORD);
        boolean has = Ranks.powers(ctx.state, ctx.now, kingdom, rank).contains(granted);
        if (on && has) return Outcome.refuse(Ranks.UNCHANGED);
        if (!on && Ranks.rankGrants(ctx.state, kingdom, rank).stream().noneMatch(g -> g.power == granted)) {
            return Outcome.refuse(Ranks.UNCHANGED);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Ranks ranks = ctx.state.ranks;
        ranks.edit(kingdom);
        String name = ranks.rank(kingdom, rank).orElseThrow().name();
        if (!on) {
            for (Grant grant : Ranks.rankGrants(ctx.state, kingdom, rank)) {
                if (grant.power == granted) grant.revoke(ctx.now);
            }
            return List.of(new RankEvents.RankPowerTaken(ctx.now, kingdom, rank, name, granted));
        }
        Authority authority = ctx.authority();
        boolean derived = authority.kind() == Authority.Kind.GRANT;
        Grant parent = derived ? authority.via() : null;
        Grant grant = new Grant(ctx.state.ids.grant(), kingdom, granted, new Holder.OfRank(kingdom, rank),
                derived ? parent.recipient : new Holder.OfOrg(kingdom), derived ? parent.id : null,
                derived ? parent.scope : GrantScope.ANY, false, ctx.now, 0);
        ctx.state.addGrant(grant);
        // A grant that would not hold (its chain broke in this very instant) is not left behind.
        if (!new AuthorityResolver(ctx.state, ctx.now).valid(grant)) grant.revoke(ctx.now);
        return List.of(new RankEvents.RankPowerGiven(ctx.now, kingdom, rank, name, granted, grant.id));
    }
}
