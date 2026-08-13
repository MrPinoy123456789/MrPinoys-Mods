package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import wondrous.api.WondrousTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Right-click: sorts each nearby non-sideloaded container by item, keeping the
 * same total contents without merging stacks.
 */
public final class TidyUp {

    public static final String ID = "tidy_up_stick";

    private TidyUp() {}

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
        if (!WondrousTag.is(held, ID)) {
            return InteractionResult.PASS;
        }

        ServerLevel level = (ServerLevel) player.level();
        BlockPos centre = player.blockPosition();
        List<Container> containers = new ArrayList<>(Nearby.containers(level, centre, 8));
        containers.removeIf(c -> !c.stillValid(player) || c instanceof WorldlyContainer);

        if (containers.isEmpty()) {
            Chime.say(player, "nothing to tidy");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        for (Container container : containers) {
            tidy(container);
        }

        Chime.say(player, "tidied up");
        Chime.confirm(player);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void tidy(Container container) {
        int size = container.getContainerSize();
        List<ItemStack> sorted = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sorted.add(container.getItem(i).copy());
        }

        sorted.sort(Comparator.comparing(s -> s.getItem().getDescriptionId()));

        for (int i = 0; i < size; i++) {
            ItemStack stack = sorted.get(i);
            if (container.canPlaceItem(i, stack)) {
                container.setItem(i, stack);
            }
        }
    }
}
