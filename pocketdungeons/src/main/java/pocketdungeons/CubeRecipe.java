package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * M59: the Cube recipe path. A recipe is "keystone in the main hand, a
 * specific item in the off-hand, right-click the Cube." The recipe writes a
 * tag into the keystone's custom data that the door reads at generation
 * time.
 *
 * <p>Recipes are discovered, never listed. The Cube's existing extract/imbue
 * stays as is; recipes are a third path that checks for a keystone in the
 * main hand and a matching item in the off-hand.
 *
 * <p>The tag written is {@code custom_data.pocketdungeons.recipe.<id> = true}.
 * The generation path reads these tags and adjusts the plan accordingly.
 *
 * <p>M71: the recipe definitions are data-driven ({@link CubeRecipeManifest}).
 * The match path iterates the live manifest's definitions in priority order
 * and rejects an ambiguous match (two definitions matching the same
 * off-hand stack) rather than silently picking one. The apply path writes
 * the recipe id (resolved to its namespaced form) into the keystone tag,
 * consumes the catalyst, escrows it for recovery, records the discovery in
 * the player's {@link RecipeDiscovery}, and records the catalyst in the
 * player's ingredient surface.
 *
 * <p>This class retains the keystone tag readers ({@link #recipesOf},
 * {@link #hasRecipe}, {@link #bagOverrideId}) and the catalyst escrow
 * helpers ({@link #clearCatalystEscrow}, {@link #pendingCatalystId},
 * {@link #restoreCatalyst}) because they are the durable store the M63
 * custody pattern depends on, and the Cube station interception stays in
 * Java because component-aware keystone validation cannot be expressed by
 * ordinary {@code Ingredient} matching.
 */
final class CubeRecipe {

    private CubeRecipe() {}

    /**
     * Matches a recipe definition against the held keystone and the off-hand
     * catalyst. Returns the matching definition, or {@code null} if none
     * matches. Rejects an ambiguous match (two definitions matching the same
     * off-hand stack) by returning {@code null} and warning the player, so a
     * third-party recipe that collides with a built-in catalyst cannot
     * silently shadow it.
     *
     * <p>M71: the match path iterates the live {@link CubeRecipeManifest}'s
     * definitions in priority order. A definition with a {@code catalyst}
     * item matches that item; a definition with a {@code catalyst_tag} tag
     * matches any item in that tag. The keystone level is checked here too,
     * so a recipe whose {@code min_level} the player has not reached refuses
     * before the catalyst is spent.
     */
    static CubeRecipeDefinition match(ItemStack keystone, ItemStack offHand, int keystoneLevel) {
        if (!Keystone.isKeystone(keystone) || offHand.isEmpty()) {
            return null;
        }
        Item offItem = offHand.getItem();
        List<CubeRecipeDefinition> matches = new ArrayList<>();
        for (CubeRecipeDefinition def : CubeRecipeManifest.current().definitions()) {
            if (def.minLevel > keystoneLevel) {
                continue;
            }
            if (catalystMatches(def, offItem)) {
                matches.add(def);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            // Ambiguous: two definitions match the same off-hand stack.
            // Refuse rather than silently pick one.
            return null;
        }
        return matches.get(0);
    }

    private static boolean catalystMatches(CubeRecipeDefinition def, Item offItem) {
        if (def.catalystItem != null) {
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.tryParse(def.catalystItem);
            if (id == null) {
                return false;
            }
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
                    .orElse(null);
            return item == offItem;
        }
        if (def.catalystTag != null) {
            String tagId = def.catalystTag;
            if (tagId.startsWith("#")) {
                tagId = tagId.substring(1);
            }
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.tryParse(tagId);
            if (id == null) {
                return false;
            }
            net.minecraft.tags.TagKey<Item> tag = net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM, id);
            return offItem.builtInRegistryHolder().is(tag);
        }
        return false;
    }

    /**
     * Applies a recipe definition: writes the recipe id tag into the
     * keystone's custom data, consumes the catalyst, escrows it for
     * recovery, records the discovery in the player's
     * {@link RecipeDiscovery}, and records the catalyst in the player's
     * ingredient surface.
     *
     * <p>M66: the catalyst is escrowed, not just consumed. The consumed
     * item's registry id is stored as {@code pending_catalyst} in the
     * keystone's custom data, so a cancelled preview can restore it. The
     * escrow is cleared on successful commit ({@link #clearCatalystEscrow})
     * and restored on cancellation ({@link #restoreCatalyst}). This is the
     * tagged recovery escrow: the keystone's custom data is the durable
     * store, following the same prepare/commit/recover pattern as M63's
     * InventoryJournal.
     *
     * <p>M71: the discovery is recorded only on a successful apply, never on
     * a refused or cancelled attempt. The ingredient is recorded on every
     * apply, so the discovery floor's ingredient surface grows as the player
     * touches catalysts at the Cube.
     */
    static void apply(ServerPlayer player, ItemStack keystone, ItemStack offHand,
                      CubeRecipeDefinition def) {
        String recipeId = def.id;
        // Write the recipe tag into the keystone's custom data. The tag key
        // is the recipe id verbatim (namespaced), so a reload that renames
        // a recipe does not silently migrate an old tag.
        CustomData.update(DataComponents.CUSTOM_DATA, keystone, tag -> {
            CompoundTag root = tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
            if (root == null) {
                root = new CompoundTag();
            }
            CompoundTag recipes = root.getCompound("recipe").orElse(null);
            if (recipes == null) {
                recipes = new CompoundTag();
            }
            recipes.putBoolean(recipeId, true);
            root.put("recipe", recipes);
            // M66: escrow the consumed catalyst for recovery on cancel.
            root.putString("pending_catalyst",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offHand.getItem()).toString());
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
        // Consume one catalyst. The keystone is never consumed.
        offHand.shrink(1);
        // M71: record the discovery and the ingredient. The discovery is
        // recorded only on a successful apply; the ingredient is recorded
        // on every apply so the floor's ingredient surface grows.
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        log.recordRecipeDiscovery(player.getUUID(), recipeId);
        String catalystItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(offHand.getItem()).toString();
        // The catalyst was already shrunk; record the original item id.
        // offHand is the same stack reference, so re-resolve from the
        // definition's catalyst item if present, else from the stack.
        if (def.catalystItem != null) {
            log.recordIngredientEncountered(player.getUUID(), def.catalystItem);
        } else {
            log.recordIngredientEncountered(player.getUUID(), catalystItemId);
        }
        player.sendSystemMessage(Component.literal(def.confirmation)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ---- reading recipe tags from a keystone --------------------------------

    /**
     * Reads the recipe tags from a keystone stack's custom data. Returns an
     * empty CompoundTag if the keystone carries no recipes.
     */
    static CompoundTag recipesOf(ItemStack keystone) {
        if (!Keystone.isKeystone(keystone)) {
            return new CompoundTag();
        }
        CustomData data = keystone.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return new CompoundTag();
        }
        CompoundTag root = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        if (root == null) {
            return new CompoundTag();
        }
        return root.getCompound("recipe").orElse(new CompoundTag());
    }

    /**
     * Whether the keystone carries the given recipe tag.
     */
    static boolean hasRecipe(ItemStack keystone, String tagKey) {
        return recipesOf(keystone).getBooleanOr(tagKey, false);
    }

    /**
     * The bag id stored by a legacy BAG_OVERRIDE recipe, or {@code null} if
     * the keystone does not carry one. M66 kept this for owner-approved
     * refund of a retired recipe; M71 keeps it for the same reason.
     */
    static String bagOverrideId(ItemStack keystone) {
        CompoundTag recipes = recipesOf(keystone);
        if (!recipes.getBooleanOr("bag_override", false)) {
            return null;
        }
        String id = recipes.getStringOr("bag_override_id", "");
        return id.isBlank() ? null : id;
    }

    /**
     * Clears all recipe tags from a keystone. Called after the door reads
     * them at generation, so a single-use recipe does not persist across
     * runs.
     */
    static void clearRecipes(ItemStack keystone) {
        CustomData.update(DataComponents.CUSTOM_DATA, keystone, tag -> {
            CompoundTag root = tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
            if (root == null) {
                return;
            }
            root.remove("recipe");
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
    }

    /**
     * M66: Clears the catalyst escrow from the keystone. Called on
     * successful commit, when the catalyst is permanently spent and no
     * longer recoverable.
     */
    static void clearCatalystEscrow(ItemStack keystone) {
        CustomData.update(DataComponents.CUSTOM_DATA, keystone, tag -> {
            CompoundTag root = tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
            if (root == null) {
                return;
            }
            root.remove("pending_catalyst");
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
    }

    /**
     * M66: Reads the pending catalyst item id from the keystone's escrow, or
     * {@code null} if no catalyst is escrowed.
     */
    static String pendingCatalystId(ItemStack keystone) {
        if (!Keystone.isKeystone(keystone)) {
            return null;
        }
        CustomData data = keystone.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        CompoundTag root = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        if (root == null) {
            return null;
        }
        String id = root.getStringOr("pending_catalyst", "");
        return id.isBlank() ? null : id;
    }

    /**
     * M66: Restores the escrowed catalyst to the player and clears the
     * escrow. Called when a preview is cancelled, so a consumed catalyst
     * does not charge for nothing. The catalyst is delivered via
     * {@link Payout#deliver} so overflow drops at the player's feet rather
     * than being voided.
     */
    static void restoreCatalyst(ServerPlayer player, ItemStack keystone) {
        String catalystId = pendingCatalystId(keystone);
        if (catalystId == null) {
            return;
        }
        net.minecraft.resources.Identifier loc =
                net.minecraft.resources.Identifier.tryParse(catalystId);
        if (loc == null) {
            return;
        }
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(loc).orElse(null);
        if (item == null || item == Items.AIR) {
            return;
        }
        Payout.deliver(player, new ItemStack(item));
        clearCatalystEscrow(keystone);
        player.sendSystemMessage(Component.literal(
                "The preview was cancelled. Your catalyst is returned.")
                .withStyle(ChatFormatting.YELLOW));
    }
}
