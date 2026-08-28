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

        System.out.println("LobbyBrowserTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
