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

import java.util.List;

/**
 * M59: the nine Cube recipes from spec section 7. Each recipe is "keystone in
 * the main hand, specific item in the off-hand, right-click the Cube." The
 * recipe writes a tag into the keystone's custom data that the door reads at
 * generation time.
 *
 * <p>Recipes are discovered, never listed. The Cube's existing extract/imbue
 * stays as is; recipes are a third path that checks for a keystone in the
 * main hand and a matching item in the off-hand.
 *
 * <p>The tag written is {@code custom_data.pocketdungeons.recipe.<key> = true}.
 * The generation path reads these tags and adjusts the plan accordingly.
 */
enum CubeRecipe {
    OMINOUS("ominous", Items.OMINOUS_BOTTLE,
            "Ominous from the start.",
            "The next run starts ominous."),
    FERAL("feral", Items.BONE,
            "Feral affix guaranteed.",
            "The next run is Feral."),
    BAG_OVERRIDE("bag_override", null,
            "Overrides the door's bag with the bag whose headline item you hold.",
            "The next run uses the bag you framed."),
    INFESTED("infested", Items.TNT,
            "At least one Infested Wall or Creeper Kennel guaranteed.",
            "The next run guarantees an infested room."),
    FLOODED("flooded", Items.WATER_BUCKET,
            "Flooded and Chasm rooms weighted up.",
            "The next run favours water and lava."),
    DEEP_DARK("deep_dark", null,
            "Deep Dark Landing guaranteed, tier permitting.",
            "The next run guarantees a Deep Dark room."),
    COMPASS("compass", Items.COMPASS,
            "The completion line lists the run's situations by name afterward.",
            "The next run will name its rooms on completion."),
    DOUBLE_KEY("double_key", null,
            "Path length +2 at the lower key's level.",
            "The next run is two rooms longer."),
    STORE("store", Items.EMERALD,
            "A Store spur guaranteed.",
            "The next run guarantees a Store.");

    private final String tagKey;
    private final Item catalyst;
    private final String description;
    private final String confirmation;

    CubeRecipe(String tagKey, Item catalyst, String description, String confirmation) {
        this.tagKey = tagKey;
        this.catalyst = catalyst;
        this.description = description;
        this.confirmation = confirmation;
    }

    /**
     * The tag key this recipe writes, under {@code pocketdungeons.recipe.}
     * in the keystone's custom data.
     */
    String tagKey() {
        return tagKey;
    }

    /**
     * Matches a recipe against the held keystone and the off-hand catalyst.
     * Returns the matching recipe, or {@code null} if none matches.
     *
     * <p>{@code BAG_OVERRIDE} matches any bag headline item (resolved via
     * {@link Bags#headlineItems()}). {@code DOUBLE_KEY} matches a second
     * keystone in the off-hand.
     */
    static CubeRecipe match(ItemStack keystone, ItemStack offHand) {
        if (!Keystone.isKeystone(keystone) || offHand.isEmpty()) {
            return null;
        }
        Item offItem = offHand.getItem();
        // Check fixed-catalyst recipes first.
        for (CubeRecipe recipe : values()) {
            if (recipe.catalyst != null && recipe.catalyst == offItem) {
                return recipe;
            }
        }
        // DEEP_DARK: any wool block (Items.WOOL is a ColorCollection in 26.2).
        if (Items.WOOL.asList().contains(offItem)) {
            return DEEP_DARK;
        }
        // BAG_OVERRIDE: any bag's headline item.
        if (BAG_OVERRIDE.catalyst == null) {
            for (Bags bag : Bags.values()) {
                for (Item headline : bag.headlineItems()) {
                    if (headline == offItem) {
                        return BAG_OVERRIDE;
                    }
                }
            }
        }
        // DOUBLE_KEY: a second keystone in the off-hand.
        if (DOUBLE_KEY.catalyst == null && Keystone.isKeystone(offHand)) {
            return DOUBLE_KEY;
        }
        return null;
    }

    /**
     * Applies this recipe: writes the tag into the keystone's custom data,
     * consumes the catalyst, and sends a confirmation message.
     */
    void apply(ServerPlayer player, ItemStack keystone, ItemStack offHand) {
        // Write the recipe tag into the keystone's custom data.
        CustomData.update(DataComponents.CUSTOM_DATA, keystone, tag -> {
            CompoundTag root = tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
            if (root == null) {
                root = new CompoundTag();
            }
            CompoundTag recipes = root.getCompound("recipe").orElse(null);
            if (recipes == null) {
                recipes = new CompoundTag();
            }
            recipes.putBoolean(tagKey, true);
            // For BAG_OVERRIDE, also store which bag to use.
            if (this == BAG_OVERRIDE) {
                String bagId = bagIdForCatalyst(offHand);
                if (bagId != null) {
                    recipes.putString("bag_override_id", bagId);
                }
            }
            root.put("recipe", recipes);
            tag.put(PocketDungeonsMod.MOD_ID, root);
        });
        // Consume one catalyst. The keystone is never consumed.
        offHand.shrink(1);
        player.sendSystemMessage(Component.literal(confirmation)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /**
     * Returns the bag id whose headline list contains the catalyst item,
     * or {@code null} if none.
     */
    private static String bagIdForCatalyst(ItemStack offHand) {
        Item offItem = offHand.getItem();
        for (Bags bag : Bags.values()) {
            for (Item headline : bag.headlineItems()) {
                if (headline == offItem) {
                    return bag.id;
                }
            }
        }
        return null;
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
     * The bag id stored by a BAG_OVERRIDE recipe, or {@code null} if the
     * keystone does not carry one.
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
}
