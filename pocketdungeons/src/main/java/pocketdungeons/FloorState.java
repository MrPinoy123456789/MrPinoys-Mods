package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The state of one floor: everything that lives from the commit that opens a
 * floor until the commit that opens the next one (or the end of the
 * interval).
 *
 * <p>The rule: a floor starts with {@code record.floor = new FloorState()}
 * inside {@link InstanceRecord#startFloor}, and an interval ends with
 * {@link InstanceRecord#beginInterval}, which replaces it too. Nothing here
 * is cleared by hand. The cleared floor's state stays in place through
 * {@code FLOOR_CLEARED}: settlement reads its reward chests and theme there,
 * and the door choice for the next floor (selection and preview) is recorded
 * on it until the commit replaces it.
 *
 * <p>The floor's layout stays on {@link InstanceRecord#layout}: it is also the
 * lobby's one-cell layout at home, and a dozen lookups outside the loop read
 * it.
 */
final class FloorState {

    // ---- what opened this floor ------------------------------------------------

    /**
     * The affixes riding on this floor, as the commit resolved them. Empty at
     * home, and for the admin untimed run whatever its command seeded.
     */
    Set<String> affixes = Set.of();

    /** The theme id this floor was stamped with, or {@code null} for none (and at home). */
    String theme;

    /**
     * Which of {@code Keystone.offers}' three doors (1, 2 or 3) opened this
     * floor, or {@code 0} before any door has. Recorded onto the interval's
     * {@code floorSteps} when the floor is cleared.
     */
    int chosenStep;

    /** Whether {@link #chosenStep} was door 1, the free tier: it pays fuel at the settlement. */
    boolean freeDoor;

    /**
     * The recipe tags the commit resolved from its preview, as a compound of
     * {@code recipe_key -> true}. {@code null} when the floor carried no recipe.
     */
    CompoundTag recipeTags;

    /** The situation (or room) names this floor's cells resolved to, for the compass recipe's study list. */
    final List<String> situations = new ArrayList<>();

    // ---- progress on this floor ------------------------------------------------

    /**
     * Members who have reached the terminal pad. The first contact advances
     * the floor; every member who touches it while the floor is cleared is
     * credited once.
     */
    final Set<UUID> completed = new HashSet<>();

    /** Members currently standing on a pad, so a contact is an edge and not a state. */
    final Set<UUID> onPad = new HashSet<>();

    /**
     * Members whose keystone has already been settled for this floor. Makes
     * {@code RunLifecycle.returnKeystone} idempotent when an exit and the purge
     * behind it reach for the same member in one tick.
     */
    final Set<UUID> keystoneReturned = new HashSet<>();

    /** Cells whose every trial spawner has reached cooldown, so the cleared chime fires once per cell. */
    final Set<PlanCell> clearedCells = new HashSet<>();

    /**
     * The committed plan's cell to room map ({@link FloorRooms#of}), so a
     * position resolves to a room id. Empty before the first commit and for
     * runs built without a floor plan (admin builds, untimed runs).
     */
    Map<PlanCell, FloorRooms.Room> rooms = Map.of();

    /** Plan cells some member has stood in on this floor, for the journal's first-entry event. */
    final Set<PlanCell> enteredRooms = new HashSet<>();

    /** Overworld game time at the commit that opened this floor, or 0 before one. */
    long startedAtTick;

    /** This floor's trial spawners grouped by cell, built on the first watch tick and reused after. */
    Map<PlanCell, List<BlockPos>> spawnerCellsByCell;

    /**
     * The completion gate's last reading from the watcher: trial spawners at
     * cooldown, and how many exist. {@code -1} until the first watch tick of
     * the floor. The omen bar reads these rather than scanning again.
     */
    int spawnersCleared = -1;
    int spawnersTotal = -1;

    /**
     * How many completion chests the cleared floor earned, from the interval's
     * omen band. {@code -1} until the floor is cleared.
     */
    int rewardChests = -1;

    /**
     * Game tick at which an idle cleared floor tears itself down, or {@code 0}
     * while unarmed. Armed on the first completion and pushed back for as long
     * as anyone active is still inside.
     */
    long expiresAtTick;

    /**
     * Whether this floor's trial spawners have been added to the interval's
     * bounty tally, so leaving the floor and settling the interval on it do
     * not count it twice.
     */
    boolean spawnersTallied;

    // ---- the choice of the next floor ------------------------------------------

    /** Which selector door (1, 2 or 3) the owner last right-clicked, or {@code 0}. */
    int selectedStep;

    /** The plan behind the current door preview, reused by the commit. {@code null} with no preview. */
    DungeonPlan previewPlan;

    /**
     * PD-90: {@link PlaytestBias#generation} when {@link #previewPlan} was
     * planned, so a commit can tell the bias changed under an open preview.
     */
    int previewBiasGeneration;

    /** The world origin of the stamped preview cell, or {@code null} with no preview. */
    BlockPos previewCellOrigin;

    /**
     * The recipe plan the preview was resolved against, so the commit applies
     * exactly the effects the party saw and a second click on the same door
     * does not farm a new entrance. {@code null} with no preview.
     */
    RunRecipePlan previewRecipePlan;

    /** The door step (1, 2 or 3) of the current preview, or {@code 0}. */
    int previewOfferStep;
}
