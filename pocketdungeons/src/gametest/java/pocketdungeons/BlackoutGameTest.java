package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;

/**
 * Affix workshop 2026-10-11 row 11: Blackout at the block level. A blacked out cell loses every light
 * source, a room that {@code requiresLight} keeps all of them, and the Blackout affix is in the live
 * manifest with its operation. Which cells go dark is covered by the pure {@code BlackoutTest}.
 */
public final class BlackoutGameTest {

    private static final BlockPos ORIGIN = new BlockPos(8192, 120, 8192);

    private static int lights(ServerLevel level, BlockPos origin, DungeonRoomMeta meta) {
        int minY = meta.spanY > 1 ? -RoomGeometry.storyOffset(meta.spanY) : 0;
        int count = 0;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int y = minY; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    String path = BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(origin.offset(x, y, z)).getBlock()).getPath();
                    if (DungeonLight.isLightSource(path)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void stamp(ServerLevel level, BlockPos origin, RoomManifest.Entry entry) {
        int minY = entry.meta.spanY > 1 ? -RoomGeometry.storyOffset(entry.meta.spanY) : 0;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int y = minY; y <= RoomGeometry.CEILING_Y + 1; y++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(origin.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                Identifier.parse(entry.meta.template), 0, 1L, null);
    }

    @GameTest(maxTicks = 200)
    public void blackoutPutsOutTheLightsExceptWhereLightIsRequired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AffixDefinition blackout = AffixManifest.current().byId(AffixIds.BLACKOUT);
        helper.assertTrue(blackout != null && blackout.effects.darkCells > 0.0,
                "Blackout is in the manifest with dark_cells");

        java.util.Set<BlockPos> forced = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                BlockPos at = ORIGIN.offset(dx * 16, 0, dz * 16);
                level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
                forced.add(at);
            }
        }
        try {
            RoomManifest.Entry open = null;
            int found = 0;
            int tried = 0;
            for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
                if (entry.meta.requiresLight || entry.meta.spanY > 1 || tried >= 60
                        || DungeonRoomMeta.LIGHT_DARK.equals(entry.meta.light)
                        || DungeonRoomMeta.LIGHT_DIM.equals(entry.meta.light)) {
                    continue;
                }
                tried++;
                stamp(level, ORIGIN, entry);
                found = lights(level, ORIGIN, entry.meta);
                if (found >= 2) {
                    open = entry;
                    break;
                }
            }
            helper.assertTrue(open != null, "some lit room holds at least two light sources");
            NodeStamper.applyCell(level, ORIGIN, 0, open.meta, NodeStamper.Context.NONE, 1L, new HashSet<>(), true);
            helper.assertValueEqual(lights(level, ORIGIN, open.meta), 0, "a blacked out cell has no lights left");

            // The same room, not blacked out, keeps its lights.
            stamp(level, ORIGIN, open);
            NodeStamper.applyCell(level, ORIGIN, 0, open.meta, NodeStamper.Context.NONE, 1L, new HashSet<>(), false);
            helper.assertValueEqual(lights(level, ORIGIN, open.meta), found, "a cell outside the blackout keeps its lights");

            // A room that requires light is never darkened, even when it is picked.
            RoomManifest.Entry lit = null;
            for (RoomManifest.Entry entry : RoomManifest.current().rooms()) {
                if (entry.meta.requiresLight && entry.meta.spanY <= 1) {
                    BlockPos at = ORIGIN.offset(16, 0, 0);
                    stamp(level, at, entry);
                    if (lights(level, at, entry.meta) > 0) {
                        lit = entry;
                        break;
                    }
                }
            }
            helper.assertTrue(lit != null, "a requiresLight room holds light sources");
            BlockPos at = ORIGIN.offset(16, 0, 0);
            int before = lights(level, at, lit.meta);
            NodeStamper.applyCell(level, at, 0, lit.meta, NodeStamper.Context.NONE, 1L, new HashSet<>(), true);
            helper.assertValueEqual(lights(level, at, lit.meta), before, "a requiresLight room stays lit under Blackout");
        } finally {
            for (BlockPos at : forced) {
                level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, false);
            }
        }
        helper.succeed();
    }

    /** Restless widened: a risen mob of any kind is a vanilla type that exists in the entity registry. */
    @GameTest(maxTicks = 20)
    public void everyRisenTypeIsARegisteredVanillaMob(GameTestHelper helper) {
        java.util.Set<String> types = new HashSet<>(RestlessRules.THEME_RISE.values());
        types.addAll(RestlessRules.KIND_RISE.values());
        types.addAll(RestlessRules.UNDEAD);
        types.add(RestlessRules.DEFAULT_RISE);
        for (String id : types) {
            helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(id)),
                    "a risen type is registered: " + id);
            helper.assertTrue(id.startsWith("minecraft:"), "a risen type is vanilla: " + id);
        }
        helper.succeed();
    }
}
