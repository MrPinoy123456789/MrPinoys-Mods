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
        if (this == LORDSHIP) {
            Office lord = new Office(state.ids.office(), org.id, LORD, true, true);
            state.addOffice(lord);
            lord.fill(founder, now);
            events.add(new GovEvents.OfficeCreated(now, lord.id, org.id, lord.name, true));
            events.add(new GovEvents.OfficeFilled(now, lord.id, org.id, founder));
            Holder self = new Holder.OfOrg(org.id);
            for (Power power : LORD_POWERS) {
                GrantScope scope = power == Power.SPEND
                        ? GrantScope.spendUpTo(knobs.lordSpendLimitCobblestone, knobs.lordSpendLimitDiamonds)
                        : GrantScope.ANY;
                state.addGrant(new Grant(state.ids.grant(), org.id, power, new Holder.OfOffice(lord.id), self,
                        null, scope, true, now, 0));
            }
        }
        return events;
    }
}
