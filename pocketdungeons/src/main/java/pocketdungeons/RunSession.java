package pocketdungeons;

import java.util.EnumSet;
import java.util.Set;

/**
 * M65: explicit state machine for the floor loop lifecycle (spec 12).
 *
 * <p>Today the run's phase is implicit in a scatter of boolean fields on
 * {@link InstanceRecord}: {@code awaitingDoorChoice}, {@code previewPlan != null},
 * {@code timer != null}, {@code safeStaging}, {@code floorIndex}. Every
 * lifecycle method reads a different subset of those to decide whether it
 * is allowed to run, and the subsets do not always agree. This class
 * replaces that with a single {@link Phase} field and a transition table
 * that every lifecycle method checks before doing work.
 *
 * <p>The phases, in loop order:
 * <ul>
 *   <li>{@link Phase#HOME}: safe room visit. No dungeon active. The party
 *       is in the safe room, choosing whether to enter the staging room.</li>
 *   <li>{@link Phase#PREVIEW}: door preview active. The entrance cell is
 *       stamped and the selector door is a window. The host has not
 *       committed yet.</li>
 *   <li>{@link Phase#ACTIVE}: dungeon floor in progress. The timer is
 *       running, the party is exploring.</li>
 *   <li>{@link Phase#FLOOR_CLEARED}: terminal pad reached. The floor's
 *       omen is banked, completion chests are placed, and a new staging
 *       room is stamped behind the far wall. The party is choosing the
 *       next door (or the safe door, if this is a safe staging room).</li>
 *   <li>{@link Phase#SAFE_RETURN}: safe door selected in a safe staging
 *       room. The dungeon is being purged and the safe room is being
 *       re-stamped. This is a transient phase: it is set at the start of
 *       {@link RunLifecycle#returnToSafe} and cleared when the safe room
 *       is stamped and the party is teleported in.</li>
 *   <li>{@link Phase#RECOVERY}: join or restart recovery. Set when a
 *       player reconnects to an instance whose phase needs reconstructing,
 *       and cleared once the phase is derived from the record's state.</li>
 * </ul>
 *
 * <p>The transition table is closed: every edge not listed in
 * {@link #ALLOWED} is rejected. This is what makes the state machine
 * load-bearing rather than advisory. A method that tries to commit a door
 * while in {@code FLOOR_CLEARED} (skipping preview) is rejected; a method
 * that tries to return to the safe room from {@code ACTIVE} (skipping the
 * terminal pad) is rejected.
 *
 * <p>Recovery is special: any phase can enter {@code RECOVERY}, and
 * {@code RECOVERY} can transition to any non-transient phase. This is the
 * one escape hatch, used by the join path to reconstruct the phase from
 * the record's fields after a disconnect or restart.
 */
final class RunSession {

    private RunSession() {}

    /** The six phases of the floor loop. See class javadoc. */
    enum Phase {
        HOME,
        PREVIEW,
        ACTIVE,
        FLOOR_CLEARED,
        SAFE_RETURN,
        RECOVERY
    }

    /**
     * The allowed transitions, as a set of (from, to) pairs. Every edge
     * not in this set (and not entering RECOVERY, which is always allowed)
     * is rejected by {@link #canTransition}.
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
        // select the safe door.
        pair(Phase.FLOOR_CLEARED, Phase.PREVIEW),
        pair(Phase.FLOOR_CLEARED, Phase.SAFE_RETURN),
        // Completing the safe return lands in the safe room.
        pair(Phase.SAFE_RETURN, Phase.HOME),
        // Recovery can land in any non-transient phase.
        pair(Phase.RECOVERY, Phase.HOME),
        pair(Phase.RECOVERY, Phase.PREVIEW),
        pair(Phase.RECOVERY, Phase.ACTIVE),
        pair(Phase.RECOVERY, Phase.FLOOR_CLEARED)
    );

    /**
     * Whether the transition from {@code from} to {@code to} is legal.
     * Entering RECOVERY is always allowed from any phase; everything else
     * must be in {@link #ALLOWED}.
     */
    static boolean canTransition(Phase from, Phase to) {
        if (to == Phase.RECOVERY && from != Phase.RECOVERY) {
            return true;
        }
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
     * Derives the phase from the record's existing fields, for the join
     * and restart recovery paths that need to reconstruct the phase after
     * a disconnect or server restart. This is the only place the phase is
     * inferred rather than transitioned, and it is used exclusively by
     * the RECOVERY exit path.
     *
     * <p>The derivation mirrors the field checks the lifecycle methods
     * already do, so it agrees with the transitions by construction:
     * <ul>
     *   <li>{@code previewPlan != null}: PREVIEW.</li>
     *   <li>{@code awaitingDoorChoice && floorIndex > 0}: FLOOR_CLEARED
     *       (or SAFE_RETURN if {@code safeStaging}, but SAFE_RETURN is
     *       transient and a reconnecting player finds the staging room
     *       already stamped, so FLOOR_CLEARED is the stable phase).</li>
     *   <li>{@code awaitingDoorChoice}: HOME (floorIndex == 0, safe room
     *       loaded).</li>
     *   <li>Otherwise: ACTIVE (dungeon in progress).</li>
     * </ul>
     */
    static Phase derivePhase(InstanceRecord record) {
        if (record.previewPlan != null) {
            return Phase.PREVIEW;
        }
        if (record.awaitingDoorChoice) {
            if (record.floorIndex > 0) {
                return Phase.FLOOR_CLEARED;
            }
            return Phase.HOME;
        }
        return Phase.ACTIVE;
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

    // ---- internal --------------------------------------------------------

    private record PhasePair(Phase from, Phase to) {}

    private static PhasePair pair(Phase from, Phase to) {
        return new PhasePair(from, to);
    }
}
