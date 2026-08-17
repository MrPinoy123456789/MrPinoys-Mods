package smalltalk.interaction;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import smalltalk.identity.Identity;
import smalltalk.identity.IdentityDeriver;
import smalltalk.social.Feedback;
import smalltalk.social.FamiliarityAttachment;
import smalltalk.social.FamiliarityEntry;
import smalltalk.social.GiftCategories;
import smalltalk.social.GiftReaction;

/**
 * Applies a confirmed gift (SPEC.md section 6.2). Only ever reached from the
 * {@code /smalltalk gift-confirm} command the gift dialog's Yes button runs
 * -- there is no hand-item interaction path anymore. Everything here
 * re-verifies rather than trusting the dialog payload: the item might have
 * left the player's inventory, the cooldown might have lapsed, or the
 * config might have changed between the picker opening and the confirm
 * button landing (SPEC.md section 12.2's discipline, applied here too).
 */
public final class GiftHandler {

    private GiftHandler() {}

    public static boolean confirmGift(Villager villager, ServerLevel level, ServerPlayer player, String itemId) {
        long tick = level.getGameTime();
        FamiliarityEntry entry = FamiliarityAttachment.current(villager, player.getUUID(), tick);

        if (FamiliarityAttachment.giftOnCooldown(entry, tick)) {
            player.sendSystemMessage(Component.literal(
                            IdentityDeriver.displayName(villager) + " has already had a gift from you today.")
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }

        ItemStack stack = findInInventory(player, itemId);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Component.literal("You don't have that to give anymore.")
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }

        Identity identity = IdentityDeriver.derive(villager.getUUID());
        GiftReaction reaction = GiftCategories.reactionFor(identity, stack);
        if (reaction == null) {
            // Not a configured gift item -- only possible if itemCategories changed
            // between the picker being built and this button landing.
            return false;
        }

        stack.shrink(1);
        FamiliarityAttachment.recordGift(villager, player.getUUID(), tick,
                reaction.familiarityDelta(), "gave_gift:" + itemId);
        Feedback.giftReaction(villager, level, reaction);
        return true;
    }

    private static ItemStack findInInventory(ServerPlayer player, String itemId) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
