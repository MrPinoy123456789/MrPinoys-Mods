package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.Shop;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server shop, as chest GUIs. Every action here goes through
 * {@link ShopPurchase#attempt}, the same purchase path {@code /buy} uses -- this class
 * owns layout and clicks, nothing about the transaction. A vanilla client sees an
 * ordinary chest window; nothing is added to a synced registry.
 *
 * <p>Three screens: a category hub ({@link #openCategories}), a page per category
 * ({@link #openCategory}) with the entries themselves, and a confirmation
 * ({@link #openConfirm}) that stands between a click and an expensive mistake.
 * Left-click buys one lot, shift-click buys as many lots as funds and inventory space
 * allow.
 */
final class ShopMenu {

    private ShopMenu() {}

    /** Item slots per category page; the ninth row is nav, not goods. */
    private static final int PAGE_SIZE = 45;

    /** Safety cap on a shift-click bulk buy, so a misconfigured free-ish entry cannot
     *  spin the loop forever. Ordinary purchases stop long before this on funds or
     *  space. */
    private static final int BULK_CAP = 64;

    // ---- category hub ---------------------------------------------------------

    static void openCategories(ServerPlayer player, EconomyService economy, ShopCatalog catalog,
                               TransactionLog log, EconomySettings settings) {
        grantOnboarding(player);
        List<String> categories = catalog.categories();
        int rows = rowsFor(categories.size() + 2); // +2: the wallet slot and Close
        SimpleGui gui = new SimpleGui(menuType(rows), player, false);
        gui.setTitle(Component.literal("Server Shop").withStyle(ChatFormatting.GOLD));

        int slots = rows * 9;
        int shown = Math.min(categories.size(), slots - 2);
        for (int i = 0; i < shown; i++) {
            String category = categories.get(i);
            List<ShopEntry> entries = catalog.inCategory(category);
            gui.setSlot(i, categoryElement(economy, catalog, log, settings, player, category, entries));
        }
        gui.setSlot(slots - 2, walletElement(economy, catalog, player));
        // This is the root of /shop, so there is nothing to go back to -- but a player
        // should never have to reach for Esc to leave a menu the mod opened for them.
        gui.setSlot(slots - 1, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((i, t, a, g) -> g.close())
                .build());
        gui.open();
    }

    /** First time only -- {@code award} is a no-op once the criterion is already met. */
    private static void grantOnboarding(ServerPlayer player) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("cobbleeconomy", "open_shop"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "code_triggered");
        }
    }

    private static GuiElement categoryElement(EconomyService economy, ShopCatalog catalog,
                                               TransactionLog log, EconomySettings settings,
                                               ServerPlayer player,
                                               String category, List<ShopEntry> entries) {
        ItemStack icon = entries.isEmpty()
                ? new ItemStack(Items.CHEST)
                : iconFor(entries.get(0), player.registryAccess());
        return new GuiElementBuilder(icon)
                .setName(Component.literal(category).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .setLore(List.of(
                        dim(entries.size() + (entries.size() == 1 ? " item" : " items")),
                        dim("Click to browse")))
                .setCallback((i, t, a, g) ->
                        openCategory(player, economy, catalog, log, settings, category, 0))
                .build();
    }

    // ---- category page ----------------------------------------------------------

    /** Package-private so {@link ShopDialogs} can land the player back here after a confirm. */
    static void openCategory(ServerPlayer player, EconomyService economy, ShopCatalog catalog,
                             TransactionLog log, EconomySettings settings,
                             String category, int page) {
        List<ShopEntry> entries = catalog.inCategory(category);
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clampedPage = Math.max(0, Math.min(page, pageCount - 1));

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal(category).withStyle(ChatFormatting.GOLD));
        drawCategory(gui, player, economy, catalog, log, settings, category, clampedPage);
        gui.open();
    }

    private static void drawCategory(SimpleGui gui, ServerPlayer player, EconomyService economy,
                                     ShopCatalog catalog, TransactionLog log,
                                     EconomySettings settings, String category, int page) {
        List<ShopEntry> entries = catalog.inCategory(category);
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int from = page * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = from + slot;
            if (index < to) {
                gui.setSlot(slot, entryElement(gui, player, economy, catalog, log, settings,
                        category, page, entries.get(index)));
            } else {
                gui.clearSlot(slot);
            }
        }

        gui.setSlot(45, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("Back").withStyle(ChatFormatting.WHITE))
                .setLore(List.of(dim("All categories")))
                .setCallback((i, t, a, g) -> openCategories(player, economy, catalog, log, settings))
                .build());

        if (page > 0) {
            gui.setSlot(46, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Previous page").withStyle(ChatFormatting.WHITE))
                    .setCallback((i, t, a, g) -> drawCategory(gui, player, economy, catalog, log,
                            settings, category, page - 1))
                    .build());
        } else {
            gui.clearSlot(46);
        }

        gui.setSlot(49, walletElement(economy, catalog, player));

        if (page + 1 < pageCount) {
            gui.setSlot(52, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Next page").withStyle(ChatFormatting.WHITE))
                    .setCallback((i, t, a, g) -> drawCategory(gui, player, economy, catalog, log,
                            settings, category, page + 1))
                    .build());
        } else {
            gui.clearSlot(52);
        }

        gui.setSlot(53, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((i, t, a, g) -> g.close())
                .build());
    }

    private static GuiElement entryElement(SimpleGui gui, ServerPlayer player, EconomyService economy,
                                           ShopCatalog catalog, TransactionLog log,
                                           EconomySettings settings, String category,
                                           int page, ShopEntry entry) {
        boolean afford = canAfford(economy, player.getUUID(), entry);

        GuiElementBuilder b = new GuiElementBuilder(iconFor(entry, player.registryAccess()))
                .setCount(Math.max(1, Math.min(entry.quantity(), 64)))
                .setName(Component.literal(ShopDisplay.displayName(entry))
                        .withStyle(afford ? ChatFormatting.GREEN : ChatFormatting.RED));

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(dim(entry.quantity() + " per purchase"));
        lore.add(Component.empty().append(dim("Costs ")).append(ShopDisplay.priceComponent(entry)));
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            long balance = economy.getBalance(player.getUUID(), price.getKey());
            lore.add(Messages.balanceLine(price.getKey(), balance));
        }
        lore.add(dim("Click: buy 1"));
        lore.add(dim("Shift-click: buy as many as you can"));
        if (PurchaseConfirm.required(entry, 1, settings)) {
            lore.add(Component.literal("Asks before buying")
                    .withStyle(ChatFormatting.YELLOW));
        }
        b.setLore(lore);

        b.setCallback((i, t, a, g) -> {
            boolean bulk = t.shift;
            // A bulk buy is sized before anything is charged, so the prompt can name the
            // real total. One lot at a time would let a shift-click spend a stack of
            // diamonds while only ever confirming the first one.
            //
            // Zero means the purchase cannot happen at all, and there is nothing to
            // confirm -- fall through so the buy reports the real reason rather than
            // asking a player to approve a failure.
            int lots = bulk ? PurchaseConfirm.plannedLots(economy, player, entry, BULK_CAP) : 1;
            if (lots > 0 && PurchaseConfirm.required(entry, lots, settings)) {
                ShopDialogs.confirmPurchase(player, entry, lots, bulk, category, page);
                return;
            }
            if (bulk) {
                bulkBuy(economy, log, player, entry);
            } else {
                ShopPurchase.attempt(economy, log, player, entry, true);
            }
            drawCategory(gui, player, economy, catalog, log, settings, category, page);
        });
        return b.build();
    }

    // ---- purchases --------------------------------------------------------------

    /** Package-private so a confirmed bulk buy from {@link ShopDialogs} takes this same path. */
    static void bulkBuy(EconomyService economy, TransactionLog log, ServerPlayer player,
                        ShopEntry entry) {
        int bought = 0;
        Shop.PurchaseResult last = null;
        for (int i = 0; i < BULK_CAP; i++) {
            last = ShopPurchase.attempt(economy, log, player, entry, false);
            if (!last.ok()) break;
            bought++;
        }

        if (bought == 0) {
            // Nothing purchased -- reuse the exact wording a single failed /buy
            // would show rather than inventing new copy for the same case.
            ShopPurchase.attempt(economy, log, player, entry, true);
            return;
        }

        player.sendSystemMessage(Messages.good("Purchased ")
                .append(Messages.body(bought + " x " + entry.quantity() + " "
                        + ShopDisplay.displayName(entry)))
                .append(Messages.good(" (" + (bought * (long) entry.quantity()) + " total) for "))
                .append(totalPrice(entry, bought))
                .append(Messages.good(".")));
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            long balance = economy.getBalance(player.getUUID(), price.getKey());
            player.sendSystemMessage(Messages.balanceLine(price.getKey(), balance));
        }
    }

    private static Component totalPrice(ShopEntry entry, int lots) {
        Component out = Component.empty();
        boolean first = true;
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            if (!first) out = Component.empty().append(out).append(Messages.body(" + "));
            out = Component.empty().append(out)
                    .append(Messages.priced(price.getKey(), price.getValue() * lots));
            first = false;
        }
        return out;
    }

    // ---- shared -------------------------------------------------------------

    private static boolean canAfford(EconomyService economy, UUID player, ShopEntry entry) {
        for (Map.Entry<Currency, Long> price : entry.price().entrySet()) {
            if (economy.getBalance(player, price.getKey()) < price.getValue()) return false;
        }
        return true;
    }

    /**
     * The icon shown for an entry. A wondrous entry uses its own fully-tagged stack
     * (name, lore and glow included) rather than the plain base item -- the same stack
     * {@link WondrousShop#deliver} would hand over, so what is shown is what is sold.
     *
     * <p>Package-private so {@link ShopAdminMenu} shows an entry exactly as a shopper
     * would see it. An admin editing a listing that renders differently from the one
     * players buy is editing the wrong thing.
     */
    static ItemStack iconFor(ShopEntry entry, HolderLookup.Provider registries) {
        if (WondrousShop.isWondrousItemId(entry.itemId())) {
            String id = WondrousShop.idFrom(entry.itemId());
            return WondrousShop.iconStack(id)
                    .or(() -> WondrousShop.baseItem(id).map(ItemStack::new))
                    .orElse(new ItemStack(Items.BARRIER));
        }
        if (SuiteItems.isSuiteItemId(entry.itemId())) {
            String id = SuiteItems.idFrom(entry.itemId());
            return SuiteItems.iconStack(id, registries)
                    .or(() -> SuiteItems.baseItem(id).map(ItemStack::new))
                    .orElse(new ItemStack(Items.BARRIER));
        }
        return ItemBank.resolve(entry.itemId())
                .map(item -> ItemComponents.apply(new ItemStack(item), entry.components(), registries))
                .orElse(new ItemStack(Items.BARRIER));
    }

    /** All currencies used anywhere in the catalog, in first-seen order. */
    private static Set<Currency> currenciesInUse(ShopCatalog catalog) {
        Set<Currency> out = new LinkedHashSet<>();
        for (ShopEntry entry : catalog.available()) out.addAll(entry.price().keySet());
        return out;
    }

    /** A pinned, non-clickable readout of every currency the shop actually sells in. */
    private static GuiElement walletElement(EconomyService economy, ShopCatalog catalog,
                                            ServerPlayer player) {
        List<Component> lore = new java.util.ArrayList<>();
        for (Currency currency : currenciesInUse(catalog)) {
            lore.add(Messages.balanceLine(currency, economy.getBalance(player.getUUID(), currency)));
        }
        if (lore.isEmpty()) lore.add(dim("Nothing to spend yet"));

        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("Your Balances").withStyle(ChatFormatting.YELLOW))
                .setLore(lore)
                .build();
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static int rowsFor(int slotsNeeded) {
        int rows = (int) Math.ceil(slotsNeeded / 9.0);
        return Math.max(1, Math.min(6, rows));
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
