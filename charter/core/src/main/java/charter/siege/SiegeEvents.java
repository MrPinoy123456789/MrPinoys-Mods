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
    /** A nation declared war to capture an outpost; capture may begin at {@code opensAt} (siege time). */
    public record WarDeclared(long at, long siegeAt, long goal, OrgId attacker, OrgId defender, CoreId outpost, long opensAt) implements HistoryEvent {
    }

    /** The notice is over: the attackers may now begin to capture the outpost. */
    public record WarOpened(long at, long siegeAt, long goal, OrgId attacker, OrgId defender, CoreId outpost) implements HistoryEvent {
    }

    /** The attackers held the outpost's chunk long enough: its banner is gone, its land released, its deposit theirs. */
    public record OutpostCaptured(long at, long siegeAt, long goal, OrgId attacker, OrgId defender, CoreId outpost, ChunkKey center, long deposit) implements HistoryEvent {
    }

    /** The goal ran out without a capture; the two nations may not fight again for the truce. */
    public record WarLapsed(long at, long siegeAt, long goal, OrgId attacker, OrgId defender, CoreId outpost) implements HistoryEvent {
    }

    public record KeepRunningLow(long at, long siegeAt, CoreId keep, OrgId owner, int daysLeft) implements HistoryEvent {
    }

    /** A keep's granary ran dry: its claim lapsed and the land went back to the wilderness; the blocks stand. */
    public record KeepLapsed(long at, long siegeAt, CoreId keep, OrgId owner, ChunkKey center) implements HistoryEvent {
    }

    /** Food went into a store; {@code underSiege} says whether an allowance was needed. */
    public record FoodDeposited(long at, long siegeAt, CoreId site, String carrier, Map<String, Integer> accepted, double nourishment, boolean underSiege) implements HistoryEvent {
    }

}
