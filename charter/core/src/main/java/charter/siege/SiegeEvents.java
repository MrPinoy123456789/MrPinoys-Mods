package charter.siege;

import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.treasury.Currency;

import java.util.Map;
import java.util.Set;

/**
 * History events of the siege. Each also feeds the siege log for the debrief
 * (PLAN.md §8.3). {@code siegeAt} is the siege clock when it happened, so a
 * debrief can line events up on the war's own timeline.
 */
public final class SiegeEvents {

    private SiegeEvents() {
    }

    /** A camp finished raising: its claims are protected and the defenders are alerted. */
    public record CampActive(long at, long siegeAt, CoreId camp, OrgId owner, Set<OrgId> targets) implements HistoryEvent {
    }

    public record SiegeBegan(long at, long siegeAt, CoreId keep, OrgId defender, Set<OrgId> besiegers) implements HistoryEvent {
    }

    public record Encircled(long at, long siegeAt, CoreId keep) implements HistoryEvent {
    }

    /** An encircled keep has a side open again. */
    public record RingBroken(long at, long siegeAt, CoreId keep) implements HistoryEvent {
    }

    public record SiegeLifted(long at, long siegeAt, CoreId keep, Set<OrgId> besiegers) implements HistoryEvent {
    }

    public record Starving(long at, long siegeAt, CoreId keep) implements HistoryEvent {
    }

    public record KeepFell(long at, long siegeAt, CoreId keep, OrgId owner, OrgId besieger, ChunkKey center) implements HistoryEvent {
    }

    public record Razed(long at, long siegeAt, CoreId keep, OrgId owner, OrgId by) implements HistoryEvent {
    }

    public record Occupied(long at, long siegeAt, CoreId keep, OrgId owner, OrgId by, long untilSiege) implements HistoryEvent {
    }

    public record OccupationEnded(long at, long siegeAt, CoreId keep, OrgId occupier, String why) implements HistoryEvent {
    }

    /** Treasury taken when a keep falls, to be spilled as items in its core chunk. */
    public record Plundered(long at, CoreId keep, OrgId from, ChunkKey at_chunk, Map<Currency, Long> amounts) implements HistoryEvent {
    }

    public record CampStoreEmpty(long at, long siegeAt, CoreId camp) implements HistoryEvent {
    }

    public record CampCutOff(long at, long siegeAt, CoreId camp) implements HistoryEvent {
    }

    /** A sally emptied a camp's sally hold: its claims lose protection and the banner can be broken. */
    public record CampExposed(long at, long siegeAt, CoreId camp) implements HistoryEvent {
    }

    public record HoldEmptied(long at, long siegeAt, ChunkKey chunk, OrgId owner, OrgId raider) implements HistoryEvent {
    }

    public record TruceBegan(long at, long siegeAt, CoreId keep, OrgId attacker, long untilSiege) implements HistoryEvent {
    }

    /** Food went into a store; {@code underSiege} says whether an allowance was needed. */
    public record FoodDeposited(long at, long siegeAt, CoreId site, String carrier, Map<String, Integer> accepted, double nourishment, boolean underSiege) implements HistoryEvent {
    }

    public record ProtectionWaived(long at, OrgId faction) implements HistoryEvent {
    }
}
