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

    /** The root grants of the decision makers office: every routine power, the spend limit included. */
    public static void addLordGrants(CharterState state, charter.id.OrgId org, charter.id.OfficeId lord, Knobs knobs, long now) {
        Holder self = new Holder.OfOrg(org);
        for (Power power : LORD_POWERS) {
            GrantScope scope = power == Power.SPEND
                    ? GrantScope.spendUpTo(knobs.lordSpendLimitCobblestone, knobs.lordSpendLimitDiamonds)
                    : GrantScope.ANY;
            state.addGrant(new Grant(state.ids.grant(), org, power, new Holder.OfOffice(lord), self,
                    null, scope, true, now, 0));
        }
    }
}
