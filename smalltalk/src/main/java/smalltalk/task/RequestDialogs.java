package smalltalk.task;

import net.minecraft.core.Holder;
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
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import smalltalk.identity.IdentityDeriver;
import smalltalk.interaction.DialogHold;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SPEC.md sections 12.2 and 12.4: builds the request-related vanilla
 * Dialog screens -- the offer ({@code multi_action}: Accept / Not now /
 * Trade) and the completion flow (hand in items, confirm, receive reward).
 * Mirrors {@code interaction.DialogScreens} and the
 * picker-&gt;confirm-&gt;re-verify discipline {@code interaction.GiftHandler}
 * already uses for gifting (SPEC.md section 6.2) -- Fetch has only one item
 * predicate per task, so there's no picker step, but completion still
 * re-verifies at confirm time via {@code SmallTalkCommands}'s
 * {@code request-complete}, not just at dialog-open time.
 *
 * <p>Buttons route through {@code SmallTalkCommands}: {@code /smalltalk
 * request-accept}, {@code /smalltalk request-decline}, {@code /smalltalk
 * request-complete}. The payload is untrusted at every step -- those
 * commands re-verify the task still exists, is in the expected state,
 * belongs to this player, and hasn't expired before touching anything.
 */
public final class RequestDialogs {

    private RequestDialogs() {}

    /** The offer dialog (SPEC.md section 12.2): Accept / Not now / Trade -- Trade is always reachable here. */
    public static void openOffer(ServerPlayer player, Villager villager, Task task) {
        DialogHold.hold(villager, player);

        String name = IdentityDeriver.displayName(villager);
        int count = FetchTaskLogic.wantedCount(task);
        String itemName = itemDisplayName(FetchTaskLogic.wantedItem(task));

        Component title = Component.literal(name);
        List<DialogBody> body = List.of(new PlainMessage(
                Component.literal(name + " could use " + count + " " + itemName + ". Would you bring some by?"),
                PlainMessage.DEFAULT_WIDTH));
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        List<ActionButton> actions = new ArrayList<>();
        actions.add(commandButton("Accept", "smalltalk request-accept " + villager.getUUID() + " " + task.taskId()));
        actions.add(commandButton("Not now", "smalltalk request-decline " + villager.getUUID() + " " + task.taskId()));
        if (!villager.getOffers().isEmpty()) {
            actions.add(commandButton("Trade", "smalltalk trade " + villager.getUUID()));
        }
        actions.add(new ActionButton(
                new CommonButtonData(Component.literal("Close"), CommonButtonData.DEFAULT_WIDTH), Optional.empty()));

        MultiActionDialog dialog = new MultiActionDialog(common, actions, Optional.empty(), 2);
        Holder<Dialog> holder = Holder.direct(dialog);
        player.openDialog(holder);
    }

    /** The completion dialog (SPEC.md section 12.4): hand in the items, confirm, receive reward. */
    public static void openCompletion(ServerPlayer player, Villager villager, Task task) {
        DialogHold.hold(villager, player);

        int count = FetchTaskLogic.wantedCount(task);
        Item item = BuiltInRegistries.ITEM.getOptional(FetchTaskLogic.wantedItem(task)).orElse(Items.PAPER);
        ItemStack display = new ItemStack(item, Math.max(1, count));
        String name = IdentityDeriver.displayName(villager);

        Component title = Component.literal(
                "Hand in " + count + " " + itemDisplayName(FetchTaskLogic.wantedItem(task)) + " to " + name + "?");
        List<DialogBody> body = List.of(
                new ItemBody(ItemStackTemplate.fromNonEmptyStack(display), Optional.empty(), true, true, 16, 16));
        CommonDialogData common = new CommonDialogData(
                title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        ActionButton yes = new ActionButton(
                new CommonButtonData(Component.literal("Hand in"), CommonButtonData.DEFAULT_WIDTH),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(
                        "smalltalk request-complete " + villager.getUUID() + " " + task.taskId()))));
        ActionButton no = new ActionButton(
                new CommonButtonData(Component.literal("Not yet"), CommonButtonData.DEFAULT_WIDTH), Optional.empty());

        ConfirmationDialog dialog = new ConfirmationDialog(common, yes, no);
        Holder<Dialog> holder = Holder.direct(dialog);
        player.openDialog(holder);
    }

    private static String itemDisplayName(Identifier itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.PAPER);
        return new ItemStack(item).getHoverName().getString();
    }

    private static ActionButton commandButton(String label, String command) {
        CommonButtonData data = new CommonButtonData(Component.literal(label), CommonButtonData.DEFAULT_WIDTH);
        Action action = new StaticAction(new ClickEvent.RunCommand(command));
        return new ActionButton(data, Optional.of(action));
    }
}
