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
 * A Lord sends one of their own citizens off the server for a moment. It changes no state and bans nothing; the
 * citizen may log straight back in. The rules check and journal it; the fabric layer does the disconnecting.
 */
public record LordKick(PlayerId player, String reason) implements Action {

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
        return LordRules.checkReason(reason);
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        OrgId kingdom = LordRules.kingdomOf(ctx.state, ctx.player()).orElseThrow();
        return List.of(new ModerationEvents.Kicked(ctx.now, kingdom, ctx.player(), player, LordRules.cleanReason(reason)));
    }
}
