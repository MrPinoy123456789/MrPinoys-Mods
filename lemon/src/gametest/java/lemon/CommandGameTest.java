package lemon;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class CommandGameTest {
    @GameTest
    public void standaloneCommandsRegistered(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        var lemon = root.getChild("lemon");
        helper.assertTrue(lemon != null, "Lemon owns the root command");
        for (String command : new String[]{"say", "ask", "reply", "think", "quiet", "on", "mode",
                "report", "summon", "tour", "context"}) {
            helper.assertTrue(lemon.getChild(command) != null, "registered " + command);
        }
        helper.succeed();
    }

    @GameTest
    public void companionJournalLivesOutsideGameJournals(GameTestHelper helper) throws Exception {
        @SuppressWarnings("removal")
        var player = helper.makeMockServerPlayerInLevel();
        LemonJournal.lemonAsk(player, "Question?", "llm", 0, 2);
        LemonJournal.lemonAnswer(player, "A question", "An answer");
        LemonJournal.lemonHeard(player, "Neighbour", "Hello");
        LemonJournal.report(player, "A report");
        var day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        var path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("lemon/journal").resolve(day).resolve(player.getUUID() + ".jsonl");
        var kinds = new java.util.HashSet<String>();
        for (String line : java.nio.file.Files.readAllLines(path)) {
            var event = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            helper.assertValueEqual(event.get("player").getAsString(), player.getUUID().toString(), "journal UUID");
            kinds.add(event.get("ev").getAsString());
        }
        helper.assertTrue(kinds.containsAll(java.util.Set.of("lemon_ask", "lemon_answer", "lemon_heard", "report")),
                "all companion event kinds were written");
        helper.succeed();
    }

    @GameTest
    public void interactionHooksMatchIdsTagsAndEmptyOverrides(GameTestHelper helper) {
        var book = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        helper.assertValueEqual(LemonConfig.interactionCommand(book, "menu", java.util.Map.of()), "menu",
                "unmatched click uses general hook");
        helper.assertValueEqual(LemonConfig.interactionCommand(book, "menu",
                java.util.Map.of("minecraft:written_book", "archive")), "archive", "id hook overrides menu");
        helper.assertValueEqual(LemonConfig.interactionCommand(book, "menu",
                java.util.Map.of("minecraft:written_book", "")), "", "empty matching hook suppresses general hook");
        var sword = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_SWORD);
        helper.assertValueEqual(LemonConfig.interactionCommand(sword, "menu",
                java.util.Map.of("#minecraft:swords", "sword hook")), "sword hook", "item tags are supported");
        helper.assertValueEqual(LemonConfig.interactionCommand(book, "", java.util.Map.of()), "",
                "default config does nothing");
        helper.succeed();
    }

    @GameTest
    public void driverTargetsRequireOperatorPermission(GameTestHelper helper) {
        @SuppressWarnings("removal")
        var player = helper.makeMockServerPlayerInLevel();
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("lemon");
        for (String verb : new String[]{"say", "ask", "reply", "think", "quiet", "mode", "context"}) {
            var target = root.getChild(verb).getChild("target");
            helper.assertTrue(!target.canUse(player.createCommandSourceStack()), "player cannot drive " + verb);
            helper.assertTrue(target.canUse(helper.getLevel().getServer().createCommandSourceStack()),
                    "console can drive " + verb);
        }
        helper.assertTrue(root.getChild("quiet").getCommand() != null, "player quiet remains available");
        helper.succeed();
    }

    @GameTest
    public void tourCommandReturnsStableBridgeCodes(GameTestHelper helper) throws Exception {
        @SuppressWarnings("removal")
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var level = helper.getLevel();
        var at = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        player.teleportTo(level, at.getX(), at.getY(), at.getZ(), java.util.Set.of(), 0, 0, false);
        var server = level.getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var source = server.createCommandSourceStack().withEntity(player).withSuppressedOutput();
        String command = "lemon tour @s A bridge test.";
        try {
            Lemon.setQuiet(player, true);
            helper.assertValueEqual(dispatcher.execute(command, source), 3, "quiet returns 3");
            Lemon.setQuiet(player, false);
            helper.assertValueEqual(dispatcher.execute(command, source), 1, "shown returns 1");
            net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.invoker()
                    .afterDamage(player, level.damageSources().generic(), 1, 1, false);
            helper.assertValueEqual(dispatcher.execute(command, source), 2, "combat returns 2");
            helper.assertValueEqual(dispatcher.execute("lemon summon @s", source), 1,
                    "summon returns 1 when present");
            helper.succeed();
        } finally {
            Lemon.forget(server, player.getUUID());
        }
    }
}
