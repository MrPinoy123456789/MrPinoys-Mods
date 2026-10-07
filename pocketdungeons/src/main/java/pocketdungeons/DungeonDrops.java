package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Balance rules for everything that drops inside the dungeon dimension
 * (playtest 2026-09-27). Nothing here touches the overworld.
 *
 * <ul>
 *   <li><strong>Durability (PD-69).</strong> Every item that appears in the
 *       dungeon, from loot tables or as a dropped entity, goes through
 *       {@link DungeonTools#limitDurability}, so a mob's axe or a chest sword
 *       follows the same cap as a crafted one.</li>
 *   <li><strong>Bones.</strong> A mob's loot (a skeleton's bones) keeps about a
 *       quarter of its bones. The mod's own chest, spawner and vault tables
 *       carry the same cut in their data files, where an author can see it.</li>
 *   <li><strong>Equipment drops.</strong> A mob's worn or held gear drops at
 *       least {@link #EQUIPMENT_DROP_CHANCE} of the time (the trial spawner
 *       tables said 0, vanilla says 0.085). A bow or crossbow in the hand
 *       floors at {@link #RANGED_DROP_CHANCE} instead (playtest 2026-09-29-2:
 *       every skeleton is an archer, so the general floor turned bows into
 *       disposables while arrows stayed scarce).</li>
 *   <li><strong>Torches drop nothing</strong> (playtest 2026-10-01-1). Light is
 *       a resource here, so a torch broken by any means (by hand, its support
 *       broken, washed off by water) is gone. Every torch, the player's own
 *       included: one that came back would let a player carry the same light
 *       through the run forever. Redstone torches are not light and are left
 *       alone.</li>
 *   <li><strong>Raised spawner rewards (PD-72).</strong> A trial spawner that
 *       hangs above the floor (Ledge Archers) ejects its key or emeralds onto
 *       its own top, out of reach. Items that appear there are moved to the
 *       floor straight below.</li>
 *   <li><strong>Piglin bartering (K3).</strong> A barter rolled inside the
 *       dungeon pays from {@code pocketdungeons:gameplay/piglin_bartering}
 *       instead of vanilla's table, so gold turns into the kit's own supplies
 *       and not the Nether's. The vanilla table file is left alone: overriding
 *       it would change bartering in a suite server's Nether too.</li>
 * </ul>
 */
final class DungeonDrops {

    /** Vanilla's barter table, replaced in the dungeon by {@link #BARTER_TABLE}. */
    private static final String VANILLA_BARTER = "minecraft:gameplay/piglin_bartering";
    /** The kit's barter table (K3): gold in, one rolled supply out. */
    private static final String BARTER_TABLE = "pocketdungeons:gameplay/piglin_bartering";

    /** Share of a mob's bones that survive (the owner asked for about a quarter). */
    static final double BONE_SHARE = 0.25;
    /** Floor for a dungeon mob's per-slot equipment drop chance. */
    static final float EQUIPMENT_DROP_CHANCE = 0.2f;
    /**
     * The floor for a worn armour slot (PD-106, playtest 2026-10-02-1: "A LOT
     * of trimmed armour"). Four slots at the general floor rained armour from
     * one room; this is roughly vanilla's 0.085.
     */
    static final float ARMOUR_DROP_CHANCE = 0.08f;
    /**
     * The floor for a held bow or crossbow. Lower than the rest because
     * skeletons are the commonest mob and each one holds a bow: at the general
     * floor a floor's worth of archers left a pile of bows and made Unbreaking
     * worthless next to arrow-saving enchantments.
     */
    static final float RANGED_DROP_CHANCE = 0.05f;

    /** The block loot tables of the light torches; wall torches drop through these too. */
    static final Set<String> TORCH_TABLES = Set.of(
            "minecraft:blocks/torch", "minecraft:blocks/soul_torch", "minecraft:blocks/copper_torch");

    private static final List<EquipmentSlot> GEAR_SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /**
     * See {@link InventorySwap.Probe#useDimensionForTesting}: a gametest server
     * has no datapack dimensions, so a test points this at the nether.
     */
    private static ResourceKey<Level> dungeonLevel = PocketDungeonsMod.DUNGEON_LEVEL;

    private DungeonDrops() {}

    /** Wires the loot and entity hooks. Call once from mod init. */
    static void register() {
        LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            ServerLevel level = context.getLevel();
            if (!level.dimension().equals(dungeonLevel)) {
                return;
            }
            String tableId = table.unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (VANILLA_BARTER.equals(tableId)) {
                drops.clear();
                drops.addAll(barterDrops(level, context.getRandom()));
                return;
            }
            if (TORCH_TABLES.contains(tableId)) {
                drops.clear();
                return;
            }
            boolean mobLoot = table.unwrapKey()
                    .map(key -> key.identifier().getPath().startsWith("entities/"))
                    .orElse(false);
            for (int i = drops.size() - 1; i >= 0; i--) {
                ItemStack stack = drops.get(i);
                if (mobLoot && stack.is(Items.BONE)) {
                    int kept = keptBones(stack.getCount(), context.getRandom());
                    if (kept <= 0) {
                        drops.remove(i);
                        continue;
                    }
                    stack.setCount(kept);
                }
                drops.set(i, DungeonTools.limitDurability(stack));
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return;
            }
            if (entity instanceof ItemEntity item) {
                ItemStack capped = DungeonTools.limitDurability(item.getItem());
                if (capped != item.getItem()) {
                    item.setItem(capped);
                }
                BlockPos floor = floorBelowRaisedSpawner(level, item.blockPosition());
                if (floor != null) {
                    // Deferred: moving an entity from inside its own add callback
                    // is not something the entity manager expects.
                    level.getServer().execute(() -> {
                        if (item.isAlive()) {
                            item.setPos(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
                            item.setDeltaMovement(0, 0, 0);
                        }
                    });
                }
            } else if (entity instanceof Mob mob) {
                for (EquipmentSlot slot : GEAR_SLOTS) {
                    ItemStack worn = mob.getItemBySlot(slot);
                    if (worn.isEmpty()) {
                        continue;
                    }
                    float floor = worn.is(Items.BOW) || worn.is(Items.CROSSBOW)
                            ? RANGED_DROP_CHANCE
                            : slot.isArmor() ? ARMOUR_DROP_CHANCE : EQUIPMENT_DROP_CHANCE;
                    if (mob.getDropChances().byEquipment(slot) < floor) {
                        mob.setDropChance(slot, floor);
                    }
                }
            }
        });
    }

    /** One roll of the kit's barter table. */
    private static List<ItemStack> barterDrops(ServerLevel level, RandomSource random) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.parse(BARTER_TABLE));
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level).create(LootContextParamSets.EMPTY);
        return new ArrayList<>(table.getRandomItems(params, random.nextLong()));
    }

    /** About {@link #BONE_SHARE} of {@code count}, rolled per bone so a single bone can survive. */
    static int keptBones(int count, RandomSource random) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (random.nextDouble() < BONE_SHARE) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * If {@code pos} is just above a trial spawner that has open air under it
     * (a spawner hung above the floor), the first standing spot below that
     * spawner; otherwise {@code null}. A spawner on the floor, the usual case,
     * is left alone: its rewards already land where the player is.
     */
    static BlockPos floorBelowRaisedSpawner(ServerLevel level, BlockPos pos) {
        BlockPos spawner = null;
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos below = pos.below(dy);
            if (level.getBlockState(below).is(Blocks.TRIAL_SPAWNER)) {
                spawner = below;
                break;
            }
        }
        if (spawner == null || !level.getBlockState(spawner.below()).isAir()) {
            return null;
        }
        BlockPos cursor = spawner.below();
        for (int i = 0; i < RoomGeometry.CEILING_Y + 2 && level.getBlockState(cursor).isAir(); i++) {
            cursor = cursor.below();
        }
        return cursor.above();
    }

    /** Test seams; gametest code reaches these through reflection-free package access. */
    public static final class Probe {
        private Probe() {}

        /** Points the dungeon-dimension check at a stock dimension for the gametest server. */
        public static void useDimensionForTesting(ResourceKey<Level> level) {
            dungeonLevel = level == null ? PocketDungeonsMod.DUNGEON_LEVEL : level;
        }
    }
}
