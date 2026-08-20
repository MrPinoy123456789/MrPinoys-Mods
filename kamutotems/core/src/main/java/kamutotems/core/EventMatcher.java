package kamutotems.core;

import java.util.Map;

/** Matches a quest event identifier and required key/value predicates. */
public record EventMatcher(
        String eventId,
        Map<String, String> predicate,
        int requiredCount) {

    /** Returns whether the event satisfies the optional id and every configured predicate. */
    public boolean matches(Map<String, String> event) {
        if (event == null) {
            return false;
        }
        if (eventId != null && !eventId.isEmpty()) {
            String id = event.get("eventId");
            if (!eventId.equals(id)) {
                return false;
            }
        }
        for (Map.Entry<String, String> e : predicate.entrySet()) {
            String v = event.get(e.getKey());
            if (v == null || !v.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }
}
