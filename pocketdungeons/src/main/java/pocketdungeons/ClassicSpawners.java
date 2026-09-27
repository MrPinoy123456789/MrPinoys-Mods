package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.Optional;

/**
 * PD-68: gives a room's classic (non-trial) mob spawner the mob its room
 * intends, and lets it spawn in a lit room.
 *
 * <p>Template generation ({@code RoomTemplateGenerator.placeSpawner}) bakes
 * every classic spawner as a zombie spawner, and the rooms that keep one
 * (Thicket, Ice Run) never re-authored it. Worse, those rooms are lit by sea
 * lanterns and the pocket dimension sets {@code monster_spawn_block_light_limit}
 * to 0, so a monster spawner's placement check always failed and the room
 * spawned nothing. Vanilla's answer is {@code custom_spawn_rules} on the spawn
 * data: when present, the spawner checks those light ranges instead of the
 * mob's placement rules. Full ranges mean "spawn regardless of light".
 *
 * <p>Runs at stamp time from the room's situation handler, so templates
 * already baked keep working without a regeneration.
 */
final class ClassicSpawners {

    private ClassicSpawners() {}

    /**
     * Reconfigures every classic spawner in the cell at {@code cellOrigin} to
     * spawn {@code type}, keeping the anti-farm tuning {@code placeSpawner}
     * gives it.
     */
    static void configure(ServerLevel level, BlockPos cellOrigin, EntityType<?> type) {
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.SPAWNER)
                            && level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                        apply(level, pos, spawner, type);
                    }
                }
            }
        }
    }

    private static void apply(ServerLevel level, BlockPos pos, SpawnerBlockEntity spawner, EntityType<?> type) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", EntityType.getKey(type).toString());
        SpawnData data = new SpawnData(entity,
                Optional.of(new SpawnData.CustomSpawnRules(new InclusiveRange<>(0, 15), new InclusiveRange<>(0, 15))),
                Optional.empty());
        Tag dataTag = SpawnData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        Tag potentials = SpawnData.LIST_CODEC.encodeStart(NbtOps.INSTANCE, WeightedList.of(data)).getOrThrow();

        CompoundTag tuning = new CompoundTag();
        tuning.put("SpawnData", dataTag);
        tuning.put("SpawnPotentials", potentials);
        tuning.putInt("SpawnCount", 2);
        tuning.putInt("SpawnRange", 4);
        tuning.putInt("MinSpawnDelay", 200);
        tuning.putInt("MaxSpawnDelay", 400);
        tuning.putInt("MaxNearbyEntities", 6);
        tuning.putInt("RequiredPlayerRange", 12);

        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tuning);
        spawner.getSpawner().load(level, pos, input);
        spawner.setChanged();
    }
}
