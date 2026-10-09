package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * Dungeon structure W6: the Cow Pits finite rule (plan default). The floor is a finite
 * larder: 6 to 13 adult cows across its rooms, no wheat, seeds, carrots or potatoes in any
 * loot (the Cow Pits loot tables are filtered copies, see {@code tools/gen_resource_content.py}),
 * and breeding blocked here as a second lock: a cow of the floor refuses wheat, and any baby
 * cow that still appears in the dungeon dimension is removed.
 *
 * <p>The cap is a property of the room set, not a runtime counter: every cow room declares how
 * many cows it holds ({@link #ROOM_COWS}) and a per floor room limit ({@code maxPerDungeon} in
 * its metadata, {@link #ROOM_LIMITS}), so the most a floor can hold is {@link #maxPossible()}.
 * The pure parts run headless in {@code CowPitsTest}.
 */
final class CowPits {

    private CowPits() {}

    /** The fewest cows a Cow Pits floor aims for when its pens are drawn. */
    static final int FLOOR_MIN = 6;
    /** The most cows a floor may hold. */
    static final int FLOOR_CAP = 13;
    /** Name of the cow on the Specimen Ward pedestal. */
    static final String WARD_PEDESTAL_NAME = "Specimen 0 (do not feed)";
    /** Names of the two cell cows in the Specimen Ward, handed out in spawn order. */
    static final List<String> WARD_CELL_NAMES = List.of("Specimen 031", "Specimen 032");
    /** Entity tag on every cow the rooms spawn. */
    static final String TAG = "pocketdungeons_cow";

    /** Cows each cow room spawns, by room content id. */
    static final Map<String, Integer> ROOM_COWS = Map.of("cow_pens", 3, "hay_loft", 2, "cow_yard", 2, "cow_ward", 3);
    /** The most copies of each cow room one floor holds (the rooms' {@code maxPerDungeon}). */
    static final Map<String, Integer> ROOM_LIMITS = Map.of("cow_pens", 2, "hay_loft", 1, "cow_yard", 1, "cow_ward", 1);

    /** The most cows a floor can hold with every cow room at its limit. */
    static int maxPossible() {
        int total = 0;
        for (Map.Entry<String, Integer> room : ROOM_COWS.entrySet()) {
            total += room.getValue() * ROOM_LIMITS.getOrDefault(room.getKey(), 1);
        }
        return total;
    }

    /** How many cows a room spawns given {@code already} on the floor: its own count, held under {@link #FLOOR_CAP}. */
    static int spawnCount(int roomCows, int already) {
        return Math.max(0, Math.min(roomCows, FLOOR_CAP - Math.max(0, already)));
    }

    /** Whether a held item may be offered to a floor cow: wheat breeds cows, so it never may. */
    static boolean refusesFood(boolean isWheat) {
        return isWheat;
    }

    // ---- world side ----------------------------------------------------------------

    /** Spawns {@code count} adult cows at the room's spawn points, tagged and persistent. */
    static void spawnCows(ServerLevel level, BlockPos cellOrigin, int count, List<BlockPos> spawns, long seed) {
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.COW, count, spawns, seed, entity -> {
            entity.addTag(TAG);
        });
    }

    /**
     * The Specimen Ward: the cow at the highest spawn point (the pedestal) is "Specimen 0 (do not
     * feed)", and two of the other spawn points (the cells) get "Specimen 031" and "Specimen 032".
     */
    static void spawnWard(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns, long seed) {
        if (spawns.isEmpty()) {
            return;
        }
        BlockPos pedestal = spawns.get(0);
        for (BlockPos p : spawns) {
            if (p.getY() > pedestal.getY()) {
                pedestal = p;
            }
        }
        List<BlockPos> cells = new java.util.ArrayList<>(spawns);
        cells.remove(pedestal);
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.COW, 1, List.of(pedestal), seed, entity -> {
            entity.addTag(TAG);
            entity.setCustomName(Component.literal(WARD_PEDESTAL_NAME));
            entity.setCustomNameVisible(true);
        });
        int[] next = {0};
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.COW, WARD_CELL_NAMES.size(), cells, seed, entity -> {
            entity.addTag(TAG);
            entity.setCustomName(Component.literal(WARD_CELL_NAMES.get(next[0]++ % WARD_CELL_NAMES.size())));
            entity.setCustomNameVisible(true);
        });
    }

    private static boolean registered;

    /** Registers the two locks. Safe to call twice. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || entity.getType() != EntityTypes.COW
                    || !level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return InteractionResult.PASS;
            }
            if (refusesFood(player.getItemInHand(hand).is(Items.WHEAT))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.getType() == EntityTypes.COW
                    && level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    && entity instanceof LivingEntity living && living.isBaby()) {
                // Deferred: an entity is not removed from inside its own add callback.
                level.getServer().execute(entity::discard);
            }
        });
    }
}
