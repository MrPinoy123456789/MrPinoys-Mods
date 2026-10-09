package pocketdungeons;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * The act the Astrolabe Room is showing (design pass 2026-10-09, Q1), or 0 before it has chosen one. Only
     * the first staging room reads it; a new interval starts it at zero and the room opens on the act that
     * holds the next door to take.
     */
    int hallAct;

    /** Floors cleared since the last safe visit. The next floor is {@code floorIndex + 1}. */
    int floorIndex;

    /**
     * The trip's omen, 0 to 4: the count of deaths this trip (J3: death is
     * the only thing that raises it). The player reads it as lives,
     * {@code 5 - omen}; the fifth death ends the run. Per trip, so it is
     * never zeroed at a floor clear.
     */
    int omen;

    /**
     * The trip's omen at each floor clear, in order, for the journal's
     * {@code floor_complete} event. Record-keeping only: {@link #omen}
     * itself carries across floors.
     */
    final List<Integer> floorOmens = new ArrayList<>();

    /**
     * Each cleared floor's dealt chart scrap (1 to 3, 0 for a resource
     * dungeon), in order: what the floor banks toward the compass at the
     * settlement. See {@link IntervalBanking}.
     */
    final List<Integer> floorSteps = new ArrayList<>();

    /**
     * Each cleared floor's level, in order, parallel to {@link #floorSteps}:
     * the level a member's scrap is discounted against
     * ({@link IntervalBanking#effectiveScrap}).
     */
    final List<Integer> floorLevels = new ArrayList<>();

    /**
     * What the dungeon finish banked for each member (PD-179): the finish pays the haul at once, so
     * the go-home board and title read this instead of an emptied haul.
     */
    final Map<UUID, Integer> finishBanked = new HashMap<>();

    // ---- the dungeon trip (dungeon structure W2) -----------------------------------------
    //
    // One trip is one dungeon (design D1). The interval is the trip, so this state is
    // replaced with the interval by InstanceRecord#beginInterval: a bank, a quit and a
    // fail all clear it with no reset list. In memory only, like the rest of the record:
    // a restart forgives the trip. There is no saved position inside a dungeon.

    /**
     * The dungeon id this trip is playing ({@code pocketdungeons:frostworks}), or an
     * empty string before the first door of the trip is committed, and for an
     * Endless Mine or an operator's experimental offer, which run outside any dungeon
     * graph.
     */
    String dungeonId = "";

    /**
     * How many times the owner re-dealt the first staging room's dungeons
     * ({@code /dungeon reroll}); mixed into the deal's salt. Only the first
     * deal of a trip reads it, and a new interval starts it at zero.
     */
    int doorReroll;

    /** The node the party last entered: its floor is in progress or cleared. Empty with no dungeon. */
    String nodeId = "";

    /** The node ids the trip has taken, in order; the current node is last. */
    final List<String> path = new ArrayList<>();

    /**
     * Whether the dungeon's final floor has been cleared. The staging room then
     * offers only the way home (the HOME lever); see {@code RunLifecycle#finishDungeon}.
     */
    boolean finished;

    /**
     * Whether this interval is an Endless Mine. Set on the first commit whose
     * recipe opens the Mine and held for the rest of the interval, after the
     * recipe tags themselves are spent. See {@link EndlessMineRules}.
     */
    boolean endlessMine;

    /**
     * The act that must be cleared before the next Endless Mine floor may be entered, or 0 when
     * the shaft is open (D13). While non zero {@link #finished} is also set, so the staging room
     * offers only the HOME lever.
     */
    int mineSealedAct;

    /**
     * Whether this interval's safe visit has already been settled (keystone,
     * payout, bounties). A safe return that throws after settling puts the
     * phase back so the lever works again, and the retry must not pay twice.
     */
    boolean safeVisitSettled;

    /** The interval's omen: the trip's deaths, clamped. Alias kept for the journal and context. */
    int omenSum() {
        return Omen.clamp(omen);
    }

    /** The same reading under the old name, for the callers that predate J3. */
    int bankedOmenSum() {
        return Omen.clamp(omen);
    }
}
