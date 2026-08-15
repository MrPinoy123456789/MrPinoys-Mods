package kamutotems.core;

import java.util.Map;

public record EventMatcher(
        String eventId,
        Map<String, String> predicate,
        int requiredCount) {

    public boolean matches(Map<String, String> event) {
        if (event == null) {
            return false;
        }
        if (eventId != null && !eventId.isEmpty()) {
            String id = event.get("eventId");
            if (id != null && !id.equals(eventId)) {
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
