package charter.moderation.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.moderation.LordRules;
import charter.moderation.ModerationEvents;
import charter.org.PlayerRecord;

import java.util.List;
import java.util.Optional;

/** A Lord ends the suspension of one of their own citizens early. */
public record LordUnsuspend(PlayerId player) implements Action {

    static final Reason NOT_SUSPENDED = new Reason("not_suspended", "That player is not suspended.");

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
        Outcome target = LordRules.checkTarget(ctx, player);
        if (target.refused()) return target;
        return ctx.state.knownPlayer(player).filter(r -> r.suspendedUntil > ctx.now).isPresent() ? Outcome.OK : Outcome.refuse(NOT_SUSPENDED);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId kingdom = LordRules.kingdomOf(ctx.state, ctx.player()).orElseThrow();
        PlayerRecord record = ctx.state.player(player);
        record.suspendedUntil = 0;
        record.suspendedFrom = null;
        return List.of(new ModerationEvents.Unsuspended(ctx.now, kingdom, ctx.player(), player));
    }
}
