package chatdonkey.core;

/**
 * The editable half of an {@code events.json} entry: how often an event comes up
 * and how long it lasts.
 *
 * <p>Separate from {@link EventDefinition} purely so the file on disk reads
 * cleanly. The behavior id is the map key, so repeating it inside the object
 * would give an operator two places to write the same name and only one of them
 * would be obeyed:
 *
 * <pre>
 * "serenade": { "weight": 12, "minSeconds": 15, "maxSeconds": 30 }
 * </pre>
 */
public record EventTuning(int weight, int minSeconds, int maxSeconds) {

    public static EventTuning of(EventDefinition definition) {
        return new EventTuning(definition.weight(),
                definition.minSeconds(), definition.maxSeconds());
    }

    public EventDefinition toDefinition(String behaviorId) {
        return new EventDefinition(behaviorId, weight, minSeconds, maxSeconds);
    }
}
