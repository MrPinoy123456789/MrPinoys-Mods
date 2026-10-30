package charter.siege.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.siege.SiegeEvents;
import charter.siege.WarBook;
import charter.time.Durations;

import java.util.List;
import java.util.Optional;

/**
 * A nation declares war with one goal: to capture another nation's outpost (protected keeps, O1). The outpost is not
 * touched until the notice has run out. The declaring nation must hold a raised outpost of its own.
 */
public record DeclareWar(OrgId org, CoreId outpost) implements Action {

    public static final Reason NOT_A_NATION = new Reason("war_not_a_nation", "Only a nation declares war, not a sub-faction.");
    public static final Reason NO_TARGET = new Reason("war_no_target", "There is no outpost like that to name.");
    public static final Reason NOT_RAISED = new Reason("war_not_raised", "That outpost is still being raised.");
    public static final Reason OWN_OUTPOST = new Reason("war_own_outpost", "That outpost belongs to your own nation.");
    public static final Reason ADMIN_KINGDOM = new Reason("war_admin_kingdom", "That kingdom is kept by the server and cannot be warred on.");
    public static final Reason RAIDERS = new Reason("war_raiders", "The raiders are not at war with anyone. Fight them in the field.");
    public static final Reason NO_OUTPOST_OF_YOURS = new Reason("war_no_outpost", "You need a raised outpost of your own before you declare war.");
    public static final Reason ALREADY_NAMED = new Reason("war_already_named", "A war already names that outpost.");
    public static final Reason TOO_MANY = new Reason("war_too_many", "Your nation already has all the wars it may hold at once.");
    public static final Reason TRUCE = new Reason("war_truce", "There was a war between your nations too recently. The truce has not run out.");

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.DECLARE_WAR);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        var attacker = ctx.state.org(org);
        if (attacker.isEmpty() || !attacker.get().isTopLevel()) return Outcome.refuse(NOT_A_NATION);
        Core target = ctx.state.core(outpost).orElse(null);
        if (target == null || target.isKeep()) return Outcome.refuse(NO_TARGET);
        OrgId defender = ctx.state.root(target.owner);
        if (defender.equals(org)) return Outcome.refuse(OWN_OUTPOST);
        if (ctx.state.org(defender).map(o -> o.npc).orElse(false)) return Outcome.refuse(RAIDERS);
        if (ctx.state.org(defender).map(o -> o.adminKingdom).orElse(false)) return Outcome.refuse(ADMIN_KINGDOM);
        if (!target.activeCamp()) return Outcome.refuse(NOT_RAISED);
        boolean mine = ctx.state.cores().stream().anyMatch(c -> !c.isKeep() && c.activeCamp() && ctx.state.root(c.owner).equals(org));
        if (!mine) return Outcome.refuse(NO_OUTPOST_OF_YOURS);
        if (ctx.state.wars.against(outpost).isPresent()) return Outcome.refuse(ALREADY_NAMED);
        if (ctx.state.wars.openBy(org) >= ctx.knobs.warGoalsPerWar) return Outcome.refuse(TOO_MANY);
        if (ctx.state.wars.truce(org, defender, ctx.state.siegeClock.now())) return Outcome.refuse(TRUCE);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Core target = ctx.state.core(outpost).orElseThrow();
        OrgId defender = ctx.state.root(target.owner);
        long now = ctx.state.siegeClock.now();
        WarBook.Goal goal = new WarBook.Goal();
        goal.id = ctx.state.wars.nextId++;
        goal.attacker = org;
        goal.defender = defender;
        goal.outpost = outpost;
        goal.declaredAt = now;
        goal.opensAt = now + Durations.hours(ctx.knobs.warGoalNoticeHours);
        goal.expiresAt = now + Durations.hours(ctx.knobs.warGoalHours);
        ctx.state.wars.goals.add(goal);
        return List.of(new SiegeEvents.WarDeclared(ctx.now, now, goal.id, org, defender, outpost, goal.opensAt));
    }
}
