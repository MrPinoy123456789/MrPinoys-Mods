package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regression for the bag picker (M48): the picker lists all eight bags in
 * declaration order, each button carries the clicker's UUID and the bag id,
 * and the confirm dialog carries the bag id on its Confirm button. No server
 * is available in this headless test, so the live {@link DialogScreens#bagPicker}
 * and {@link DialogScreens#bagConfirm} wrappers (which read a ServerPlayer) are
 * pinned at the pure {@link DialogScreens#bagPickerDialog} and
 * {@link DialogScreens#bagConfirmDialog} halves, the same split
 * {@code LodestoneMenuTest} uses.
 */
public class BagSelectionTest {

    public static void main(String[] args) {
        // Building a real Dialog touches BuiltInRegistries in its static
        // initializers, the same bootstrap LobbyBrowserTest needs.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

        // The picker lists all eight bags in declaration order, each with a
        // non-empty label and blurb.
        List<DialogScreens.BagOption> options = DialogScreens.bagOptions();
        check(options.size(), 8, "picker lists all eight bags");
        List<String> ids = new ArrayList<>();
        for (DialogScreens.BagOption option : options) {
            ids.add(option.bagId());
            check(option.label().isEmpty(), false, "bag label is non-empty: " + option.bagId());
            check(option.tooltip().isEmpty(), false, "bag tooltip is non-empty: " + option.bagId());
            check(Bags.byId(option.bagId()) != null, true, "bag id resolves: " + option.bagId());
        }
        check(ids, List.of("mason", "plumber", "sapper", "magician",
                        "ranger", "shepherd", "innkeeper", "pilgrim"),
                "bag ids in declaration order");

        // The picker dialog is a MultiActionDialog with one button per bag,
        // each carrying the owner UUID and the bag id under the bag action.
        net.minecraft.server.dialog.Dialog picker = DialogScreens.bagPickerDialog(options, owner);
        check(picker instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "picker is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) picker;
        check(list.actions().size(), 8, "one picker button per bag");
        for (int i = 0; i < 8; i++) {
            net.minecraft.server.dialog.action.CustomAll action = (net.minecraft.server.dialog.action.CustomAll)
                    list.actions().get(i).action().orElseThrow();
            net.minecraft.nbt.CompoundTag payload = action.additions().orElseThrow();
            check(payload.getStringOr(DialogScreens.KEY_OWNER, ""), owner.toString(),
                    "picker button carries the owner UUID");
            check(payload.getStringOr(DialogScreens.KEY_BAG_ID, ""), ids.get(i),
                    "picker button carries its bag id");
        }

        // The confirm dialog carries the bag id on its Confirm button and the
        // owner UUID on its Back button (the no-bag-id signal to re-open the
        // picker).
        net.minecraft.server.dialog.Dialog confirm = DialogScreens.bagConfirmDialog(owner, "ranger");
        check(confirm instanceof net.minecraft.server.dialog.ConfirmationDialog, true,
                "confirm is a ConfirmationDialog");
        net.minecraft.server.dialog.ConfirmationDialog confirmDialog =
                (net.minecraft.server.dialog.ConfirmationDialog) confirm;
        net.minecraft.server.dialog.action.CustomAll yes = (net.minecraft.server.dialog.action.CustomAll)
                confirmDialog.yesButton().action().orElseThrow();
        net.minecraft.nbt.CompoundTag yesPayload = yes.additions().orElseThrow();
        check(yesPayload.getStringOr(DialogScreens.KEY_BAG_ID, ""), "ranger",
                "confirm button carries the bag id");
        check(yesPayload.getStringOr(DialogScreens.KEY_OWNER, ""), owner.toString(),
                "confirm button carries the owner UUID");
        net.minecraft.server.dialog.action.CustomAll back = (net.minecraft.server.dialog.action.CustomAll)
                confirmDialog.noButton().action().orElseThrow();
        net.minecraft.nbt.CompoundTag backPayload = back.additions().orElseThrow();
        check(backPayload.getStringOr(DialogScreens.KEY_BAG_ID, ""), "",
                "back button carries no bag id, signalling a re-open of the picker");
        check(backPayload.getStringOr(DialogScreens.KEY_OWNER, ""), owner.toString(),
                "back button carries the owner UUID");

        System.out.println("BagSelectionTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
