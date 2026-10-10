package pocketdungeons;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.level.ServerPlayer;

/**
 * Manage Party (2026-10-09): a button-less MultiActionDialog cannot be encoded, and sending one disconnects
 * the player (seen live on the Invite screen with nobody else online). Every new screen is built in its
 * emptiest state and must not be one.
 */
public final class PartyScreensGameTest {

    @SuppressWarnings("removal")
    @GameTest(maxTicks = 40)
    public void noPartyScreenIsAButtonlessList(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID owner = player.getUUID();
        check(helper, DialogScreens.manageParty(server, owner), "party");
        check(helper, DialogScreens.inviteList(server, owner), "invite");
        check(helper, DialogScreens.bannedList(server, owner), "banned");
        check(helper, DialogScreens.partyPerson(server, owner, UUID.randomUUID()), "person");
        check(helper, DialogScreens.manageRoom(server, owner), "room");
        check(helper, DialogScreens.lobbiesHub(owner), "lobbies");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, Dialog dialog, String name) {
        if (dialog instanceof MultiActionDialog multi && multi.actions().isEmpty()) {
            helper.fail("The " + name + " screen is a MultiActionDialog with no buttons");
        }
    }
}
