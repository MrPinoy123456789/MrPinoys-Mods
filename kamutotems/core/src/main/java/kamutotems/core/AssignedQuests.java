package kamutotems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AssignedQuests {

    public static final int DEFAULT_MAX_ACTIVE = 3;

    /** Refusals are return values, never exceptions. */
    public record GrantResult(boolean ok, AssignedQuest quest, String message) {}

    private AssignedQuests() {}

    public static GrantResult grant(List<AssignedQuest> held, QuestDefinition def,
                                     String todayKey, int maxActive) {
        if (def == null) {
            return new GrantResult(false, null, "This scroll means nothing to you.");
        }

        List<AssignedQuest> safeHeld = held == null ? List.of() : held;

        int activeCount = 0;
        for (AssignedQuest q : safeHeld) {
            if (q.state() != QuestState.ACTIVE) {
                continue;
            }
            activeCount++;
            if (q.definitionId().equals(def.id())) {
                if (q.isExpired(todayKey, def)) {
                    return new GrantResult(false, null, "This errand is long past.");
                }
                if (!def.repeatable()) {
                    return new GrantResult(false, null, "You are already doing this.");
                }
            }
        }

        if (activeCount >= maxActive) {
            return new GrantResult(false, null,
                    "You are already carrying as many errands as you can remember.");
        }

        AssignedQuest fresh = new AssignedQuest(
                def.id(), todayKey, new QuestProgress(todayKey, List.of()), QuestState.ACTIVE);
        return new GrantResult(true, fresh, null);
    }

    /** First ACTIVE quest with a segment matching this event, or -1. */
    public static int findMatching(List<AssignedQuest> held, QuestCatalog catalog,
                                    String kind, Map<String, String> event) {
        if (held == null || catalog == null) {
            return -1;
        }
        for (int i = 0; i < held.size(); i++) {
            AssignedQuest q = held.get(i);
            if (q.state() != QuestState.ACTIVE) {
                continue;
            }
            QuestDefinition def = catalog.get(q.definitionId());
            if (def == null || def.segments() == null) {
                continue;
            }
            QuestChain shim = new QuestChain(q.grantedDateKey(), def.segments());
            for (int s = 0; s < def.segments().size(); s++) {
                QuestSegment seg = def.segments().get(s);
                if (kind != null && !kind.equals(seg.kind())) {
                    continue;
                }
                if (q.progress().segmentComplete(s, shim)) {
                    continue;
                }
                if (seg.matcher() != null && seg.matcher().matches(event)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Sweep expiries. Returns the list with states updated. */
    public static List<AssignedQuest> tick(List<AssignedQuest> held, QuestCatalog catalog,
                                            String todayKey) {
        if (held == null) {
            return List.of();
        }
        List<AssignedQuest> out = new ArrayList<>(held.size());
        for (AssignedQuest q : held) {
            if (q.state() != QuestState.ACTIVE) {
                out.add(q);
                continue;
            }
            QuestDefinition def = catalog == null ? null : catalog.get(q.definitionId());
            if (def == null) {
                out.add(q);
                continue;
            }
            if (q.complete(def)) {
                out.add(new AssignedQuest(q.definitionId(), q.grantedDateKey(), q.progress(), QuestState.COMPLETE));
            } else if (q.isExpired(todayKey, def)) {
                out.add(new AssignedQuest(q.definitionId(), q.grantedDateKey(), q.progress(), QuestState.EXPIRED));
            } else {
                out.add(q);
            }
        }
        return List.copyOf(out);
    }
}
