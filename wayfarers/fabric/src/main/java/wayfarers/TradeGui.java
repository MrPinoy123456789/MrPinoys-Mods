package wayfarers;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import wayfarers.core.Listing;
import wayfarers.core.Purchase;

import java.util.ArrayList;
import java.util.List;

/**
 * sgui trade screen for a patient encounter's {@code sells} block.
 *
 * <p>Shows what the player is carrying, re-validates on every click, and greys
 * out sold-out listings. A successful purchase updates the active encounter's
 * mutable stock list so the same body cannot be farmed.
 */
public final class TradeGui {

    private TradeGui() {}

    /** Opens the trade screen for the encounter this player is facing. */
    public static void open(ServerPlayer player, Encounters.Active active) {
        int rows = rowsFor(active.sells.size(), active.buys.size());
        SimpleGui gui = new SimpleGui(menuType(rows), player, false);
        gui.setTitle(Component.literal(active.def.body().name() + " - " + active.def.id())
                .withStyle(ChatFormatting.GOLD));
        draw(gui, player, active);
        gui.open();
    }

    static int rowsFor(int sellCount, int buyCount) {
        int separator = sellCount > 0 && buyCount > 0 ? 1 : 0;
        int renderedSlots = sellCount + separator + buyCount + 1;
        return Math.max(1, Math.min(6, (renderedSlots + 8) / 9));
    }

    private static void draw(SimpleGui gui, ServerPlayer player, Encounters.Active active) {
        List<Listing> sells = active.sells;
        List<Listing> buys = active.buys;
        int slots = gui.getSize();
        int slot = 0;
        for (int i = 0; i < sells.size() && slot < slots - 1; i++, slot++) {
            gui.setSlot(slot, listingElement(gui, player, active, i, sells.get(i), false));
        }
        if (!sells.isEmpty() && !buys.isEmpty() && slot < slots - 1) {
            slot++; // blank separator
        }
        for (int i = 0; i < buys.size() && slot < slots - 1; i++, slot++) {
            gui.setSlot(slot, listingElement(gui, player, active, i, buys.get(i), true));
        }
        gui.setSlot(slots - 1, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((i, t, a, g) -> gui.close())
                .build());
    }

    private static GuiElement listingElement(SimpleGui gui, ServerPlayer player, Encounters.Active active,
                                             int index, Listing listing, boolean sellToTrader) {
        ItemStack icon = resolveIcon(listing, player);
        if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);

        String customName = ItemComponents.itemName(listing.components()).orElse(listing.item());
        boolean inStock = listing.stock() > 0 && listing.enabled();
        boolean canAfford = sellToTrader
                ? active.coin >= listing.price()
                : Payment.scan(player).canAfford(listing.currencyType(), listing.price());
        boolean available = inStock && canAfford;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Price: " + listing.price() + " " + listing.currency()).withStyle(ChatFormatting.GOLD));
        lore.add(Component.literal("Stock: " + listing.stock()).withStyle(ChatFormatting.GRAY));
        if (sellToTrader) {
            lore.add(Component.literal("Click to sell " + listing.quantity()).withStyle(ChatFormatting.DARK_GRAY));
        } else if (inStock) {
            lore.add(Component.literal("Click to buy 1").withStyle(ChatFormatting.DARK_GRAY));
        } else if (!listing.enabled()) {
            lore.add(Component.literal("Disabled (bad currency)").withStyle(ChatFormatting.RED));
        } else {
            lore.add(Component.literal("Sold out").withStyle(ChatFormatting.RED));
        }

        ChatFormatting color = inStock ? (canAfford ? ChatFormatting.GREEN : ChatFormatting.RED) : ChatFormatting.GRAY;
        return new GuiElementBuilder(icon)
                .setName(Component.literal(customName).withStyle(color))
                .setLore(lore)
                .setCallback((i, t, a, g) -> {
                    if (sellToTrader) {
                        if (active.buys.get(index).stock() <= 0 || !active.buys.get(index).enabled()) return;
                        Purchase.Plan plan = Payment.sell(player, active, active.buys.get(index));
                        if (plan != null) active.updateBuy(index, plan.listing());
                    } else {
                        if (active.sells.get(index).stock() <= 0 || !active.sells.get(index).enabled()) return;
                        Purchase.Plan plan = Payment.buy(player, active.sells.get(index));
                        if (plan != null) active.updateSell(index, plan.listing());
                    }
                    draw(gui, player, active);
                })
                .build();
    }

    private static ItemStack resolveIcon(Listing listing, ServerPlayer player) {
        Identifier id = Identifier.tryParse(listing.item());
        if (id == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        return ItemComponents.apply(stack, listing.components(), player.registryAccess());
    }

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
