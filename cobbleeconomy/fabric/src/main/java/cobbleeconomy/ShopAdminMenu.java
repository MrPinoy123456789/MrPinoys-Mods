package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code shop.json}, edited in game.
 *
 * <p>Prices are the setting a server owner changes most and the one that is hardest to
 * change: it means leaving the game, finding a config file, not breaking the JSON, and
 * running a reload. This is the same file, edited from inside the world, with the item
 * you are pricing in your hand.
 *
 * <p><b>It owns no rules.</b> Every action here builds a {@link ShopEntry}, puts it in
 * the live {@link ShopCatalog}, and calls {@link ShopConfig#save} -- exactly what the
 * {@code /cobbleeconomy shop} chat commands do, which are all still there. The two
 * interfaces cannot disagree, because there is only one of them underneath. And because
 * the write goes straight to disk on every edit, there is no unsaved state to lose to a
 * crash and no "save" button anyone can forget to press.
 *
 * <p>Reached from {@code /cobbleeconomy shop edit}, behind the same permission gate as
 * the rest of {@link AdminCommands} -- this changes what things cost, which is the same
 * power as minting money slowly.
 */
final class ShopAdminMenu {

    /** Entry slots on the list screen; the ninth row is nav. */
    private static final int PAGE_SIZE = 45;

    /** Where the per-currency price buttons start on the editor screen. */
    private static final int PRICE_ROW = 9;

    private final ServerPlayer player;
    private final ShopCatalog catalog;
    private final ShopConfig config;
    private final CurrencyRegistry currencies;
    private final TransactionLog log;

    private ShopAdminMenu(ServerPlayer player, ShopCatalog catalog, ShopConfig config,
                          CurrencyRegistry currencies, TransactionLog log) {
        this.player = player;
        this.catalog = catalog;
        this.config = config;
        this.currencies = currencies;
        this.log = log;
    }

    static void open(ServerPlayer player, ShopCatalog catalog, ShopConfig config,
                     CurrencyRegistry currencies, TransactionLog log) {
        new ShopAdminMenu(player, catalog, config, currencies, log).openList(0);
    }

    // ---- the listing --------------------------------------------------------

    private void openList(int page) {
        List<ShopEntry> entries = catalog.all();
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pageCount - 1));
        int from = current * PAGE_SIZE;

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Shop Editor").withStyle(ChatFormatting.GOLD));

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = from + slot;
            if (index < entries.size()) {
                gui.setSlot(slot, listElement(entries.get(index), current));
            } else {
                gui.clearSlot(slot);
            }
        }

        if (current > 0) {
            gui.setSlot(45, button(Items.ARROW, "Previous page", ChatFormatting.WHITE,
                    List.of(dim("Page " + current + " of " + pageCount)),
                    () -> openList(current - 1)));
        }

        gui.setSlot(47, button(Items.EMERALD, "New listing", ChatFormatting.GREEN,
                List.of(dim("Sells whatever you are holding"),
                        dim("The stack size becomes the quantity")),
                () -> addFromHand(current)));

        gui.setSlot(49, new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("shop.json").withStyle(ChatFormatting.YELLOW))
                .setLore(List.of(
                        dim(entries.size() + (entries.size() == 1 ? " listing" : " listings")),
                        dim("config/cobbleeconomy/shop.json"),
                        dim("Every change here is written straight to it")))
                .build());

        gui.setSlot(51, button(Items.BOOK, "Reload from disk", ChatFormatting.WHITE,
                List.of(dim("Discards nothing -- edits are already saved"),
                        dim("Use after hand-editing the file")),
                () -> reload(current)));

        if (current + 1 < pageCount) {
            gui.setSlot(52, button(Items.ARROW, "Next page", ChatFormatting.WHITE,
                    List.of(dim("Page " + (current + 2) + " of " + pageCount)),
                    () -> openList(current + 1)));
        }

        gui.setSlot(53, button(Items.BARRIER, "Close", ChatFormatting.RED, List.of(), gui::close));
        gui.open();
    }

    private GuiElement listElement(ShopEntry entry, int page) {
        boolean broken = !entry.isValid();
        ChatFormatting colour = broken ? ChatFormatting.RED
                : entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY;

        List<Component> lore = new ArrayList<>();
        lore.add(dim(entry.itemId()));
        lore.add(dim(entry.quantity() + " per purchase"));
        lore.add(Component.empty().append(dim("Costs "))
                .append(ShopDisplay.priceComponent(entry)));
        lore.add(dim("Category: " + entry.category()));
        if (broken) {
            lore.add(Component.literal("Malformed -- players cannot buy it")
                    .withStyle(ChatFormatting.RED));
        } else if (!entry.enabled()) {
            lore.add(Component.literal("Disabled").withStyle(ChatFormatting.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(dim("Click to edit every field at once"));
        lore.add(dim("Right-click for item and price nudges"));
        lore.add(dim("Shift-click to " + (entry.enabled() ? "disable" : "enable")));

        GuiElementBuilder b = new GuiElementBuilder(ShopMenu.iconFor(entry, player.registryAccess()))
                .setCount(Math.max(1, Math.min(entry.quantity(), 64)))
                .setName(Component.literal(entry.key()).withStyle(colour))
                .setLore(lore);

        b.setCallback((i, t, a, g) -> {
            if (t.shift) {
                save(withEnabled(entry, !entry.enabled()),
                        entry.key() + " is now " + (entry.enabled() ? "disabled" : "enabled"));
                openList(page);
            } else if (t.isRight) {
                // The sgui editor keeps the two things a dialog cannot do: take the item
                // out of your hand, and nudge a price without typing a number.
                openEntry(entry.key(), page);
            } else {
                // Every field as one form, rather than a screen per field.
                g.close();
                ShopDialogs.openEditor(player, entry.key());
            }
        });
        return b.build();
    }

    // ---- one listing --------------------------------------------------------

    /**
     * Looked up by key on every open rather than held as a field: an edit replaces the
     * record, and a rename replaces the key, so a captured {@link ShopEntry} would be a
     * stale copy of a listing that no longer exists.
     */
    private void openEntry(String key, int page) {
        Optional<ShopEntry> found = catalog.find(key);
        if (found.isEmpty()) {
            openList(page);
            return;
        }
        ShopEntry entry = found.get();

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal(entry.key()).withStyle(ChatFormatting.GOLD));

        List<Component> itemLore = new ArrayList<>();
        itemLore.add(value(entry.itemId()));
        itemLore.add(dim("Click to sell what you are holding"));
        if (entry.hasComponents()) {
            itemLore.add(dim("Changing it clears the components block"));
        }
        gui.setSlot(0, new GuiElementBuilder(ShopMenu.iconFor(entry, player.registryAccess()))
                .setName(Component.literal("Item").withStyle(ChatFormatting.WHITE))
                .setLore(itemLore)
                .setCallback((i, t, a, g) -> setItemFromHand(entry, page))
                .build());

        gui.setSlot(2, field(Items.NAME_TAG, "Key", entry.key(),
                "What players type after /buy",
                () -> askText("Rename this listing", entry.key(),
                        typed -> rename(entry, typed, page))));

        gui.setSlot(4, nudge(Items.PAPER, "Quantity", entry.quantity() + " per purchase",
                "item",
                delta -> {
                    int quantity = (int) Math.max(1, Math.min(entry.quantity() + delta, 9_999));
                    save(withQuantity(entry, quantity),
                            entry.key() + " now grants " + quantity + "x " + entry.itemId());
                    openEntry(entry.key(), page);
                },
                () -> askNumber("Quantity per purchase", entry.quantity(), typed -> {
                    if (typed < 1) {
                        bad("A listing has to grant at least one item.");
                        openEntry(entry.key(), page);
                        return;
                    }
                    int quantity = (int) Math.min(typed, 9_999);
                    save(withQuantity(entry, quantity),
                            entry.key() + " now grants " + quantity + "x " + entry.itemId());
                    openEntry(entry.key(), page);
                })));

        gui.setSlot(6, field(Items.BOOKSHELF, "Category", entry.category(),
                "The heading it appears under",
                () -> askText("Category", entry.category(), typed -> {
                    String category = typed.isBlank() ? "Goods" : typed;
                    save(withCategory(entry, category),
                            entry.key() + " moved to " + category);
                    openEntry(entry.key(), page);
                })));

        if (entry.hasComponents()) {
            gui.setSlot(8, new GuiElementBuilder(Items.AMETHYST_SHARD)
                    .setName(Component.literal("Components").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .setLore(List.of(
                            dim("This listing sells a marked item, not"),
                            dim("the plain one. Edit the block in shop.json."),
                            dim("Shift-click to clear it")))
                    .setCallback((i, t, a, g) -> {
                        if (!t.shift) {
                            bad("Shift-click to clear the components block.");
                            return;
                        }
                        save(withComponents(entry, null),
                                entry.key() + " lost its components block");
                        openEntry(entry.key(), page);
                    })
                    .build());
        }

        drawPrices(gui, entry, page);

        gui.setSlot(18, button(Items.ARROW, "Back", ChatFormatting.WHITE,
                List.of(dim("All listings")), () -> openList(page)));

        // Assigned first, and to an Item: a ternary inside the constructor call makes
        // the GuiElementBuilder overloads ambiguous. 26.x picks a coloured item off a
        // ColorCollection rather than naming LIME_DYE, which no longer exists.
        Item switchIcon = entry.enabled() ? Items.DYE.lime() : Items.DYE.gray();
        gui.setSlot(22, new GuiElementBuilder(switchIcon)
                .setName(Component.literal(entry.enabled() ? "On sale" : "Hidden")
                        .withStyle(entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
                .setLore(List.of(dim(entry.enabled()
                                ? "Players can see and buy it"
                                : "Kept in the file, hidden from /shop"),
                        dim("Click to " + (entry.enabled() ? "hide" : "put on sale"))))
                .setCallback((i, t, a, g) -> {
                    save(withEnabled(entry, !entry.enabled()),
                            entry.key() + " is now " + (entry.enabled() ? "disabled" : "enabled"));
                    openEntry(entry.key(), page);
                })
                .build());

        // Was shift-to-confirm, which is a gesture that has to be learned and cannot say
        // what it is about to destroy. A real confirmation names the listing.
        gui.setSlot(26, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Delete this listing").withStyle(ChatFormatting.RED))
                .setLore(List.of(dim("Removes it from shop.json"),
                        dim("Asks before it does")))
                .setCallback((i, t, a, g) -> {
                    g.close();
                    ShopDialogs.confirmDelete(player, entry.key());
                })
                .build());

        gui.open();
    }

    /**
     * One button per currency, whether or not this listing charges in it -- a price
     * denominated in something new is the same click as changing the one it already has,
     * and a compound price is just two of them set at once.
     */
    private void drawPrices(SimpleGui gui, ShopEntry entry, int page) {
        List<Currency> all = new ArrayList<>(currencies.all());
        int shown = Math.min(all.size(), 9);

        for (int i = 0; i < shown; i++) {
            Currency currency = all.get(i);
            long amount = entry.price().getOrDefault(currency, 0L);
            Item icon = ItemBank.itemFor(currency).orElse(Items.PAPER);

            List<Component> lore = new ArrayList<>();
            if (amount > 0) {
                lore.add(Messages.priced(currency, amount));
            } else {
                lore.add(Component.literal("not part of the price")
                        .withStyle(ChatFormatting.DARK_RED));
            }
            lore.add(Component.empty());
            lore.add(dim("Left-click  +1     (shift +100)"));
            lore.add(dim("Right-click -1     (shift -100)"));
            lore.add(dim("Middle-click to type an amount"));
            if (amount > 0 && entry.price().size() > 1) {
                lore.add(dim("Down to 0 drops this currency"));
            }

            GuiElementBuilder b = new GuiElementBuilder(icon)
                    .setName(Component.literal(currency.displayName())
                            .withStyle(Messages.colourOf(currency)))
                    .setLore(lore);
            if (amount > 0) b.glow();

            b.setCallback((slot, type, action, g) -> {
                if (type.isMiddle) {
                    askNumber(currency.displayName() + " price", amount,
                            typed -> setPrice(entry, currency, typed, page));
                    return;
                }
                long step = type.shift ? 100 : 1;
                long next = amount + (type.isRight ? -step : step);
                setPrice(entry, currency, Math.max(0, next), page);
            });
            gui.setSlot(PRICE_ROW + i, b.build());
        }
    }

    private void setPrice(ShopEntry entry, Currency currency, long amount, int page) {
        // LinkedHashMap and put-in-place: an existing currency keeps its position, so
        // "500 cobblestone + 1 diamond" does not silently reorder itself in shop.json
        // every time someone nudges one half of it.
        Map<Currency, Long> price = new LinkedHashMap<>(entry.price());
        if (amount <= 0) {
            price.remove(currency);
        } else {
            price.put(currency, amount);
        }

        if (price.isEmpty()) {
            bad("A listing needs a price. Set another currency first, or delete it.");
            openEntry(entry.key(), page);
            return;
        }

        ShopEntry updated = new ShopEntry(entry.key(), entry.itemId(), entry.quantity(),
                price, entry.category(), entry.enabled(), entry.components());
        save(updated, entry.key() + " now costs " + updated.describePrice());
        openEntry(entry.key(), page);
    }

    // ---- editing actions ----------------------------------------------------

    private void setItemFromHand(ShopEntry entry, int page) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            bad("Hold the item you want this listing to sell.");
            return;
        }
        String itemId = idOf(held);

        // The components block described the old item. Carrying it onto a new one sells
        // a Spirit Stone's name and lore stamped onto whatever is now in the listing,
        // which is worse than selling the plain item.
        ShopEntry updated = new ShopEntry(entry.key(), itemId, entry.quantity(),
                entry.price(), entry.category(), entry.enabled(), null);
        save(updated, entry.key() + " now sells " + itemId);
        openEntry(entry.key(), page);
    }

    private void addFromHand(int page) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            bad("Hold the item you want to sell, then click again.");
            return;
        }
        String itemId = idOf(held);
        String key = freeKey(itemId);

        // The first currency in the registry, not a hard-coded cobblestone: a server
        // that registered its own currency first should get that one.
        Currency currency = currencies.all().iterator().next();
        ShopEntry entry = ShopEntry.of(key, itemId, held.getCount(), currency, 1, "Goods");
        save(entry, "added shop entry " + key);
        good("Added " + key + ". Set its price before players see a bargain.");
        openEntry(key, page);
    }

    private void rename(ShopEntry entry, String typed, int page) {
        // Same normalisation the chat command applies, plus the space-to-underscore an
        // anvil makes easy to type: the key is what a player types after /buy, and
        // /buy takes a single word.
        String key = typed.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_./-]", "");
        if (key.isBlank()) {
            bad("A listing needs a key made of letters, digits or underscores.");
            openEntry(entry.key(), page);
            return;
        }
        if (key.equals(entry.key())) {
            openEntry(entry.key(), page);
            return;
        }
        if (catalog.find(key).isPresent()) {
            bad("'" + key + "' is already used by another listing.");
            openEntry(entry.key(), page);
            return;
        }

        catalog.remove(entry.key());
        ShopEntry updated = new ShopEntry(key, entry.itemId(), entry.quantity(),
                entry.price(), entry.category(), entry.enabled(), entry.components());
        save(updated, "renamed shop entry " + entry.key() + " to " + key);
        openEntry(key, page);
    }

    private void reload(int page) {
        catalog.clear();
        for (ShopEntry entry : config.load(player.registryAccess()).all()) {
            catalog.put(entry);
        }
        log.admin(player.getName().getString() + " reloaded the shop config");
        good("Reloaded shop.json (" + catalog.size() + " entries).");
        openList(page);
    }

    /** Put it in the live catalog, write the file, write the audit line. In that order. */
    private void save(ShopEntry entry, String what) {
        catalog.put(entry);
        config.save(catalog);
        log.admin(player.getName().getString() + " " + what + " (shop editor)");
    }

    // ---- entry rebuilders ---------------------------------------------------
    //
    // ShopEntry is a record, so every edit is a new one. These exist so each call site
    // names the one field it is changing instead of repeating a seven-argument
    // constructor and getting an argument out of order.

    private static ShopEntry withQuantity(ShopEntry e, int quantity) {
        return new ShopEntry(e.key(), e.itemId(), quantity, e.price(), e.category(),
                e.enabled(), e.components());
    }

    private static ShopEntry withCategory(ShopEntry e, String category) {
        return new ShopEntry(e.key(), e.itemId(), e.quantity(), e.price(), category,
                e.enabled(), e.components());
    }

    private static ShopEntry withEnabled(ShopEntry e, boolean enabled) {
        return new ShopEntry(e.key(), e.itemId(), e.quantity(), e.price(), e.category(),
                enabled, e.components());
    }

    private static ShopEntry withComponents(ShopEntry e, String components) {
        return new ShopEntry(e.key(), e.itemId(), e.quantity(), e.price(), e.category(),
                e.enabled(), components);
    }

    // ---- text and number entry ----------------------------------------------

    /**
     * A real text field.
     *
     * <p>Was an anvil rename screen -- the same trick {@code ballot} used, and the same
     * one its own comment conceded was a workaround. An anvil caps a field at what fits
     * in a rename box and puts the cursor somewhere a player does not expect it. The
     * vanilla dialog system has an actual text input, so this is one now.
     *
     * <p>Backing out still cancels cleanly: escape closes the dialog and nothing is
     * submitted, which is what the accept-click-not-close rule bought before.
     */
    private void askText(String prompt, String initial, Consumer<String> onDone) {
        ShopDialogs.askText(player, prompt, initial, onDone);
    }

    /** The same field, for the numbers. Anything unparseable is rejected, not guessed. */
    private void askNumber(String prompt, long initial, Consumer<Long> onDone) {
        askText(prompt, Long.toString(initial), typed -> {
            try {
                onDone.accept(Long.parseLong(typed.replace(",", "").trim()));
            } catch (NumberFormatException e) {
                bad("'" + typed + "' is not a number.");
            }
        });
    }

    // ---- shared -------------------------------------------------------------

    /** A key not already taken, so adding two of the same item does not overwrite one. */
    private String freeKey(String itemId) {
        String base = itemId.contains(":")
                ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        if (catalog.find(base).isEmpty()) return base;
        for (int n = 2; n < 1_000; n++) {
            String candidate = base + "_" + n;
            if (catalog.find(candidate).isEmpty()) return candidate;
        }
        return base + "_" + System.currentTimeMillis();
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private GuiElement button(Item item, String label, ChatFormatting colour,
                              List<Component> lore, Runnable onClick) {
        return new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(colour))
                .setLore(lore)
                .setCallback((i, t, a, g) -> onClick.run())
                .build();
    }

    /** A labelled value that opens a text field when clicked. */
    private GuiElement field(Item item, String label, String current, String hint,
                             Runnable onClick) {
        return new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(ChatFormatting.WHITE))
                .setLore(List.of(value(current), dim(hint), dim("Click to type a new one")))
                .setCallback((i, t, a, g) -> onClick.run())
                .build();
    }

    /**
     * A number you can shove up and down without typing. The click scheme matches the
     * price buttons, so learning one teaches the other.
     */
    private GuiElement nudge(Item item, String label, String current, String unit,
                             Consumer<Long> onDelta, Runnable onType) {
        return new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(ChatFormatting.WHITE))
                .setLore(List.of(value(current),
                        Component.empty(),
                        dim("Left-click  +1 " + unit + "   (shift +16)"),
                        dim("Right-click -1 " + unit + "   (shift -16)"),
                        dim("Middle-click to type an amount")))
                .setCallback((i, t, a, g) -> {
                    if (t.isMiddle) {
                        onType.run();
                        return;
                    }
                    long step = t.shift ? 16 : 1;
                    onDelta.accept(t.isRight ? -step : step);
                })
                .build();
    }

    private void good(String text) {
        player.sendSystemMessage(Messages.good(text));
    }

    private void bad(String text) {
        player.sendSystemMessage(Messages.bad(text));
    }

    private static Component value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }
}
