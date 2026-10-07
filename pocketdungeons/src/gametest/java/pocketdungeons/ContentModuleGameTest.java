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
 * {@code loot_table/modules/alchemy/chests/tier_2.json}. The override the
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
