package charter.org.actions;

import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Authority;
import charter.id.OrgId;
import charter.org.Ranks;

/** Checks every rank action shares. */
final class RankRules {

    private RankRules() {
    }

    /** OK for a kingdom that exists; ranks belong to top level kingdoms only. */
    static Outcome kingdom(ActionContext ctx, OrgId kingdom) {
        return Ranks.isKingdom(ctx.state, kingdom) ? Outcome.OK : Outcome.refuse(Ranks.NOT_A_KINGDOM);
    }

    /**
     * True when the change is made by the kingdom's ruler: an operator, the kingdom's own vote, or a player who holds a
     * seat in its decision office. Someone given the power to manage ranks is not, and so may not touch powers.
     */
    static boolean ruler(ActionContext ctx, OrgId kingdom) {
        if (ctx.admin()) return true;
        if (ctx.authority().kind() == Authority.Kind.SOURCE) return true;
        return ctx.player() != null && Ranks.isRuler(ctx.state, ctx.player(), kingdom);
    }

    /** True when the rank carries at least one power now. */
    static boolean powered(ActionContext ctx, OrgId kingdom, String rank) {
        return !Ranks.powers(ctx.state, ctx.now, kingdom, rank).isEmpty();
    }
}
