package smalltalk.social;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import smalltalk.SmallTalkConfig;
import smalltalk.identity.Identity;
import smalltalk.identity.ItemCategory;

import java.util.List;
import java.util.Map;

/**
 * Maps items to the categories configured in {@code smalltalk.json}
 * (SPEC.md section 2's quirks), and doubles as the acceptable-gifts
 * allowlist the gift dialog (SPEC.md section 6.2) filters a player's
 * inventory against.
 *
 * <p>Earlier drafts excluded tools from the default list because a bare
 * right-click while holding one could accidentally consume it. That risk is
 * gone now that gifting only happens through a deliberate dialog pick --
 * see the gift dialog flow in {@code interaction.DialogScreens} -- so tools
 * are back in the defaults.
 */
public final class GiftCategories {

    private GiftCategories() {}

    public static boolean isGiftItem(ItemStack stack) {
        return categoryOf(stack) != null;
    }

    public static ItemCategory categoryOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (Map.Entry<String, List<String>> entry : SmallTalkConfig.itemCategories().entrySet()) {
            if (entry.getValue().contains(itemId.toString())) {
                try {
                    return ItemCategory.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Null means the item isn't a gift item at all. */
    public static GiftReaction reactionFor(Identity identity, ItemStack stack) {
        ItemCategory category = categoryOf(stack);
        if (category == null) {
            return null;
        }
        if (category == identity.likedCategory()) {
            return GiftReaction.LOVED;
        }
        if (category == identity.dislikedCategory()) {
            return GiftReaction.DISLIKED;
        }
        return GiftReaction.NEUTRAL;
    }
}
