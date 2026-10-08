package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Haul and Blood Doors: the haul banks into the compass at every exit (home, finish, fail,
 * orphan), a failed dungeon keeps half, a member who is not online banks too, the keystone item's
 * level follows the compass, and the operator can set the compass and the haul.
 */
public final class HaulGameTest {

    @GameTest
    public void homeBanksTheWholeHaulAndTheKeystoneFollows(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = UUID.randomUUID();
        log.setCompass(id, 12, 3);
        log.setKeystone(id, 12, java.util.Set.of());
        log.addHaul(id, 5);

        DungeonLog.BankResult result = RunLifecycle.bankHaul(server, null, id, RunLifecycle.BankContext.HOME);
        helper.assertValueEqual(result.banked(), 5, "home banks the whole haul");
        helper.assertValueEqual(result.lost(), 0, "and loses none");
        helper.assertValueEqual(log.get(id).highestCharts(), 13, "3 + 5 = 8 crosses a level: compass 13");
        helper.assertValueEqual(log.get(id).chartProgress(), 3, "with 3 left in the bar");
        helper.assertValueEqual(log.haulOf(id), 0, "the haul is empty");
        helper.assertValueEqual(log.get(id).keystoneLevel(), 13, "the keystone item's level follows the compass");
        helper.succeed();
    }

    @GameTest
    public void aFailedDungeonKeepsHalfEvenForAMemberWhoIsNotOnline(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = UUID.randomUUID();
        log.setCompass(id, 4, 0);
        log.addHaul(id, 7);

        DungeonLog.BankResult result = RunLifecycle.bankHaul(server, null, id, RunLifecycle.BankContext.FAIL);
        helper.assertValueEqual(result.banked(), 3, "half of 7, rounded down");
        helper.assertValueEqual(result.lost(), 4, "the rest is lost");
        helper.assertValueEqual(log.get(id).highestCharts(), 4, "3 does not fill the bar");
        helper.assertValueEqual(log.get(id).chartProgress(), 3, "it sits in the bar");
        helper.assertValueEqual(log.haulOf(id), 0, "the haul is gone either way");
        helper.succeed();
    }

    @GameTest
    public void aFailBankNeverLowersTheCompassOrTheBar(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = UUID.randomUUID();
        log.setCompass(id, 9, 4);
        RunLifecycle.bankHaul(server, null, id, RunLifecycle.BankContext.FAIL);
        helper.assertValueEqual(log.get(id).highestCharts(), 9, "an empty haul changes nothing");
        helper.assertValueEqual(log.get(id).chartProgress(), 4, "and leaves the bar");
        helper.succeed();
    }

    @GameTest
    public void aPartyIsPaidByEachMembersOwnCompass(GameTestHelper helper) {
        // A compass 4 member and a compass 9 member on a level 7 floor dealt +2.
        helper.assertValueEqual(ScrapMath.floorPay(2, 7, 4), 2, "the lower member earns the full step");
        helper.assertValueEqual(ScrapMath.floorPay(2, 7, 9), 1, "the higher member earns 1");
        helper.succeed();
    }

    @GameTest
    public void anOrphanedHaulBanksWhenTheMemberJoins(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID id = player.getUUID();
        log.setCompass(id, 2, 0);
        log.addHaul(id, 5);

        RunLifecycle.onJoinHaul(server, player);
        helper.assertValueEqual(log.haulOf(id), 0, "the orphaned haul came home");
        helper.assertValueEqual(log.get(id).highestCharts(), 3, "5 scrap is one level");
        helper.assertTrue(log.get(id).haulIntroSeen(), "the one time message was shown and recorded");
        log.setCompass(id, 0, 0);
        helper.succeed();
    }

    @GameTest
    public void theOperatorCanSetTheCompassAndTheHaul(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        UUID id = UUID.randomUUID();
        log.setCompass(id, 20, 2);
        log.setHaul(id, 9);
        helper.assertValueEqual(log.get(id).highestCharts(), 20, "the compass is set");
        helper.assertValueEqual(log.get(id).chartProgress(), 2, "with its bar");
        helper.assertValueEqual(log.haulOf(id), 9, "the haul is set");
        helper.assertTrue(!log.setCompass(id, 5, 0), "setting it lower reports no rise");
        helper.assertValueEqual(log.get(id).highestCharts(), 5, "and lowers it");
        helper.succeed();
    }

    @GameTest
    public void aSideDoorCannotTakeTheLastLife(GameTestHelper helper) {
        for (int omen = 0; omen <= Omen.MAX_OMEN; omen++) {
            int livesLeft = Omen.lives(omen);
            helper.assertTrue(DoorLives.affordable(livesLeft, 1) == (livesLeft > 1),
                    "one life door at " + livesLeft + " lives left");
            helper.assertTrue(DoorLives.affordable(livesLeft, 2) == (livesLeft > 2),
                    "two life door at " + livesLeft + " lives left");
        }
        helper.assertValueEqual(Omen.add(0, 2), 2, "a two life door raises the omen by two");
        helper.succeed();
    }
}
