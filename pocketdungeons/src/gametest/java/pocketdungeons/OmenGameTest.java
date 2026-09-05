package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * M64 (SITUATIONS_SPEC 5.2, 5.4): the omen spur's "taken" test.
 *
 * <p>{@link OmenSources#spurTaken} decides whether a spur reward container has
 * been emptied, which is what fires the one-shot omen contribution for the
 * Ominous Bargain and the Barred Vault. The current implementation reads
 * {@code container.isEmpty()}, which is correct for the ordinary case (the
 * player takes the loot, the container is empty) but has four edge cases this
 * scenario pins down:
 *
 * <ul>
 *   <li><strong>Partial loot.</strong> The player takes some but not all items.
 *       The container is not empty, so {@code spurTaken} returns false. The
 *       omen does not fire. This is a known gap: the player got the reward but
 *       the spur does not notice.</li>
 *   <li><strong>Inserted junk.</strong> The player takes the loot and puts
 *       junk back in. The container is not empty, so {@code spurTaken} returns
 *       false. Same gap: the reward was taken but the junk masks it.</li>
 *   <li><strong>Initially empty.</strong> The loot table rolled nothing, so the
 *       container was never filled. {@code spurTaken} returns true on the first
 *       poll, and the spur fires without the player ever taking anything. This
 *       is the false positive: an unearned omen contribution.</li>
 *   <li><strong>Alternate container.</strong> A barrel instead of a chest.
 *       Both implement {@link Container}, so {@code spurTaken} works the same.
 *       This is the one case that is correct by construction.</li>
 * </ul>
 *
 * <p>These scenarios document the current behavior. The partial-loot and
 * inserted-junk cases are false negatives (the omen should fire but does not),
 * and the initially-empty case is a false positive (the omen should not fire
 * but does). Fixing them is a separate milestone; this test pins down what the
 * code does today so a fix can prove it changed.
 *
 * <p>Same package rationale as {@link CustodyGameTest}: {@link OmenSources} is
 * package-private.
 */
public final class OmenGameTest {

    // ---- partial loot: spur does not fire (false negative) ----

    /**
     * A container with partial loot is not empty, so {@code spurTaken} returns
     * false. The omen does not fire even though the player took some reward.
     */
    @GameTest(maxTicks = 20)
    public void spurTakenPartialLootDoesNotFire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        // Place two items, then remove one: partial loot.
        placeItem(level, chestPos, 0, new ItemStack(Items.DIAMOND, 1));
        placeItem(level, chestPos, 1, new ItemStack(Items.GOLD_INGOT, 1));
        removeItem(level, chestPos, 0);

        helper.assertFalse(OmenSources.spurTaken(level, chestPos),
                "partial loot: the container is not empty, so spurTaken returns false; "
                        + "this is the false negative: the player took reward but the omen does not fire");
        helper.succeed();
    }

    // ---- inserted junk: spur does not fire (false negative) ----

    /**
     * A container where the loot was taken and junk was inserted is not empty,
     * so {@code spurTaken} returns false. The omen does not fire even though
     * the reward is gone.
     */
    @GameTest(maxTicks = 20)
    public void spurTakenInsertedJunkDoesNotFire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        // Place loot, take it all, then insert junk.
        placeItem(level, chestPos, 0, new ItemStack(Items.DIAMOND, 1));
        removeItem(level, chestPos, 0);
        placeItem(level, chestPos, 0, new ItemStack(Items.COBBLESTONE, 1));

        helper.assertFalse(OmenSources.spurTaken(level, chestPos),
                "inserted junk: the container is not empty, so spurTaken returns false; "
                        + "this is the false negative: the reward was taken but junk masks it");
        helper.succeed();
    }

    // ---- initially empty: spur fires (false positive) ----

    /**
     * A container that was never filled (the loot table rolled nothing) is
     * empty, so {@code spurTaken} returns true. The omen fires without the
     * player ever taking anything. This is the false positive.
     */
    @GameTest(maxTicks = 20)
    public void spurTakenInitiallyEmptyFiresFalsePositive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        // The chest was never filled. It is empty.
        helper.assertTrue(OmenSources.spurTaken(level, chestPos),
                "initially empty: the container is empty, so spurTaken returns true; "
                        + "this is the false positive: the omen fires without the player taking anything");
        helper.succeed();
    }

    // ---- alternate container: barrel works the same as chest ----

    /**
     * A barrel implements {@link Container} the same way a chest does, so
     * {@code spurTaken} works for both. This is the one case that is correct
     * by construction.
     */
    @GameTest(maxTicks = 20)
    public void spurTakenAlternateContainerBarrelWorks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos barrelPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), 3);

        // Empty barrel: spurTaken returns true.
        helper.assertTrue(OmenSources.spurTaken(level, barrelPos),
                "an empty barrel is a taken spur, same as an empty chest");

        // Place an item: spurTaken returns false.
        placeItem(level, barrelPos, 0, new ItemStack(Items.DIAMOND, 1));
        helper.assertFalse(OmenSources.spurTaken(level, barrelPos),
                "a barrel with an item is not a taken spur, same as a chest with an item");
        helper.succeed();
    }

    // ---- helpers ----

    private static void placeItem(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.setItem(slot, stack);
        }
    }

    private static void removeItem(ServerLevel level, BlockPos pos, int slot) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.setItem(slot, ItemStack.EMPTY);
        }
    }
}
