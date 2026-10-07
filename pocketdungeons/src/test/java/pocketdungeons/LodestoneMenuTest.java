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

        // Overworld menu: Start Dungeon, Browse Lobbies, Visit a Friend,
        // Manage Room, Inspect Compass, Diaries, Reset Compass.
        List<DialogScreens.MenuOption> overworld = DialogScreens.menuOptions(false, false, false);
        check(overworld.size(), 7, "overworld menu has seven options");
        check(overworld.get(0).label(), "Start Dungeon", "overworld first is Start Dungeon");
        check(overworld.get(0).action(), DialogScreens.ACTION_START_DUNGEON,
                "Start Dungeon carries its action id");
        check(overworld.get(1).label(), "Browse Lobbies", "overworld second is Browse Lobbies");
        check(overworld.get(1).action(), DialogScreens.ACTION_BROWSE_LOBBIES,
                "Browse Lobbies carries its action id");
        check(overworld.get(2).label(), "Visit a Friend", "overworld third is Visit a Friend");
        check(overworld.get(2).action(), DialogScreens.ACTION_BROWSE_FRIENDS,
                "Visit a Friend carries its action id");
        check(overworld.get(3).label(), "Manage Room", "overworld fourth is Manage Room");
        check(overworld.get(3).action(), DialogScreens.ACTION_MANAGE_ROOM,
                "Manage Room carries its action id");
        check(overworld.get(4).label(), "Inspect Compass", "overworld fifth is Inspect Compass");
        check(overworld.get(4).action(), DialogScreens.ACTION_INSPECT_KEYSTONE,
                "Inspect Compass carries its action id");
        check(overworld.get(5).label(), "Diaries", "overworld sixth is Diaries");
        check(overworld.get(5).action(), DialogScreens.ACTION_DIARIES,
                "Diaries carries its action id");

        // In-dungeon owner, no door chosen yet (lobby): Leave, Manage Room,
        // Change Shell, Inspect Compass, Diaries. No Quit Door,
        // because there is no door to quit.
        List<DialogScreens.MenuOption> owner = DialogScreens.menuOptions(true, true, false);
        check(owner.size(), 6, "in-dungeon owner menu (lobby) has six options");
        check(owner.get(0).label(), "Leave", "in-dungeon first is Leave");
        check(owner.get(0).action(), DialogScreens.ACTION_LEAVE_DUNGEON,
                "Leave carries its action id");
        check(owner.get(1).label(), "Manage Room", "the room's own owner sees Manage Room");
        check(owner.get(2).label(), "Change Shell", "the room's own owner sees Change Shell");
        check(owner.get(2).action(), DialogScreens.ACTION_CHANGE_SHELL,
                "Change Shell carries its action id");
        check(owner.get(3).label(), "Inspect Compass", "in-dungeon fourth is Inspect Compass");
        check(owner.get(4).label(), "Diaries", "in-dungeon owner fifth is Diaries");
        check(owner.get(5).label(), "Reset Compass", "in-dungeon owner last is Reset Key");
        check(owner.get(5).action(), DialogScreens.ACTION_RESET_KEY, "Reset Compass opens its confirm");
        check(overworld.get(6).label(), "Reset Compass", "overworld last is Reset Key");
        check(owner.get(4).action(), DialogScreens.ACTION_DIARIES,
                "Diaries carries its action id");

        // In-dungeon owner, door chosen (mid-run): Quit Door appears after Leave.
        List<DialogScreens.MenuOption> ownerRun = DialogScreens.menuOptions(true, true, true);
        check(ownerRun.size(), 7, "in-dungeon owner menu (mid-run) has seven options");
        check(ownerRun.get(0).label(), "Leave", "mid-run first is Leave");
        check(ownerRun.get(1).label(), "Quit Door", "mid-run second is Quit Door");
        check(ownerRun.get(1).action(), DialogScreens.ACTION_QUIT_DUNGEON,
                "Quit Door carries its action id");
        check(ownerRun.get(2).label(), "Manage Room", "mid-run third is Manage Room");

        // In-dungeon visitor: Leave, Inspect Compass, Diaries; no Manage Room, no Quit Door.
        List<DialogScreens.MenuOption> visitor = DialogScreens.menuOptions(true, false, true);
        check(visitor.size(), 3, "in-dungeon visitor menu omits Manage Room and Quit Door");
        check(visitor.get(0).label(), "Leave", "visitor sees Leave");
        check(visitor.get(1).label(), "Inspect Compass", "visitor sees Inspect Compass");
        check(visitor.get(2).label(), "Diaries", "visitor sees Diaries");
        check(visitor.stream().noneMatch(o -> o.action().equals(DialogScreens.ACTION_RESET_KEY)), true,
                "a visitor never sees Reset Compass");

        // The dialog is a MultiActionDialog with one button per option, each
        // carrying the owner UUID so DialogRouter's owner check passes.
        net.minecraft.server.dialog.Dialog dialog = DialogScreens.lodestoneMenuDialog(overworld, player, false);
        check(dialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "overworld menu is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) dialog;
        check(list.actions().size(), 7, "one button per overworld option");
        net.minecraft.server.dialog.action.CustomAll action =
                (net.minecraft.server.dialog.action.CustomAll) list.actions().get(0).action().orElseThrow();
        net.minecraft.nbt.CompoundTag payload = action.additions().orElseThrow();
        check(payload.getStringOr(DialogScreens.KEY_OWNER, ""), player.toString(),
                "button carries the owner UUID");

        net.minecraft.server.dialog.Dialog dungeonDialog = DialogScreens.lodestoneMenuDialog(owner, player, true);
        check(dungeonDialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "in-dungeon menu is a MultiActionDialog");
        check(((net.minecraft.server.dialog.MultiActionDialog) dungeonDialog).actions().size(), 6,
                "one button per in-dungeon owner option");

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
