package pocketdungeons;

import net.minecraft.core.BlockPos;

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
     */
    final InstanceLayout layout;

    /** Member -> where that member came from. Insertion-ordered: opener first. */
    final Map<UUID, ReturnPoint> members = new LinkedHashMap<>();

    /** Cells any member has stood in, for the boss bar's room-visited counter. */
    final Set<PlanCell> visited = new HashSet<>();

    // ---- keystone run state --------------------------------------------------
    // All of it dies with the instance, which is the whole point: the keystone
    // itself lives in DungeonLog, so there is no run to persist and nothing here
    // has to survive a restart.

    /** The affix the spent keystone carried; {@code FRAGILE} doubles every depletion. */
    final Keystone.Affix affix;

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
     * How many of the reward room's three chests were earned, fixed at the first
     * completion and shared by every member who reaches it afterwards. {@code -1}
     * until the reward room has been stamped.
     */
    int rewardChests = -1;

    /** Floor corner of the reward room, once stamped (T10). Null until then. */
    BlockPos rewardRoomOrigin;

    /**
     * True for the small, private, single-player instance a completed run's door
     * choice is presented in (U8 Stage 3). Not a keystone run in its own right --
     * it carries no timer, no reward room, and closes the moment its one member
     * leaves or chooses, unlike an ordinary dungeon which now outlives its members.
     */
    final boolean selectorRoom;

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

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Keystone.Affix affix, UUID owner) {
        this(slot, origin, createdAtTick, layout, affix, owner, false, false);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Keystone.Affix affix, UUID owner, boolean selectorRoom) {
        this(slot, origin, createdAtTick, layout, affix, owner, selectorRoom, false);
    }

    InstanceRecord(int slot, BlockPos origin, long createdAtTick, InstanceLayout layout,
                   Keystone.Affix affix, UUID owner, boolean selectorRoom, boolean untimed) {
        this.slot = slot;
        this.origin = origin;
        this.createdAtTick = createdAtTick;
        this.layout = layout;
        this.affix = affix;
        this.owner = owner;
        this.selectorRoom = selectorRoom;
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
