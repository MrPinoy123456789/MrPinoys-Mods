package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgReasons;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An operator gives a kingdom a second Lord. The Lord office becomes two seats in which either Lord may act alone, the
 * sitting Lord keeps their seat, and the player joins the kingdom if they are in none and takes the other. With the seat
 * empty the player simply becomes its Lord. Two Lords rule as equals: each holds every Lord power, and neither can remove
 * the other or change the seats alone (those need a majority of the seats, both of them). Operators only; players never
 * choose this, and there are never more than two Lords.
 */
public record AppointCoLeader(OrgId org, PlayerId player) implements Action {

    static final Reason NOT_A_LORDSHIP = new Reason("not_a_lordship", "Only a lordship can have two Lords.");
    static final Reason ALREADY_LORD = new Reason("already_lord", "That player is already a Lord of this kingdom.");
    static final Reason TWO_ALREADY = new Reason("two_lords_already",
            "This kingdom already has two Lords. Remove one first; there are never three.");

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
        Organization o = ctx.state.org(org).orElse(null);
        if (o == null || !o.exists()) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (!o.isTopLevel()) return Outcome.refuse(AppointLeader.NOT_A_KINGDOM);
        if (o.governance.preset != Preset.LORDSHIP || o.governance.deciders == null) return Outcome.refuse(NOT_A_LORDSHIP);
        var member = ctx.state.membership(player);
        if (member.isPresent() && !member.get().org().equals(org)) return Outcome.refuse(AppointLeader.ELSEWHERE);
        Office lord = ctx.state.office(o.governance.deciders).orElse(null);
        if (lord == null) return Outcome.refuse(NOT_A_LORDSHIP);
        if (lord.holds(player)) return Outcome.refuse(ALREADY_LORD);
        if (lord.holders().size() >= 2) return Outcome.refuse(TWO_ALREADY);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        if (!ctx.state.isMember(player, org)) ctx.state.join(player, org, ctx.now);
        Office lord = ctx.state.office(ctx.state.org(org).orElseThrow().governance.deciders).orElseThrow();
        int seats = lord.vacant() ? 1 : 2;
        if (lord.seats() != seats || lord.deciding() != Office.Deciding.ALONE) {
            lord.setSeats(seats);
            lord.setDeciding(Office.Deciding.ALONE);
            events.add(new GovEvents.SeatsChanged(ctx.now, lord.id, org, seats, Office.Deciding.ALONE));
        }
        lord.seat(player, ctx.now);
        events.add(new GovEvents.OfficeFilled(ctx.now, lord.id, org, player));
        return events;
    }
}
