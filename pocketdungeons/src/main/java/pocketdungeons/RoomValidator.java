package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a build room before saving. Errors block save; warnings and
 * informational messages do not.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.8.
 */
final class RoomValidator {

    private RoomValidator() {}

    /**
     * Runs validation and reports results in chat. Returns true if there
     * are no errors (safe to save), false otherwise.
     */
    static boolean validateAndReport(ServerPlayer player) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> info = new ArrayList<>();

        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.adminBuild) {
            player.sendSystemMessage(Component.literal(
                    "You are not in a build room.").withStyle(ChatFormatting.RED));
            return false;
        }

        ServerLevel level = player.level().getServer()
                .getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon dimension is not loaded.").withStyle(ChatFormatting.RED));
            return false;
        }

        BlockPos origin = record.origin;
        RoomEditorMetadata.EditorMeta meta = RoomEditorMetadata.state(player.getUUID());

        // Count gameplay objects in the cell.
        int spawnerCount = 0;
        int vaultCount = 0;
        int chestCount = 0;
        int doorJigsawCount = 0;
        int entityCount = 0;

        LevelChunk chunk = level.getChunkAt(origin);
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            int dx = pos.getX() - origin.getX();
            int dy = pos.getY() - origin.getY();
            int dz = pos.getZ() - origin.getZ();
            if (dx < 0 || dx >= RoomGeometry.CELL || dz < 0 || dz >= RoomGeometry.CELL
                    || dy < 0 || dy > RoomGeometry.CEILING_Y) {
                continue;
            }
            if (entry.getValue() instanceof TrialSpawnerBlockEntity) {
                spawnerCount++;
            } else if (entry.getValue() instanceof VaultBlockEntity) {
                vaultCount++;
            } else if (entry.getValue() instanceof net.minecraft.world.RandomizableContainer) {
                chestCount++;
            }
        }

        // Count door jigsaws by scanning for jigsaw blocks with the door name.
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    var state = level.getBlockState(pos);
                    if (state.is(Blocks.JIGSAW)) {
                        var be = level.getBlockEntity(pos);
                        if (be != null) {
                            var tag = be.saveWithoutMetadata(level.registryAccess());
                            String name = tag.getStringOr("name", "");
                            if (name.contains("door")) {
                                doorJigsawCount++;
                            }
                        }
                    }
                }
            }
        }

        // Count entities in the cell.
        var entities = level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                new net.minecraft.world.phys.AABB(
                        origin.getX(), origin.getY() + 1, origin.getZ(),
                        origin.getX() + RoomGeometry.CELL, origin.getY() + RoomGeometry.CEILING_Y + 1,
                        origin.getZ() + RoomGeometry.CELL));
        entityCount = entities.size();

        // Check 1: at least one role.
        if (meta.roles.isEmpty()) {
            errors.add("No roles selected. Set at least one in the metadata GUI.");
        }

        // Check 2: room has zero door jigsaws and is not an entrance room.
        if (doorJigsawCount == 0 && !meta.roles.contains(RoleIds.ENTRANCE)) {
            errors.add("Room has no door jigsaws. It will be unreachable in a dungeon. "
                    + "Place door jigsaws on at least one wall, or set the role to entrance.");
        }

        // Check 3: encounter role with no spawner or spawn jigsaw.
        if (meta.roles.contains(RoleIds.ENCOUNTER) && spawnerCount == 0) {
            // Check for spawn jigsaws too.
            boolean hasSpawnJigsaw = false;
            for (int x = 0; x < RoomGeometry.CELL && !hasSpawnJigsaw; x++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y && !hasSpawnJigsaw; y++) {
                    for (int z = 0; z < RoomGeometry.CELL && !hasSpawnJigsaw; z++) {
                        var be = level.getBlockEntity(origin.offset(x, y, z));
                        if (be != null) {
                            var tag = be.saveWithoutMetadata(level.registryAccess());
                            if ("pocketdungeons:spawn".equals(tag.getStringOr("name", ""))) {
                                hasSpawnJigsaw = true;
                            }
                        }
                    }
                }
            }
            if (!hasSpawnJigsaw) {
                warnings.add("Encounter room has no spawner or spawn anchor. Stamp will skip the encounter.");
            }
        }

        // Check 4: loot role with no vault or chest.
        if (meta.roles.contains(RoleIds.LOOT) && vaultCount == 0 && chestCount == 0) {
            warnings.add("Loot room has no vault or chest. Stamp will skip the loot.");
        }

        // Check 5: multiple spawners (info).
        if (spawnerCount > 1) {
            info.add("Room has " + spawnerCount + " trial spawners. All count toward the completion gate.");
        }

        // Check 6: multiple vaults (info).
        if (vaultCount > 1) {
            info.add("Room has " + vaultCount + " vaults. All use the same tiered loot table.");
        }

        // Check 7: entity count warning.
        if (entityCount > 50) {
            warnings.add("Room has " + entityCount + " entities. High entity counts may cause lag when stamped.");
        }

        // Report.
        for (String error : errors) {
            player.sendSystemMessage(Component.literal("[Error] " + error)
                    .withStyle(ChatFormatting.RED));
        }
        for (String warning : warnings) {
            player.sendSystemMessage(Component.literal("[Warning] " + warning)
                    .withStyle(ChatFormatting.YELLOW));
        }
        for (String msg : info) {
            player.sendSystemMessage(Component.literal("[Info] " + msg)
                    .withStyle(ChatFormatting.GRAY));
        }

        if (errors.isEmpty()) {
            player.sendSystemMessage(Component.literal("Validation passed. No errors.")
                    .withStyle(ChatFormatting.GREEN));
            return true;
        } else {
            player.sendSystemMessage(Component.literal(
                    "Validation failed with " + errors.size() + " error(s). Save blocked.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }
}
