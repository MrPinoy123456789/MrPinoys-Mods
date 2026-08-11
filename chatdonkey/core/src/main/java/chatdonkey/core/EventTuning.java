package chatdonkey.core;

/**
 * The editable half of an {@code events.json} entry: how often an event comes up,
 * how long it lasts, and — for a demand event — what it wants.
 *
 * <p>Separate from {@link EventDefinition} purely so the file on disk reads
 * cleanly. The behavior id is the map key, so repeating it inside the object
 * would give an operator two places to write the same name and only one of them
 * would be obeyed:
 *
 * <pre>
 * "serenade":   { "weight": 12, "minSeconds": 15, "maxSeconds": 30 }
 * "foodcritic": { "weight": 20, "minSeconds": 30, "maxSeconds": 45,
 *                 "wants": "minecraft:carrot",
 *                 "wantsPremium": "minecraft:golden_carrot" }
 * </pre>
 *
 * <p>An entry with {@code wants} <em>is</em> a demand event — the donkey follows
 * you asking for that item, takes it and leaves happy, and pays the golden tier
 * for {@code wantsPremium}. No code is involved, so adding one is an edit here
 * plus a set of lines.
 */
public record EventTuning(int weight, int minSeconds, int maxSeconds,
                          String wants, String wantsPremium,
                          java.util.List<String> duplicates, Integer multiplier) {

    public EventTuning(int weight, int minSeconds, int maxSeconds) {
        this(weight, minSeconds, maxSeconds, null, null, null, null);
    }

    /**
     * Fields that do not apply are written as {@code null} so Gson omits them —
     * an ordinary event's entry should not carry a puzzling {@code "multiplier": 0}
     * that does nothing.
     */
    public static EventTuning of(EventDefinition definition) {
        Demand demand = definition.demandOrNone();
        boolean dupes = demand.duplicatesAnything();
        return new EventTuning(definition.weight(),
                definition.minSeconds(), definition.maxSeconds(),
                demand.ordinaryItem(), demand.premiumItem(),
                dupes ? demand.duplicates() : null,
                dupes ? demand.multiplier() : null);
    }

    public EventDefinition toDefinition(String behaviorId) {
        return new EventDefinition(behaviorId, weight, minSeconds, maxSeconds,
                new Demand(wants, wantsPremium, duplicates,
                        multiplier == null ? 0 : multiplier));
    }
}
