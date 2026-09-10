package pocketdungeons;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M66: an immutable request resolved before planning, carrying every recipe
 * effect the floor previewed and committed against. A consumed catalyst changes
 * exactly the floor previewed, or remains recoverable without charging for
 * nothing. This class is the contract between the Cube (where the player frames
 * a recipe) and the planner (where the effects land).
 *
 * <p>M71 generalises the room-shaping effects from hardcoded booleans to
 * data-driven lists. The weighted and guaranteed room operations M66
 * introduced as fixed booleans ({@code floodedChasmWeighted},
 * {@code infestedGuarantee}, {@code deepDarkGuarantee}, {@code storeSpur})
 * are now {@link #weightedRooms} and {@link #guaranteedRooms} lists, so a
 * third-party recipe names its own rooms without a Java edit. The boolean
 * and integer effects ({@link #ominous}, {@link #feral},
 * {@link #completionStudyList}, {@link #boundedSupply},
 * {@link #pathLengthBonus}) are the typed effects M66 shipped; M71 lifts
 * them into {@link RecipeEffects} data and accumulates every active
 * definition's effects here.
 *
 * <p>Conflict order is explicit. An impossible guarantee (a guaranteed room
 * whose {@code min_tier} the offer's loot tier cannot satisfy) refuses
 * before consumption: {@link #resolve} returns {@code null} with a reason
 * in {@link Refusal#reason}, and the caller must not spend the catalyst.
 *
 * <p>Class identity never changes: the bag the player chose stays the bag they
 * enter with. Bounded supply adds a tool cache inside the dungeon; it does not
 * swap the bag.
 *
 * <p>The {@link #revision} field is a stable hash of every input that could
 * change the plan's membership: the recipe id set, the offer level, and
 * the party capability set. A preview stores its revision; a re-preview is required
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
    final boolean completionStudyList;
    final boolean boundedSupply;
    final int pathBonus;

    /** Room names to weight up in the selection pass, qualified. */
    final List<String> weightedRooms;

    /** Room groups to force onto eligible cells after the main pass. */
    final List<RecipeEffects.GuaranteedRoom> guaranteedRooms;

    /** The planning seed captured at resolution time. Freezes the plan membership. */
    final long seed;

    /** The offer level captured at resolution time. Tier checks key off this. */
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

    /** The recipe ids that are active, in resolution order. */
    final Set<String> activeRecipes;

    private RunRecipePlan(boolean ominous, boolean feral, boolean completionStudyList,
                         boolean boundedSupply, int pathBonus,
                         List<String> weightedRooms,
                         List<RecipeEffects.GuaranteedRoom> guaranteedRooms,
                         long seed, int offerLevel,
                         Set<String> affixNames, Set<String> partyCapabilities,
                         int revision, Set<String> activeRecipes) {
        this.ominous = ominous;
        this.feral = feral;
        this.completionStudyList = completionStudyList;
        this.boundedSupply = boundedSupply;
        this.pathBonus = pathBonus;
        this.weightedRooms = List.copyOf(weightedRooms);
        this.guaranteedRooms = List.copyOf(guaranteedRooms);
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
     * <p>M71: the recipe tags are recipe ids (namespaced, or legacy bare keys
     * resolved via {@link RecipeIds#resolve}). Each id is looked up in the
     * live {@link CubeRecipeManifest} and its {@link RecipeEffects} are
     * accumulated. An id the manifest does not carry is dropped silently,
     * the same login-safe lenience a removed pack gets everywhere else.
     *
     * @param seed              the planning seed
     * @param offerLevel        the keystone level of the chosen door
     * @param affixes           the effective affix set (before recipe additions)
     * @param partyCapabilities the bag tags and party-size tags
     * @param recipeTags        the recipe id tags read from the keystone
     * @return the resolved plan, or {@code null} if the effects refuse
     */
    static RunRecipePlan resolve(long seed, int offerLevel, Set<String> affixes,
                                 Set<String> partyCapabilities, CompoundTag recipeTags,
                                 Refusal[] refusalOut) {
        Set<String> activeRecipes = new LinkedHashSet<>();
        boolean ominous = false;
        boolean feral = false;
        boolean completionStudyList = false;
        boolean boundedSupply = false;
        int bonus = 0;
        List<String> weightedRooms = new ArrayList<>();
        List<RecipeEffects.GuaranteedRoom> guaranteedRooms = new ArrayList<>();

        if (recipeTags != null && !recipeTags.isEmpty()) {
            for (String key : recipeTags.keySet()) {
                if (!recipeTags.getBooleanOr(key, false)) {
                    continue;
                }
                String recipeId = RecipeIds.resolve(key);
                if (recipeId == null) {
                    continue;
                }
                CubeRecipeDefinition def = CubeRecipeManifest.current().byId(recipeId);
                if (def == null) {
                    // A recipe id the manifest does not carry (a removed
                    // pack, or a legacy tag with no replacement) is dropped
                    // silently, the same lenience every other manifest has.
                    continue;
                }
                activeRecipes.add(recipeId);
                RecipeEffects fx = def.effects;
                ominous |= fx.ominous;
                feral |= fx.feral;
                completionStudyList |= fx.completionStudyList;
                boundedSupply |= fx.boundedSupply;
                bonus += fx.pathLengthBonus;
                for (String room : fx.weightedRooms) {
                    String qualified = JsonPackSupport.qualify(room);
                    if (!weightedRooms.contains(qualified)) {
                        weightedRooms.add(qualified);
                    }
                }
                for (RecipeEffects.GuaranteedRoom g : fx.guaranteedRooms) {
                    guaranteedRooms.add(g);
                }
            }
        }

        // Conflict order: a guaranteed room whose min_tier the offer's loot
        // tier cannot satisfy refuses before consumption. Generalises M66's
        // hardcoded "Deep Dark requires tier 3" check to any guaranteed room
        // that declares a min_tier.
        int offerTier = KeystoneMath.lootTier(offerLevel);
        for (RecipeEffects.GuaranteedRoom g : guaranteedRooms) {
            if (g.minTier() > offerTier) {
                if (refusalOut != null && refusalOut.length > 0) {
                    refusalOut[0] = new Refusal(
                            "A guaranteed room requires tier " + g.minTier()
                                    + " (keystone level " + (g.minTier() * 5) + "+); "
                                    + "this door is level " + offerLevel + " (tier " + offerTier + ").");
                }
                return null;
            }
        }

        Set<String> affixNames = new LinkedHashSet<>();
        if (affixes != null) {
            affixNames.addAll(affixes);
        }

        int revision = computeRevision(offerLevel, affixNames, partyCapabilities, activeRecipes);

        return new RunRecipePlan(ominous, feral, completionStudyList, boundedSupply,
                bonus, weightedRooms, guaranteedRooms,
                seed, offerLevel, affixNames, partyCapabilities,
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
        return ominous || feral || completionStudyList || boundedSupply || pathBonus > 0
                || !weightedRooms.isEmpty() || !guaranteedRooms.isEmpty();
    }

    /**
     * The path length bonus to add to both min and max path bounds.
     */
    int pathLengthBonus() {
        return pathBonus;
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
