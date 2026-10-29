package charter.siege;

import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;

import java.util.Map;

/**
 * History events of the siege. Each also feeds the siege log for the debrief
 * (PLAN.md §8.3). {@code siegeAt} is the siege clock when it happened, so a
 * debrief can line events up on the war's own timeline.
 */
public final class SiegeEvents {

    private SiegeEvents() {
    }

    /** A camp finished raising: its claims are protected. */
    public record CampActive(long at, long siegeAt, CoreId camp, OrgId owner) implements HistoryEvent {
    }

    /** A keep's granary is running low (protected keeps, K2): {@code daysLeft} is the warning threshold just passed. */
    public record KeepRunningLow(long at, long siegeAt, CoreId keep, OrgId owner, int daysLeft) implements HistoryEvent {
    }

    /** A keep's granary ran dry: its claim lapsed and the land went back to the wilderness; the blocks stand. */
    public record KeepLapsed(long at, long siegeAt, CoreId keep, OrgId owner, ChunkKey center) implements HistoryEvent {
    }

    public record CampStoreEmpty(long at, long siegeAt, CoreId camp) implements HistoryEvent {
    }

    public record CampCutOff(long at, long siegeAt, CoreId camp) implements HistoryEvent {
    }

    /** Food went into a store; {@code underSiege} says whether an allowance was needed. */
    public record FoodDeposited(long at, long siegeAt, CoreId site, String carrier, Map<String, Integer> accepted, double nourishment, boolean underSiege) implements HistoryEvent {
    }

}
