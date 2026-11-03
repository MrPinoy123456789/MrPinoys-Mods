package excellentenchants;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Silk Spawner's other half: placing the spawner it gave you.
 *
 * <p>Vanilla only copies a spawner item's stored data into the placed block for operators (a spawner can hold
 * any entity data, so it is an anti-cheat rule), so a player placed an empty spawner. For anyone else this
 * copies just the mob type, never the rest of the data, and only for real mobs: no bosses and nothing from the
 * MISC category (items, TNT, armour stands and the like). Operators keep the vanilla behaviour.
 */
public final class SpawnerPlacing {

    /** Mobs a placed spawner may never make, whatever the item says. */
    static final Set<EntityType<?>> BLOCKED = Set.of(EntityTypes.WITHER, EntityTypes.ENDER_DRAGON,
            EntityTypes.WARDEN, EntityTypes.ELDER_GUARDIAN, EntityTypes.GIANT);

    private SpawnerPlacing() {
    }

    /**
     * Gives a just placed spawner the mob its item carried. Returns true when it did, so the caller can skip
     * vanilla's own copy; false leaves everything to vanilla.
     */
    public static boolean restore(Level level, Player player, BlockPos pos, ItemStack stack) {
        if (!(level instanceof ServerLevel server) || player == null || player.canUseGameMasterBlocks()) return false;
        if (!stack.is(Items.SPAWNER)) return false;
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null || !(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) return false;
        Optional<EntityType<?>> type = mobType(data.getUnsafe());
        if (type.isEmpty()) return false;
        spawner.setEntityId(type.get(), server.getRandom());
        spawner.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
        return true;
    }

    /** The mob a spawner's saved data spawns next, if it is one a player may place. */
    public static Optional<EntityType<?>> mobType(CompoundTag spawnerData) {
        String id = spawnerData.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "");
        Identifier key = id.isEmpty() ? null : Identifier.tryParse(id);
        if (key == null) return Optional.empty();
        return BuiltInRegistries.ENTITY_TYPE.getOptional(key).<EntityType<?>>map(t -> t).filter(SpawnerPlacing::allowed);
    }

    public static boolean allowed(EntityType<?> type) {
        return !BLOCKED.contains(type) && type.getCategory() != MobCategory.MISC;
    }
}
