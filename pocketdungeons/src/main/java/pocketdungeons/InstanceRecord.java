package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One live dungeon instance and the party inside it.
 *
 * <p><strong>U8: membership is no longer lifetime.</strong> Before U8 an instance
 * lived until its last member walked out. Now it lives until its own clock says
 * otherwise -- see {@code Instances}' expiry check -- and membership can fall to
 * zero and climb back up any number of times in between, as the owner leaves and
 * returns for free (U8 Stage 1). {@link #owner} exists because of this: it is the
 * one identity depletion applies to, independent of who happens to be standing
 * inside at any given moment.
 *
 * <p>Milestone 0 keeps these in memory only; the spec's {@code SavedData}
 * persistence (section 11) is still deliberately unshipped, so a restart forgives
 * whatever run was in progress rather than resuming it.
 */
final class InstanceRecord {

    final int slot;
    /** Floor corner of the layout's anchor cell -- everything else is derived from this. */
    final BlockPos origin;
    final long createdAtTick;

    /** The player who opened this instance. Depletion on timeout applies to them alone. */
    final UUID owner;

    /**
     * The shape of this particular dungeon: entrance, exit pad, bounds, occupied
     * cells, tier. Procedural layouts differ per instance, so this travels with
     * the record rather than being recomputed from a constant.
     *
     * <p>Not {@code final}: a real keystone run now starts as a one-cell lobby
     * (M2/M3 §3.2.3) with no plan behind it yet -- see {@link #awaitingDoorChoice}
     * -- and this is replaced with the real, full layout the moment a door is
     * chosen and the rest of the dungeon is generated and stamped.
     */
    InstanceLayout layout;

    /** Member -> where that member came from. Insertion-ordered: opener first. */
    final Map<UUID, ReturnPoint> members = new LinkedHashMap<>();

    /** Cells any member has stood in, for the boss bar's room-visited counter. */
    final Set<PlanCell> visited = new HashSet<>();

    // ---- keystone run state --------------------------------------------------
    // All of it dies with the instance, which is the whole point: the keystone
    // itself lives in DungeonLog, so there is no run to persist and nothing here
    // has to survive a restart.

    /**
     * The affixes riding on this run: whatever the key's level seeded.
     * Not {@code final} for the same reason {@link #layout} is not:
     * unknown until a door is chosen out of the lobby.
     */
    Set<Affix> affixes;

    /** The dungeon theme id selected for this run, or null for an unthemed run. */
    String theme;

    /**
     * True while this instance is still just the lobby -- the owner's
     * standing room, stamped alone with its one connecting door sealed,
     * showing three doors rendered from {@code Keystone.offers} but no
     * dungeon behind any of them yet. Cleared the moment a door is chosen:
     * {@link #layout} and {@link #affixes} are replaced with the real thing,
     * the seal comes down, and {@link #timer} starts.
     *
     * <p>Not the same concept as the pre-U8 selector room this retired -- that
     * was a separate, off-grid, post-completion-only instance; the lobby is the
     * run's own cell 0, all the time.
     */
    boolean awaitingDoorChoice;

    /**
     * Which of {@code Keystone.offers(level)}'s three doors (1, 2 or 3) was
     * chosen to open this run, or {@code 0} before that happens. Carried so a
     * completing member's own banked level/affix (T2.4-adjacent) can be
     * computed from *their* current keystone level at the same step the
     * opener chose, rather than the opener's -- see {@code completeRun}.
     */
    int chosenStep;

    /**
     * True when {@link #chosenStep} was door 1, the free tier (M12). Read by
     * {@code RunLifecycle.returnKeystone}/{@code expireTimedOut} to exempt a
     * free-door run from every depletion outcome: door 1 never costs fuel and
     * a failed door-1 run never costs a level either, so this is checked ahead
     * of translating a timeout or a late finish into a {@code Keystones
     * .Outcome}. Meaningless (and left at its default {@code false}) until a
     * door is actually chosen; the lobby itself is neither tier.
     */
    boolean freeDoor;

    /**
     * True once the timeout depletion has been applied to the owner's
     * keystone. Prevents repeated depletion on every watcher tick while
     * the run continues in overtime (PD-7: timeout no longer closes the
     * dungeon).
     */
    boolean timedOutPenaltyApplied;

    /**
     * The clock. Null only when nobody paid a keystone, or for an operator's
     * {@code /dungeon admin untimed} run -- see {@link #untimed}.
     *
     * <p>There is deliberately no config that turns this off. The timer is the
     * only thing that ends an ordinary run (U8 Stage 1), so a keystone dungeon
     * without one is a slot and a fistful of chunk tickets held until someone
     * notices.
     */
    RunTimer timer;

    /**
     * Members who have reached the exit pad. Reaching it once <em>completes</em> a
     * keystone run for that member -- record it, offer a door, and teleport them
     * to the reward room -- and does not eject; a second lodestone contact, in the
     * reward room, is what leaves (U8 Stage 2).
     */
    final Set<UUID> completed = new HashSet<>();

    /** Members currently standing on a pad, so a contact is an edge and not a state. */
    final Set<UUID> onPad = new HashSet<>();

    /**
     * Members whose keystone has already been settled once for this instance. The
     * guard that makes {@link Keystones#returnTo} idempotent: an expiry and the
     * purge that follows it both reach for the same owner in the same tick.
     */
    final Set<UUID> keystoneReturned = new HashSet<>();

    /**
     * Game tick at which this instance tears itself down, or {@code 0} while the
     * run is still live (U8 Stage 1). Set once the first member completes, to
     * {@code now + rewardRoomGraceSeconds}, so the reward room stays lootable for
     * the rest of the party without holding the slot open forever.
     */
    long expiresAtTick;

    /**
     * How many of the completion chests were earned, fixed at the first
     * completion and shared by every member who reaches it afterwards. {@code -1}
     * until the room has been moved to the terminal cell.
     */
    int rewardChests = -1;

    /**
     * An operator's deliberately clockless run, opened by
     * {@code /dungeon admin untimed}.
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
     * Floor corner of whichever cell currently holds this run's persistent room
     * (M2 T2.1/T2.4) -- the entrance cell until the run completes, the cell
     * behind the terminal cell after. {@code null} for a run with no room at all
     * (an untimed or admin-built dungeon). This is what
     * {@link Instances#roomOwnerAt} checks against for the T2.2 permission mask.
     */
    BlockPos roomCellOrigin;

    /**
     * Which wall of {@link #roomCellOrigin} the next dungeon extends from (the
     * {@code MM} side). Fixed at lobby creation; updated when the room is moved
     * behind the terminal cell so the next run can start in the right direction.
     */
    DoorMask.Direction roomDungeonDoor = DoorMask.Direction.SOUTH;

    /**
     * True once the room has been moved to the terminal cell and the dungeon has
     * become a lingering quarry (T2.5): force-load tickets released, blocks left
     * standing, slot still claimed so at most one lingers per owner. Exempted
     * from every ordinary expiry/grace check in {@code onTick} -- the only way
     * out is {@code Instances} purging it outright when the owner opens a new run.
     */
    boolean lingering;

    /**
     * True for a read-only visit instance created when someone uses a calling card
     * while the room's owner is away (M3 T3.3). It has no timer, no reward room,
     * and its copy of the room is never captured back to the owner's blob.
     */
    boolean visitInstance;

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<Affix> affixes, UUID owner, boolean untimed) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
        this.affixes = affixes == null ? EnumSet.noneOf(Affix.class) : affixes;
        this.owner = owner;
        this.untimed = untimed;
    }

    /** Whether a keystone was spent to open this run. False for {@code /dungeon admin build}. */
    boolean isKeystoneRun() {
        return layout.keystoneLevel() > 0;
    }

    /** Whether the reward room has been stamped yet -- lazily, on first completion. */
    boolean rewardRoomStamped() {
        return rewardChests >= 0;
    }
}
