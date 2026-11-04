package charter.chronicle;

import charter.gov.GovEvents;
import charter.history.HistoryEvent;
import charter.org.OrgEvents;
import charter.siege.SiegeEvents;

import java.util.Optional;

/**
 * The standing story titles (docs/2026-11-04-chronicle-design.md 3.3): a defection
 * is always The Betrayal, so the next one lands as a sequel. The renderer appends a
 * title to the line and the headline, and the feed carries it as a hint for whoever
 * writes the Chronicle on Discord. The table is fixed; editorial judgment about
 * new titles lives with the scribe, not here.
 */
public final class Stories {

    public static final String BETRAYAL = "The Betrayal";
    public static final String CROWNING = "The Crowning";
    public static final String FORMER_KING = "Former King";
    public static final String FIRST_BLOOD = "First Blood";
    public static final String HELD_THE_LINE = "Held the Line";
    public static final String LIGHT_IN_THE_WINDOW = "A Light in the Window";
    public static final String FULL_HOUSE = "Full House";

    private Stories() {
    }

    public static Optional<String> of(HistoryEvent event, NameLookup names) {
        if (event instanceof OrgEvents.Joined e && e.from() != null && !names.isNpc(e.from()) && !names.isNpc(e.org())) {
            return Optional.of(BETRAYAL);
        }
        if (event instanceof GovEvents.OfficeFilled e && names.isDeciders(e.office())) return Optional.of(CROWNING);
        if (event instanceof GovEvents.OfficeVacated e && names.isDeciders(e.office())) return Optional.of(FORMER_KING);
        if (event instanceof SiegeEvents.WarLapsed) return Optional.of(HELD_THE_LINE);
        if (event instanceof MilestoneEvents.MembersReached e) {
            if (e.count() == 5) return Optional.of(LIGHT_IN_THE_WINDOW);
            if (e.count() == 10) return Optional.of(FULL_HOUSE);
        }
        if (event instanceof MilestoneEvents.FirstOf e && e.what().equals(Milestones.FIRST_WAR)) return Optional.of(FIRST_BLOOD);
        return Optional.empty();
    }
}
