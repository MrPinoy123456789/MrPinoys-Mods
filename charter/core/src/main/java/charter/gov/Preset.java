package charter.gov;

import charter.config.Knobs;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.PlayerId;
import charter.org.Organization;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Starting governance for a new organization (DESIGN.md §6.3). A preset only
 * fills things in at founding: offices and root grants, all of which the members
 * can later change by vote. Nothing branches on the preset afterwards.
 */
public enum Preset {

    /** Every eligible member votes on what matters. Officers are created and filled by vote. */
    COMMUNE,

    /**
     * One Lord office, reserved (only a vote fills or empties it) and emptied if the
     * Lord is absent too long. The Lord holds every routine power, passable on, and
     * spends up to a limit per action. The members keep the reserved powers:
     * amending the charter and dissolving, and removing the Lord.
     */
    LORDSHIP;

    public static final String LORD = "Lord";

    /** Routine powers a Lord holds. AMEND and DISSOLVE stay with the members. */
    public static final Set<Power> LORD_POWERS = EnumSet.complementOf(EnumSet.of(Power.AMEND, Power.DISSOLVE));

    public List<HistoryEvent> apply(CharterState state, Organization org, PlayerId founder, Knobs knobs, long now) {
        List<HistoryEvent> events = new ArrayList<>();
        // Every faction has the decision makers office: one seat for a Lordship, none for a Commune (S1).
        int seats = this == LORDSHIP ? 1 : 0;
        Office lord = new Office(state.ids.office(), org.id, LORD, true, true, seats);
        state.addOffice(lord);
        org.governance.deciders = lord.id;
        events.add(new GovEvents.OfficeCreated(now, lord.id, org.id, lord.name, true));
        if (seats > 0) {
            lord.seat(founder, now);
            events.add(new GovEvents.OfficeFilled(now, lord.id, org.id, founder));
        }
        addLordGrants(state, org.id, lord.id, knobs, now);
        return events;
    }

    /** Routine powers added after kingdoms were already founded; a Lord of an older kingdom is given them on load. */
    public static final Set<Power> LATER_LORD_POWERS = EnumSet.of(Power.MANAGE_RANKS);

    /**
     * Gives every decision makers office that has the preset's root grants (a Lord's, or a Commune's office with no
     * seats) a root grant of each {@link #LATER_LORD_POWERS} it never had, passable on, as founding would give it today.
     * A power the office once had and lost (revoked) is not given again. Idempotent; returns the grants added.
     */
    public static List<Grant> addLaterLordGrants(CharterState state, long now) {
        List<Grant> added = new ArrayList<>();
        for (Organization org : List.copyOf(state.orgs())) {
            if (org.npc || org.governance.deciders == null) continue;
            charter.id.OfficeId office = org.governance.deciders;
            if (state.office(office).isEmpty()) continue;
            Holder seat = new Holder.OfOffice(office);
            boolean preset = state.grants().stream().anyMatch(g -> g.subject.equals(org.id) && g.isRoot()
                    && g.recipient.equals(seat) && g.power == Power.MANAGE_MEMBERS);
            if (!preset) continue;
            for (Power power : LATER_LORD_POWERS) {
                boolean ever = state.grants().stream().anyMatch(g -> g.subject.equals(org.id) && g.power == power && g.recipient.equals(seat));
                if (ever) continue;
                Grant grant = new Grant(state.ids.grant(), org.id, power, seat, new Holder.OfOrg(org.id), null, GrantScope.ANY, true, now, 0);
                state.addGrant(grant);
                added.add(grant);
            }
        }
        return added;
    }

    /** The root grants of the decision makers office: every routine power, the spend limit included. */
    public static void addLordGrants(CharterState state, charter.id.OrgId org, charter.id.OfficeId lord, Knobs knobs, long now) {
        Holder self = new Holder.OfOrg(org);
        for (Power power : LORD_POWERS) {
            GrantScope scope = power == Power.SPEND && knobs.lordSpendLimitMarks >= 0
                    ? GrantScope.spendUpTo(knobs.lordSpendLimitMarks)
                    : GrantScope.ANY;
            state.addGrant(new Grant(state.ids.grant(), org, power, new Holder.OfOffice(lord), self,
                    null, scope, true, now, 0));
        }
    }
}
