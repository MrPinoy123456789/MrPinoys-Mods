package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One live dungeon instance and the party inside it.
 *
 * <p>State is split by lifetime, and each part is replaced wholesale rather
 * than cleared field by field:
 * <ul>
 *   <li>this record: the instance itself, for as long as the slot is held.
 *       Membership, the room and staging cells, the phase, the layout.</li>
 *   <li>{@link #interval}: from the first door out of the safe room to the
 *       next safe visit. Replaced by {@link #beginInterval}.</li>
 *   <li>{@link #floor}: from the commit that opens a floor to the next
 *       commit. Replaced by {@link #startFloor} (and by
 *       {@link #beginInterval}).</li>
 * </ul>
 * {@link #phase} is the one source of truth for where the loop stands; see
 * {@link RunSession}.
 *
 * <p>Membership can fall to zero and climb back any number of times: the
 * owner leaves and returns for free. {@link #owner} is the one identity a
 * quit's depletion applies to, whoever happens to be standing inside.
 *
 * <p>In memory only. A restart forgives whatever run was in progress rather
 * than resuming it.
 */
final class InstanceRecord {

    final int slot;
    /** Floor corner of the slot's anchor cell: where the lobby is first stamped. */
    final BlockPos origin;
    final long createdAtTick;

    /** The player who opened this instance. A quit's depletion applies to them alone. */
    final UUID owner;

    /**
     * The shape of the floor in progress (entrance, exit pad, bounds, cells,
     * level), or at home the lobby's one-cell layout. Replaced at every commit
     * and at every return home.
     */
    InstanceLayout layout;

    /** Member -> where that member came from. Insertion-ordered: opener first. */
    final Map<UUID, ReturnPoint> members = new LinkedHashMap<>();

    /**
     * (M48) Block positions placed by a player inside a dungeon cell, mapped
     * to the placer's UUID, for the "right tool for the job" exemption: a
     * player can always break what they placed, regardless of tool. In-memory,
     * dies with the instance. Populated by {@code BlockItemPlaceMixin} on
     * every successful block placement in the dungeon dimension. Only the
     * placer is exempt; other party members still need the correct tool.
     */
    final Map<BlockPos, UUID> playerPlaced = new HashMap<>();

    /** The run's phase in the floor loop. The only phase truth; see {@link RunSession}. */
    RunSession.Phase phase = RunSession.Phase.HOME;

    /** State for the interval in progress. Never cleared in place; see {@link IntervalState}. */
    IntervalState interval = new IntervalState();

    /** State for the floor in progress (or just cleared). Never cleared in place; see {@link FloorState}. */
    FloorState floor = new FloorState();

    /** The omen boss bar, created the first time it has something to show. {@code null} until then. */
    OmenBar omenBar;

    /**
     * An operator's run outside the floor loop, opened by
     * {@code /dungeon admin untimed}: a full layout at once, no keystone
     * spent.
     *
     * <p>It is the one dungeon that never expires on its own, which makes it the
     * one that can be forgotten -- so both its creation and its teardown are
     * logged at {@code INFO}, and {@code /dungeon admin list} marks it. That
     * visibility <em>is</em> the safeguard: an operator who wants a dungeon to
     * stand still while they walk it gets exactly that, and the console says so
     * until they purge it.
     */
    final boolean untimed;

    /**
     * True for an operator's {@code /dungeon admin buildroom} shell: one empty
     * cell with no doors, no keystone, and no room to save. The
     * record exists so the shell has an owner to find it by and a slot to tear
     * down, and so {@code /dungeon admin saveroom} knows which cell to capture.
     * One per player: opening another build room tears the old one down.
     */
    final boolean adminBuild;

    /**
     * Floor corner of the cell that holds this run's saved room right now, or
     * {@code null} while it is despawned (a floor is in progress) and for a run
     * with no room at all (an admin build). This is what
     * {@link Instances#roomOwnerAt} checks against for the room permission mask.
     */
    BlockPos roomCellOrigin;

    /**
     * Which wall of the staging room the next dungeon extends from (the
     * {@code MM} side). Updated whenever the staging room moves.
     */
    DoorMask.Direction roomDungeonDoor = DoorMask.Direction.SOUTH;

    /**
     * True from the moment {@code InstanceTeardown.purge} starts tearing this
     * record down, before it ejects a single member. The purge ejects online
     * members through the same {@code Instances.eject} path a voluntary exit
     * uses, and without this flag {@link RunLifecycle#isReenterable} would see
     * a record mid-teardown as an ordinary reenterable run and offer the owner
     * a free re-entry into an instance seconds away from being removed.
     * {@link RunLifecycle#isReenterable} checks this first.
     */
    boolean tearingDown;

    /**
     * True for a read-only visit instance created when someone visits while the
     * room's owner is away (M3 T3.3). It has no reward room,
     * and its copy of the room is never captured back to the owner's blob.
     */
    boolean visitInstance;

    // ---- M25: Pocket2 child instance ----------------------------------------

    /**
     * The slot of the parent instance this Pocket2 child belongs to, or {@code -1}
     * for a top-level run. A child is a nested sub-dungeon opened from a rare
     * door in the parent's cleared encounter room: its own slot, its own cells,
     * no keystone, no spawner gate, no completion pad. Only its deadline or a
     * player death ends it, and its members are returned to the parent.
     */
    final int parentSlot;

    /**
     * Where a member of this Pocket2 child is returned on expiry or death: the
     * standing spot just inside the parent's rare door. {@code null} for a
     * top-level run.
     */
    final BlockPos returnPos;

    /**
     * M25: the game tick at which this Pocket2 child closes, or {@code 0}
     * while no deadline is armed.
     */
    long deadlineTick;

    /**
     * Floor corner of the staging room: the cell with the selector doors,
     * the lever and the screens. {@code null} for a run without one (admin
     * build, untimed, visit).
     */
    BlockPos stagingCellOrigin;

    /**
     * A silent homecoming waiting for the party to walk into the room, or
     * {@code null} when none is pending. Set by {@code returnToSafe};
     * {@code Instances.onTick} releases the old floor and staging room once
     * every member has crossed, then clears it.
     */
    Homecoming homecoming;

    /**
     * The old staging room and floor a silent homecoming still has to release,
     * and the game tick (overworld clock) it started waiting, so stragglers can
     * be pulled into the room once it has waited long enough.
     */
    record Homecoming(BlockPos oldStagingCellOrigin, InstanceLayout oldLayout, long sinceTick) {}

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<String> affixes, UUID owner, boolean untimed) {
        this(slot, origin, createdAtTick, layout, affixes, owner, untimed, false);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<String> affixes, UUID owner, boolean untimed, boolean adminBuild) {
        this(slot, origin, createdAtTick, layout, affixes, owner, untimed, adminBuild, -1, null);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<String> affixes, UUID owner, boolean untimed, boolean adminBuild,
                   int parentSlot, BlockPos returnPos) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
        this.floor.affixes = affixes == null ? Set.of() : affixes;
        this.owner = owner;
        this.untimed = untimed;
        this.adminBuild = adminBuild;
        this.parentSlot = parentSlot;
        this.returnPos = returnPos;
    }

    /**
     * Opens a floor: {@code layout} becomes the record's layout and
     * {@code next} its floor state, replacing the previous floor's wholesale.
     * A homecoming still pending from the last interval is dropped: a new floor
     * starts clean.
     */
    void startFloor(InstanceLayout layout, FloorState next) {
        this.layout = layout;
        this.floor = next;
        this.homecoming = null;
    }

    /**
     * Ends the interval in progress and stands the record at home: the lobby
     * layout, a fresh interval and a fresh floor. Every exit from an interval
     * (the safe return, its teleport fallback, a quit back to the lobby) comes
     * through here, so no reset list can miss a field. The homecoming is left
     * to the caller: only the safe return sets one.
     */
    void beginInterval(InstanceLayout lobbyLayout) {
        this.layout = lobbyLayout;
        this.interval = new IntervalState();
        this.floor = new FloorState();
    }

    /** Whether a keystone was spent to open the floor in progress. False at home and for admin builds. */
    boolean isKeystoneRun() {
        return layout.keystoneLevel() > 0;
    }

    /** M25: whether this record is a Pocket2 child of a parent instance. */
    boolean isChild() {
        return parentSlot >= 0;
    }

    /**
     * Whether this record takes part in the floor loop at all: an ordinary
     * keystone lobby, as opposed to an admin build, an untimed run, a visit
     * copy or a Pocket2 child, none of which ever leave {@code HOME}.
     */
    boolean inFloorLoop() {
        return !untimed && !adminBuild && !visitInstance && !isChild();
    }
}
