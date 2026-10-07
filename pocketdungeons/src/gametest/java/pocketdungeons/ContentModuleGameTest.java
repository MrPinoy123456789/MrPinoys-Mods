package pocketdungeons;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * L2 (D41) live server regression: toggling the {@code alchemy} content
 * module adds its module table's drops to the {@code chests/tier_2} target,
 * and disabling it removes them again. The alchemy module table rolls one
 * guaranteed nether wart pool, so a single roll is a deterministic check.
 *
 * <p>Runs inside the GameTestServer, which loads the mod's bundled datapack
 * including {@code content_module/alchemy.json} and
 * {@code loot_table/modules/alchemy/chests/alchemy.json}. The override the
 * test sets is cleared in a {@code finally} so a failure cannot leak an
 * enabled module into the rest of the suite or the config file.
 */
public final class ContentModuleGameTest {

    @GameTest
    public void alchemyModuleToggleAddsNetherWart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        // Publish the module manifest into ContentModules.current().
        ContentReload.reload(server);
        helper.assertTrue(ContentModules.module("alchemy") != null,
                "the bundled alchemy module should load");
        helper.assertTrue(!ContentModules.enabled("alchemy"),
                "alchemy should default to off (manifest default)");
        try {
            helper.assertTrue(!rollContainsWart(server, level),
                    "tier_2 chest should not roll nether wart while alchemy is off");
            PocketDungeonsConfig.setModuleOverride("alchemy", true);
            helper.assertTrue(rollContainsWart(server, level),
                    "tier_2 chest should roll nether wart once alchemy is on");
            PocketDungeonsConfig.setModuleOverride("alchemy", false);
            helper.assertTrue(!rollContainsWart(server, level),
                    "tier_2 chest should stop rolling nether wart once alchemy is off again");
        } finally {
            // Do not leak the override into the gametest server's config file.
            PocketDungeonsConfig.setModuleOverride("alchemy", null);
        }
        helper.succeed();
    }

    /**
     * A saved choice of a gated bag would strand its owner; the choice is
     * cleared so the bag chest offers the picker again.
     */
    @GameTest
    public void aGatedBagChoiceIsClearedNotStranded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentReload.reload(server);
        DungeonLog log = DungeonLog.forServer(server);
        java.util.UUID id = java.util.UUID.randomUUID();
        try {
            log.setBag(id, BagIds.LUMBERJACK);
            helper.assertTrue(!Bags.clearGatedChoice(log, id), "a core bag choice is left alone");
            log.setBag(id, BagIds.MASON);
            helper.assertTrue(Bags.clearGatedChoice(log, id), "a gated bag choice is cleared");
            helper.assertTrue(log.bagOf(id).isEmpty(), "the picker is open again");
        } finally {
            log.setBag(id, "");
        }
        helper.succeed();
    }

    /**
     * L1 (D40): while the {@code extra_bags} module is off the five cut bags
     * never reach the bag manifest, so the picker cannot offer them and a
     * held one cannot roll. Enabling the module and reloading restores them.
     * The reload is real: bags publish with the content snapshot, unlike the
     * per-roll loot injection above.
     */
    @GameTest
    public void extraBagsModuleGatesTheCutBags(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentReload.reload(server);
        helper.assertTrue(ContentModules.module("extra_bags") != null,
                "the bundled extra_bags module should load");
        helper.assertTrue(!ContentModules.enabled("extra_bags"),
                "extra_bags should default to off (manifest default)");
        helper.assertTrue(Bags.byId(BagIds.LUMBERJACK) != null,
                "the Lumberjack bag loads while extra_bags is off");
        helper.assertTrue(Bags.byId(BagIds.MASON) == null,
                "the Mason bag is gated out while extra_bags is off");
        try {
            PocketDungeonsConfig.setModuleOverride("extra_bags", true);
            ContentReload.reload(server);
            helper.assertTrue(Bags.byId(BagIds.MASON) != null,
                    "the Mason bag loads once extra_bags is on");
        } finally {
            PocketDungeonsConfig.setModuleOverride("extra_bags", null);
            ContentReload.reload(server);
        }
        helper.succeed();
    }

    private static boolean rollContainsWart(MinecraftServer server, ServerLevel level) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "chests/tier_2"));
        LootTable table = server.reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        for (ItemStack stack : table.getRandomItems(params, level.getRandom().nextLong())) {
            if (stack.is(Items.NETHER_WART)) {
                return true;
            }
        }
        return false;
    }
}
