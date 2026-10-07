package pocketdungeons;

import java.util.EnumSet;
import java.util.Set;

/**
 * M65: explicit state machine for the floor loop lifecycle (spec 12).
 *
 * <p>{@link InstanceRecord#phase} is the one source of truth for where the
 * loop stands. Every lifecycle method checks or transitions it before doing
 * its work, and nothing else on the record encodes the phase: whether a lobby
 * is still waiting for its first door, for example, is read from the phase
 * and the floor's chosen step, not from a flag of its own.
 *
 * <p>The phases, in loop order:
 * <ul>
 *   <li>{@link Phase#HOME}: safe room visit. No dungeon active. The party
 *       is in the safe room, choosing whether to enter the staging room.
 *       Admin builds, untimed runs, visit copies and Pocket2 children sit
 *       here for their whole life; they never enter the loop.</li>
 *   <li>{@link Phase#PREVIEW}: door preview active. The entrance cell is
 *       stamped and the selector door is a window. The host has not
 *       committed yet.</li>
 *   <li>{@link Phase#ACTIVE}: dungeon floor in progress. The party is
 *       exploring.</li>
 *   <li>{@link Phase#FLOOR_CLEARED}: terminal pad reached. The floor's
 *       omen is banked, completion chests are placed, and a new staging
 *       room is stamped behind the far wall. The party is choosing the
 *       next door or the way home (the HOME lever).</li>
 *   <li>{@link Phase#SAFE_RETURN}: the HOME lever was pulled. The interval
 *       settles, the dungeon is being purged and the safe room is being
 *       re-stamped. This is a transient phase: it is set at the start of
 *       {@link RunLifecycle#returnToSafe} and left for {@code HOME} when the
 *       room is stamped, or back for {@code FLOOR_CLEARED} when the return
 *       fails, so the lever still works.</li>
 * </ul>
 *
 * <p>The transition table is closed: every edge not listed in
 * {@link #ALLOWED} is rejected. This is what makes the state machine
 * load-bearing rather than advisory. A method that tries to commit a door
 * while in {@code FLOOR_CLEARED} (skipping preview) is rejected; a method
 * that tries to return to the safe room from {@code ACTIVE} (skipping the
 * terminal pad) is rejected.
 */
final class RunSession {

    private RunSession() {}

    /** The five phases of the floor loop. See class javadoc. */
    enum Phase {
        HOME,
        PREVIEW,
        ACTIVE,
        FLOOR_CLEARED,
        SAFE_RETURN
    }

    /**
     * The allowed transitions, as a set of (from, to) pairs. Every edge
     * not in this set is rejected by {@link #canTransition}.
     *
     * <p>The loop's normal cycle:
     * <pre>
     * HOME -> PREVIEW -> ACTIVE -> FLOOR_CLEARED -> PREVIEW -> ACTIVE -> ...
     *                                              -> SAFE_RETURN -> HOME
     * </pre>
     */
    private static final Set<PhasePair> ALLOWED = Set.of(
        // Entering the loop from the safe room.
        pair(Phase.HOME, Phase.PREVIEW),
        // Committing a previewed door starts the floor.
        pair(Phase.PREVIEW, Phase.ACTIVE),
        // Cancelling a preview returns to the safe room (or the staging
        // room, if the safe room is already despawned).
        pair(Phase.PREVIEW, Phase.HOME),
        pair(Phase.PREVIEW, Phase.FLOOR_CLEARED),
        // Reaching the terminal pad advances the floor.
        pair(Phase.ACTIVE, Phase.FLOOR_CLEARED),
        // From the cleared staging room, preview the next floor or
        // go home.
        pair(Phase.FLOOR_CLEARED, Phase.PREVIEW),
        pair(Phase.FLOOR_CLEARED, Phase.SAFE_RETURN),
        // Completing the safe return lands in the safe room.
        pair(Phase.SAFE_RETURN, Phase.HOME),
        // A safe return that fails puts the run back between floors, so
        // the lever works again.
        pair(Phase.SAFE_RETURN, Phase.FLOOR_CLEARED),
        // F9: aborting an active or between-floors run via /dungeon quit
        // returns the record to HOME. resetToLobby rebuilds the lobby and
        // re-arms the door choice; without these edges the phase stays
        // stranded in ACTIVE or FLOOR_CLEARED and canChooseDoor rejects
        // every later door selection.
        pair(Phase.ACTIVE, Phase.HOME),
        pair(Phase.FLOOR_CLEARED, Phase.HOME)
    );

    /** Whether the transition from {@code from} to {@code to} is in {@link #ALLOWED}. */
    static boolean canTransition(Phase from, Phase to) {
        return ALLOWED.contains(pair(from, to));
    }

    /**
     * Attempts to transition the record from its current phase to
     * {@code to}. Returns {@code true} and updates the record if the
     * transition is legal; returns {@code false} and logs a warning if
     * it is not.
     *
     * <p>Every lifecycle method that changes the run's phase calls this
     * before doing its work. A {@code false} return is a programming
     * error (the method was called in the wrong phase), not a player
     * error; the caller should treat it as a no-op.
     */
    static boolean transition(InstanceRecord record, Phase to) {
        Phase from = record.phase;
        if (!canTransition(from, to)) {
            PocketDungeonsMod.LOG.warn(
                    "Rejected illegal RunSession transition: {} -> {} (slot {})",
                    from, to, record.slot);
            return false;
        }
        record.phase = to;
        return true;
    }

    /**
     * Requires the record to be in one of {@code allowed} phases. Returns
     * {@code true} if it is; returns {@code false} and logs a warning if
     * it is not. Used by methods that do not change the phase but are
     * only valid in certain phases (e.g. {@code previewDoor} is only
     * valid in HOME or FLOOR_CLEARED).
     */
    static boolean require(InstanceRecord record, Phase... allowed) {
        Phase current = record.phase;
        for (Phase p : allowed) {
            if (current == p) {
                return true;
            }
        }
        PocketDungeonsMod.LOG.warn(
                "RunSession phase check failed: expected one of {} but was {} (slot {})",
                java.util.Arrays.toString(allowed), current, record.slot);
        return false;
    }

    /**
     * Whether the record is in a phase where the party is standing in a
     * staging room and the host can interact with the door selection
     * furniture. This covers both the initial safe-room staging room
     * (HOME) and the floor-cleared staging room (FLOOR_CLEARED), plus
     * the preview phase (PREVIEW) where the host can either pull the
     * lever to commit the current preview or right-click a different
     * selector door to switch previews. Used by the door selection,
     * commit lever, and commit door checks.
     */
    static boolean canChooseDoor(InstanceRecord record) {
        return record.phase == Phase.HOME
                || record.phase == Phase.FLOOR_CLEARED
                || record.phase == Phase.PREVIEW;
    }

    /**
     * Whether the record is in a phase where the dungeon is active and
     * the party is exploring. Used by the tick watcher and the pad
     * contact check.
     */
    static boolean isActive(InstanceRecord record) {
        return record.phase == Phase.ACTIVE;
    }

    /**
     * Whether the record is in a phase where the safe room is loaded and
     * the party is in it. Used by the visit service and the stash
     * invariant.
     */
    static boolean isHome(InstanceRecord record) {
        return record.phase == Phase.HOME;
    }

    /**
     * Whether this is a floor loop lobby that has not committed a door in
     * the interval yet: {@code HOME}, or a preview opened from it. An empty
     * lobby in this state holds a slot for nothing and is purged.
     */
    static boolean awaitingFirstDoor(InstanceRecord record) {
        return record.inFloorLoop()
                && (record.phase == Phase.HOME || record.phase == Phase.PREVIEW)
                && !record.floor.hasDoor();
    }

    // ---- internal --------------------------------------------------------

    private record PhasePair(Phase from, Phase to) {}

    private static PhasePair pair(Phase from, Phase to) {
        return new PhasePair(from, to);
    }
}
