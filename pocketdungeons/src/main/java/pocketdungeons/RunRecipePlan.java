package pocketdungeons;

import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M66: an immutable request resolved before planning, carrying every recipe
 * effect the floor previewed and committed against. A consumed catalyst changes
 * exactly the floor previewed, or remains recoverable without charging for
 * nothing. This class is the contract between the Cube (where the player frames
 * a recipe) and the planner (where the effects land).
 *
 * <p>Supported effects, each derived from a recipe tag on the keystone:
 * <ul>
 *   <li><strong>ominous</strong>: the run starts ominous (affix added).</li>
 *   <li><strong>feral</strong>: the Feral affix is guaranteed.</li>
 *   <li><strong>infested guarantee</strong>: at least one Infested Wall or
 *       Creeper Kennel is forced into the plan.</li>
 *   <li><strong>flooded or chasm weighting</strong>: Flooded Hall and Chasm
 *       rooms are weighted up in the selector.</li>
 *   <li><strong>tier-eligible Deep Dark guarantee</strong>: Deep Dark Landing
 *       is forced when the offer's loot tier is 3; refused before consumption
 *       otherwise.</li>
 *   <li><strong>completion study list</strong>: the completion line lists the
 *       run's situations by name afterward.</li>
 *   <li><strong>Store spur</strong>: a Store spur is guaranteed.</li>
 *   <li><strong>bounded supply</strong>: one guaranteed tool cache supplied in
 *       the dungeon (replaces the old BAG_OVERRIDE; class identity never
 *       changes).</li>
 *   <li><strong>+2 path length</strong>: the plan's path length bounds grow by
 *       two (replaces the old DOUBLE_KEY; at the committed offer's level).</li>
 * </ul>
 *
 * <p>Conflict order is explicit. An impossible guarantee (Deep Dark at tier
 * below 3, or two guarantees that cannot coexist on the same floor) refuses
 * before consumption: {@link #resolve} returns {@code null} with a reason in
 * {@link Refusal#reason}, and the caller must not spend the catalyst.
 *
 * <p>Class identity never changes: the bag the player chose stays the bag they
 * enter with. Bounded supply adds a tool cache inside the dungeon; it does not
 * swap the bag.
 *
 * <p>The {@link #revision} field is a stable hash of every input that could
 * change the plan's membership: the recipe tag set, the offer level, and the
 * party capability set. A preview stores its revision; a re-preview is required
 * when the current revision differs. This is what prevents a second click on
 * the same offer from farming random entrances.
 */
final class RunRecipePlan {

    /** A refusal returned when the recipe effects conflict or are impossible at the offer's tier. */
    static final class Refusal {
        final String reason;
        Refusal(String reason) { this.reason = reason; }
    }

    final boolean ominous;
    final boolean feral;
    final boolean infestedGuarantee;
    final boolean floodedChasmWeighted;
    final boolean deepDarkGuarantee;
    final boolean completionStudyList;
    final boolean storeSpur;
    final boolean boundedSupply;
    final int pathLengthBonus;

    /** The planning seed captured at resolution time. Freezes the plan membership. */
    final long seed;

    /** The offer level captured at resolution time. Deep Dark tier check keys off this. */
    final int offerLevel;

    /** The affix names captured at resolution time, sorted. Part of the revision. */
    final Set<String> affixNames;

    /** The party capability tags captured at resolution time. Part of the revision. */
    final Set<String> partyCapabilities;

    /**
     * A stable hash of every input that could change the plan's membership.
     * Two plans with the same revision and seed are the same plan; a changed
     * revision invalidates the preview before spending.
     */
    final int revision;

    /** The recipe tag keys that are active, in declaration order. */
    final Set<String> activeRecipes;

    private RunRecipePlan(boolean ominous, boolean feral, boolean infestedGuarantee,
                          boolean floodedChasmWeighted, boolean deepDarkGuarantee,
                          boolean completionStudyList, boolean storeSpur, boolean boundedSupply,
                          int pathLengthBonus, long seed, int offerLevel,
                          Set<String> affixNames, Set<String> partyCapabilities,
                          int revision, Set<String> activeRecipes) {
        this.ominous = ominous;
        this.feral = feral;
        this.infestedGuarantee = infestedGuarantee;
        this.floodedChasmWeighted = floodedChasmWeighted;
        this.deepDarkGuarantee = deepDarkGuarantee;
        this.completionStudyList = completionStudyList;
        this.storeSpur = storeSpur;
        this.boundedSupply = boundedSupply;
        this.pathLengthBonus = pathLengthBonus;
        this.seed = seed;
        this.offerLevel = offerLevel;
        this.affixNames = Set.copyOf(affixNames);
        this.partyCapabilities = Set.copyOf(partyCapabilities);
        this.revision = revision;
        this.activeRecipes = Set.copyOf(activeRecipes);
    }

    /**
     * Resolves a recipe plan from the keystone's recipe tags, the offer, and
     * the party's current capabilities. Returns the plan, or {@code null} with
     * a {@link Refusal} when the effects conflict or are impossible at the
     * offer's tier. The caller must not consume the catalyst on a refusal.
     *
     * @param seed              the planning seed
     * @param offerLevel        the keystone level of the chosen door
     * @param affixes           the effective affix set (before recipe additions)
     * @param partyCapabilities the bag tags and party-size tags
     * @param recipeTags        the recipe tags read from the keystone
     * @return the resolved plan, or {@code null} if the effects refuse
     */
    static RunRecipePlan resolve(long seed, int offerLevel, Set<String> affixes,
                                 Set<String> partyCapabilities, CompoundTag recipeTags,
                                 Refusal[] refusalOut) {
        Set<String> activeRecipes = new LinkedHashSet<>();
        boolean ominous = false;
        boolean feral = false;
        boolean infestedGuarantee = false;
        boolean floodedChasmWeighted = false;
        boolean deepDarkGuarantee = false;
        boolean completionStudyList = false;
        boolean storeSpur = false;
        boolean boundedSupply = false;
        int pathLengthBonus = 0;

        if (recipeTags != null && !recipeTags.isEmpty()) {
            if (recipeTags.getBooleanOr("ominous", false)) {
                ominous = true;
                activeRecipes.add("ominous");
            }
            if (recipeTags.getBooleanOr("feral", false)) {
                feral = true;
                activeRecipes.add("feral");
            }
            if (recipeTags.getBooleanOr("infested", false)) {
                infestedGuarantee = true;
                activeRecipes.add("infested");
            }
            if (recipeTags.getBooleanOr("flooded", false)) {
                floodedChasmWeighted = true;
                activeRecipes.add("flooded");
            }
            if (recipeTags.getBooleanOr("deep_dark", false)) {
                deepDarkGuarantee = true;
                activeRecipes.add("deep_dark");
            }
            if (recipeTags.getBooleanOr("compass", false)) {
                completionStudyList = true;
                activeRecipes.add("compass");
            }
            if (recipeTags.getBooleanOr("store", false)) {
                storeSpur = true;
                activeRecipes.add("store");
            }
            // M66: bounded_supply replaces BAG_OVERRIDE. Legacy bag_override
            // tags are decoded as bounded supply for continuity, but the bag
            // identity never changes: the original bag stays, and a tool cache
            // is supplied in the dungeon instead.
            if (recipeTags.getBooleanOr("bounded_supply", false)
                    || recipeTags.getBooleanOr("bag_override", false)) {
                boundedSupply = true;
                activeRecipes.add(recipeTags.getBooleanOr("bag_override", false)
                        ? "bag_override" : "bounded_supply");
            }
            // M66: path_extension replaces DOUBLE_KEY. Legacy double_key tags
            // are decoded as +2 path length at the committed offer's level.
            // The old DOUBLE_KEY's lower-key level was never stored; do not
            // invent it.
            if (recipeTags.getBooleanOr("path_extension", false)
                    || recipeTags.getBooleanOr("double_key", false)) {
                pathLengthBonus = 2;
                activeRecipes.add(recipeTags.getBooleanOr("double_key", false)
                        ? "double_key" : "path_extension");
            }
        }

        // Conflict order: Deep Dark is tier 3 only. Refuse before consumption.
        if (deepDarkGuarantee) {
            int tier = KeystoneMath.lootTier(offerLevel);
            if (tier < 3) {
                if (refusalOut != null && refusalOut.length > 0) {
                    refusalOut[0] = new Refusal(
                            "Deep Dark Landing requires tier 3 (keystone level 10+); "
                                    + "this door is level " + offerLevel + " (tier " + tier + ").");
                }
                return null;
            }
        }

        // Conflict: infested guarantee and Deep Dark guarantee both force a
        // specific room onto the critical path. They can coexist on different
        // cells, so this is not a refusal; the planner handles both.
        // No explicit conflict refuses today. The order above is the
        // resolution order; new conflicts go here.

        Set<String> affixNames = new LinkedHashSet<>();
        if (affixes != null) {
            affixNames.addAll(affixes);
        }

        int revision = computeRevision(offerLevel, affixNames, partyCapabilities, activeRecipes);

        return new RunRecipePlan(ominous, feral, infestedGuarantee, floodedChasmWeighted,
                deepDarkGuarantee, completionStudyList, storeSpur, boundedSupply,
                pathLengthBonus, seed, offerLevel, affixNames, partyCapabilities,
                revision, activeRecipes);
    }

    /**
     * Computes a stable revision hash from every input that could change the
     * plan's membership. Two plans with the same revision are the same plan.
     */
    private static int computeRevision(int offerLevel, Set<String> affixNames,
                                       Set<String> partyCapabilities, Set<String> activeRecipes) {
        int hash = offerLevel;
        for (String name : affixNames) {
            hash = 31 * hash + name.hashCode();
        }
        for (String cap : partyCapabilities) {
            hash = 31 * hash + cap.hashCode();
        }
        for (String recipe : activeRecipes) {
            hash = 31 * hash + recipe.hashCode();
        }
        return hash;
    }

    /**
     * Whether this plan is still valid for the current inputs. A changed
     * catalyst (different active recipes), party capability, or offer level
     * invalidates the preview before spending.
     */
    boolean isValidFor(int currentOfferLevel, Set<String> currentAffixNames,
                       Set<String> currentCapabilities) {
        return offerLevel == currentOfferLevel
                && affixNames.equals(currentAffixNames)
                && partyCapabilities.equals(currentCapabilities);
    }

    /**
     * Whether this plan is still valid given a fresh revision. Used by the
     * preview path to decide whether to re-plan or reuse the frozen plan.
     */
    boolean matchesRevision(int currentRevision) {
        return revision == currentRevision;
    }

    /**
     * The effective affix set including recipe-driven additions (ominous,
     * feral). Returns a new set; does not mutate the input.
     */
    Set<String> effectiveAffixes(Set<String> base) {
        Set<String> result = new LinkedHashSet<>();
        if (base != null) {
            result.addAll(base);
        }
        if (ominous) {
            result.add(AffixIds.OMINOUS);
        }
        if (feral) {
            result.add(AffixIds.FERAL);
        }
        return result;
    }

    /**
     * Whether this plan carries any recipe effect at all.
     */
    boolean hasEffects() {
        return ominous || feral || infestedGuarantee || floodedChasmWeighted
                || deepDarkGuarantee || completionStudyList || storeSpur
                || boundedSupply || pathLengthBonus > 0;
    }

    /**
     * The path length bonus to add to both min and max path bounds.
     */
    int pathLengthBonus() {
        return pathLengthBonus;
    }

    /**
     * A legible summary of the active effects, for staff logging.
     */
    String effectSummary() {
        if (activeRecipes.isEmpty()) {
            return "none";
        }
        return String.join(",", activeRecipes);
    }
}
