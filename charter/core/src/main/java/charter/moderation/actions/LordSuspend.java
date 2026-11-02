package charter.moderation.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.moderation.LordRules;
import charter.moderation.ModerationEvents;
import charter.org.PlayerRecord;

import java.util.List;
import java.util.Optional;

/**
 * A Lord keeps one of their own citizens off the kingdom's land for a stated time, at most {@code lordSuspendMaxHours}.
 * It is the banishment push: the citizen is sent out of the land the moment they stand in it, and stays a member with
 * their rank. A second suspension replaces the first; it never adds to it.
 */
public record LordSuspend(PlayerId player, long durationMillis, String reason) implements Action {

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
        Outcome reasoned = LordRules.checkReason(reason);
        if (reasoned.refused()) return reasoned;
        return LordRules.checkDuration(durationMillis, LordRules.suspendCap(ctx.knobs));
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId kingdom = LordRules.kingdomOf(ctx.state, ctx.player()).orElseThrow();
        PlayerRecord record = ctx.state.player(player);
        record.suspendedUntil = ctx.now + durationMillis;
        record.suspendedFrom = kingdom;
        return List.of(new ModerationEvents.Suspended(ctx.now, kingdom, ctx.player(), player, durationMillis, LordRules.cleanReason(reason)));
    }
}
