package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.util.HashMap;
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

    /** The qualified id of the Cow Pits dungeon. */
    static final String DUNGEON = "pocketdungeons:cow_pits";
    /** The fewest cows a Cow Pits floor aims for when its rooms are drawn. */
    static final int FLOOR_MIN = 5;
    /** The most cows a floor may hold. */
    static final int FLOOR_CAP = 21;
    /** Name of the cow on the Specimen Ward and Specimen Bay pedestals. */
    static final String WARD_PEDESTAL_NAME = "Specimen 0 (do not feed)";
    /** Names of the two cell cows in the Specimen Ward, handed out in spawn order. */
    static final List<String> WARD_CELL_NAMES = List.of("Specimen 031", "Specimen 032");
    /** Names of the stalled specimens in the Milking Parlour, handed out in spawn order. */
    static final List<String> STALL_NAMES = List.of("Specimen 101", "Specimen 102", "Specimen 103");
    /** Names of the penned specimens in the Specimen Byre, handed out in spawn order. */
    static final List<String> BYRE_NAMES = List.of("Specimen 104", "Specimen 105");
    /** Names of the attendant cows in the Specimen Bay, handed out in spawn order. */
    static final List<String> ZERO_CELL_NAMES = List.of("Specimen 011", "Specimen 012");
    /** Entity tag on every cow the rooms spawn. */
    static final String TAG = "pocketdungeons_cow";

    /** Cows each cow room spawns, by room content id. */
    static final Map<String, Integer> ROOM_COWS = Map.of(
            "cow_pens", 3, "hay_loft", 2, "cow_yard", 2, "cow_ward", 3,
            "milking_stalls", 3, "specimen_byre", 2, "zero_bay", 3);
    /** The most copies of each cow room one floor holds (the rooms' {@code maxPerDungeon}). */
    static final Map<String, Integer> ROOM_LIMITS = Map.of(
            "cow_pens", 2, "hay_loft", 1, "cow_yard", 1, "cow_ward", 1,
            "milking_stalls", 1, "specimen_byre", 1, "zero_bay", 1);

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
        spawnBay(level, cellOrigin, spawns, seed, WARD_CELL_NAMES);
    }

    /**
     * The Specimen Bay on the final floor: the same pedestal cow as the ward, with its own
     * attendants. This is the Specimen 0 the sign is about.
     */
    static void spawnZeroBay(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns, long seed) {
        spawnBay(level, cellOrigin, spawns, seed, ZERO_CELL_NAMES);
    }

    /**
     * One cow on the highest spawn point (the pedestal, named {@link #WARD_PEDESTAL_NAME}) and a
     * named cow at each of the other spawn points.
     */
    private static void spawnBay(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns, long seed,
                                 List<String> cellNames) {
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
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.COW, cellNames.size(), cells, seed, entity -> {
            entity.addTag(TAG);
            entity.setCustomName(Component.literal(cellNames.get(next[0]++ % cellNames.size())));
            entity.setCustomNameVisible(true);
        });
    }

    /** Spawns {@code count} named specimens, tagged and persistent, cycling {@code names}. */
    static void spawnSpecimens(ServerLevel level, BlockPos cellOrigin, int count, List<BlockPos> spawns,
                               long seed, List<String> names) {
        int[] next = {0};
        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.COW, count, spawns, seed, entity -> {
            entity.addTag(TAG);
            entity.setCustomName(Component.literal(names.get(next[0]++ % names.size())));
            entity.setCustomNameVisible(true);
        });
    }

    /**
     * Stocks every milk churn (a barrel) in the cell with {@code buckets} milk buckets each: the
     * parlour's finite pay. Milking a cow is refused, so these churns are the only milk in the dungeon.
     */
    static void fillChurns(ServerLevel level, BlockPos o, int buckets) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= 6; y++) {
                    if (level.getBlockEntity(o.offset(x, y, z)) instanceof BarrelBlockEntity churn) {
                        for (int slot = 0; slot < buckets && slot < churn.getContainerSize(); slot++) {
                            churn.setItem(slot, new ItemStack(Items.MILK_BUCKET));
                        }
                    }
                }
            }
        }
    }

    // ---- Specimen 0 -------------------------------------------------------------------------------

    /**
     * Floors where somebody fed Specimen 0: the instance slot to the floor's layout seed, so a stale
     * mark dies with its floor and a revisited floor cannot inherit it.
     */
    private static final Map<Integer, Long> FED = SessionState.clearedAtStop(new HashMap<>());

    /** Whether the cow is the pedestal cow of a ward or bay room. */
    static boolean isSpecimenZero(Entity entity) {
        return entity.hasCustomName() && entity.getCustomName() != null
                && WARD_PEDESTAL_NAME.equals(entity.getCustomName().getString());
    }

    /** Whether somebody fed Specimen 0 on this floor: the trigger {@link CowPitsFinale} owns. */
    static boolean fedOn(InstanceRecord record) {
        Long seed = FED.get(record.slot);
        return seed != null && record.layout != null && seed == record.layout.seed();
    }

    /** Drops the fed mark when the floor ends (the fight won, lost or never woken). */
    static void forgetFed(int slot) {
        FED.remove(slot);
    }

    /** Marks this floor fed: the next watch tick sees {@link #fedOn} and wakes the fight. */
    static void markFed(InstanceRecord record) {
        if (record != null && record.layout != null) {
            FED.put(record.slot, record.layout.seed());
        }
    }

    /**
     * Marks the floor fed when {@code player} clicked Specimen 0 while standing in a Cow Pits final
     * floor.
     */
    private static boolean markFed(Player player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.layout == null || !DUNGEON.equals(record.interval.dungeonId)
                || !TripView.onFinal(record)) {
            return false;
        }
        markFed(record);
        return true;
    }

    private static boolean registered;

    /** Registers the locks and the Specimen 0 feed. Safe to call twice. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || entity.getType() != EntityTypes.COW
                    || !DungeonWorldRules.inDungeon(level)) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (refusesFood(held.is(Items.WHEAT))) {
                return InteractionResult.FAIL;
            }
            if (held.is(Items.BUCKET) && entity.entityTags().contains(TAG)) {
                // The specimens are not for milking: the parlour churns are the only milk.
                return InteractionResult.FAIL;
            }
            if (isSpecimenZero(entity) && held.get(DataComponents.FOOD) != null && markFed(player)) {
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.getType() == EntityTypes.COW
                    && DungeonWorldRules.inDungeon(level)
                    && entity instanceof LivingEntity living && living.isBaby()) {
                // Deferred: an entity is not removed from inside its own add callback.
                level.getServer().execute(entity::discard);
            }
        });
    }
}
