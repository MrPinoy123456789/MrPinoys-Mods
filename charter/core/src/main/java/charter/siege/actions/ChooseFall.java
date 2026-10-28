package charter.siege.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.siege.FallChoice;
import charter.siege.Falls;
import charter.siege.SiegeBook;

import java.util.List;
import java.util.Optional;

/**
 * The besieger chooses to raze or occupy a fallen keep (DESIGN.md §10.8). Needs
 * the choose-fall power over the besieging organization. If nobody chooses in
 * time, the sim razes it.
 */
public record ChooseFall(OrgId besieger, CoreId keep, FallChoice choice) implements Action {

    public static final Reason RAZE_ONLY = new Reason("raze_only", "The raiders pay no tribute and sign no treaty. Raze the stronghold.");
    public static final Reason NOT_FALLEN = new Reason("not_fallen", "That keep has not fallen to you.");

    @Override
    public OrgId actingFor() {
        return besieger;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.CHOOSE_FALL);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        SiegeBook.PendingFall pending = ctx.state.siege.falls.get(keep);
        if (pending == null || !pending.besieger().equals(besieger)) return Outcome.refuse(NOT_FALLEN);
        // A stronghold of the raiders is only razed: no tribute, no treaty (NPC strongholds, N3).
        boolean npc = ctx.state.core(keep).flatMap(k -> ctx.state.org(k.owner)).map(o -> o.npc).orElse(false);
        if (npc && choice == FallChoice.OCCUPY) return Outcome.refuse(RAZE_ONLY);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        return Falls.resolve(ctx.state, ctx.knobs, keep, choice, ctx.now);
    }
}
