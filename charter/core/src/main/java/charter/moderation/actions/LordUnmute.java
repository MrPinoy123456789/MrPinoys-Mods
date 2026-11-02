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

import java.util.List;
import java.util.Optional;

/** A Lord lets one of their own citizens speak again before the mute runs out. */
public record LordUnmute(PlayerId player) implements Action {

    static final Reason NOT_MUTED = new Reason("not_muted", "That player is not muted.");

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
        return ctx.state.knownPlayer(player).filter(r -> r.mutedUntil > ctx.now).isPresent() ? Outcome.OK : Outcome.refuse(NOT_MUTED);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId kingdom = LordRules.kingdomOf(ctx.state, ctx.player()).orElseThrow();
        ctx.state.player(player).mutedUntil = 0;
        return List.of(new ModerationEvents.Unmuted(ctx.now, kingdom, ctx.player(), player));
    }
}
