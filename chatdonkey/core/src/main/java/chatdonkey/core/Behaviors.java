package chatdonkey.core;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The behavior pool (SPEC.md section 4).
 *
 * <p>Behaviors are stateful -- they hold their own line cadence -- so this
 * registry stores suppliers and hands out a fresh instance per event rather
 * than sharing one.
 *
 * <p>All six v1 behaviors are registered here. Adding a seventh means adding a
 * class and one line to {@code REGISTRY} -- command completion, the weighted
 * pool's defaults, random selection, and the exit-line coverage test all key off
 * this list, so nothing else needs touching.
 */
public final class Behaviors {

    private record Entry(String id, Supplier<DonkeyBehavior> factory) {}

    private static final List<Entry> REGISTRY = List.of(
            new Entry("lecture", LectureBehavior::new),
            new Entry("roadblock", RoadblockBehavior::new),
            new Entry("foodcritic", FoodCriticBehavior::new),
            new Entry("clingy", ClingyBehavior::new),
            new Entry("serenade", SerenadeBehavior::new),
            new Entry("falsealarm", FalseAlarmBehavior::new));

    private Behaviors() {}

    /** Every registered behavior id, for command completion and error messages. */
    public static List<String> ids() {
        return REGISTRY.stream().map(Entry::id).toList();
    }

    /** @return a fresh behavior, or {@code null} if the id is unknown */
    public static DonkeyBehavior byId(String id) {
        for (Entry entry : REGISTRY) {
            if (entry.id().equalsIgnoreCase(id)) {
                return entry.factory().get();
            }
        }
        return null;
    }

    /** A fresh behavior chosen at random. */
    public static DonkeyBehavior random(Random random) {
        return REGISTRY.get(random.nextInt(REGISTRY.size())).factory().get();
    }

    /** Rolls a duration in ticks from a behavior's own range. */
    public static int rollDurationTicks(DonkeyBehavior behavior, Random random) {
        int min = behavior.minDurationSeconds();
        int max = behavior.maxDurationSeconds();
        return 20 * (min + random.nextInt(Math.max(1, max - min + 1)));
    }
}
