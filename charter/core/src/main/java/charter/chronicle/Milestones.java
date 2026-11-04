package charter.chronicle;

import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.org.OrgEvents;
import charter.siege.SiegeEvents;
import charter.state.CharterState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives {@link MilestoneEvents} from a batch of events the engine just recorded.
 * Pure: it reads the state and the history and returns what to record, so the
 * engine decides the actor. Every milestone fires once per kingdom; the history
 * itself is the memory, so a restart never repeats one.
 */
public final class Milestones {

    public static final int[] MEMBER_THRESHOLDS = {5, 10, 20};
    public static final String FIRST_WAR = "war";
    public static final String FIRST_OUTPOST_TAKEN = "outpost taken";
    public static final String FIRST_OUTPOST_HELD = "outpost held";

    private Milestones() {
    }

    /** The milestones these events bring about, in the order they should be recorded. */
    public static List<HistoryEvent> after(CharterState state, List<HistoryEvent> events) {
        List<HistoryEvent> out = new ArrayList<>();
        Set<OrgId> grew = new LinkedHashSet<>();
        for (HistoryEvent event : events) {
            if (event instanceof OrgEvents.Joined e) grew.add(e.org());
            else if (event instanceof OrgEvents.Founded e) grew.add(e.org());
            else if (event instanceof OrgEvents.GroupBrokeAway e) grew.add(e.org());
            else if (event instanceof SiegeEvents.WarDeclared e) first(state, out, e.at(), e.attacker(), FIRST_WAR);
            else if (event instanceof SiegeEvents.OutpostCaptured e) first(state, out, e.at(), e.attacker(), FIRST_OUTPOST_TAKEN);
            else if (event instanceof SiegeEvents.WarLapsed e) first(state, out, e.at(), e.defender(), FIRST_OUTPOST_HELD);
        }
        for (OrgId org : grew) {
            if (state.org(org).map(o -> o.npc).orElse(true)) continue;
            int members = state.members(org).size();
            long at = events.getLast().at();
            for (int threshold : MEMBER_THRESHOLDS) {
                if (members < threshold || reached(state.history, org, threshold)) continue;
                if (out.stream().anyMatch(e -> e instanceof MilestoneEvents.MembersReached m && m.org().equals(org) && m.count() == threshold)) continue;
                out.add(new MilestoneEvents.MembersReached(at, org, threshold));
            }
        }
        return out;
    }

    private static void first(CharterState state, List<HistoryEvent> out, long at, OrgId org, String what) {
        if (state.org(org).map(o -> o.npc).orElse(true)) return;
        for (MilestoneEvents.FirstOf f : state.history.of(MilestoneEvents.FirstOf.class)) {
            if (f.org().equals(org) && f.what().equals(what)) return;
        }
        for (HistoryEvent e : out) {
            if (e instanceof MilestoneEvents.FirstOf f && f.org().equals(org) && f.what().equals(what)) return;
        }
        out.add(new MilestoneEvents.FirstOf(at, org, what));
    }

    private static boolean reached(History history, OrgId org, int threshold) {
        for (MilestoneEvents.MembersReached m : history.of(MilestoneEvents.MembersReached.class)) {
            if (m.org().equals(org) && m.count() == threshold) return true;
        }
        return false;
    }
}
