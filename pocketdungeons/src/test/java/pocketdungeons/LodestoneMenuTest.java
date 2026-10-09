package pocketdungeons;

import java.util.List;
import java.util.UUID;

/**
 * Regression for the wall-lodestone menu's pure halves (M21): the option
 * list per context (in-dungeon visitor omits Manage Room and Change Shell)
 * and the dialog construction from
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

        // Home menu: Start Dungeon, Manage Room, Inspect Compass, Manage Party, View Lobbies.
        List<DialogScreens.MenuOption> overworld = DialogScreens.menuOptions(false, false, false);
        check(overworld.size(), 5, "home menu has five options");
        String[] homeLabels = {"Start Dungeon", "Manage Room", "Inspect Compass", "Manage Party", "View Lobbies"};
        String[] homeActions = {DialogScreens.ACTION_START_DUNGEON, DialogScreens.ACTION_MANAGE_ROOM,
                DialogScreens.ACTION_INSPECT_KEYSTONE, DialogScreens.ACTION_MANAGE_PARTY,
                DialogScreens.ACTION_VIEW_LOBBIES};
        for (int i = 0; i < homeLabels.length; i++) {
            check(overworld.get(i).label(), homeLabels[i], "home option " + i + " label");
            check(overworld.get(i).action(), homeActions[i], "home option " + i + " action");
        }

        // In-dungeon owner, no door chosen yet (lobby): Leave, Manage Room, Inspect Compass, Manage Party.
        List<DialogScreens.MenuOption> owner = DialogScreens.menuOptions(true, true, false);
        check(owner.size(), 5, "in-dungeon owner menu (lobby) has five options");
        check(owner.get(0).label(), "Leave", "in-dungeon first is Leave");
        check(owner.get(0).action(), DialogScreens.ACTION_LEAVE_DUNGEON, "Leave carries its action id");
        check(owner.get(1).label(), "Manage Room", "the room's own owner sees Manage Room");
        check(owner.get(2).label(), "Inspect Compass", "in-dungeon third is Inspect Compass");
        check(owner.get(3).label(), "Manage Party", "the room's own owner sees Manage Party");
        check(owner.get(4).label(), "View Lobbies", "a lobby owner can view lobbies");

        // In-dungeon owner, door chosen (mid-run): Quit Door appears after Leave.
        List<DialogScreens.MenuOption> ownerRun = DialogScreens.menuOptions(true, true, true);
        check(ownerRun.size(), 5, "in-dungeon owner menu (mid-run) has five options");
        check(ownerRun.get(0).label(), "Leave", "mid-run first is Leave");
        check(ownerRun.get(1).label(), "Quit Door", "mid-run second is Quit Door");
        check(ownerRun.get(1).action(), DialogScreens.ACTION_QUIT_DUNGEON, "Quit Door carries its action id");
        check(ownerRun.get(2).label(), "Manage Room", "mid-run third is Manage Room");

        // In-dungeon visitor: Leave and Inspect Compass only.
        List<DialogScreens.MenuOption> visitor = DialogScreens.menuOptions(true, false, true);
        check(visitor.size(), 2, "in-dungeon visitor menu omits Manage Room, Manage Party and Quit Door");
        check(visitor.get(0).label(), "Leave", "visitor sees Leave");
        check(visitor.get(1).label(), "Inspect Compass", "visitor sees Inspect Compass");
        check(visitor.stream().noneMatch(o -> o.action().equals(DialogScreens.ACTION_RESET_KEY)), true,
                "a visitor never sees Reset Compass");

        // The dialog is a MultiActionDialog with one button per option, each
        // carrying the owner UUID so DialogRouter's owner check passes.
        net.minecraft.server.dialog.Dialog dialog = DialogScreens.lodestoneMenuDialog(overworld, player, false);
        check(dialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "overworld menu is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) dialog;
        check(list.actions().size(), 5, "one button per home option");
        net.minecraft.server.dialog.action.CustomAll action =
                (net.minecraft.server.dialog.action.CustomAll) list.actions().get(0).action().orElseThrow();
        net.minecraft.nbt.CompoundTag payload = action.additions().orElseThrow();
        check(payload.getStringOr(DialogScreens.KEY_OWNER, ""), player.toString(),
                "button carries the owner UUID");

        net.minecraft.server.dialog.Dialog dungeonDialog = DialogScreens.lodestoneMenuDialog(owner, player, true);
        check(dungeonDialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "in-dungeon menu is a MultiActionDialog");
        check(((net.minecraft.server.dialog.MultiActionDialog) dungeonDialog).actions().size(), 5,
                "one button per in-dungeon owner option, Leave last");

        // PD-130: the listing and the whitelist are two lines, and a public
        // room never claims that nobody may enter.
        check(DialogScreens.listingLine(true).startsWith("Listed in the lobby directory"), true,
                "a public room says it is listed");
        check(DialogScreens.listingLine(false), "Not listed in the lobby directory.",
                "a private room says it is not listed");
        check(DialogScreens.whitelistLine(0).contains("Nobody"), false,
                "an empty whitelist no longer reads as nobody may enter");
        check(DialogScreens.whitelistLine(1), "1 trusted player may visit from their friends list and build here.",
                "one trusted player");

        System.out.println("LodestoneMenuTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
