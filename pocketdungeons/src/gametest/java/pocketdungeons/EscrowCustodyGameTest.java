package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

/**
 * Coverage for the F3/F4/F5/F6/F7 escrow redesign: a consumed catalyst is
 * escrowed on the keystone as a multi-entry list, refunded in full on cancel,
 * cleared on commit, preserved across keystone reconciliation, and charged at
 * the recipe's declared cost. These run as gametests because the apply and
 * restore paths need a real server for {@link DungeonLog}.
 *
 * <p>Same package rationale as {@link CubeRecipeGameTest}: {@link CubeRecipe}
 * and {@link CubeRecipeDefinition} are package-private, and sharing the
 * package is cheaper than opening them.
 */
public final class EscrowCustodyGameTest {

    private static final String REDSTONE_ID = "minecraft:redstone";
    private static final String GLOWSTONE_ID = "minecraft:glowstone";

    @GameTest(maxTicks = 20)
    public void applyEscrowsCatalystAndTag(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        ItemStack keystone = Keystone.mint(5);
        ItemStack catalyst = new ItemStack(Items.REDSTONE, 3);
        CubeRecipeDefinition def = testRecipe("pocketdungeons:test_escrow_a", REDSTONE_ID, 1);

        CubeRecipe.apply(player, keystone, catalyst, def);

        // Escrow has one entry with the real catalyst id and count 1.
        List<CubeRecipe.EscrowEntry> escrow = CubeRecipe.escrowOf(keystone);
        if (escrow.size() != 1) {
            helper.fail("Expected 1 escrow entry, got " + escrow.size());
            return;
        }
        if (!escrow.get(0).catalystItemId().equals(REDSTONE_ID)) {
            helper.fail("Escrow catalyst should be redstone, got " + escrow.get(0).catalystItemId());
            return;
        }
        if (escrow.get(0).count() != 1) {
            helper.fail("Escrow count should be 1, got " + escrow.get(0).count());
            return;
        }
        // Recipe tag is armed.
        if (!CubeRecipe.hasRecipe(keystone, "pocketdungeons:test_escrow_a")) {
            helper.fail("Recipe tag should be armed after apply");
            return;
        }
        // One catalyst consumed.
        if (catalyst.getCount() != 2) {
            helper.fail("Catalyst count should be 2 after consuming 1, got " + catalyst.getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * F4: a second application accumulates a second escrow entry instead of
     * overwriting the first catalyst's recovery record.
     */
    @GameTest(maxTicks = 20)
    public void multipleAppliesAccumulateEscrow(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        ItemStack keystone = Keystone.mint(5);
        ItemStack redstone = new ItemStack(Items.REDSTONE, 4);
        ItemStack glowstone = new ItemStack(Items.GLOWSTONE, 2);
        CubeRecipeDefinition defA = testRecipe("pocketdungeons:test_escrow_a", REDSTONE_ID, 1);
        CubeRecipeDefinition defB = testRecipe("pocketdungeons:test_escrow_b", GLOWSTONE_ID, 1);

        CubeRecipe.apply(player, keystone, redstone, defA);
        CubeRecipe.apply(player, keystone, glowstone, defB);

        List<CubeRecipe.EscrowEntry> escrow = CubeRecipe.escrowOf(keystone);
        if (escrow.size() != 2) {
            helper.fail("Expected 2 escrow entries after two applies, got " + escrow.size());
            return;
        }
        if (!CubeRecipe.hasRecipe(keystone, "pocketdungeons:test_escrow_a")
                || !CubeRecipe.hasRecipe(keystone, "pocketdungeons:test_escrow_b")) {
            helper.fail("Both recipe tags should be armed");
            return;
        }
        helper.succeed();
    }

    /**
     * F4: cancel refunds every escrowed catalyst and clears the armed recipe
     * tags, so a cancelled recipe does not linger for a free re-preview.
     */
    @GameTest(maxTicks = 20)
    public void restoreRefundsAllAndClearsTags(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());
        emptyInventory(player);

        ItemStack keystone = Keystone.mint(5);
        player.getInventory().add(keystone);
        // Re-read the actual stack the inventory holds, so apply mutates the
        // same reference restoreCatalyst later reads.
        keystone = player.getInventory().getItem(0);

        ItemStack redstone = new ItemStack(Items.REDSTONE, 2);
        ItemStack glowstone = new ItemStack(Items.GLOWSTONE, 1);
        CubeRecipeDefinition defA = testRecipe("pocketdungeons:test_escrow_a", REDSTONE_ID, 1);
        CubeRecipeDefinition defB = testRecipe("pocketdungeons:test_escrow_b", GLOWSTONE_ID, 1);

        CubeRecipe.apply(player, keystone, redstone, defA);
        CubeRecipe.apply(player, keystone, glowstone, defB);

        int redstoneBefore = countItem(player, Items.REDSTONE);
        int glowstoneBefore = countItem(player, Items.GLOWSTONE);

        CubeRecipe.restoreCatalyst(player, keystone);

        // Escrow cleared.
        if (!CubeRecipe.escrowOf(keystone).isEmpty()) {
            helper.fail("Escrow should be empty after restore");
            return;
        }
        // Recipe tags cleared.
        if (!CubeRecipe.recipesOf(keystone).isEmpty()) {
            helper.fail("Recipe tags should be cleared after restore");
            return;
        }
        // Both catalysts refunded: redstone +1, glowstone +1.
        if (countItem(player, Items.REDSTONE) != redstoneBefore + 1) {
            helper.fail("Redstone should be refunded by 1");
            return;
        }
        if (countItem(player, Items.GLOWSTONE) != glowstoneBefore + 1) {
            helper.fail("Glowstone should be refunded by 1");
            return;
        }
        helper.succeed();
    }

    /**
     * F5: a recipe whose declared cost exceeds the held stack refuses before
     * consuming anything, writes no escrow, and arms no recipe tag.
     */
    @GameTest(maxTicks = 20)
    public void costEnforcedBeforeConsumption(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        ItemStack keystone = Keystone.mint(5);
        ItemStack catalyst = new ItemStack(Items.REDSTONE, 1);
        CubeRecipeDefinition cost2 = testRecipe("pocketdungeons:test_escrow_cost2", REDSTONE_ID, 2);

        CubeRecipe.apply(player, keystone, catalyst, cost2);

        // Nothing consumed.
        if (catalyst.getCount() != 1) {
            helper.fail("Catalyst should not be consumed when cost is unmet, got " + catalyst.getCount());
            return;
        }
        // No escrow, no tag.
        if (!CubeRecipe.escrowOf(keystone).isEmpty()) {
            helper.fail("No escrow should be written when cost is unmet");
            return;
        }
        if (CubeRecipe.hasRecipe(keystone, "pocketdungeons:test_escrow_cost2")) {
            helper.fail("No recipe tag should be armed when cost is unmet");
            return;
        }
        helper.succeed();
    }

    /**
     * F5: a recipe whose declared cost is met consumes exactly that many and
     * escrows the same quantity.
     */
    @GameTest(maxTicks = 20)
    public void costMetConsumesAndEscrowsQuantity(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        ItemStack keystone = Keystone.mint(5);
        ItemStack catalyst = new ItemStack(Items.REDSTONE, 4);
        CubeRecipeDefinition cost3 = testRecipe("pocketdungeons:test_escrow_cost3", REDSTONE_ID, 3);

        CubeRecipe.apply(player, keystone, catalyst, cost3);

        if (catalyst.getCount() != 1) {
            helper.fail("Catalyst should be 1 after consuming 3, got " + catalyst.getCount());
            return;
        }
        List<CubeRecipe.EscrowEntry> escrow = CubeRecipe.escrowOf(keystone);
        if (escrow.size() != 1 || escrow.get(0).count() != 3) {
            helper.fail("Escrow should have one entry with count 3, got " + escrow);
            return;
        }
        helper.succeed();
    }

    /**
     * F7: a recipe that consumes the last item of a stack still records the
     * real catalyst id in the escrow, not minecraft:air.
     */
    @GameTest(maxTicks = 20)
    public void lastItemRecordsRealCatalystId(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setKeystone(player.getUUID(), 5, Set.of());

        ItemStack keystone = Keystone.mint(5);
        ItemStack catalyst = new ItemStack(Items.REDSTONE, 1);
        CubeRecipeDefinition def = testRecipe("pocketdungeons:test_escrow_last", REDSTONE_ID, 1);

        CubeRecipe.apply(player, keystone, catalyst, def);

        // Stack is now empty.
        if (!catalyst.isEmpty()) {
            helper.fail("Catalyst should be empty after consuming the last item");
            return;
        }
        // Escrow still records the real item, not air.
        List<CubeRecipe.EscrowEntry> escrow = CubeRecipe.escrowOf(keystone);
        if (escrow.size() != 1) {
            helper.fail("Expected 1 escrow entry, got " + escrow.size());
            return;
        }
        if (!escrow.get(0).catalystItemId().equals(REDSTONE_ID)) {
            helper.fail("Escrow should record redstone, not air, after consuming the last item: "
                    + escrow.get(0).catalystItemId());
            return;
        }
        helper.succeed();
    }

    /**
     * F6: reconciling a stale keystone (wrong level) re-mints the label but
     * preserves the pending escrow and armed recipe tags, so an in-flight
     * recipe application is not destroyed by a label refresh.
     */
    @GameTest(maxTicks = 20)
    public void reconciliationPreservesEscrow(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        // Authority level is 5; the held keystone labels level 3, so it is stale.
        log.setKeystone(player.getUUID(), 5, Set.of());
        emptyInventory(player);

        ItemStack stale = Keystone.mint(3);
        player.getInventory().add(stale);
        stale = player.getInventory().getItem(0);

        ItemStack catalyst = new ItemStack(Items.REDSTONE, 2);
        CubeRecipeDefinition def = testRecipe("pocketdungeons:test_escrow_reconcile", REDSTONE_ID, 1);
        CubeRecipe.apply(player, stale, catalyst, def);

        // Escrow is armed on the stale stack.
        if (CubeRecipe.escrowOf(stale).size() != 1) {
            helper.fail("Escrow should be armed before reconcile");
            return;
        }

        // Reconcile replaces the stale label with a level-5 one.
        Keystone.reconcile(player, 5, Set.of());
        ItemStack refreshed = player.getInventory().getItem(0);
        if (Keystone.levelOf(refreshed).orElse(-1) != 5) {
            helper.fail("Reconciled keystone should be level 5, got "
                    + Keystone.levelOf(refreshed).orElse(-1));
            return;
        }
        // F6: the escrow and recipe tag survived the re-mint.
        if (CubeRecipe.escrowOf(refreshed).size() != 1) {
            helper.fail("Escrow should survive reconciliation, got "
                    + CubeRecipe.escrowOf(refreshed));
            return;
        }
        if (!CubeRecipe.hasRecipe(refreshed, "pocketdungeons:test_escrow_reconcile")) {
            helper.fail("Recipe tag should survive reconciliation");
            return;
        }
        helper.succeed();
    }

    /**
     * A legacy keystone carrying the pre-fix single pending_catalyst string
     * migrates into a one-entry escrow on read, so an older save still
     * refunds.
     */
    @GameTest(maxTicks = 20)
    public void legacyPendingCatalystMigrates(GameTestHelper helper) {
        ItemStack keystone = Keystone.mint(5);
        // Write a legacy single-catalyst escrow directly.
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, keystone, tag -> {
                    net.minecraft.nbt.CompoundTag root =
                            tag.getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
                    if (root == null) {
                        root = new net.minecraft.nbt.CompoundTag();
                    }
                    root.putString("pending_catalyst", REDSTONE_ID);
                    tag.put(PocketDungeonsMod.MOD_ID, root);
                });

        List<CubeRecipe.EscrowEntry> escrow = CubeRecipe.escrowOf(keystone);
        if (escrow.size() != 1) {
            helper.fail("Legacy pending_catalyst should migrate to 1 entry, got " + escrow.size());
            return;
        }
        if (!escrow.get(0).catalystItemId().equals(REDSTONE_ID)) {
            helper.fail("Migrated entry should carry redstone, got " + escrow.get(0).catalystItemId());
            return;
        }
        if (escrow.get(0).count() != 1) {
            helper.fail("Migrated entry should have count 1, got " + escrow.get(0).count());
            return;
        }
        helper.succeed();
    }

    private static CubeRecipeDefinition testRecipe(String id, String catalystItem, int cost) {
        return new CubeRecipeDefinition(id, "Escrow applied.", catalystItem, null, cost, 100, 0,
                RecipeEffects.none());
    }

    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }

    private static void emptyInventory(ServerPlayer player) {
        player.getInventory().clearContent();
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        return player.getInventory().countItem(item);
    }
}
