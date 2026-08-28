package pocketdungeons;

import java.util.List;
import java.util.UUID;

/**
 * Regression for the wall-lodestone menu's pure halves (M21): the option
 * list per context (overworld has four options, in-dungeon owner has three,
 * in-dungeon visitor omits Manage Room) and the dialog construction from
 * prebuilt options. No server is available in this headless test, so the
 * owner check (which reads a live {@code InstanceRecord}) is pinned at the
 * {@link DialogScreens#menuOptions} level; the resulting dialog is the same
 * object a live open would send.
 */
public class LodestoneMenuTest {

    public static void main(String[] args) {
        // Building a real Dialog touches BuiltInRegistries (the dialog codecs
        // resolve vanilla ids in their static initializers), the same bootstrap
        // LobbyBrowserTest needs.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

        // Overworld menu: Start Dungeon, Browse Lobbies, Manage Room, Inspect Keystone.
        List<DialogScreens.MenuOption> overworld = DialogScreens.menuOptions(false, false);
        check(overworld.size(), 4, "overworld menu has four options");
        check(overworld.get(0).label(), "Start Dungeon", "overworld first is Start Dungeon");
        check(overworld.get(0).action(), DialogScreens.ACTION_START_DUNGEON,
                "Start Dungeon carries its action id");
        check(overworld.get(1).label(), "Browse Lobbies", "overworld second is Browse Lobbies");
        check(overworld.get(1).action(), DialogScreens.ACTION_BROWSE_LOBBIES,
                "Browse Lobbies carries its action id");
        check(overworld.get(2).label(), "Manage Room", "overworld third is Manage Room");
        check(overworld.get(2).action(), DialogScreens.ACTION_MANAGE_ROOM,
                "Manage Room carries its action id");
        check(overworld.get(3).label(), "Inspect Keystone", "overworld fourth is Inspect Keystone");
        check(overworld.get(3).action(), DialogScreens.ACTION_INSPECT_KEYSTONE,
                "Inspect Keystone carries its action id");

        // In-dungeon owner: Leave, Manage Room, Inspect Keystone.
        List<DialogScreens.MenuOption> owner = DialogScreens.menuOptions(true, true);
        check(owner.size(), 3, "in-dungeon owner menu has three options");
        check(owner.get(0).label(), "Leave", "in-dungeon first is Leave");
        check(owner.get(0).action(), DialogScreens.ACTION_LEAVE_DUNGEON,
                "Leave carries its action id");
        check(owner.get(1).label(), "Manage Room", "the room's own owner sees Manage Room");
        check(owner.get(2).label(), "Inspect Keystone", "in-dungeon last is Inspect Keystone");

        // In-dungeon visitor: Leave, Inspect Keystone; no Manage Room.
        List<DialogScreens.MenuOption> visitor = DialogScreens.menuOptions(true, false);
        check(visitor.size(), 2, "in-dungeon visitor menu omits Manage Room");
        check(visitor.get(0).label(), "Leave", "visitor sees Leave");
        check(visitor.get(1).label(), "Inspect Keystone", "visitor sees Inspect Keystone");

        // The dialog is a MultiActionDialog with one button per option, each
        // carrying the owner UUID so DialogRouter's owner check passes.
        net.minecraft.server.dialog.Dialog dialog = DialogScreens.lodestoneMenuDialog(overworld, player, false);
        check(dialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "overworld menu is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) dialog;
        check(list.actions().size(), 4, "one button per overworld option");
        net.minecraft.server.dialog.action.CustomAll action =
                (net.minecraft.server.dialog.action.CustomAll) list.actions().get(0).action().orElseThrow();
        net.minecraft.nbt.CompoundTag payload = action.additions().orElseThrow();
        check(payload.getStringOr(DialogScreens.KEY_OWNER, ""), player.toString(),
                "button carries the owner UUID");

        net.minecraft.server.dialog.Dialog dungeonDialog = DialogScreens.lodestoneMenuDialog(owner, player, true);
        check(dungeonDialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "in-dungeon menu is a MultiActionDialog");
        check(((net.minecraft.server.dialog.MultiActionDialog) dungeonDialog).actions().size(), 3,
                "one button per in-dungeon owner option");

        System.out.println("LodestoneMenuTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
