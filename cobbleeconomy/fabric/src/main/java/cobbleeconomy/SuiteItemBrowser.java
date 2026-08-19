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
import net.minecraft.resources.Identifier;
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
import java.util.Set;

/**
 * Browser for adding suite items to the shop.
 *
 * <p>Three screens: a source picker, a paged item grid, and an immediate add that
 * hands off to the existing per-entry editor. Built to the same conventions as
 * {@link ShopAdminMenu}: 45 entry slots, ninth-row nav, component-stamped icons.
 *
 * <p>Every click that mutates the shop builds a {@link ShopEntry}, puts it in the
 * live {@link ShopCatalog}, and calls {@link ShopConfig#save} -- exactly the same
 * path the sgui editor and the chat commands use.
 */
final class SuiteItemBrowser {

    private static final int PAGE_SIZE = 45;
    private static final int BOTTOM_ROW_START = 45;
    private static final Set<String> CATEGORIES = Set.of(
            "trinket", "tool", "consumable", "cosmetic");

    /**
     * Default price for a suite item when it is first added, in diamonds. Kept in one
     * place.
     *
     * <p>Calibrated against the wondrous items already hand-priced in {@code shop.json}
     * before suite items existed -- those cluster at 12 diamonds for a single-effect
     * tool, 24 for a novelty like the boomerang ball, and 32 for most of the "pocket X"
     * utilities. This table is ordered by rarity, which the hand-tuned prices are not
     * (Flying Boots is tagged legendary but was only ever priced at 12), so it will not
     * reproduce that file exactly -- it only needs to land in the same neighbourhood.
     * It is a starting point the owner immediately owns (SUITE_ITEMS.md §8.3): once a
     * listing is created the price lives in shop.json and this table is never consulted
     * again for it.
     */
    private static final Map<String, Long> RARITY_PRICE = Map.of(
            "common", 8L,
            "uncommon", 16L,
            "rare", 24L,
            "legendary", 40L);

    private final ServerPlayer player;
    private final ShopCatalog catalog;
    private final ShopConfig config;
    private final CurrencyRegistry currencies;
    private final TransactionLog log;

    /**
     * The shop editor page the admin opened this from, so Back returns them to it
     * rather than to page 1. Someone stocking a large shop is usually deep in the
     * listing when they go looking for something to add.
     */
    private final int originPage;

    private SuiteItemBrowser(ServerPlayer player, ShopCatalog catalog, ShopConfig config,
                             CurrencyRegistry currencies, TransactionLog log, int originPage) {
        this.player = player;
        this.catalog = catalog;
        this.config = config;
        this.currencies = currencies;
        this.log = log;
        this.originPage = originPage;
    }

    static void open(ServerPlayer player, ShopCatalog catalog, ShopConfig config,
                     CurrencyRegistry currencies, TransactionLog log, int originPage) {
        new SuiteItemBrowser(player, catalog, config, currencies, log, originPage).openSources();
    }

    // ---- source picker ------------------------------------------------------

    private void openSources() {
        List<String> namespaces = SuiteItems.namespaces();

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Suite Items").withStyle(ChatFormatting.GOLD));

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < namespaces.size()) {
                gui.setSlot(i, sourceElement(namespaces.get(i)));
            } else {
                gui.clearSlot(i);
            }
        }

        gui.setSlot(51, navArrow(Items.ARROW, "Back to shop editor",
                () -> ShopAdminMenu.open(player, catalog, config, currencies, log, originPage)));
        gui.setSlot(53, closeButton(gui));
        gui.open();
    }

    private GuiElement sourceElement(String namespace) {
        SuiteSource source = SuiteItems.source(namespace);
        Item icon = ItemBank.resolve(source.icon()).orElse(Items.BARRIER);
        List<SuiteItemDefinition> items = SuiteItems.inNamespace(namespace);

        List<Component> lore = new ArrayList<>();
        if (source.description() != null && !source.description().isBlank()) {
            lore.add(dim(source.description()));
        }
        lore.add(dim(items.size() + (items.size() == 1 ? " item" : " items")));
        lore.add(dim("Click to browse"));

        return new GuiElementBuilder(icon)
                .setName(Component.literal(source.displayName()).withStyle(ChatFormatting.AQUA))
                .setLore(lore)
                .setCallback((i, t, a, g) -> openGrid(namespace, 0, null))
                .build();
    }

    // ---- item grid ----------------------------------------------------------

    private void openGrid(String namespace, int page, String filterTag) {
        SuiteSource source = SuiteItems.source(namespace);
        List<SuiteItemDefinition> all = SuiteItems.inNamespace(namespace);
        List<SuiteItemDefinition> items = new ArrayList<>();
        for (SuiteItemDefinition def : all) {
            if (filterTag == null || def.hasTag(filterTag)) {
                items.add(def);
            }
        }

        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pageCount - 1));
        int from = current * PAGE_SIZE;

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        String title = source.displayName()
                + (filterTag == null ? "" : " \u00b7 #" + filterTag);
        gui.setTitle(Component.literal(title).withStyle(ChatFormatting.GOLD));

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = from + slot;
            if (index < items.size()) {
                gui.setSlot(slot, gridElement(items.get(index), namespace, current, filterTag));
            } else {
                gui.clearSlot(slot);
            }
        }

        List<String> tags = SuiteItems.tagsIn(namespace);

        if (current > 0) {
            gui.setSlot(BOTTOM_ROW_START, navArrow(Items.ARROW, "Previous page",
                    () -> openGrid(namespace, current - 1, filterTag)));
        }

        int chipSlot = BOTTOM_ROW_START + 1;
        for (String tag : tags) {
            if (chipSlot >= BOTTOM_ROW_START + 5) break;
            gui.setSlot(chipSlot++, filterChip(namespace, tag, filterTag, current));
        }

        gui.setSlot(50, new GuiElementBuilder(Items.CHEST)
                .setName(Component.literal("Add all visible").withStyle(ChatFormatting.GREEN))
                .setLore(List.of(dim("Adds every item on this page")))
                .setCallback((i, t, a, g) -> addAllVisible(namespace, filterTag))
                .build());

        gui.setSlot(51, navArrow(Items.ARROW, "Back", this::openSources));

        if (current + 1 < pageCount) {
            gui.setSlot(52, navArrow(Items.ARROW, "Next page",
                    () -> openGrid(namespace, current + 1, filterTag)));
        }

        gui.setSlot(53, closeButton(gui));
        gui.open();
    }

    private GuiElement gridElement(SuiteItemDefinition def, String namespace,
                                   int page, String filterTag) {
        Optional<ItemStack> icon = SuiteItems.iconStack(def.id(), player.registryAccess());
        ItemStack stack = icon.orElseGet(() -> {
            Optional<Item> base = ItemBank.resolve(def.item());
            return new ItemStack(base.orElse(Items.BARRIER));
        });

        Optional<String> existing = existingKey(def);
        ChatFormatting nameColour = existing.isPresent() ? ChatFormatting.GREEN : ChatFormatting.WHITE;

        List<Component> lore = new ArrayList<>();
        if (existing.isPresent()) {
            lore.add(Component.literal("✓ Already listed as " + existing.get())
                    .withStyle(ChatFormatting.GREEN));
        }
        lore.add(dim(def.id()));
        lore.add(dim(def.item()));
        if (!def.tags().isEmpty()) {
            lore.add(dim(String.join(", ", def.tags())));
        }
        if (def.rarity() != null && !def.rarity().isBlank()) {
            lore.add(dim(def.rarity()));
        }

        return new GuiElementBuilder(stack)
                .setCount(1)
                .setName(Component.literal(def.name()).withStyle(nameColour))
                .setLore(lore)
                .setCallback((i, t, a, g) -> {
                    if (existing.isPresent()) {
                        ShopDialogs.openEditor(player, existing.get());
                    } else {
                        add(def, namespace, page, filterTag);
                    }
                })
                .build();
    }

    private GuiElement filterChip(String namespace, String tag, String activeTag, int page) {
        boolean active = tag.equals(activeTag);
        ChatFormatting colour = active ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        String label = "#" + tag;

        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal(label).withStyle(colour))
                .setLore(List.of(dim("Click to " + (active ? "clear" : "filter"))))
                .setCallback((i, t, a, g) -> openGrid(namespace, page, active ? null : tag))
                .build();
    }

    private GuiElement navArrow(Item item, String label, Runnable onClick) {
        return new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(ChatFormatting.WHITE))
                .setCallback((i, t, a, g) -> onClick.run())
                .build();
    }

    private GuiElement closeButton(SimpleGui gui) {
        return new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((i, t, a, g) -> gui.close())
                .build();
    }

    // ---- adding --------------------------------------------------------------

    private void add(SuiteItemDefinition def, String namespace, int page, String filterTag) {
        if (currencies.all().isEmpty()) {
            bad("No currencies are registered.");
            return;
        }

        String key = freeKey(def);
        String itemId = SuiteItems.PREFIX + def.id();
        Currency currency = defaultCurrency();
        long price = priceFor(def.rarity());
        String category = categoryFor(def);

        Map<Currency, Long> priceMap = new LinkedHashMap<>();
        priceMap.put(currency, price);

        ShopEntry entry = new ShopEntry(key, itemId, 1, priceMap,
                category, true, def.components());

        save(entry, "added suite item " + key + " (" + def.id() + ")");

        // Redraw the grid BEFORE showing the dialog, not after. ShopDialogs.openEditor
        // only sends a ClientboundShowDialogPacket -- it never closes the chest menu
        // still open underneath, so the client just layers the dialog on top of
        // whatever slots were last sent. Without this line the admin sees the tick and
        // "Already listed" only once they close the chest GUI entirely and reopen it,
        // because that is the only thing that forces a fresh slot sync. Refreshing here
        // means the screen behind the dialog is already correct by the time it closes.
        openGrid(namespace, page, filterTag);

        good("Added " + def.name() + " as " + key + ". Set a price before players see it.");
        ShopDialogs.openEditor(player, key);
    }

    private void addAllVisible(String namespace, String filterTag) {
        if (currencies.all().isEmpty()) {
            bad("No currencies are registered.");
            return;
        }

        List<SuiteItemDefinition> all = SuiteItems.inNamespace(namespace);
        int added = 0;
        int skipped = 0;

        for (SuiteItemDefinition def : all) {
            if (filterTag != null && !def.hasTag(filterTag)) continue;
            if (existingKey(def).isPresent()) {
                skipped++;
                continue;
            }

            String key = freeKey(def);
            String itemId = SuiteItems.PREFIX + def.id();
            Currency currency = defaultCurrency();
            long price = priceFor(def.rarity());
            String category = categoryFor(def);

            Map<Currency, Long> priceMap = new LinkedHashMap<>();
            priceMap.put(currency, price);

            ShopEntry entry = new ShopEntry(key, itemId, 1, priceMap,
                    category, true, def.components());

            save(entry, "added suite item " + key + " (" + def.id() + ")");
            added++;
        }

        if (added > 0) {
            good("Added " + added + " suite item" + (added == 1 ? "" : "s")
                    + " from " + SuiteItems.source(namespace).displayName() + ".");
        }
        if (skipped > 0) {
            player.sendSystemMessage(Messages.body(skipped + " already listed."));
        }
        openGrid(namespace, 0, filterTag);
    }

    /** A key not already taken; namespace-qualifies on collision. */
    private String freeKey(SuiteItemDefinition def) {
        String path = def.path();
        if (catalog.find(path).isEmpty()) return path;
        String qualified = def.namespace() + "." + path;
        if (catalog.find(qualified).isEmpty()) return qualified;
        return qualified;
    }

    private Optional<String> existingKey(SuiteItemDefinition def) {
        String itemId = SuiteItems.PREFIX + def.id();
        for (ShopEntry entry : catalog.all()) {
            if (itemId.equals(entry.itemId())) {
                return Optional.of(entry.key());
            }
        }
        return Optional.empty();
    }

    private String categoryFor(SuiteItemDefinition def) {
        for (String tag : def.tags()) {
            if (CATEGORIES.contains(tag)) {
                return tag.substring(0, 1).toUpperCase(Locale.ROOT) + tag.substring(1);
            }
        }
        return SuiteItems.source(def.namespace()).displayName();
    }

    /**
     * Diamond if it is registered, since a suite item is meant to be a step up from
     * the vanilla catalogue and cobblestone reads as pocket change next to one -- else
     * whatever currency exists, so a server running a single custom currency still
     * gets a sane default instead of {@link #add} refusing outright.
     */
    private Currency defaultCurrency() {
        return currencies.byId("diamond")
                .orElseGet(() -> currencies.all().iterator().next());
    }

    private static long priceFor(String rarity) {
        return RARITY_PRICE.getOrDefault(
                rarity == null || rarity.isBlank() ? "common" : rarity, 1L);
    }

    /** Put it in the live catalog, write the file, write the audit line. */
    private void save(ShopEntry entry, String what) {
        catalog.put(entry);
        config.save(catalog);
        log.admin(player.getName().getString() + " " + what + " (suite browser)");
    }

    private void good(String text) {
        player.sendSystemMessage(Messages.good(text));
    }

    private void bad(String text) {
        player.sendSystemMessage(Messages.bad(text));
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }
}
