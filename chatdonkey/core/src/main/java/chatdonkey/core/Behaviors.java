package chatdonkey.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The behavior pool (SPEC.md section 4).
 *
 * <p>Two kinds of behavior live here:
 *
 * <ul>
 *   <li><b>Built-ins</b> — the movement events. Each is a class, because what
 *       they do is code: circling, re-planting, teleporting.</li>
 *   <li><b>Demand events</b> — the Food Critic shape. These are <em>data</em>.
 *       Any {@code events.json} entry naming an item it {@code wants} becomes a
 *       {@link DemandBehavior} with no code at all, so a new one is a config
 *       edit plus some lines.</li>
 * </ul>
 *
 * <p>Behaviors are stateful — they hold their own line cadence — so this hands
 * out a fresh instance per event rather than sharing one.
 */
public final class Behaviors {

    private record Entry(String id, Supplier<DonkeyBehavior> factory) {}

    /**
     * The movement events. Adding one means adding a class and a line here;
     * command completion, the weighted pool's defaults, random selection and the
     * exit-line coverage test all key off this list.
     */
    private static final List<Entry> BUILT_IN = List.of(
            new Entry("lecture", LectureBehavior::new),
            new Entry("roadblock", RoadblockBehavior::new),
            new Entry("serenade", SerenadeBehavior::new),
            new Entry("falsealarm", FalseAlarmBehavior::new),
            new Entry("burrs", BurrsBehavior::new));

    private Behaviors() {}

    /** Built-in ids only. For the full list including configured demands, see {@link #ids(EventPool)}. */
    public static List<String> ids() {
        return BUILT_IN.stream().map(Entry::id).toList();
    }

    /** Every id this server can actually run: built-ins plus configured demand events. */
    public static List<String> ids(EventPool pool) {
        List<String> all = new ArrayList<>(ids());
        if (pool != null) {
            for (EventDefinition entry : pool.entries()) {
                if (entry.isDemand() && !all.contains(entry.behavior())) {
                    all.add(entry.behavior());
                }
            }
        }
        return all;
    }

    /** @return a fresh built-in behavior, or {@code null} if the id is not one */
    public static DonkeyBehavior byId(String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : BUILT_IN) {
            if (entry.id().equalsIgnoreCase(id)) {
                return entry.factory().get();
            }
        }
        return null;
    }

    /**
     * Resolves an id against built-ins first, then configured demand events.
     *
     * @return a fresh behavior, or {@code null} if nothing by that name exists
     */
    public static DonkeyBehavior byId(String id, EventPool pool) {
        DonkeyBehavior builtIn = byId(id);
        if (builtIn != null) {
            return builtIn;
        }
        if (id == null || pool == null) {
            return null;
        }
        for (EventDefinition entry : pool.entries()) {
            if (entry.behavior().equalsIgnoreCase(id) && entry.isDemand()) {
                return forDefinition(entry);
            }
        }
        return null;
    }

    /** Builds the behavior an event-pool entry describes. */
    public static DonkeyBehavior forDefinition(EventDefinition entry) {
        if (entry == null) {
            return null;
        }
        if (entry.isDemand()) {
            return new DemandBehavior(entry.behavior(), entry.demandOrNone(),
                    entry.minSeconds(), entry.maxSeconds());
        }
        return byId(entry.behavior());
    }

    /** A fresh built-in chosen at random. Play uses the weighted pool instead. */
    public static DonkeyBehavior random(Random random) {
        return BUILT_IN.get(random.nextInt(BUILT_IN.size())).factory().get();
    }

    /** Rolls a duration in ticks from a behavior's own range. */
    public static int rollDurationTicks(DonkeyBehavior behavior, Random random) {
        int min = behavior.minDurationSeconds();
        int max = behavior.maxDurationSeconds();
        return 20 * (min + random.nextInt(Math.max(1, max - min + 1)));
    }
}
