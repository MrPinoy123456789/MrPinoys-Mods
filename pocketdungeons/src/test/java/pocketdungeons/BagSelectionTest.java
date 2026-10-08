package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regression for the bag picker (M48): the picker lists the five offered
 * bags in declaration order, each button carries the clicker's UUID and the
 * bag id, and the confirm dialog carries the bag id on its Confirm button.
 * No server is available in this headless test, so the live
 * {@link DialogScreens#bagPicker} and {@link DialogScreens#bagConfirm}
 * wrappers (which read a ServerPlayer) are pinned at the pure
 * {@link DialogScreens#bagPickerDialog} and
 * {@link DialogScreens#bagConfirmDialog} halves, the same split
 * {@code LodestoneMenuTest} uses.
 *
 * <p>M70: the picker now reads the data-driven {@link BagManifest} rather than
 * the deleted {@code Bags} enum. This test publishes a synthetic manifest
 * holding L1's five offered bags, so the headless test sees the same picker
 * the live server shows with the {@code extra_bags} module off.
 */
public class BagSelectionTest {

    public static void main(String[] args) {
        // Building a real Dialog touches BuiltInRegistries in its static
        // initializers, the same bootstrap LobbyBrowserTest needs.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        // M70: publish a synthetic bag manifest with L1's five offered bags,
        // so the picker reads the same list the live server shows while the
        // extra_bags module is off.
        publishSyntheticManifest();

        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

        // The picker lists the five offered bags in order, each with a
        // non-empty label and blurb.
        List<DialogScreens.BagOption> options = DialogScreens.bagOptions();
        check(options.size(), 5, "picker lists the five offered bags");
        List<String> ids = new ArrayList<>();
        for (DialogScreens.BagOption option : options) {
            ids.add(option.bagId());
            check(option.label().isEmpty(), false, "bag label is non-empty: " + option.bagId());
            check(option.tooltip().isEmpty(), false, "bag tooltip is non-empty: " + option.bagId());
            check(Bags.byId(option.bagId()) != null, true, "bag id resolves: " + option.bagId());
        }
        check(ids, List.of(BagIds.LUMBERJACK, BagIds.SAPPER, BagIds.RANGER, BagIds.INNKEEPER,
                BagIds.GUARD), "bag ids in picker order");

        // The picker dialog is a MultiActionDialog with one button per bag,
        // each carrying the owner UUID and the bag id under the bag action.
        net.minecraft.server.dialog.Dialog picker = DialogScreens.bagPickerDialog(options, owner);
        check(picker instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "picker is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) picker;
        check(list.actions().size(), 5, "one picker button per bag");
        for (int i = 0; i < 5; i++) {
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
        net.minecraft.server.dialog.Dialog confirm = DialogScreens.bagConfirmDialog(owner, BagIds.RANGER);
        check(confirm instanceof net.minecraft.server.dialog.ConfirmationDialog, true,
                "confirm is a ConfirmationDialog");
        net.minecraft.server.dialog.ConfirmationDialog confirmDialog =
                (net.minecraft.server.dialog.ConfirmationDialog) confirm;
        net.minecraft.server.dialog.action.CustomAll yes = (net.minecraft.server.dialog.action.CustomAll)
                confirmDialog.yesButton().action().orElseThrow();
        net.minecraft.nbt.CompoundTag yesPayload = yes.additions().orElseThrow();
        check(yesPayload.getStringOr(DialogScreens.KEY_BAG_ID, ""), BagIds.RANGER,
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

    /**
     * Publishes a synthetic {@link BagManifest} built from L1's five offered
     * {@link BagIds}, so the headless picker reads the same bags the live
     * server would load from {@code dungeon_bag/*.json} with the
     * {@code extra_bags} module off. The definitions carry the label, blurb
     * and order the JSON files ship, so the dialog text and order match.
     */
    private static void publishSyntheticManifest() {
        Map<String, BagManifest.Entry> entries = new LinkedHashMap<>();
        Object[][] bags = {
                {BagIds.LUMBERJACK, "Lumberjack's Bag",
                        "An axe and an armful of timber. The rest is elbow grease.", 1},
                {BagIds.SAPPER, "Sapper's Bag",
                        "Three sticks of the loudest answer there is, and a pick for the quiet bits.", 2},
                {BagIds.RANGER, "Ranger's Bag", "Reach. See it first, hit it from there.", 4},
                {BagIds.INNKEEPER, "Innkeeper's Bag",
                        "Milk, an apple, a warm light, and a rolling pin you will regret "
                                + "underestimating.", 6},
                {BagIds.GUARD, "Guard's Bag", "A sword and a shield. Hold the line.", 8},
        };
        for (Object[] bag : bags) {
            String id = (String) bag[0];
            String label = (String) bag[1];
            String blurb = (String) bag[2];
            int order = (Integer) bag[3];
            BagDefinition def = new BagDefinition(id, label, blurb, order,
                    List.of(), java.util.Set.of(),
                    BagMeta.defaultLootTable(id));
            entries.put(id, new BagManifest.Entry(id, def));
        }
        // BagManifest has a package-private constructor; use a reflection-free
        // approach by parsing into it through the same path the live loader
        // would. Since the constructor is package-private and this test is in
        // the same package, we can build it directly.
        BagManifest manifest = buildManifest(entries);
        BagManifest.publish(manifest);
    }

    private static BagManifest buildManifest(Map<String, BagManifest.Entry> entries) {
        // The BagManifest constructor is package-private; this test is in the
        // pocketdungeons package so it can call it. The rejections list is
        // empty for a synthetic manifest.
        return BagManifest.create(entries, List.of());
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
