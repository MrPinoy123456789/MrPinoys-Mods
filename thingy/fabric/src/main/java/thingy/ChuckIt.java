package thingy;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import thingy.api.VirtualTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-click: move as much as fits of each backpack stack into a nearby container
 * that already holds the same item. Never creates a new stack, never touches the
 * hotbar or armour.
 */
public final class ChuckIt {

    public static final String ID = "chuck_it_wand";

    private ChuckIt() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryUse(serverPlayer, hand);
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryUse(serverPlayer, hand);
        });
    }

    private static InteractionResult tryUse(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!VirtualTag.is(held, "wondrous", ID)) {
            return InteractionResult.PASS;
        }

        ServerLevel level = (ServerLevel) player.level();
        BlockPos centre = player.blockPosition();
        List<Container> containers = new ArrayList<>(Nearby.containers(level, centre, 8));
        containers.removeIf(c -> !c.stillValid(player));

        int moved = 0;

        // First 27 inventory slots are the backpack; hotbar, armour and off-hand are excluded.
        for (int i = 0; i < 27; i++) {
            ItemStack source = player.getInventory().getItem(i);
            if (source.isEmpty()) {
                continue;
            }

            for (Container container : containers) {
                if (!container.stillValid(player)) {
                    continue;
                }
                for (int slot = 0; slot < container.getContainerSize() && !source.isEmpty(); slot++) {
                    ItemStack existing = container.getItem(slot);
                    if (existing.isEmpty()) {
                        continue;
                    }
                    if (!ItemStack.isSameItemSameComponents(source, existing)) {
                        continue;
                    }
                    if (!container.canPlaceItem(slot, source)) {
                        continue;
                    }

                    int max = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(source));
                    int canAdd = Math.min(source.getCount(), max - existing.getCount());
                    if (canAdd <= 0) {
                        continue;
                    }

                    existing.grow(canAdd);
                    container.setItem(slot, existing);
                    source.shrink(canAdd);
                    container.setChanged();
                    moved += canAdd;
                }
            }
        }

        player.getInventory().setChanged();

        if (moved > 0) {
            Chime.say(player, "put away " + moved + " items");
        } else {
            Chime.say(player, "nothing to put away");
        }
        Chime.confirm(player);

        return InteractionResult.SUCCESS_SERVER;
    }
}
