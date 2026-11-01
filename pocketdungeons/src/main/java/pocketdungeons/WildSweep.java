package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the loose items of a closing wild dungeon into chests beside the lodestone, so a reset or a collapse never
 * deletes what a player dropped or left on the floor. The dungeon's own reward chests and barrel are not swept; they go
 * with the dungeon.
 */
final class WildSweep {

    /** Slots in one chest. */
    static final int CHEST_SLOTS = 27;

    /** How the swept stacks divide into chests. */
    record Plan(int chests, int lostStacks) {}

    /** What the last sweep did, for logs and tests. */
    static String lastReport = "no sweep yet";

    private WildSweep() {}

    /** Pure: how many chests {@code stacks} stacks need, and how many stacks do not fit under {@code maxChests}. */
    static Plan plan(int stacks, int maxChests) {
        if (stacks <= 0 || maxChests <= 0) {
            return new Plan(0, Math.max(0, stacks));
        }
        int needed = (stacks + CHEST_SLOTS - 1) / CHEST_SLOTS;
        int chests = Math.min(needed, maxChests);
        return new Plan(chests, Math.max(0, stacks - chests * CHEST_SLOTS));
    }

    /** Merges stacks that match (item and components) and splits any over the item's stack size. */
    static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : in) {
            if (stack.isEmpty()) {
                continue;
            }
            int left = stack.getCount();
            for (ItemStack held : merged) {
                if (left > 0 && ItemStack.isSameItemSameComponents(held, stack)
                        && held.getCount() < held.getMaxStackSize()) {
                    int take = Math.min(left, held.getMaxStackSize() - held.getCount());
                    held.grow(take);
                    left -= take;
                }
            }
            while (left > 0) {
                int take = Math.min(left, stack.getMaxStackSize());
                merged.add(stack.copyWithCount(take));
                left -= take;
            }
        }
        return merged;
    }

    /** Loose items in the dungeon's layout, removed from the world. */
    static List<ItemStack> collect(ServerLevel dungeon, InstanceRecord record) {
        AABB box = record.layout.bounds().expandTowards(0.0, 96.0, 0.0).expandTowards(0.0, -64.0, 0.0);
        List<ItemStack> found = new ArrayList<>();
        for (ItemEntity item : dungeon.getEntitiesOfClass(ItemEntity.class, box)) {
            found.add(item.getItem().copy());
            item.discard();
        }
        return found;
    }

    /** A free block with solid ground under it in this column, within a few blocks of {@code aroundY}; else null. */
    static BlockPos standable(ServerLevel level, BlockPos column, int aroundY) {
        for (int dy = 0; dy <= 6; dy++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                BlockPos at = new BlockPos(column.getX(), aroundY + dy * sign, column.getZ());
                if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)
                        && level.getBlockState(at.above()).isAir()) {
                    return at;
                }
            }
        }
        return null;
    }

    /**
     * Collects the dungeon's loose items and puts them in chests on the surface around {@code site} in
     * {@code overworld}. Records the chest positions on the lodestone. Returns the number of chests placed.
     */
    static int sweep(MinecraftServer server, InstanceRecord record, ServerLevel overworld, BlockPos site,
                     WildState.Lodestone lodestone) {
        ServerLevel dungeon = WildReset.dungeonLevel(server, lodestone.id);
        if (dungeon == null) {
            return 0;
        }
        Instances.forceLoad(dungeon, record.layout.geometry().chunks(), true);
        for (net.minecraft.world.level.ChunkPos chunk : record.layout.geometry().chunks()) {
            dungeon.getChunk(chunk.x(), chunk.z());
        }
        List<ItemStack> merged = merge(collect(dungeon, record));
        lastReport = "collected " + merged.size() + " stacks";
        if (merged.isEmpty()) {
            return 0;
        }
        Plan plan = plan(merged.size(), PocketDungeonsConfig.wildLostAndFoundMaxChests());
        // The lodestone's chunks may be unloaded by now; a block read on an unloaded chunk answers air.
        for (int cx = (site.getX() - 12) >> 4; cx <= (site.getX() + 12) >> 4; cx++) {
            for (int cz = (site.getZ() - 12) >> 4; cz <= (site.getZ() + 12) >> 4; cz++) {
                overworld.getChunk(cx, cz);
            }
        }
        int placed = 0;
        int next = 0;
        for (int ring = 3; ring <= 12 && placed < plan.chests(); ring++) {
            for (int dx = -ring; dx <= ring && placed < plan.chests(); dx++) {
                for (int dz = -ring; dz <= ring && placed < plan.chests(); dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || (dx + dz) % 2 != 0) {
                        continue;
                    }
                    BlockPos column = site.offset(dx, 0, dz);
                    BlockPos at = standable(overworld, column, site.getY());
                    if (at == null) {
                        continue;
                    }
                    overworld.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
                    if (overworld.getBlockEntity(at) instanceof ChestBlockEntity chest) {
                        for (int slot = 0; slot < CHEST_SLOTS && next < merged.size(); slot++) {
                            chest.setItem(slot, merged.get(next++));
                        }
                        chest.setChanged();
                    }
                    lodestone.lostAndFound.add(new int[] {at.getX(), at.getY(), at.getZ()});
                    placed++;
                }
            }
        }
        lastReport = "collected " + merged.size() + " stacks, placed " + placed + " of " + plan.chests()
                + " chests around " + site.toShortString();
        int lost = merged.size() - next;
        if (lost > 0) {
            PocketDungeonsMod.LOG.error("Wild lodestone {}: {} swept stacks did not fit and were lost", lodestone.id, lost);
        }
        return placed;
    }
}
