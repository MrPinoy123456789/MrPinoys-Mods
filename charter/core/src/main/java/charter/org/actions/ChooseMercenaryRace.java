package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgReasons;
import charter.org.Organization;
import charter.org.PlayerRecord;
import charter.time.Durations;

import java.util.List;
import java.util.Optional;

/**
 * A player with no organization takes the race of an ordinary kingdom and becomes a mercenary: they may wield that
 * race's weapons, but belong to no kingdom, hold no land and answer to no one. Kingdoms the operators have marked as
 * admin kingdoms cannot be chosen. A change of race waits out {@code mercChangeDays}. Joining an organization ends it.
 */
public record ChooseMercenaryRace(PlayerId player, OrgId kingdom) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    /** True when a mercenary may take the race of this organization. */
    public static boolean choosable(ActionContext ctx, OrgId id) {
        Organization org = ctx.state.org(id).orElse(null);
        return org != null && org.isTopLevel() && org.exists() && !org.npc && !org.adminKingdom;
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.admin() && !player.equals(ctx.player())) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (ctx.state.membership(player).isPresent()) return Outcome.refuse(OrgReasons.MERC_IN_ORG);
        if (!choosable(ctx, kingdom)) return Outcome.refuse(OrgReasons.MERC_NOT_A_RACE);
        PlayerRecord record = ctx.state.knownPlayer(player).orElse(null);
        if (record == null) return Outcome.OK;
        if (kingdom.equals(record.mercKingdom)) return Outcome.refuse(OrgReasons.MERC_SAME);
        if (!ctx.admin() && record.mercKingdom != null && record.mercChosenAt != 0
                && ctx.now - record.mercChosenAt < Durations.days(ctx.knobs.mercChangeDays)) {
            return Outcome.refuse(OrgReasons.MERC_COOLDOWN);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        PlayerRecord record = ctx.state.player(player);
        record.mercKingdom = kingdom;
        record.mercChosenAt = ctx.now;
        return List.of();
    }
}
