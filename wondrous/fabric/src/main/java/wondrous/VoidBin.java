package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import wondrous.api.WondrousGive;
import wondrous.api.WondrousTag;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tagged composter that opens an empty chest GUI. Contents are destroyed on
 * close, after a confirmation.
 */
public final class VoidBin {

    public static final String ID = "void_bin";

    private static final Map<UUID, SimpleContainer> pending = new ConcurrentHashMap<>();

    private VoidBin() {}

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

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> pending.remove(handler.player.getUUID()));
    }

    private static InteractionResult tryOpen(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!WondrousTag.is(held, ID)) {
            return InteractionResult.PASS;
        }

        SimpleContainer container = new SimpleContainer(27);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new VoidBinMenu(id, inv, container),
                held.getHoverName()));

        return InteractionResult.SUCCESS_SERVER;
    }

    static void onClose(ServerPlayer player, SimpleContainer container) {
        if (container.isEmpty()) {
            return;
        }
        pending.put(player.getUUID(), container);

        int count = 0;
        for (ItemStack stack : container) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }

        Component msg = Component.literal("About to void " + count + " items. ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("[Confirm]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.RED)
                                .withClickEvent(new ClickEvent.RunCommand("/wondrous void_confirm"))))
                .append(" ")
                .append(Component.literal("[Cancel]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent.RunCommand("/wondrous void_cancel"))));

        player.sendSystemMessage(msg, false);
    }

    static void confirm(ServerPlayer player) {
        SimpleContainer container = pending.remove(player.getUUID());
        if (container == null) {
            Chime.say(player, "nothing to confirm");
            Chime.reject(player);
            return;
        }
        container.clearContent();
        Chime.say(player, "gone");
    }

    static void cancel(ServerPlayer player) {
        SimpleContainer container = pending.remove(player.getUUID());
        if (container == null) {
            Chime.say(player, "nothing to cancel");
            return;
        }
        for (ItemStack stack : container) {
            if (!stack.isEmpty()) {
                WondrousGive.giveOrDrop(player, stack);
            }
        }
        Chime.say(player, "returned");
    }

    /** A 3-row chest that records its contents when the menu closes. */
    private static final class VoidBinMenu extends ChestMenu {

        private final SimpleContainer container;

        VoidBinMenu(int id, Inventory inv, SimpleContainer container) {
            super(MenuType.GENERIC_9x3, id, inv, container, 3);
            this.container = container;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (player instanceof ServerPlayer serverPlayer) {
                onClose(serverPlayer, container);
            }
        }
    }
}
