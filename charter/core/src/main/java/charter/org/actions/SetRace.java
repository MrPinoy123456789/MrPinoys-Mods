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

import java.util.List;
import java.util.Optional;

/**
 * An operator sets a player's race to a kingdom's, or removes it ({@code kingdom} null). It works on members and on
 * players with no organization, any kingdom may be named (admin kingdoms too), and nothing else about the player
 * changes. A player with no race can take one by joining a kingdom or, with no organization, by choosing.
 */
public record SetRace(PlayerId player, OrgId kingdom) implements Action {

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
        if (!ctx.admin()) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (kingdom != null) {
            Organization org = ctx.state.org(kingdom).orElse(null);
            if (org == null || !org.isTopLevel() || !org.exists() || org.npc) return Outcome.refuse(OrgReasons.MERC_NOT_A_RACE);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        PlayerRecord record = ctx.state.player(player);
        record.race = kingdom;
        record.noRace = kingdom == null;
        return List.of();
    }
}
