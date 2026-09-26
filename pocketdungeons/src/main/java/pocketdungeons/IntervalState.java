package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The state of one interval: everything that lives from the first door out of
 * the safe room until the next safe visit (or a quit back to the lobby).
 *
 * <p>The rule: nothing here is ever cleared field by field. An interval ends
 * with {@link InstanceRecord#beginInterval}, which replaces this object with a
 * fresh one, so a field added here is reset by construction. State that must
 * reset at every floor commit instead belongs on {@link FloorState}; state
 * that outlives the interval (the room, the staging cell, membership) stays on
 * {@link InstanceRecord}.
 */
final class IntervalState {

    /** Floors cleared since the last safe visit. The next floor is {@code floorIndex + 1}. */
    int floorIndex;

    /**
     * The running omen of the floor in progress, 0 to 4. Banked onto
     * {@link #floorOmens} and zeroed when the floor is cleared. It lives here
     * rather than on the floor because omen gathered between floors (dwelling
     * in an old, unsolved cell) counts toward the next one.
     */
    int omen;

    /** Each cleared floor's clamped omen, in order. {@link Omen#floorSum} keys the finish table off this. */
    final List<Integer> floorOmens = new ArrayList<>();

    /**
     * Whether this interval is an Endless Mine. Set on the first commit whose
     * recipe opens the Mine and held for the rest of the interval, after the
     * recipe tags themselves are spent. See {@link EndlessMineRules}.
     */
    boolean endlessMine;

    /**
     * Whether this interval's safe visit has already been settled (keystone,
     * payout, bounties). A safe return that throws after settling puts the
     * phase back so the lever works again, and the retry must not pay twice.
     */
    boolean safeVisitSettled;

    /** The interval's omen so far: every banked floor plus the running one. */
    int omenSum() {
        int sum = Omen.clamp(omen);
        for (int banked : floorOmens) {
            sum += Omen.clamp(banked);
        }
        return sum;
    }

    /** The banked floors' omen alone, the sum settlement uses. */
    int bankedOmenSum() {
        return Omen.floorSum(floorOmens.stream().mapToInt(Integer::intValue).toArray());
    }
}
