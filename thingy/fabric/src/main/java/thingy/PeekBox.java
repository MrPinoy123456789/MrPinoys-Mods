package thingy;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import thingy.api.VirtualTag;

/**
 * A tagged shulker box that opens its own contents as a chest menu, from the
 * hotbar, without being placed.
 */
public final class PeekBox {

    public static final String ID = "peek_box";

    private PeekBox() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryOpen(serverPlayer, hand);
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryOpen(serverPlayer, hand);
        });
    }

    private static InteractionResult tryOpen(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!VirtualTag.is(held, "wondrous", ID)) {
            return InteractionResult.PASS;
        }

        int heldSlot = findSlot(player, held);
        if (heldSlot < 0) {
            return InteractionResult.PASS;
        }

        ItemContainerContents contents = held.getOrDefault(
                DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(items);

        PeekBoxContainer container = new PeekBoxContainer(player, heldSlot, held, items);

        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> ChestMenu.threeRows(id, inv, container),
                held.getHoverName()));

        return InteractionResult.SUCCESS_SERVER;
    }

    private static int findSlot(ServerPlayer player, ItemStack stack) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i) == stack) {
                return i;
            }
        }
        return -1;
    }

    /** A 27-slot chest that writes back to the held shulker stack on every change. */
    private static final class PeekBoxContainer extends SimpleContainer {

        private final ServerPlayer player;
        private final int heldSlot;
        private final ItemStack heldStack;

        PeekBoxContainer(ServerPlayer player, int heldSlot, ItemStack heldStack,
                         NonNullList<ItemStack> items) {
            super(items.toArray(new ItemStack[0]));
            this.player = player;
            this.heldSlot = heldSlot;
            this.heldStack = heldStack;
        }

        @Override
        public boolean stillValid(Player p) {
            return p == player && player.getInventory().getItem(heldSlot) == heldStack;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            if (stack == heldStack) {
                return false;
            }
            return !(stack.getItem() instanceof BlockItem bi
                    && bi.getBlock() instanceof ShulkerBoxBlock);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            heldStack.set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(getItems()));
        }
    }
}
