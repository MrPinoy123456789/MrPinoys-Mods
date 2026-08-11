package chatdonkey.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The weighted event pool from {@code events.json} (SPEC.md section 4).
 *
 * <p>Entries naming a behavior this build does not have are dropped with a
 * warning rather than treated as fatal: an operator who downgrades the mod, or
 * who mistypes {@code roadblok}, should lose that one event and keep the rest.
 * A pool that ends up completely empty falls back to the built-in defaults,
 * because a chat donkey mod with no events is just a config file.
 */
public final class EventPool {

    private final List<EventDefinition> entries;
    private final int totalWeight;

    private EventPool(List<EventDefinition> entries) {
        this.entries = List.copyOf(entries);
        int sum = 0;
        for (EventDefinition entry : entries) {
            sum += entry.weight();
        }
        this.totalWeight = sum;
    }

    /**
     * Builds a pool, dropping unknown or disabled entries.
     *
     * @param known    behavior ids this build actually implements
     * @param onWarning called once per dropped entry
     */
    public static EventPool of(List<EventDefinition> definitions, List<String> known,
                               java.util.function.Consumer<String> onWarning) {
        List<EventDefinition> usable = new ArrayList<>();
        if (definitions != null) {
            for (EventDefinition raw : definitions) {
                if (raw == null || raw.behavior() == null) {
                    continue;
                }
                EventDefinition entry = raw.sanitised();
                if (!known.contains(entry.behavior())) {
                    onWarning.accept("Unknown event behavior '" + entry.behavior()
                            + "' in events.json -- skipping it. Known: " + known);
                    continue;
                }
                if (!entry.isEnabled()) {
                    continue;
                }
                usable.add(entry);
            }
        }
        return new EventPool(usable);
    }

    /** The stock pool: every behavior at its spec'd duration, evenly weighted. */
    public static EventPool defaults() {
        List<EventDefinition> list = new ArrayList<>();
        for (String id : Behaviors.ids()) {
            DonkeyBehavior behavior = Behaviors.byId(id);
            list.add(new EventDefinition(id, defaultWeightFor(id),
                    behavior.minDurationSeconds(), behavior.maxDurationSeconds()));
        }
        return new EventPool(list);
    }

    /**
     * Starting weights.
     *
     * <p>Not uniform, and deliberately so. Lecture is the plainest and wears
     * best, so it is the most common. False Alarm is the loudest joke with the
     * least variation, so it is the rarest -- a gag that fires as often as the
     * others stops landing fastest.
     */
    private static int defaultWeightFor(String id) {
        return switch (id) {
            case "lecture" -> 25;
            case "roadblock" -> 20;
            case "foodcritic" -> 20;
            case "clingy" -> 15;
            case "serenade" -> 12;
            case "falsealarm" -> 8;
            default -> 10;
        };
    }

    public boolean isEmpty() {
        return entries.isEmpty() || totalWeight <= 0;
    }

    public int size() {
        return entries.size();
    }

    public List<EventDefinition> entries() {
        return entries;
    }

    public int totalWeight() {
        return totalWeight;
    }

    /** @return a weighted random entry, or {@code null} if the pool is empty */
    public EventDefinition pick(Random random) {
        if (isEmpty()) {
            return null;
        }
        int roll = random.nextInt(totalWeight);
        for (EventDefinition entry : entries) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        // Unreachable while totalWeight is the true sum; returning the last
        // entry is a safer tail than throwing on a rounding surprise.
        return entries.get(entries.size() - 1);
    }

    /** Rolls a duration in ticks for one entry. */
    public static int durationTicks(EventDefinition entry, Random random) {
        int span = entry.maxSeconds() - entry.minSeconds() + 1;
        return 20 * (entry.minSeconds() + random.nextInt(Math.max(1, span)));
    }

    /** Serialisable form for {@code events.json}: behavior id to its tuning. */
    public Map<String, EventTuning> asMap() {
        Map<String, EventTuning> map = new LinkedHashMap<>();
        for (EventDefinition entry : entries) {
            map.put(entry.behavior(), EventTuning.of(entry));
        }
        return map;
    }
}
