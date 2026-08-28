package thingy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent crafting table. The grid is backed by {@link WondrousState} instead
 * of being dropped when the menu closes.
 */
public final class StationMenu extends CraftingMenu {

    /**
     * Which stations currently have a menu open, by position. While a menu is open
     * it, not the saved grid, holds the real contents, so breaking the table
     * must not also drop the saved copy.
     */
    private static final Map<WondrousState.PosKey, Integer> OPEN = new HashMap<>();

    private final ContainerLevelAccess access;
    private WondrousState.PosKey opened;

    public StationMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(id, inventory, access);
        this.access = access;

        access.evaluate((level, pos) -> {
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            List<ItemStack> saved = WondrousState.forLevel(serverLevel).getStation(serverLevel, pos);
            int size = saved.size();
            for (int i = 0; i < 9; i++) {
                craftSlots.setItem(i, i < size ? saved.get(i).copy() : ItemStack.EMPTY);
            }
            slotsChanged(craftSlots);
            opened = new WondrousState.PosKey(serverLevel.dimension(), pos);
            OPEN.merge(opened, 1, Integer::sum);
            return null;
        });
    }

    /** Whether any player has this station's grid open right now. */
    public static boolean isOpen(ServerLevel level, BlockPos pos) {
        return OPEN.containsKey(new WondrousState.PosKey(level.dimension(), pos));
    }

    @Override
    public void removed(Player player) {
        if (opened != null) {
            OPEN.computeIfPresent(opened, (k, count) -> count <= 1 ? null : count - 1);
            opened = null;
        }

        List<ItemStack> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(craftSlots.getItem(i).copy());
        }

        access.evaluate((level, pos) -> {
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            WondrousState state = WondrousState.forLevel(serverLevel);
            if (state.hasStation(serverLevel, pos)) {
                // Cleared first so super.removed() has nothing left to scatter:
                // the point of the item is that the grid stays put.
                craftSlots.clearContent();
                state.setStation(serverLevel, pos, grid);
            }
            // Station gone (broken while open): leave the slots alone and let
            // super.removed() hand the contents back the vanilla way.
            return null;
        });

        super.removed(player);
    }
}
