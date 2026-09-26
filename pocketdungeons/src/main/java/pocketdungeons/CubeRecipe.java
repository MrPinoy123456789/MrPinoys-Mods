package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        int cost = def.cost;
        // F7: capture the catalyst item id BEFORE shrinking, so a tag-based
        // recipe that consumes the last item does not record minecraft:air
        // as the encountered ingredient.
        Item catalystItem = offHand.getItem();
        String catalystItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(catalystItem).toString();
        // F5: enforce the declared cost. The match path proved the off-hand
        // holds the right item identity; verify the quantity here so a recipe
        // whose cost is greater than 1 cannot be applied with a single item.
        if (offHand.getCount() < cost) {
            player.sendSystemMessage(Component.literal(
                    "This recipe needs " + cost + " catalysts; you are holding "
                            + offHand.getCount() + ".")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
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
            // F4: append to a multi-entry escrow list rather than
            // overwriting a single pending_catalyst string, so a second
            // application does not lose the first catalyst's recovery
            // record. Each entry carries the recipe id, the catalyst item
            // id, and the quantity consumed, so cancel can refund the
            // exact items and commit can settle the whole pending set.
            ListTag escrow = root.getList("escrow").orElse(null);
            if (escrow == null) {
                escrow = new ListTag();
            }
            CompoundTag entry = new CompoundTag();
            entry.putString("recipe", recipeId);
            entry.putString("catalyst", catalystItemId);
            entry.putInt("count", cost);
            escrow.add(entry);
            root.put("escrow", escrow);
            // Drop the legacy single-catalyst field so a migrated keystone
            // does not carry both representations.
            root.remove("pending_catalyst");
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
        // Consume the declared cost. The keystone is never consumed.
        offHand.shrink(cost);
        // M71: record the discovery and the ingredient. The discovery is
        // recorded only on a successful apply; the ingredient is recorded
        // on every apply so the floor's ingredient surface grows. The id
        // was captured before the shrink, so it is the real catalyst even
        // when the stack is now empty.
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        log.recordRecipeDiscovery(player.getUUID(), recipeId);
        log.recordIngredientEncountered(player.getUUID(), catalystItemId);
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
            root.remove("escrow");
            // Also drop a legacy single-catalyst field if a pre-fix keystone
            // is committed without ever having been re-applied.
            root.remove("pending_catalyst");
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
    }

    /**
     * One escrowed catalyst entry: the recipe id that armed it, the catalyst
     * item id to refund, and the quantity consumed. F4: the escrow is a list
     * of these, not a single string, so multiple applications accumulate
     * instead of overwriting each other.
     */
    record EscrowEntry(String recipeId, String catalystItemId, int count) {}

    /**
     * Reads the full escrow list from the keystone. Migrates a legacy
     * {@code pending_catalyst} string into a single count-1 entry so an older
     * keystone that was applied before the multi-entry escrow still refunds.
     */
    static List<EscrowEntry> escrowOf(ItemStack keystone) {
        if (!Keystone.isKeystone(keystone)) {
            return List.of();
        }
        CustomData data = keystone.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        CompoundTag root = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        if (root == null) {
            return List.of();
        }
        ListTag escrow = root.getList("escrow").orElse(null);
        if (escrow != null) {
            List<EscrowEntry> entries = new ArrayList<>();
            for (int i = 0; i < escrow.size(); i++) {
                CompoundTag entry = escrow.getCompound(i).orElse(null);
                if (entry == null) {
                    continue;
                }
                String recipe = entry.getStringOr("recipe", "");
                String catalyst = entry.getStringOr("catalyst", "");
                int count = entry.getIntOr("count", 1);
                if (!catalyst.isBlank() && count > 0) {
                    entries.add(new EscrowEntry(recipe, catalyst, count));
                }
            }
            return entries;
        }
        // Legacy migration: a pre-fix keystone carries a single
        // pending_catalyst string instead of the escrow list.
        String legacy = root.getStringOr("pending_catalyst", "");
        if (legacy.isBlank()) {
            return List.of();
        }
        return List.of(new EscrowEntry("", legacy, 1));
    }

    /**
     * Whether the keystone currently holds any escrowed catalyst. Used by the
     * preview-switch path to decide whether a true refund is owed.
     */
    static boolean hasOutstandingEscrow(ItemStack keystone) {
        return !escrowOf(keystone).isEmpty();
    }

    /**
     * M66: Reads the pending catalyst item id from the keystone's escrow, or
     * {@code null} if no catalyst is escrowed. Returns the first entry's
     * catalyst for compatibility with callers that only need to know whether
     * any catalyst is recoverable.
     */
    static String pendingCatalystId(ItemStack keystone) {
        List<EscrowEntry> entries = escrowOf(keystone);
        return entries.isEmpty() ? null : entries.get(0).catalystItemId();
    }

    /**
     * M66: Restores every escrowed catalyst to the player, clears the escrow,
     * and clears the recipe tags the escrow armed. Called when a preview is
     * cancelled, so a consumed catalyst does not charge for nothing and the
     * armed recipe does not linger for a free re-preview. The catalysts are
     * delivered via {@link Payout#deliver} so overflow drops at the player's
     * feet rather than being voided. F4: multiple applications are refunded
     * together, summed per item id so the same catalyst delivers one stack.
     */
    static void restoreCatalyst(ServerPlayer player, ItemStack keystone) {
        List<EscrowEntry> entries = escrowOf(keystone);
        if (entries.isEmpty()) {
            return;
        }
        Map<String, Integer> byItem = new LinkedHashMap<>();
        for (EscrowEntry e : entries) {
            byItem.merge(e.catalystItemId(), e.count(), Integer::sum);
        }
        boolean deliveredAny = false;
        for (Map.Entry<String, Integer> e : byItem.entrySet()) {
            net.minecraft.resources.Identifier loc =
                    net.minecraft.resources.Identifier.tryParse(e.getKey());
            if (loc == null) {
                continue;
            }
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(loc).orElse(null);
            if (item == null || item == Items.AIR) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            stack.setCount(e.getValue());
            Payout.deliver(player, stack);
            deliveredAny = true;
        }
        clearCatalystEscrow(keystone);
        // Clear the recipe tags the escrow armed, so a cancelled recipe does
        // not leave its effects on the keystone for a free re-preview.
        clearRecipes(keystone);
        if (deliveredAny) {
            player.sendSystemMessage(Component.literal(
                    "The preview was cancelled. Your catalysts are returned.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
