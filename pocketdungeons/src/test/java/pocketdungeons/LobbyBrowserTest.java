package pocketdungeons;

import java.util.List;
import java.util.UUID;

/**
 * Regression for the lobby directory's pure row logic (M20): which online
 * players appear (only those with {@code publicListed} true), what each
 * row's label and body contain, and the empty-directory notice. No server
 * is available in this headless test, so rows are built through the pure
 * {@link DialogScreens#lobbyRows}/{@link DialogScreens#lobbyBrowserDialog}
 * half; the resulting dialog is the same object a live open would send.
 */
public class LobbyBrowserTest {

    public static void main(String[] args) {
        // Building a real Dialog touches BuiltInRegistries (the dialog codecs
        // resolve vanilla ids in their static initializers), which requires the
        // one-time registry bootstrap even in a headless run. No server, no
        // world: this is the same bootstrap every vanilla unit test calls.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        UUID alice = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID carol = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        UUID clicker = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

        DungeonLog log = new DungeonLog();
        log.setPublicListed(alice, true);
        log.setRoomName(alice, "The Vault");
        // bob stays unlisted; carol is listed but never named.
        log.setPublicListed(carol, true);

        List<DialogScreens.OnlinePlayer> online = List.of(
                new DialogScreens.OnlinePlayer(alice, "Alice"),
                new DialogScreens.OnlinePlayer(bob, "Bob"),
                new DialogScreens.OnlinePlayer(carol, "Carol"));

        List<DialogScreens.LobbyRow> rows = DialogScreens.lobbyRows(log, online);
        check(rows.size(), 2, "only listed players appear");
        check(rows.get(0).owner(), carol, "rows sort by label, Carol first");
        check(rows.get(0).label(), "Carol (0)", "unnamed room label falls back to the player name");
        check(rows.get(0).body(), "Carol: away", "body carries owner name and live status");
        check(rows.get(1).owner(), alice, "second row is Alice");
        check(rows.get(1).label(), "The Vault (0)", "named room label carries the name and occupancy");
        check(rows.get(1).body(), "Alice: away", "body carries owner name and live status");

        // The empty directory is a notice, never an empty button list.
        net.minecraft.server.dialog.Dialog empty = DialogScreens.lobbyBrowserDialog(List.of(), clicker, null);
        check(empty instanceof net.minecraft.server.dialog.NoticeDialog, true,
                "empty directory is a NoticeDialog");

        // A non-empty directory is a MultiActionDialog with one button per room.
        net.minecraft.server.dialog.Dialog dialog = DialogScreens.lobbyBrowserDialog(rows, clicker, null);
        check(dialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "directory is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog list =
                (net.minecraft.server.dialog.MultiActionDialog) dialog;
        check(list.actions().size(), 2, "one button per public room");
        net.minecraft.server.dialog.ActionButton aliceButton = list.actions().get(1);
        check(aliceButton.button().label().getString(), "The Vault (0)",
                "button label matches the row label");
        // The payload carries the target owner and clicker UUIDs, the same keys
        // the whitelist actions use, so DialogRouter's owner check accepts it.
        net.minecraft.server.dialog.action.CustomAll action =
                (net.minecraft.server.dialog.action.CustomAll) aliceButton.action().orElseThrow();
        net.minecraft.nbt.CompoundTag payload = action.additions().orElseThrow();
        check(payload.getStringOr(DialogScreens.KEY_TARGET, ""), alice.toString(),
                "button carries the target owner UUID");
        check(payload.getStringOr(DialogScreens.KEY_OWNER, ""), clicker.toString(),
                "button carries the clicker UUID");

        // M75: the private visit channel. friendRows lists only online owners
        // who have whitelisted the clicker; an unwhitelisted online owner and
        // a whitelisted offline owner both stay out. The clicker's own room
        // never appears even if they somehow whitelisted themselves.
        RoomWhitelist whitelist = new RoomWhitelist();
        whitelist.add(alice, clicker);     // online, invited
        whitelist.add(carol, clicker);      // invited but offline (not in the online set below)
        whitelist.add(clicker, clicker);    // self: must not appear

        // Only alice and bob are online for this scenario; carol is offline.
        List<DialogScreens.OnlinePlayer> friendsOnline = List.of(
                new DialogScreens.OnlinePlayer(alice, "Alice"),
                new DialogScreens.OnlinePlayer(bob, "Bob"));

        List<DialogScreens.LobbyRow> friendRows = DialogScreens.friendRows(whitelist, log, friendsOnline, clicker);
        check(friendRows.size(), 1, "only online owners who whitelist the clicker appear");
        check(friendRows.get(0).owner(), alice, "the one friend row is Alice");
        check(friendRows.get(0).label(), "The Vault (0)", "friend row label carries the room name");
        check(friendRows.get(0).body(), "Alice: away", "friend row body carries owner and status");

        // The empty friend directory is a notice, never an empty button list.
        net.minecraft.server.dialog.Dialog emptyFriends =
                DialogScreens.friendBrowserDialog(List.of(), clicker, null);
        check(emptyFriends instanceof net.minecraft.server.dialog.NoticeDialog, true,
                "empty friend directory is a NoticeDialog");

        // A non-empty friend directory is a MultiActionDialog whose buttons
        // carry the visit-friend action (not the public visit-room action).
        net.minecraft.server.dialog.Dialog friendDialog =
                DialogScreens.friendBrowserDialog(friendRows, clicker, null);
        check(friendDialog instanceof net.minecraft.server.dialog.MultiActionDialog, true,
                "friend directory is a MultiActionDialog");
        net.minecraft.server.dialog.MultiActionDialog friendList =
                (net.minecraft.server.dialog.MultiActionDialog) friendDialog;
        check(friendList.actions().size(), 1, "one button per whitelisted online room");
        net.minecraft.server.dialog.action.CustomAll friendAction =
                (net.minecraft.server.dialog.action.CustomAll) friendList.actions().get(0).action().orElseThrow();
        check(friendAction.id().getPath(), DialogScreens.ACTION_VISIT_FRIEND,
                "friend button carries the visit-friend action, not the public visit-room action");

        System.out.println("LobbyBrowserTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
