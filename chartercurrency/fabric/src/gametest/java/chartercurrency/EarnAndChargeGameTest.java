package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.EarnService;
import chartercurrency.core.TxResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** The Fortune wiring in a real server: the shipped earn source, the merge, and the external charge log line (EC-2, EC-4). */
public final class EarnAndChargeGameTest {

    @GameTest
    public void theShippedEarnDefaultsCarryFortuneAndAnOldFileGainsItWithoutLosingTuning(GameTestHelper helper) throws Exception {
        EarnService.Source fortune = EarnConfigFile.defaultsEarn().sources().get("fortune");
        helper.assertTrue(fortune != null && fortune.base() == 4 && fortune.perLevel() == 2 && fortune.softCap() == 60
                        && fortune.hardCap() == 0 && fortune.renownMultiplier() == 0.5,
                "The shipped fortune source is missing or wrong: " + fortune);

        Path dir = Files.createTempDirectory("earn-merge");
        // An earn.json from before the fortune source, with a tuned dungeon source and its own cap.
        Files.writeString(dir.resolve("earn.json"), "{\"sources\":{\"dungeon\":{\"base\":99,\"perLevel\":9,\"softCap\":999,"
                + "\"hardCap\":0,\"renownMultiplier\":1.0}},\"renown\":{\"perPlayerDailyCap\":500,\"runWindowSeconds\":30,\"probationDays\":7}}");
        EarnService.Config merged = EarnConfigFile.loadEarn(dir, EarnConfigFile.defaultsEarn());
        helper.assertTrue(merged.sources().get("fortune") != null, "The fortune source was not merged into an old earn.json");
        helper.assertTrue(merged.sources().get("dungeon").base() == 99, "A tuned value was overwritten by the merge");
        helper.assertTrue(merged.perPlayerDailyCap() == 500, "A tuned renown cap was overwritten by the merge");
        helper.assertTrue(Files.readString(dir.resolve("earn.json")).contains("\"fortune\""),
                "The merged source was not written back to earn.json");

        // The live file: fortune present with renownMultiplier 1.0 stays exactly as the operator set it.
        Files.writeString(dir.resolve("earn.json"), "{\"sources\":{\"fortune\":{\"base\":4,\"perLevel\":2,\"softCap\":60,"
                + "\"hardCap\":0,\"renownMultiplier\":1.0}},\"renown\":{}}");
        EarnService.Config live = EarnConfigFile.loadEarn(dir, EarnConfigFile.defaultsEarn());
        helper.assertTrue(live.sources().get("fortune").renownMultiplier() == 1.0,
                "The operator's fortune renownMultiplier 1.0 was overwritten");
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void anExternalChargeWritesTheTransactionLogLine(GameTestHelper helper) {
        Services s = Services.current;
        helper.assertTrue(s != null, "Charter Currency did not start with the server");
        Currency coin = s.coin();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID id = player.getUUID();
        s.economy.deposit(id, coin, 640);

        String reason = "fortune_stake (5 Sovereigns at Fantasy's Fortune)";
        TxResult result = EconomyApi.chargeExternal(id, "Gambler", 320, reason);
        helper.assertTrue(result.ok() && s.economy.getBalance(id, coin) == 320, "The external charge did not take 320 Marks");
        TxResult tooMuch = EconomyApi.chargeExternal(id, "Gambler", 321, "fortune_stake");
        helper.assertTrue(!tooMuch.ok() && s.economy.getBalance(id, coin) == 320, "An unaffordable external charge changed the balance");

        Path log = FabricLoader.getInstance().getConfigDir().resolve("chartercurrency").resolve("transactions.log");
        helper.succeedWhen(() -> {
            String text;
            try {
                text = Files.exists(log) ? Files.readString(log) : "";
            } catch (java.io.IOException e) {
                text = "";
            }
            helper.assertTrue(text.contains("[Economy] Gambler was charged 320 Marks for " + reason),
                    "transactions.log has no line for the external charge");
            helper.assertTrue(!text.contains("was charged 321 Marks"), "A refused external charge was logged");
        });
    }
}
