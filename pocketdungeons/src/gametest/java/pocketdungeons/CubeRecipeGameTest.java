package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * M66: coverage for the recipe planning contract. A consumed catalyst changes
 * exactly the floor previewed, or remains recoverable without charging for
 * nothing. These tests verify the {@link RunRecipePlan} resolution, the
 * preview-freeze contract, and the refusal of impossible guarantees before
 * consumption.
 *
 * <p>Same package rationale as {@link FloorLoopGameTest}: {@link RunRecipePlan}
 * and {@link CubeRecipe} are package-private, and sharing the package is
 * cheaper than opening it.
 */
public final class CubeRecipeGameTest {

    /**
     * The core contract: once a preview resolves a recipe plan, the recipe
     * membership (which recipes are active), the effect set, and the seed are
     * frozen. Unrelated record changes do not invalidate the plan. A changed
     * catalyst (different recipe tags), party capability, or offer level
     * invalidates the preview before spending.
     */
    @GameTest(maxTicks = 20)
    public void previewFreezesRecipeMembership(GameTestHelper helper) {
        long seed = 12345L;
        int offerLevel = 5;
        Set<Affix> affixes = EnumSet.noneOf(Affix.class);
        Set<String> capabilities = Set.of("blocks", "torch");

        // Build recipe tags for a store + compass run.
        CompoundTag recipeTags = new CompoundTag();
        recipeTags.putBoolean("store", true);
        recipeTags.putBoolean("compass", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(seed, offerLevel, affixes,
                capabilities, recipeTags, refusal);
        if (plan == null) {
            helper.fail("Store + compass should resolve, not refuse: "
                    + (refusal[0] != null ? refusal[0].reason : "null"));
            return;
        }

        // Assert exact recipe membership: store and compass are active.
        if (!plan.activeRecipes.contains("store")) {
            helper.fail("Expected 'store' in active recipes");
            return;
        }
        if (!plan.activeRecipes.contains("compass")) {
            helper.fail("Expected 'compass' in active recipes");
            return;
        }
        if (plan.activeRecipes.size() != 2) {
            helper.fail("Expected exactly 2 active recipes, got " + plan.activeRecipes.size());
            return;
        }

        // Assert effect set: storeSpur and completionStudyList are true.
        if (!plan.storeSpur) {
            helper.fail("Expected storeSpur to be true");
            return;
        }
        if (!plan.completionStudyList) {
            helper.fail("Expected completionStudyList to be true");
            return;
        }
        // Effects that should NOT be active.
        if (plan.ominous || plan.feral || plan.infestedGuarantee
                || plan.floodedChasmWeighted || plan.deepDarkGuarantee
                || plan.boundedSupply || plan.pathLengthBonus > 0) {
            helper.fail("Unexpected active effect in store+compass plan");
            return;
        }

        // Assert seed is frozen.
        if (plan.seed != seed) {
            helper.fail("Expected seed " + seed + " but got " + plan.seed);
            return;
        }

        // Unrelated record changes do not invalidate the plan.
        // The revision is a hash of offerLevel, affixNames, capabilities,
        // and activeRecipes. Changing something not in that set (like the
        // seed) does not change the revision.
        int originalRevision = plan.revision;
        if (!plan.matchesRevision(originalRevision)) {
            helper.fail("Plan should match its own revision");
            return;
        }

        // A second resolve with the same inputs produces the same revision.
        RunRecipePlan samePlan = RunRecipePlan.resolve(seed + 999L, offerLevel, affixes,
                capabilities, recipeTags, refusal);
        if (samePlan == null) {
            helper.fail("Second resolve with same inputs should not refuse");
            return;
        }
        if (samePlan.revision != originalRevision) {
            helper.fail("Same inputs should produce same revision: "
                    + samePlan.revision + " vs " + originalRevision);
            return;
        }
        // The seed is different (it is not part of the revision), but the
        // recipe membership is the same.
        if (!samePlan.activeRecipes.equals(plan.activeRecipes)) {
            helper.fail("Same inputs should produce same active recipes");
            return;
        }

        // Changed catalyst (different recipe tags) invalidates the preview.
        CompoundTag differentTags = new CompoundTag();
        differentTags.putBoolean("ominous", true);
        RunRecipePlan differentPlan = RunRecipePlan.resolve(seed, offerLevel, affixes,
                capabilities, differentTags, refusal);
        if (differentPlan == null) {
            helper.fail("Ominous-only plan should resolve");
            return;
        }
        if (differentPlan.matchesRevision(originalRevision)) {
            helper.fail("Changed catalyst should invalidate the revision");
            return;
        }
        if (differentPlan.activeRecipes.equals(plan.activeRecipes)) {
            helper.fail("Changed catalyst should change active recipes");
            return;
        }

        // Changed party capability invalidates the preview.
        Set<String> differentCapabilities = Set.of("blocks", "torch", "water");
        RunRecipePlan capChanged = RunRecipePlan.resolve(seed, offerLevel, affixes,
                differentCapabilities, recipeTags, refusal);
        if (capChanged == null) {
            helper.fail("Plan with different capabilities should resolve");
            return;
        }
        if (capChanged.matchesRevision(originalRevision)) {
            helper.fail("Changed party capability should invalidate the revision");
            return;
        }

        // Changed offer level invalidates the preview.
        RunRecipePlan levelChanged = RunRecipePlan.resolve(seed, offerLevel + 1, affixes,
                capabilities, recipeTags, refusal);
        if (levelChanged == null) {
            helper.fail("Plan with different offer level should resolve");
            return;
        }
        if (levelChanged.matchesRevision(originalRevision)) {
            helper.fail("Changed offer level should invalidate the revision");
            return;
        }

        // isValidFor checks: same inputs pass, changed inputs fail.
        if (!plan.isValidFor(offerLevel, Set.of(), capabilities)) {
            helper.fail("Plan should be valid for same offer level and capabilities");
            return;
        }
        if (plan.isValidFor(offerLevel + 1, Set.of(), capabilities)) {
            helper.fail("Plan should be invalid for different offer level");
            return;
        }
        if (plan.isValidFor(offerLevel, Set.of(), differentCapabilities)) {
            helper.fail("Plan should be invalid for different capabilities");
            return;
        }

        helper.succeed();
    }

    /**
     * Deep Dark guarantee refuses before consumption when the offer's tier is
     * below 3. The catalyst must not be spent on a refusal.
     */
    @GameTest(maxTicks = 20)
    public void deepDarkRefusesBelowTier3(GameTestHelper helper) {
        CompoundTag deepDarkTags = new CompoundTag();
        deepDarkTags.putBoolean("deep_dark", true);

        // Tier 1 (level 1-4): should refuse.
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan tier1 = RunRecipePlan.resolve(42L, 3, EnumSet.noneOf(Affix.class),
                Set.of(), deepDarkTags, refusal);
        if (tier1 != null) {
            helper.fail("Deep Dark at tier 1 should refuse, not resolve");
            return;
        }
        if (refusal[0] == null) {
            helper.fail("Deep Dark at tier 1 should set a refusal reason");
            return;
        }

        // Tier 2 (level 5-9): should also refuse.
        refusal[0] = null;
        RunRecipePlan tier2 = RunRecipePlan.resolve(42L, 7, EnumSet.noneOf(Affix.class),
                Set.of(), deepDarkTags, refusal);
        if (tier2 != null) {
            helper.fail("Deep Dark at tier 2 should refuse, not resolve");
            return;
        }

        // Tier 3 (level 10+): should resolve.
        refusal[0] = null;
        RunRecipePlan tier3 = RunRecipePlan.resolve(42L, 10, EnumSet.noneOf(Affix.class),
                Set.of(), deepDarkTags, refusal);
        if (tier3 == null) {
            helper.fail("Deep Dark at tier 3 should resolve: "
                    + (refusal[0] != null ? refusal[0].reason : "null"));
            return;
        }
        if (!tier3.deepDarkGuarantee) {
            helper.fail("Deep Dark plan should have deepDarkGuarantee true");
            return;
        }

        helper.succeed();
    }

    /**
     * Legacy bag_override tags are decoded as bounded supply, not silently
     * reinterpreted as a bag swap. Class identity never changes.
     */
    @GameTest(maxTicks = 20)
    public void legacyBagOverrideDecodesAsBoundedSupply(GameTestHelper helper) {
        CompoundTag legacyTags = new CompoundTag();
        legacyTags.putBoolean("bag_override", true);
        legacyTags.putString("bag_override_id", "mason");

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(99L, 5, EnumSet.noneOf(Affix.class),
                Set.of("blocks"), legacyTags, refusal);
        if (plan == null) {
            helper.fail("Legacy bag_override should resolve as bounded supply: "
                    + (refusal[0] != null ? refusal[0].reason : "null"));
            return;
        }
        if (!plan.boundedSupply) {
            helper.fail("Legacy bag_override should decode as boundedSupply");
            return;
        }
        // The active recipe records the legacy tag name.
        if (!plan.activeRecipes.contains("bag_override")) {
            helper.fail("Legacy bag_override should appear in active recipes");
            return;
        }

        // The new bounded_supply tag also works.
        CompoundTag newTags = new CompoundTag();
        newTags.putBoolean("bounded_supply", true);
        RunRecipePlan newPlan = RunRecipePlan.resolve(99L, 5, EnumSet.noneOf(Affix.class),
                Set.of("blocks"), newTags, refusal);
        if (newPlan == null) {
            helper.fail("bounded_supply should resolve");
            return;
        }
        if (!newPlan.boundedSupply) {
            helper.fail("bounded_supply should set boundedSupply flag");
            return;
        }
        if (!newPlan.activeRecipes.contains("bounded_supply")) {
            helper.fail("bounded_supply should appear in active recipes");
            return;
        }

        helper.succeed();
    }

    /**
     * Legacy double_key tags decode as +2 path length at the committed offer's
     * level. The old DOUBLE_KEY's lower-key level was never stored; do not
     * invent it.
     */
    @GameTest(maxTicks = 20)
    public void legacyDoubleKeyDecodesAsPathExtension(GameTestHelper helper) {
        CompoundTag legacyTags = new CompoundTag();
        legacyTags.putBoolean("double_key", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(77L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), legacyTags, refusal);
        if (plan == null) {
            helper.fail("Legacy double_key should resolve as path extension");
            return;
        }
        if (plan.pathLengthBonus != 2) {
            helper.fail("Legacy double_key should give +2 path length, got " + plan.pathLengthBonus);
            return;
        }
        if (!plan.activeRecipes.contains("double_key")) {
            helper.fail("Legacy double_key should appear in active recipes");
            return;
        }

        // The new path_extension tag also works.
        CompoundTag newTags = new CompoundTag();
        newTags.putBoolean("path_extension", true);
        RunRecipePlan newPlan = RunRecipePlan.resolve(77L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), newTags, refusal);
        if (newPlan == null) {
            helper.fail("path_extension should resolve");
            return;
        }
        if (newPlan.pathLengthBonus != 2) {
            helper.fail("path_extension should give +2 path length");
            return;
        }
        if (!newPlan.activeRecipes.contains("path_extension")) {
            helper.fail("path_extension should appear in active recipes");
            return;
        }

        helper.succeed();
    }

    /**
     * Ominous and Feral recipes add their affixes to the effective set without
     * mutating the base set.
     */
    @GameTest(maxTicks = 20)
    public void ominousAndFeralAddAffixes(GameTestHelper helper) {
        CompoundTag tags = new CompoundTag();
        tags.putBoolean("ominous", true);
        tags.putBoolean("feral", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        Set<Affix> base = EnumSet.noneOf(Affix.class);
        RunRecipePlan plan = RunRecipePlan.resolve(55L, 5, base, Set.of(), tags, refusal);
        if (plan == null) {
            helper.fail("Ominous + feral should resolve");
            return;
        }
        if (!plan.ominous || !plan.feral) {
            helper.fail("Ominous and feral flags should be true");
            return;
        }

        Set<Affix> effective = plan.effectiveAffixes(base);
        if (!effective.contains(Affix.OMINOUS)) {
            helper.fail("Effective affixes should contain OMINOUS");
            return;
        }
        if (!effective.contains(Affix.FERAL)) {
            helper.fail("Effective affixes should contain FERAL");
            return;
        }
        // Base set should not be mutated.
        if (!base.isEmpty()) {
            helper.fail("Base affix set should not be mutated");
            return;
        }

        helper.succeed();
    }

    /**
     * An empty recipe tag set resolves to a plan with no effects.
     */
    @GameTest(maxTicks = 20)
    public void emptyRecipesResolveToNoEffects(GameTestHelper helper) {
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(33L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), new CompoundTag(), refusal);
        if (plan == null) {
            helper.fail("Empty recipes should resolve");
            return;
        }
        if (plan.hasEffects()) {
            helper.fail("Empty recipes should have no effects");
            return;
        }
        if (!plan.activeRecipes.isEmpty()) {
            helper.fail("Empty recipes should have no active recipes");
            return;
        }

        // Null recipe tags also resolve to no effects.
        RunRecipePlan nullPlan = RunRecipePlan.resolve(33L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), null, refusal);
        if (nullPlan == null) {
            helper.fail("Null recipe tags should resolve");
            return;
        }
        if (nullPlan.hasEffects()) {
            helper.fail("Null recipe tags should have no effects");
            return;
        }

        helper.succeed();
    }

    /**
     * Infested guarantee and flooded weighting can coexist on the same plan
     * without refusal.
     */
    @GameTest(maxTicks = 20)
    public void infestedAndFloodedCoexist(GameTestHelper helper) {
        CompoundTag tags = new CompoundTag();
        tags.putBoolean("infested", true);
        tags.putBoolean("flooded", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(88L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), tags, refusal);
        if (plan == null) {
            helper.fail("Infested + flooded should coexist without refusal: "
                    + (refusal[0] != null ? refusal[0].reason : "null"));
            return;
        }
        if (!plan.infestedGuarantee) {
            helper.fail("infestedGuarantee should be true");
            return;
        }
        if (!plan.floodedChasmWeighted) {
            helper.fail("floodedChasmWeighted should be true");
            return;
        }
        if (plan.activeRecipes.size() != 2) {
            helper.fail("Expected 2 active recipes, got " + plan.activeRecipes.size());
            return;
        }

        helper.succeed();
    }

    /**
     * All nine recipe effects can be active simultaneously without refusal
     * (when tier permits Deep Dark).
     */
    @GameTest(maxTicks = 20)
    public void allEffectsResolveAtTier3(GameTestHelper helper) {
        CompoundTag tags = new CompoundTag();
        tags.putBoolean("ominous", true);
        tags.putBoolean("feral", true);
        tags.putBoolean("infested", true);
        tags.putBoolean("flooded", true);
        tags.putBoolean("deep_dark", true);
        tags.putBoolean("compass", true);
        tags.putBoolean("store", true);
        tags.putBoolean("bounded_supply", true);
        tags.putBoolean("path_extension", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(111L, 10, EnumSet.noneOf(Affix.class),
                Set.of("blocks"), tags, refusal);
        if (plan == null) {
            helper.fail("All effects at tier 3 should resolve: "
                    + (refusal[0] != null ? refusal[0].reason : "null"));
            return;
        }
        if (!plan.ominous || !plan.feral || !plan.infestedGuarantee
                || !plan.floodedChasmWeighted || !plan.deepDarkGuarantee
                || !plan.completionStudyList || !plan.storeSpur
                || !plan.boundedSupply || plan.pathLengthBonus != 2) {
            helper.fail("All effect flags should be true at tier 3");
            return;
        }
        if (plan.activeRecipes.size() != 9) {
            helper.fail("Expected 9 active recipes, got " + plan.activeRecipes.size());
            return;
        }

        helper.succeed();
    }

    /**
     * The effect summary is a legible comma-separated list for staff logging.
     */
    @GameTest(maxTicks = 20)
    public void effectSummaryIsLegible(GameTestHelper helper) {
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan empty = RunRecipePlan.resolve(1L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), new CompoundTag(), refusal);
        if (empty == null || !"none".equals(empty.effectSummary())) {
            helper.fail("Empty plan summary should be 'none'");
            return;
        }

        CompoundTag tags = new CompoundTag();
        tags.putBoolean("store", true);
        tags.putBoolean("compass", true);
        RunRecipePlan plan = RunRecipePlan.resolve(1L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), tags, refusal);
        if (plan == null) {
            helper.fail("Store+compass should resolve");
            return;
        }
        String summary = plan.effectSummary();
        if (!summary.contains("store") || !summary.contains("compass")) {
            helper.fail("Summary should contain 'store' and 'compass': " + summary);
            return;
        }

        helper.succeed();
    }

    /**
     * Fixed-catalyst headline collision: the same recipe tag key cannot appear
     * twice in the active set. This is a structural check, not a runtime
     * collision, but it verifies the LinkedHashSet deduplication.
     */
    @GameTest(maxTicks = 20)
    public void fixedCatalystNoDuplicateActiveRecipes(GameTestHelper helper) {
        CompoundTag tags = new CompoundTag();
        tags.putBoolean("store", true);
        // Adding the same key twice in the tag is a no-op (CompoundTag
        // overwrites), so this is really testing that a single store tag
        // produces exactly one active recipe.
        tags.putBoolean("store", true);

        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(1L, 5, EnumSet.noneOf(Affix.class),
                Set.of(), tags, refusal);
        if (plan == null) {
            helper.fail("Store should resolve");
            return;
        }
        if (plan.activeRecipes.size() != 1) {
            helper.fail("Duplicate store tag should still give 1 active recipe, got "
                    + plan.activeRecipes.size());
            return;
        }

        helper.succeed();
    }

    /**
     * Catalyst escrow: writing the pending catalyst tag and reading it back
     * works without a server. This verifies the durable store that
     * {@link CubeRecipe#restoreCatalyst} reads on cancellation.
     */
    @GameTest(maxTicks = 20)
    public void catalystEscrowReadWrite(GameTestHelper helper) {
        ItemStack keystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        // Stamp it as a keystone so pendingCatalystId will read it.
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    root.putString("pending_catalyst", "minecraft:bone");
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        String pending = CubeRecipe.pendingCatalystId(keystone);
        if (pending == null || !"minecraft:bone".equals(pending)) {
            helper.fail("Expected pending catalyst 'minecraft:bone', got " + pending);
            return;
        }
        // Clearing the escrow removes it.
        CubeRecipe.clearCatalystEscrow(keystone);
        if (CubeRecipe.pendingCatalystId(keystone) != null) {
            helper.fail("Escrow should be cleared after clearCatalystEscrow");
            return;
        }
        helper.succeed();
    }

    /**
     * Catalyst escrow is empty on a fresh keystone. No false positives that
     * would cause a phantom restore.
     */
    @GameTest(maxTicks = 20)
    public void freshKeystoneHasNoEscrow(GameTestHelper helper) {
        ItemStack keystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        if (CubeRecipe.pendingCatalystId(keystone) != null) {
            helper.fail("Fresh keystone should have no pending catalyst");
            return;
        }
        // A non-keystone item also returns null.
        ItemStack notAKeystone = new ItemStack(net.minecraft.world.item.Items.STICK);
        if (CubeRecipe.pendingCatalystId(notAKeystone) != null) {
            helper.fail("Non-keystone should return null for pendingCatalystId");
            return;
        }
        helper.succeed();
    }

    /**
     * Recipe tags survive on the keystone through preview. The preview does
     * not clear them; only a successful commit clears them. This is the
     * "read pending recipe data before clearing it" contract.
     */
    @GameTest(maxTicks = 20)
    public void recipeTagsSurviveUntilCommit(GameTestHelper helper) {
        ItemStack keystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    net.minecraft.nbt.CompoundTag recipes = new net.minecraft.nbt.CompoundTag();
                    recipes.putBoolean("store", true);
                    root.put("recipe", recipes);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        // Reading the recipe tags does not clear them.
        net.minecraft.nbt.CompoundTag tags = CubeRecipe.recipesOf(keystone);
        if (!tags.getBooleanOr("store", false)) {
            helper.fail("recipesOf should read the store tag");
            return;
        }
        // Read again: still there.
        tags = CubeRecipe.recipesOf(keystone);
        if (!tags.getBooleanOr("store", false)) {
            helper.fail("recipesOf should not clear the store tag on read");
            return;
        }
        // Clearing removes them.
        CubeRecipe.clearRecipes(keystone);
        tags = CubeRecipe.recipesOf(keystone);
        if (tags.getBooleanOr("store", false)) {
            helper.fail("clearRecipes should remove the store tag");
            return;
        }
        helper.succeed();
    }

    /**
     * M66: the new BOUNDED_SUPPLY recipe matches keystone + string. The old
     * BAG_OVERRIDE no longer matches any new catalyst; it is legacy only.
     */
    @GameTest(maxTicks = 20)
    public void boundedSupplyMatchesString(GameTestHelper helper) {
        ItemStack keystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        ItemStack stringStack = new ItemStack(net.minecraft.world.item.Items.STRING);
        CubeRecipe matched = CubeRecipe.match(keystone, stringStack);
        if (matched != CubeRecipe.BOUNDED_SUPPLY) {
            helper.fail("Keystone + string should match BOUNDED_SUPPLY, got " + matched);
            return;
        }
        // A bag headline item no longer matches BAG_OVERRIDE.
        ItemStack boneStack = new ItemStack(net.minecraft.world.item.Items.BONE);
        CubeRecipe boneMatched = CubeRecipe.match(keystone, boneStack);
        if (boneMatched == CubeRecipe.BAG_OVERRIDE) {
            helper.fail("BAG_OVERRIDE should no longer match any new catalyst");
            return;
        }
        // BONE still matches FERAL (fixed catalyst).
        if (boneMatched != CubeRecipe.FERAL) {
            helper.fail("BONE should match FERAL, got " + boneMatched);
            return;
        }
        helper.succeed();
    }

    /**
     * M66: the new PATH_EXTENSION recipe matches keystone + amethyst shard.
     * The old DOUBLE_KEY no longer matches a second keystone; it is legacy
     * only. A second keystone in the off-hand no longer matches any recipe.
     */
    @GameTest(maxTicks = 20)
    public void pathExtensionMatchesAmethystShard(GameTestHelper helper) {
        ItemStack keystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        ItemStack shardStack = new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD);
        CubeRecipe matched = CubeRecipe.match(keystone, shardStack);
        if (matched != CubeRecipe.PATH_EXTENSION) {
            helper.fail("Keystone + amethyst shard should match PATH_EXTENSION, got " + matched);
            return;
        }
        // A second keystone in the off-hand no longer matches DOUBLE_KEY.
        ItemStack secondKeystone = new ItemStack(net.minecraft.world.item.Items.PAPER);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, secondKeystone, tag -> {
                    net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
                    root.putInt("keystone", 1);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });
        CubeRecipe secondMatched = CubeRecipe.match(keystone, secondKeystone);
        if (secondMatched == CubeRecipe.DOUBLE_KEY) {
            helper.fail("DOUBLE_KEY should no longer match a second keystone");
            return;
        }
        // The second keystone should not match any recipe at all.
        if (secondMatched != null) {
            helper.fail("Second keystone should match no recipe, got " + secondMatched);
            return;
        }
        helper.succeed();
    }

    /**
     * M66: legacy DOUBLE_KEY migration. The old tag decodes as +2 path length
     * at the committed offer's level. The old DOUBLE_KEY never stored the
     * lower-key level; do not invent it. The migration is to current-level
     * extension with explicit notice.
     */
    @GameTest(maxTicks = 20)
    public void legacyDoubleKeyMigratesToCurrentLevelExtension(GameTestHelper helper) {
        CompoundTag legacyTags = new CompoundTag();
        legacyTags.putBoolean("double_key", true);
        // The old DOUBLE_KEY did not store the lower-key level. The migration
        // uses the committed offer's level, which is the current level.
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        RunRecipePlan plan = RunRecipePlan.resolve(42L, 7, EnumSet.noneOf(Affix.class),
                Set.of(), legacyTags, refusal);
        if (plan == null) {
            helper.fail("Legacy double_key should resolve as path extension");
            return;
        }
        if (plan.pathLengthBonus != 2) {
            helper.fail("Legacy double_key should give +2 path length, got " + plan.pathLengthBonus);
            return;
        }
        // The active recipe records the legacy tag name, not the new one.
        if (!plan.activeRecipes.contains("double_key")) {
            helper.fail("Legacy double_key should appear in active recipes");
            return;
        }
        // The new path_extension tag also works and uses the new name.
        CompoundTag newTags = new CompoundTag();
        newTags.putBoolean("path_extension", true);
        RunRecipePlan newPlan = RunRecipePlan.resolve(42L, 7, EnumSet.noneOf(Affix.class),
                Set.of(), newTags, refusal);
        if (newPlan == null) {
            helper.fail("path_extension should resolve");
            return;
        }
        if (newPlan.pathLengthBonus != 2) {
            helper.fail("path_extension should give +2 path length");
            return;
        }
        if (!newPlan.activeRecipes.contains("path_extension")) {
            helper.fail("path_extension should appear in active recipes");
            return;
        }
        helper.succeed();
    }
}
