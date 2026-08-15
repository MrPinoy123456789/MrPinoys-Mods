package kamutotems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import kamutotems.core.AssignedQuests;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * A quest scroll is any vanilla item carrying {@code custom_data} that names a
 * quest definition. Another mod can create these without depending on this mod.
 *
 * <p>Shape:
 * <pre>
 *   minecraft:custom_data = { "kamutotems": { "quest_scroll": "quest_id" } }
 * </pre>
 *
 * <p>Right-click to read the scroll. If the quest is granted, the scroll is
 * consumed. Refusals consume nothing and the scroll stays in hand.
 */
public final class QuestScroll {

    private static final String KEY = "kamutotems";
    private static final String SCROLL_KEY = "quest_scroll";

    private QuestScroll() {}

    public static boolean isScroll(ItemStack stack) {
        String id = definitionId(stack);
        return id != null && !id.isBlank();
    }

    public static String definitionId(ItemStack stack) {
        CompoundTag root = tag(stack);
        if (root == null) {
            return null;
        }
        CompoundTag inner = root.getCompoundOrEmpty(KEY);
        return inner.getStringOr(SCROLL_KEY, null);
    }

    public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!isScroll(held)) {
            return InteractionResult.PASS;
        }

        String questId = definitionId(held);
        AssignedQuests.GrantResult result = AssignedQuestHost.attemptGrant(serverPlayer, questId);
        if (!result.ok()) {
            String message = result.message() != null ? result.message() : "The scroll cannot be read.";
            serverPlayer.sendSystemMessage(Component.literal(message)
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        String message = result.message() != null ? result.message() : "A new errand has been set before you.";
        serverPlayer.sendSystemMessage(Component.literal(message)
                .withStyle(ChatFormatting.GREEN));

        held.shrink(1);
        if (held.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY);
        }
        return InteractionResult.SUCCESS;
    }

    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag();
    }
}
