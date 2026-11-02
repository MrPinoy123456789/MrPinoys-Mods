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

import java.util.List;
import java.util.Optional;

/**
 * A Lord mutes one of their own citizens for a stated time, at most {@code lordMuteMaxMinutes}. A second mute
 * replaces the first; it never adds to it. Runs for a player only: operators have their own tools.
 */
public record LordMute(PlayerId player, long durationMillis, String reason) implements Action {

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
        return LordRules.checkDuration(durationMillis, LordRules.muteCap(ctx.knobs));
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId kingdom = LordRules.kingdomOf(ctx.state, ctx.player()).orElseThrow();
        ctx.state.player(player).mutedUntil = ctx.now + durationMillis;
        return List.of(new ModerationEvents.Muted(ctx.now, kingdom, ctx.player(), player, durationMillis, LordRules.cleanReason(reason)));
    }
}
