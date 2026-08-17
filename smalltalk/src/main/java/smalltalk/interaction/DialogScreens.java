package smalltalk.interaction;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.DialogListDialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import smalltalk.identity.IdentityDeriver;
import smalltalk.social.GiftCategories;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds every vanilla Dialog screen this mod shows (SPEC.md sections 0.1,
 * 5, 6.1, 6.2). No entity is ever spawned for this -- {@code ServerPlayer.openDialog}
 * sends the whole thing over the existing connection, and no other UI
 * exists anywhere in Small Talk: talking, trading, and gifting are all
 * Dialog screens, never a hand-item interaction.
 *
 * <p>Buttons run a command (this mod's own {@code /smalltalk ...} tree)
 * rather than the vanilla {@code custom_click_action} type SPEC.md's early
 * drafts named. Both get "no permission node, no client mod needed" --
 * {@code run_command} just does it through the ordinary command dispatcher,
 * which is far easier to get right than hooking the raw
 * {@code ServerboundCustomClickActionPacket} dispatch by hand. Navigating
 * from the gift picker into a specific item's confirmation, though, needs no
 * command at all -- {@link DialogListDialog} carries its whole list of
 * sub-dialogs to the client in one packet, so picking an entry is a purely
 * client-side screen change.
 */
public final class DialogScreens {

    private DialogScreens() {}

    /** The talk-verb dialog: the context-selected greeting, plus Gift and Trade. */
    public static Holder<Dialog> greeting(Villager villager, String line) {
        Component title = Component.literal(IdentityDeriver.displayName(villager));
        List<DialogBody> body = List.of(new PlainMessage(Component.literal(line), PlainMessage.DEFAULT_WIDTH));
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        List<ActionButton> actions = new ArrayList<>();
        actions.add(smallTalkButton(villager));
        actions.add(giftButton(villager));
        if (!villager.getOffers().isEmpty()) {
            actions.add(tradeButton(villager));
        }
        actions.add(closeButton());
        MultiActionDialog dialog = new MultiActionDialog(common, actions, Optional.empty(), 2);
        return Holder.direct(dialog);
    }

    /** The Small Talk button's reply: an oblique, in-character hint at what this resident likes. */
    public static Holder<Dialog> hint(Villager villager, String line) {
        Component title = Component.literal(IdentityDeriver.displayName(villager));
        List<DialogBody> body = List.of(new PlainMessage(Component.literal(line), PlainMessage.DEFAULT_WIDTH));
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        List<ActionButton> actions = List.of(closeButton());
        MultiActionDialog dialog = new MultiActionDialog(common, actions, Optional.empty(), 1);
        return Holder.direct(dialog);
    }

    /**
     * The gift picker: one entry per distinct gift-eligible item currently in
     * the player's inventory (SPEC.md section 6.2). Each entry is already a
     * fully-built confirmation dialog -- picking one is client-side, no
     * further server round trip until the player actually confirms.
     */
    public static Holder<Dialog> giftPicker(Villager villager, ServerPlayer player) {
        List<Holder<Dialog>> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !GiftCategories.isGiftItem(stack)) {
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!seen.add(itemId.toString())) {
                continue;
            }
            entries.add(giftConfirmation(villager, stack, itemId.toString()));
        }

        Component title = Component.literal("Give a gift to " + IdentityDeriver.displayName(villager));
        List<DialogBody> body = entries.isEmpty()
                ? List.of(new PlainMessage(
                        Component.literal("You aren't carrying anything they'd want."), PlainMessage.DEFAULT_WIDTH))
                : List.of();
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        DialogListDialog dialog = new DialogListDialog(
                common, HolderSet.direct(entries), Optional.of(closeButton()), 2, CommonButtonData.DEFAULT_WIDTH);
        return Holder.direct(dialog);
    }

    private static Holder<Dialog> giftConfirmation(Villager villager, ItemStack stack, String itemId) {
        String itemName = stack.getHoverName().getString();
        Component title = Component.literal(
                "Give " + itemName + " to " + IdentityDeriver.displayName(villager) + "?");
        List<DialogBody> body = List.of(
                new ItemBody(ItemStackTemplate.fromNonEmptyStack(stack), Optional.empty(), true, true, 16, 16));
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        ActionButton yes = new ActionButton(
                new CommonButtonData(Component.literal("Give"), CommonButtonData.DEFAULT_WIDTH),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(
                        "smalltalk gift-confirm " + villager.getUUID() + " " + itemId))));
        ActionButton no = new ActionButton(
                new CommonButtonData(Component.literal("Cancel"), CommonButtonData.DEFAULT_WIDTH), Optional.empty());

        ConfirmationDialog dialog = new ConfirmationDialog(common, yes, no);
        return Holder.direct(dialog);
    }

    private static ActionButton smallTalkButton(Villager villager) {
        CommonButtonData data = new CommonButtonData(Component.literal("Small Talk"), CommonButtonData.DEFAULT_WIDTH);
        Action action = new StaticAction(new ClickEvent.RunCommand("smalltalk hint " + villager.getUUID()));
        return new ActionButton(data, Optional.of(action));
    }

    private static ActionButton giftButton(Villager villager) {
        CommonButtonData data = new CommonButtonData(Component.literal("Gift"), CommonButtonData.DEFAULT_WIDTH);
        Action action = new StaticAction(new ClickEvent.RunCommand("smalltalk gift " + villager.getUUID()));
        return new ActionButton(data, Optional.of(action));
    }

    private static ActionButton tradeButton(Villager villager) {
        CommonButtonData data = new CommonButtonData(Component.literal("Trade"), CommonButtonData.DEFAULT_WIDTH);
        Action action = new StaticAction(new ClickEvent.RunCommand("smalltalk trade " + villager.getUUID()));
        return new ActionButton(data, Optional.of(action));
    }

    private static ActionButton closeButton() {
        CommonButtonData data = new CommonButtonData(Component.literal("Close"), CommonButtonData.DEFAULT_WIDTH);
        return new ActionButton(data, Optional.empty());
    }
}
