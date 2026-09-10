package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * M71: the bounded operation model a {@link CubeRecipeDefinition} carries.
 * Mirrors {@link AffixEffects}'s shape: every effect a Cube recipe bends is
 * expressed here as a typed field, so a third-party definition declares what
 * its recipe does in data rather than in Java. An operation a JSON file
 * declares that is not one of these fields is a load rejection: the
 * supported set is closed on purpose, the same way the affix and role
 * operation sets are. Genuinely new operations still require reviewed
 * engine work.
 *
 * <p>Pure Java (no Minecraft imports), so the headless tests can build
 * fixtures without the server classpath.
 *
 * <h2>The M66 typed effects</h2>
 *
 * <p>The boolean and integer fields ({@link #ominous}, {@link #feral},
 * {@link #completionStudyList}, {@link #boundedSupply},
 * {@link #pathLengthBonus}) are the typed effects M66 shipped as hardcoded
 * tag keys on the keystone. M71 lifts them into data: a definition declares
 * the same effect a Java constant used to, and the resolver accumulates
 * every active definition's effects into one {@link RunRecipePlan}.
 *
 * <h2>Weighted and guaranteed rooms</h2>
 *
 * <p>The two room-shaping operations M66 introduced are generalised to
 * carry their target room names as data:
 * <ul>
 *   <li><strong>{@link #weightedRooms}</strong>: room names to weight up
 *       in the selection pass. Generalises M66's {@code floodedChasmWeighted}
 *       boolean, which hardcoded Flooded Hall and Chasm. A third-party
 *       recipe names its own rooms.</li>
 *   <li><strong>{@link #guaranteedRooms}</strong>: room groups to force
 *       onto an eligible cell after the main pass. Generalises M66's
 *       {@code infestedGuarantee}, {@code deepDarkGuarantee} and
 *       {@code storeSpur} booleans, which hardcoded their target names and
 *       tier gates. Each group carries its alternative room names and a
 *       minimum loot tier; the resolver refuses a group whose tier the
 *       offer cannot satisfy before the catalyst is spent.</li>
 * </ul>
 *
 * <p>A recipe that declares neither a boolean effect, a path bonus, a
 * weighted room nor a guaranteed room is rejected at load: a definition
 * that bends nothing has no business shipping, the same rule
 * {@link AffixEffects#hasAnyOperation()} enforces for affixes.
 */
final class RecipeEffects {

    /** One group of alternative rooms to force onto an eligible cell, with a tier gate. */
    record GuaranteedRoom(List<String> names, int minTier) {
        GuaranteedRoom {
            names = names == null ? List.of() : List.copyOf(names);
        }
    }

    /** Whether the run starts ominous (the affix is added). */
    final boolean ominous;

    /** Whether the Feral affix is guaranteed. */
    final boolean feral;

    /** Whether the completion line lists the run's situations by name afterward. */
    final boolean completionStudyList;

    /** Whether one guaranteed tool cache is supplied in the dungeon (bounded supply). */
    final boolean boundedSupply;

    /**
     * M78: whether this recipe opens the Endless Mine. The Mine is a run mode,
     * not a room-shaping operation: it forces the Mine theme on every floor and
     * swaps the floor transition policy (no forced safe visit, voluntary
     * cash-out). See {@link EndlessMineRules}.
     */
    final boolean endlessMine;

    /** The path length bonus added to both min and max path bounds. */
    final int pathLengthBonus;

    /** Room names to weight up in the selection pass, qualified at resolve time. */
    final List<String> weightedRooms;

    /** Room groups to force onto eligible cells after the main pass. */
    final List<GuaranteedRoom> guaranteedRooms;

    private RecipeEffects(boolean ominous, boolean feral, boolean completionStudyList,
                          boolean boundedSupply, boolean endlessMine, int pathLengthBonus,
                          List<String> weightedRooms, List<GuaranteedRoom> guaranteedRooms) {
        this.ominous = ominous;
        this.feral = feral;
        this.completionStudyList = completionStudyList;
        this.boundedSupply = boundedSupply;
        this.endlessMine = endlessMine;
        this.pathLengthBonus = pathLengthBonus;
        this.weightedRooms = weightedRooms == null ? List.of() : List.copyOf(weightedRooms);
        this.guaranteedRooms = guaranteedRooms == null ? List.of() : List.copyOf(guaranteedRooms);
    }

    /** The no-op effects: a recipe that does nothing. Used only as the build baseline. */
    static RecipeEffects none() {
        return new RecipeEffects(false, false, false, false, false, 0, List.of(), List.of());
    }

    /**
     * Builds effects from validated parameters. Bounds are enforced here and
     * a violation throws, naming the field, so the manifest loader can catch
     * it and report the file. This is the single chokepoint for the
     * "supported operations and extension limits" gate.
     */
    static RecipeEffects build(boolean ominous, boolean feral, boolean completionStudyList,
                               boolean boundedSupply, boolean endlessMine, int pathLengthBonus,
                               List<String> weightedRooms, List<GuaranteedRoom> guaranteedRooms) {
        if (pathLengthBonus < 0 || pathLengthBonus > 8) {
            throw new IllegalArgumentException("path_length_bonus must be in [0, 8]");
        }
        List<GuaranteedRoom> gRooms = guaranteedRooms == null ? List.of() : guaranteedRooms;
        for (GuaranteedRoom g : gRooms) {
            if (g.names().isEmpty()) {
                throw new IllegalArgumentException("a guaranteed_room group must name at least one room");
            }
            if (g.minTier() < 0 || g.minTier() > 3) {
                throw new IllegalArgumentException("guaranteed_room min_tier must be in [0, 3]");
            }
        }
        return new RecipeEffects(ominous, feral, completionStudyList, boundedSupply, endlessMine,
                pathLengthBonus, weightedRooms, gRooms);
    }

    /**
     * Whether this definition touches any operation at all. A definition
     * that bends nothing is rejected at load: the rule every member owes is
     * that it bends a rule, the same gate {@link AffixEffects#hasAnyOperation()}
     * holds for affixes. The Mine flag counts: opening a Mine is bending the
     * run's transition policy.
     */
    boolean hasAnyOperation() {
        return ominous || feral || completionStudyList || boundedSupply || endlessMine
                || pathLengthBonus > 0
                || !weightedRooms.isEmpty() || !guaranteedRooms.isEmpty();
    }
}
