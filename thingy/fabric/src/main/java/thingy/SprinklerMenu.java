package thingy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A one-row bone-meal hopper for a placed sprinkler grate. The contents are
 * backed by {@link WondrousState} instead of being dropped when the menu closes.
 */
public final class SprinklerMenu extends ChestMenu {

    /** Mirrors {@link StationMenu#OPEN}: a menu open on a position owns its live contents. */
    private static final Map<WondrousState.PosKey, Integer> OPEN = new HashMap<>();

    private final ContainerLevelAccess access;
    private final BoneMealContainer ammo;
    private WondrousState.PosKey opened;

    public SprinklerMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        this(id, inventory, access, new BoneMealContainer());
    }

    private SprinklerMenu(int id, Inventory inventory, ContainerLevelAccess access, BoneMealContainer ammo) {
        super(MenuType.GENERIC_9x1, id, inventory, ammo, 1);
        this.access = access;
        this.ammo = ammo;

        access.evaluate((level, pos) -> {
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            List<ItemStack> saved = WondrousState.forLevel(serverLevel).getSprinkler(serverLevel, pos);
            int size = saved.size();
            for (int i = 0; i < 9; i++) {
                ammo.setItem(i, i < size ? saved.get(i).copy() : ItemStack.EMPTY);
            }
            opened = new WondrousState.PosKey(serverLevel.dimension(), pos);
            OPEN.merge(opened, 1, Integer::sum);
            return null;
        });
    }

    /** Whether any player has this sprinkler's contents open right now. */
    public static boolean isOpen(ServerLevel level, BlockPos pos) {
        return OPEN.containsKey(new WondrousState.PosKey(level.dimension(), pos));
    }

    @Override
    public void removed(Player player) {
        if (opened != null) {
            OPEN.computeIfPresent(opened, (k, count) -> count <= 1 ? null : count - 1);
            opened = null;
        }

        List<ItemStack> items = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            items.add(ammo.getItem(i).copy());
        }

        access.evaluate((level, pos) -> {
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            WondrousState state = WondrousState.forLevel(serverLevel);
            if (state.hasSprinkler(serverLevel, pos)) {
                ammo.clearContent();
                state.setSprinkler(serverLevel, pos, items);
            }
            return null;
        });

        super.removed(player);
    }

    /** Bone meal only: this is ammo storage, not a general-purpose chest. */
    private static final class BoneMealContainer extends SimpleContainer {
        BoneMealContainer() {
            super(9);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return stack.is(Items.BONE_MEAL);
        }
    }
}
