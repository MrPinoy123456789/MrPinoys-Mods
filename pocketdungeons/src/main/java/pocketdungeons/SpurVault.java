package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Optional;

/**
 * The Barred Vault as it is stamped (design pass 2026-10-09, Q6; PD-186). The room used to be an iron door,
 * a hopper to drop a trial key into and a chest behind it (PD-164): plumbing, not a lock, and the heaviest
 * repair code in the mod. It is now one vanilla vault block, which already means "this takes a trial key": the
 * player walks up and uses the key on it, as in a trial chamber.
 *
 * <p>The vault is baked into the template with vanilla defaults; each stamp sets what is particular to the
 * floor: an ominous floor stamps an ominous vault that takes the ominous trial key, any other floor takes the
 * plain one, and the reward is the room's own table. The floor never needs the key (the room is a loot room,
 * never on the clear path), so the soft-lock rule holds.
 */
final class SpurVault {

    /** The reward the Barred Vault pays, a vault table in the mod's own namespace. */
    static final String LOOT_TABLE = "vaults/barred_vault";

    /** The reward the Gilded Vault pays (plan 2026-10-17): less than the Barred Vault, more than the ingot it costs. */
    static final String GILDED_LOOT_TABLE = "vaults/gilded_vault";

    /** The key the Gilded Vault takes: one gold ingot, the vanilla vault verb with a different key. */
    static final net.minecraft.world.item.Item GILDED_KEY = Items.GOLD_INGOT;

    private SpurVault() {}

    /** The key a vault of this floor takes. */
    static net.minecraft.world.item.Item keyFor(boolean ominousFloor) {
        return ominousFloor ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
    }

    /** Configures every vault block in the cell at {@code origin} for a floor that is, or is not, ominous. */
    static void apply(ServerLevel level, BlockPos origin, boolean ominousFloor) {
        apply(level, origin, ominousFloor, keyFor(ominousFloor), LOOT_TABLE);
    }

    /**
     * As above with the key and the reward named by the caller: the Gilded Vault passes a gold ingot and its own
     * table. A vanilla vault takes exactly the key stack's count (one) and pays each player once.
     */
    static void apply(ServerLevel level, BlockPos origin, boolean ominousFloor,
                      net.minecraft.world.item.Item key, String lootTable) {
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(Blocks.VAULT)) {
                        continue;
                    }
                    level.setBlock(pos, state.setValue(VaultBlock.OMINOUS, ominousFloor), 3);
                    if (level.getBlockEntity(pos) instanceof VaultBlockEntity vault) {
                        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE,
                                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, lootTable));
                        vault.setConfig(new VaultConfig(table, VaultConfig.DEFAULT.activationRange(),
                                VaultConfig.DEFAULT.deactivationRange(), new ItemStack(key),
                                Optional.empty()));
                        vault.setChanged();
                    }
                }
            }
        }
    }
}
