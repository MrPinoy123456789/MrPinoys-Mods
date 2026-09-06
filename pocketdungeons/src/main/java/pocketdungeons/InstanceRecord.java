package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * (M48) Block positions placed by a player inside a dungeon cell, mapped
     * to the placer's UUID, for the "right tool for the job" exemption: a
     * player can always break what they placed, regardless of tool. In-memory,
     * dies with the instance. Populated by {@code BlockItemPlaceMixin} on
     * every successful block placement in the dungeon dimension. Only the
     * placer is exempt; other party members still need the correct tool.
     */
    final Map<BlockPos, UUID> playerPlaced = new HashMap<>();

    /**
     * M22: cells whose every trial spawner has been cleared (each at
     * {@code COOLDOWN}), for the spawner-cleared cue. In-memory like every
     * other field on the record; dies with the instance, never persisted.
     * A cell is added exactly once, when it first reaches fully cleared, so
     * the cue fires once per cell per run. Cleared alongside the other
     * per-run state in {@code Instances.generateBehindLobby}, so a second
     * run behind the same lobby cues its cells fresh.
     */
    final Set<PlanCell> clearedCells = new HashSet<>();

    /**
     * Trial spawners that have been announced as cleared (at
     * {@code COOLDOWN}) in chat to the party, for the per-spawner progress
     * message. In-memory like every other field on the record; dies with the
     * instance, never persisted. A spawner is added exactly once, when it
     * first reaches COOLDOWN, so the progress message fires once per spawner
     * per run. Cleared alongside the other per-run state in
     * {@code Instances.generateBehindLobby}, so a second run behind the same
     * lobby announces its spawners fresh.
     */
    final Set<BlockPos> announcedSpawners = new HashSet<>();

    /**
     * M18-M22 review fix: the run's trial spawners grouped by cell, for the
     * spawner-cleared watcher ({@code Instances.watchSpawnerClears}). Built
     * once per layout instead of every watch tick;
     * {@link #spawnerCellsLayout} records which layout it was built from, so
     * a second run behind the same lobby (which reuses this record with a
     * fresh layout) rebuilds the grouping rather than re-scanning stale
     * cells. In-memory like every other field; dies with the instance.
     */
    Map<PlanCell, List<BlockPos>> spawnerCellsByCell;

    /** The layout {@link #spawnerCellsByCell} was built from; {@code null} when never built. */
    InstanceLayout spawnerCellsLayout;

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
     * M65: the run's current phase in the floor loop state machine
     * ({@link RunSession.Phase}). Replaces the implicit phase that was
     * scattered across {@code awaitingDoorChoice}, {@code previewPlan},
     * {@code timer}, {@code safeStaging} and {@code floorIndex}. Every
     * lifecycle method checks or transitions this before doing its work;
     * see {@link RunSession} for the transition table.
     */
    RunSession.Phase phase = RunSession.Phase.HOME;

    /**
     * Which of {@code Keystone.offers(level)}'s three doors (1, 2 or 3) was
     * chosen to open this run, or {@code 0} before that happens. Carried so a
     * completing member's own banked level/affix (T2.4-adjacent) can be
     * computed from *their* current keystone level at the same step the
     * opener chose, rather than the opener's -- see {@code completeRun}.
     */
    int chosenStep;

    /**
     * M19: which selector door (1, 2 or 3) the owner last right-clicked while
     * this lobby was {@link #awaitingDoorChoice}, or {@code 0} for no selection.
     * The physical door screen and the copper bulbs render this; pulling the
     * room's commit lever starts the run for this step. Reset to 0 the moment
     * a run starts (see {@code Instances.generateBehindLobby}) and when the
     * room is re-armed behind the terminal cell (see
     * {@code RunLifecycle.completeDungeon}); a fresh lobby gets a fresh
     * record, so its default of 0 is never carried over.
     */
    int selectedStep;

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
     * True for an operator's {@code /dungeon admin buildroom} shell: one empty
     * cell with no doors, no timer, no keystone, and no room to save. The
     * record exists so the shell has an owner to find it by and a slot to tear
     * down, and so {@code /dungeon admin saveroom} knows which cell to capture.
     * One per player: opening another build room tears the old one down.
     */
    final boolean adminBuild;

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
     * True from the moment {@code InstanceTeardown.purge} or
     * {@code retireOrPurge} starts tearing this record down, before either
     * one ejects a single member. Both methods eject online members through
     * the exact same {@code Instances.eject} path a voluntary exit uses.
     * Without this flag, {@link RunLifecycle#isReenterable} would see every
     * other field on a record mid-teardown still reading as a perfectly
     * ordinary, reenterable run ({@code lingering} is not set until the very
     * end of {@code retireOrPurge}, and a plain {@code purge} never sets it
     * at all), and {@link RunLifecycle#reenterableInstance} would offer the
     * owner a free re-entry into an instance that is seconds away from being
     * removed from {@code InstanceRegistry.bySlot} entirely.
     * {@link RunLifecycle#isReenterable} checks this first.
     */
    boolean tearingDown;

    /**
     * True for a read-only visit instance created when someone visits while the
     * room's owner is away (M3 T3.3). It has no timer, no reward room,
     * and its copy of the room is never captured back to the owner's blob.
     */
    boolean visitInstance;

    // ---- M25: Pocket2 child instance ----------------------------------------

    /**
     * The slot of the parent instance this Pocket2 child belongs to, or {@code -1}
     * for a top-level run. A child is a nested sub-dungeon opened from a rare
     * door in the parent's cleared encounter room: its own slot, its own cells,
     * no keystone, no spawner gate, no completion pad. Only the countdown timer
     * or a player death ends it, and its members are returned to the parent.
     */
    final int parentSlot;

    /**
     * Where a member of this Pocket2 child is returned on expiry or death: the
     * standing spot just inside the parent's rare door. {@code null} for a
     * top-level run.
     */
    final BlockPos returnPos;

    /**
     * M25: the game tick at which this Pocket2 child's countdown hits zero, or
     * {@code 0} while no deadline is armed. Children are ticked by this, not by
     * the outer run's clock: time in the pocket is time the outer run counts.
     */
    long deadlineTick;

    // ---- M45: the situations round's seam ------------------------------------
    // All four are unused today. They are declared here so wave 3 can consume
    // them without opening this file while wave 2 is still running.

    /**
     * The run's accumulated omen. M48 is the only consumer: it is what pressure
     * rooms raise and what the run's difficulty reads back. In memory like
     * every other field on this record; it dies with the instance.
     */
    int omen;

    /**
     * M48: each completed floor's clamped omen, in order, since the last safe
     * room visit. {@code omen} above is the floor in progress; this is what
     * {@link Omen#floorSum} keys the finish table from.
     */
    final java.util.List<Integer> floorOmens = new java.util.ArrayList<>();

    /**
     * The situation ids this floor's cells resolved to, in the order they were
     * resolved. M59 reads it for the compass Cube recipe and the completion
     * line. Mutable and empty for a run whose cells carried no situation, which
     * is every run today.
     */
    final List<String> situations = new ArrayList<>();

    /**
     * Which floor of the run this instance is, counting the first from 0. M57
     * is the consumer: a multi-floor run needs to know which one it is
     * standing on to pick the next.
     */
    int floorIndex;

    /**
     * Floor corner of this floor's hidden staging room, or {@code null} while
     * the floor has none. M55 is the consumer; it stamps the room and records
     * where it put it.
     */
    BlockPos stagingCellOrigin;

    /**
     * (M56) The plan produced by the current door preview, or {@code null}
     * if no preview is active. Stored so {@code commitDoor} can re-use the
     * exact same plan rather than re-planning with a new seed.
     */
    DungeonPlan previewPlan;

    /**
     * (M56) The world origin of the cell stamped as the current door
     * preview, or {@code null} if no preview is active. Purged on switch
     * or commit.
     */
    BlockPos previewCellOrigin;

    /**
     * (M57) Whether the current staging room is a safe staging room, which
     * offers a safe door back to the safe room instead of three dungeon
     * doors. Set by {@code completeDungeon} when {@code floorIndex + 1} is a
     * multiple of {@code floorsPerSafeVisit}.
     */
    boolean safeStaging;

    /**
     * M65: set by {@code returnToSafe} after the saved room is stamped
     * behind the final staging door and the door is opened. The party
     * walks through physically; no teleport. {@code onTick} checks this
     * flag and, once all members have crossed into the room, releases
     * the old staging room and any remaining old floor cells, then sets
     * up the new staging room adjacent to the room. Cleared when cleanup
     * completes.
     */
    boolean pendingHomecomingCleanup;

    /**
     * M65: the old staging room origin that needs to be released once
     * all members cross into the safe room during a silent homecoming.
     * Set alongside {@link #pendingHomecomingCleanup} and cleared when
     * cleanup completes.
     */
    BlockPos oldStagingCellOrigin;

    /**
     * M65: the old layout whose cells need to be released once all
     * members cross into the safe room during a silent homecoming.
     * Set alongside {@link #pendingHomecomingCleanup} and cleared when
     * cleanup completes.
     */
    InstanceLayout oldLayoutForCleanup;

    /**
     * (M59) The recipe tags read from the keystone at commit time, stored as
     * a CompoundTag under the {@code recipe} key. The generation path reads
     * these to adjust the plan. Cleared after the first floor of a visit.
     */
    net.minecraft.nbt.CompoundTag recipeTags;

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<Affix> affixes, UUID owner, boolean untimed) {
        this(slot, origin, createdAtTick, layout, affixes, owner, untimed, false);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<Affix> affixes, UUID owner, boolean untimed, boolean adminBuild) {
        this(slot, origin, createdAtTick, layout, affixes, owner, untimed, adminBuild, -1, null);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Set<Affix> affixes, UUID owner, boolean untimed, boolean adminBuild,
                   int parentSlot, BlockPos returnPos) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
        this.affixes = affixes == null ? EnumSet.noneOf(Affix.class) : affixes;
        this.owner = owner;
        this.untimed = untimed;
        this.adminBuild = adminBuild;
        this.parentSlot = parentSlot;
        this.returnPos = returnPos;
    }

    /**
     * M43.1: clears every field that belongs to the run that just ended, not
     * the one about to start, when this record is reused for a second run
     * behind the same lobby (M3: finish a dungeon, choose again without ever
     * leaving). Colocated with the field declarations above rather than
     * living inline in {@code Instances.generateBehindLobby}'s much larger
     * body, on purpose: {@code timedOutPenaltyApplied} (PD-49) went missing
     * from that inline block for a while precisely because nothing forced
     * whoever added the field to notice the reset lived hundreds of lines
     * away in a different file. This does not make a forgotten field a
     * compile error, but it puts the checklist next to the fields it has to
     * cover, which is the cheapest real improvement available without
     * migrating every one of this class's external readers onto a separate
     * mutable run-state object.
     *
     * <p>Left out on purpose: {@link #layout}, {@link #affixes},
     * {@link #theme}, {@link #awaitingDoorChoice}, {@link #chosenStep},
     * {@link #freeDoor}, {@link #selectedStep} and {@link #timer} are all
     * freshly assigned by the caller for the new run rather than cleared to
     * a default, so they stay set directly in
     * {@code Instances.generateBehindLobby} where the new values are
     * actually computed.
     */
    void clearPreviousRunState() {
        completed.clear();
        keystoneReturned.clear();
        onPad.clear();
        visited.clear();
        // The spawner-cleared cue is per-run state too (M22): a cell added in
        // the run that just ended must cue again in the next one.
        clearedCells.clear();
        announcedSpawners.clear();
        rewardChests = -1;
        expiresAtTick = 0;
        // PD-49: a run that timed out leaves this true; left uncleared, the
        // next run behind the same lobby skips its own timeout penalty and
        // arms the reward-room grace window on its very first watcher tick.
        timedOutPenaltyApplied = false;
        // M65: clear the homecoming cleanup state. A new visit starts
        // fresh; any pending cleanup from the previous visit was either
        // completed or the record was purged.
        pendingHomecomingCleanup = false;
        oldStagingCellOrigin = null;
        oldLayoutForCleanup = null;
    }

    /** Whether a keystone was spent to open this run. False for {@code /dungeon admin build}. */
    boolean isKeystoneRun() {
        return layout.keystoneLevel() > 0;
    }

    /** M25: whether this record is a Pocket2 child of a parent instance. */
    boolean isChild() {
        return parentSlot >= 0;
    }

}
