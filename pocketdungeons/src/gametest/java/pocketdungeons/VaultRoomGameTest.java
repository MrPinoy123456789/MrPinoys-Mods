package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Design pass 2026-10-09 (Q6; PD-186): the Barred Vault is a vanilla vault block that takes the floor's trial
 * key, with no door, hopper or partition. An ominous floor stamps an ominous vault that takes the ominous trial
 * key; opening it is the one-shot relief.
 */
public final class VaultRoomGameTest {

    private static BlockPos vaultAt(BlockPos o) {
        return o.offset(8, 1, 10);
    }

    private static void stamp(ServerLevel level, BlockPos o) {
        level.setBlock(vaultAt(o), Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.NORTH), 3);
    }

    @GameTest(maxTicks = 40)
    public void aPlainFloorStampsAVaultThatTakesThePlainKey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        stamp(level, o);
        SpurVault.apply(level, o, false);
        BlockState state = level.getBlockState(vaultAt(o));
        helper.assertTrue(state.is(Blocks.VAULT), "the vault block stands");
        helper.assertTrue(!state.getValue(VaultBlock.OMINOUS), "a plain floor stamps a plain vault");
        VaultBlockEntity vault = (VaultBlockEntity) level.getBlockEntity(vaultAt(o));
        helper.assertTrue(vault != null, "the vault has its block entity");
        helper.assertTrue(vault.getConfig().keyItem().is(Items.TRIAL_KEY), "it takes the trial key");
        helper.assertTrue(vault.getConfig().lootTable().identifier().getPath().equals(SpurVault.LOOT_TABLE),
                "and pays the room's own table");
        level.setBlock(vaultAt(o), Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void anOminousFloorStampsAnOminousVaultThatTakesTheOminousKey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        stamp(level, o);
        SpurVault.apply(level, o, true);
        BlockState state = level.getBlockState(vaultAt(o));
        helper.assertTrue(state.getValue(VaultBlock.OMINOUS), "an ominous floor stamps an ominous vault");
        VaultBlockEntity vault = (VaultBlockEntity) level.getBlockEntity(vaultAt(o));
        helper.assertTrue(vault.getConfig().keyItem().is(Items.OMINOUS_TRIAL_KEY), "it takes the ominous trial key");
        helper.assertTrue(SpurVault.keyFor(true) == Items.OMINOUS_TRIAL_KEY && SpurVault.keyFor(false) == Items.TRIAL_KEY,
                "the key follows the floor");
        level.setBlock(vaultAt(o), Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aVaultThatOpenedHasPaid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        stamp(level, o);
        helper.assertTrue(!PressureSources.vaultPaid(level, vaultAt(o)), "a vault nobody has opened has not paid");
        BlockState vault = level.getBlockState(vaultAt(o));
        level.setBlock(vaultAt(o), vault.setValue(VaultBlock.STATE, VaultState.ACTIVE), 3);
        helper.assertTrue(!PressureSources.vaultPaid(level, vaultAt(o)), "an active vault has not paid");
        level.setBlock(vaultAt(o), vault.setValue(VaultBlock.STATE, VaultState.UNLOCKING), 3);
        helper.assertTrue(PressureSources.vaultPaid(level, vaultAt(o)), "an unlocking vault has paid");
        level.setBlock(vaultAt(o), vault.setValue(VaultBlock.STATE, VaultState.EJECTING), 3);
        helper.assertTrue(PressureSources.vaultPaid(level, vaultAt(o)), "and so has an ejecting one");
        level.setBlock(vaultAt(o), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(!PressureSources.vaultPaid(level, vaultAt(o)), "no vault, nothing paid");
        helper.succeed();
    }
}
