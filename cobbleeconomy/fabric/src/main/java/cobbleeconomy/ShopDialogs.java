package cobbleeconomy;

import cobbleeconomy.core.Currency;
import cobbleeconomy.core.CurrencyRegistry;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import cobbleeconomy.dialog.DialogKit;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The shop's vanilla-dialog screens, and where their buttons land.
 *
 * <p>Lives in the flat {@code cobbleeconomy} package rather than {@code .dialog} because
 * it drives {@link ShopMenu} and {@link ShopAdminMenu}, both package-private. The
 * reusable half of the dialog API is in {@link DialogKit}; this is the part that knows
 * what a shop is.
 *
 * <p><b>It owns no rules.</b> Every action ends in the same call the equivalent sgui
 * click or chat command already makes -- {@link ShopPurchase#attempt}, or a
 * {@link ShopCatalog} write followed by {@link ShopConfig#save}. Two interfaces that
 * cannot disagree, because there is only one of them underneath.
 *
 * <p>See {@code DIALOGS_SPEC.md}. The input controls used here are the ones whose
 * submitted tag shapes were confirmed in play by {@code /cobbleeconomy dialogtest}:
 * {@link TextInput} arrives as a string, {@link BooleanInput} as a byte.
 */
public final class ShopDialogs {

    // Action ids. Kept short; they travel as the path of a cobbleeconomy: identifier.
    static final String BUY = "shop_buy";
    static final String BUY_CANCEL = "shop_buy_cancel";
    static final String EDIT_SAVE = "admin_save";
    static final String EDIT_DELETE_ASK = "admin_delete_ask";
    static final String EDIT_DELETE = "admin_delete";
    static final String EDIT_REOPEN = "admin_reopen";
    static final String ASK_TEXT = "ask_text";

    // Context keys, deliberately prefixed. CustomAll merges input values into the same
    // flat compound as the context, and an input key wins on a collision -- so the
    // context key for the listing being edited must not be the word an input uses.
    private static final String CTX_TARGET = "ctx_target";
    private static final String CTX_LOTS = "ctx_lots";
    private static final String CTX_BULK = "ctx_bulk";
    private static final String CTX_CATEGORY = "ctx_category";
    private static final String CTX_PAGE = "ctx_page";

    // Input keys. These are what the player actually edits.
    private static final String IN_KEY = "key";
    private static final String IN_CATEGORY = "category";
    private static final String IN_QUANTITY = "quantity";
    private static final String IN_ENABLED = "enabled";
    private static final String IN_PRICE = "price_";
    private static final String IN_TEXT = "text";

    /** Pending one-shot {@link #askText} callbacks, one slot per player. */
    private static final Map<java.util.UUID, java.util.function.Consumer<String>> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ShopDialogs() {}

    // ---- services ---------------------------------------------------------------
    //
    // A dialog button arrives as a bare packet, with none of the wiring the sgui menus
    // were handed at construction. Set once during initialisation, same shape as
    // EconomyApi.Holder.

    private static EconomyService economy;
    private static ShopCatalog catalog;
    private static ShopConfig config;
    private static CurrencyRegistry currencies;
    private static TransactionLog log;
    private static EconomySettings settings;

    static void init(EconomyService economyService, ShopCatalog shopCatalog, ShopConfig shopConfig,
                     CurrencyRegistry currencyRegistry, TransactionLog transactionLog,
                     EconomySettings economySettings) {
        economy = economyService;
        catalog = shopCatalog;
        config = shopConfig;
        currencies = currencyRegistry;
        log = transactionLog;
        settings = economySettings;
    }

    // ---- buying -----------------------------------------------------------------

    /**
     * The screen between a click and an expensive mistake.
     *
     * <p>Was a whole {@code GENERIC_9x3} chest with a yes item and a no item four slots
     * apart, so the two could not be hit by the same twitch. A
     * {@link ConfirmationDialog} is that shape natively, and it can say the numbers in
     * words rather than hiding them in an item tooltip the player has to hover to read.
     */
    static void confirmPurchase(ServerPlayer player, ShopEntry entry, int lots, boolean bulk,
                                String category, int page) {
        long items = lots * (long) entry.quantity();
        Map<Currency, Long> total = PurchaseConfirm.total(entry, lots);

        List<DialogBody> body = new ArrayList<>();
        body.add(line(items + " x " + ShopDisplay.displayName(entry), ChatFormatting.WHITE));
        body.add(line("Costs " + describe(total), ChatFormatting.YELLOW));
        body.add(line(" ", ChatFormatting.GRAY));
        for (Map.Entry<Currency, Long> owed : total.entrySet()) {
            Currency currency = owed.getKey();
            long balance = economy.getBalance(player.getUUID(), currency);
            body.add(line(currency.displayName() + ": " + formatted(balance)
                    + "  ->  " + formatted(Math.max(0, balance - owed.getValue())),
                    Messages.colourOf(currency)));
        }

        CompoundTag context = new CompoundTag();
        context.putString(CTX_TARGET, entry.key());
        context.putInt(CTX_LOTS, lots);
        context.putBoolean(CTX_BULK, bulk);
        context.putString(CTX_CATEGORY, category);
        context.putInt(CTX_PAGE, page);

        Dialog dialog = new ConfirmationDialog(
                DialogKit.common("Confirm purchase", body, List.of()),
                DialogKit.button("Buy", "Spend " + describe(total),
                        DialogKit.submit(BUY, context)),
                DialogKit.button("Cancel", "Nothing is spent",
                        DialogKit.submit(BUY_CANCEL, context)));

        DialogKit.show(player, dialog);
    }

    private static void handleBuy(ServerPlayer player, CompoundTag tag) {
        Optional<ShopEntry> found = catalog.find(tag.getStringOr(CTX_TARGET, ""));
        if (found.isEmpty()) {
            player.sendSystemMessage(Messages.bad("That listing is gone. Nothing was bought."));
            return;
        }
        ShopEntry entry = found.get();

        // Re-checked rather than trusted: a dialog is a window, and a window can be left
        // open while the price changes or the money is spent elsewhere. The purchase
        // path would refuse anyway; this only decides what the player is told.
        if (tag.getBooleanOr(CTX_BULK, false)) {
            ShopMenu.bulkBuy(economy, log, player, entry);
        } else {
            ShopPurchase.attempt(economy, log, player, entry, true);
        }
        reopenCategory(player, tag);
    }

    private static void reopenCategory(ServerPlayer player, CompoundTag tag) {
        String category = tag.getStringOr(CTX_CATEGORY, "");
        if (category.isBlank()) return;
        ShopMenu.openCategory(player, economy, catalog, log, settings,
                category, tag.getIntOr(CTX_PAGE, 0));
    }

    // ---- the listing editor -------------------------------------------------------

    /**
     * One listing, every field at once.
     *
     * <p>The sgui editor spends a screen per field: click the key, an anvil opens, type,
     * accept, land back on the editor, click the category, another anvil. Six round
     * trips to change six things, and the anvil caps a field at what fits in a rename
     * box. This is the same six fields as one form with one Save, which is the whole
     * reason the dialog system is worth adopting.
     *
     * <p>Prices stay text rather than a {@code NumberRangeInput} slider on purpose. A
     * slider needs a bounded range to be usable, and a shop price is not bounded -- one
     * that spans 1 to 50,000 has a pixel worth hundreds of cobblestone, which is worse
     * than typing. Sliders are the right control for a bounded setting, not for money.
     */
    /**
     * The field editor's exit button carries no server callback (see the
     * {@code MultiActionDialog} built below), so dismissing it never reaches
     * {@link #handle}. Callers must not rely on a Close event ever arriving --
     * whatever screen is open behind the dialog when it is shown is what the admin
     * ends up looking at, which is why {@link ShopAdminMenu} no longer closes the
     * list before calling this.
     */
    static void openEditor(ServerPlayer player, String key) {
        Optional<ShopEntry> found = catalog.find(key);
        if (found.isEmpty()) {
            player.sendSystemMessage(Messages.bad("No listing called '" + key + "'."));
            return;
        }
        ShopEntry entry = found.get();

        List<DialogBody> body = new ArrayList<>();
        body.add(line("Sells " + entry.itemId(), ChatFormatting.WHITE));
        if (entry.hasComponents()) {
            body.add(line("Carries a components block; edit that in shop.json",
                    ChatFormatting.LIGHT_PURPLE));
        }
        if (!entry.isValid()) {
            body.add(line("Malformed -- players cannot buy it", ChatFormatting.RED));
        }

        List<Input> inputs = new ArrayList<>();
        inputs.add(new Input(IN_KEY, new TextInput(
                DialogKit.WIDE, Component.literal("Key"), true, entry.key(), 48,
                Optional.empty())));
        inputs.add(new Input(IN_CATEGORY, new TextInput(
                DialogKit.WIDE, Component.literal("Category"), true, entry.category(), 48,
                Optional.empty())));
        inputs.add(new Input(IN_QUANTITY, new TextInput(
                DialogKit.WIDE, Component.literal("Quantity per purchase"), true,
                Integer.toString(entry.quantity()), 8, Optional.empty())));
        inputs.add(new Input(IN_ENABLED, new BooleanInput(
                Component.literal("On sale"), entry.enabled(), "true", "false")));

        // One field per registered currency, whether or not this listing charges in it:
        // a price denominated in something new is the same edit as changing the one it
        // already has, and a compound price is two of them filled in at once.
        for (Currency currency : currencies.all()) {
            long amount = entry.price().getOrDefault(currency, 0L);
            inputs.add(new Input(IN_PRICE + currency.id(), new TextInput(
                    DialogKit.WIDE,
                    Component.literal(currency.displayName() + " price"), true,
                    amount > 0 ? Long.toString(amount) : "0", 12, Optional.empty())));
        }

        CompoundTag context = new CompoundTag();
        context.putString(CTX_TARGET, entry.key());

        Dialog dialog = new MultiActionDialog(
                DialogKit.common(entry.key(), body, inputs),
                List.of(
                        DialogKit.button("Save", "Writes shop.json immediately",
                                DialogKit.submit(EDIT_SAVE, context)),
                        DialogKit.button("Delete", "Asks first",
                                DialogKit.submit(EDIT_DELETE_ASK, context))),
                Optional.of(new ActionButton(
                        new CommonButtonData(Component.literal("Close"), DialogKit.WIDE),
                        Optional.empty())),
                1); // single column: these are labelled actions, not a grid

        DialogKit.show(player, dialog);
    }

    /**
     * Applies every field in one pass, or none of them.
     *
     * <p>A settings save that half-applied would leave the listing in a state the admin
     * never asked for and cannot see, so validation happens first and a failure reports
     * without writing anything.
     */
    private static void handleSave(ServerPlayer player, CompoundTag tag) {
        Optional<ShopEntry> found = catalog.find(tag.getStringOr(CTX_TARGET, ""));
        if (found.isEmpty()) {
            player.sendSystemMessage(Messages.bad("That listing is gone. Nothing was saved."));
            return;
        }
        ShopEntry entry = found.get();

        // Same normalisation the chat command and the sgui rename apply: the key is what
        // a player types after /buy, and /buy takes a single word.
        String key = tag.getStringOr(IN_KEY, entry.key()).trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_./-]", "");
        if (key.isBlank()) {
            player.sendSystemMessage(Messages.bad(
                    "A listing needs a key made of letters, digits or underscores."));
            return;
        }
        if (!key.equals(entry.key()) && catalog.find(key).isPresent()) {
            player.sendSystemMessage(Messages.bad(
                    "'" + key + "' is already used by another listing."));
            return;
        }

        String category = tag.getStringOr(IN_CATEGORY, entry.category()).trim();
        if (category.isBlank()) category = "Goods";

        long quantity;
        try {
            quantity = Long.parseLong(tag.getStringOr(IN_QUANTITY, "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            player.sendSystemMessage(Messages.bad("Quantity must be a whole number."));
            return;
        }
        if (quantity < 1) {
            player.sendSystemMessage(Messages.bad("A listing has to grant at least one item."));
            return;
        }
        quantity = Math.min(quantity, 9_999);

        Map<Currency, Long> price = new LinkedHashMap<>();
        for (Currency currency : currencies.all()) {
            String raw = tag.getStringOr(IN_PRICE + currency.id(), "").replace(",", "").trim();
            if (raw.isEmpty()) continue;
            long amount;
            try {
                amount = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                player.sendSystemMessage(Messages.bad(
                        "'" + raw + "' is not a valid " + currency.displayName() + " price."));
                return;
            }
            if (amount > 0) price.put(currency, amount);
        }
        if (price.isEmpty()) {
            player.sendSystemMessage(Messages.bad(
                    "A listing needs a price in at least one currency."));
            return;
        }

        boolean enabled = tag.getBooleanOr(IN_ENABLED, entry.enabled());

        // Validated; now the single write.
        if (!key.equals(entry.key())) {
            catalog.remove(entry.key());
        }
        ShopEntry updated = new ShopEntry(key, entry.itemId(), (int) quantity, price,
                category, enabled, entry.components());
        catalog.put(updated);
        config.save(catalog);
        log.admin(player.getName().getString() + " saved shop entry " + key
                + " (" + updated.quantity() + "x " + updated.itemId()
                + " for " + updated.describePrice() + ", "
                + (enabled ? "enabled" : "disabled") + ") (dialog editor)");

        player.sendSystemMessage(Messages.good("Saved " + key + ". ")
                .append(Messages.body(updated.quantity() + "x " + ShopDisplay.displayName(updated)
                        + " for " + updated.describePrice())));

        // Reopened rather than closed: an admin pricing a listing usually wants to see
        // what they just set, and often to adjust it again.
        openEditor(player, key);
    }

    // ---- a single text field ------------------------------------------------------

    /**
     * One prompt, one field, one callback -- the shape {@link ShopAdminMenu}'s per-field
     * edits were already written against, now backed by a real text input instead of an
     * anvil rename box.
     *
     * <p>The callback is held here rather than travelling in the payload because it is a
     * closure over the sgui screen that opened it, and a dialog submission arrives as a
     * bare packet. One slot per player: opening a second prompt abandons the first,
     * which is exactly what opening a second anvil did.
     */
    static void askText(ServerPlayer player, String prompt, String initial,
                        java.util.function.Consumer<String> onDone) {
        pending.put(player.getUUID(), onDone);

        Dialog dialog = new NoticeDialog(
                DialogKit.common(prompt, List.of(), List.of(
                        new Input(IN_TEXT, new TextInput(
                                DialogKit.WIDE, Component.literal(prompt), false,
                                initial == null ? "" : initial, 128, Optional.empty())))),
                DialogKit.button("Accept", null, DialogKit.submit(ASK_TEXT, new CompoundTag())));

        DialogKit.show(player, dialog);
    }

    private static void handleAskText(ServerPlayer player, CompoundTag tag) {
        java.util.function.Consumer<String> onDone = pending.remove(player.getUUID());
        if (onDone == null) {
            return;
        }
        String typed = tag.getStringOr(IN_TEXT, "").trim();
        if (typed.isEmpty()) {
            player.sendSystemMessage(Messages.bad("Nothing typed."));
            return;
        }
        onDone.accept(typed);
    }

    // ---- deleting -----------------------------------------------------------------

    /** Reached from the sgui editor's delete button, which used to be a shift-click. */
    static void confirmDelete(ServerPlayer player, String key, int page) {
        CompoundTag context = new CompoundTag();
        context.putString(CTX_TARGET, key);
        context.putInt(CTX_PAGE, page);
        askDelete(player, context);
    }

    private static void askDelete(ServerPlayer player, CompoundTag tag) {
        Optional<ShopEntry> found = catalog.find(tag.getStringOr(CTX_TARGET, ""));
        int page = tag.getIntOr(CTX_PAGE, 0);
        if (found.isEmpty()) {
            // The listing is already gone -- most likely a second click on a request
            // that already succeeded. Nothing to confirm, but the admin still needs
            // somewhere to land rather than a dialog about a key that no longer exists.
            ShopAdminMenu.open(player, catalog, config, currencies, log, page);
            return;
        }
        ShopEntry entry = found.get();

        CompoundTag context = new CompoundTag();
        context.putString(CTX_TARGET, entry.key());
        context.putInt(CTX_PAGE, page);

        Dialog dialog = new ConfirmationDialog(
                DialogKit.common("Delete " + entry.key() + "?",
                        List.of(
                                line(entry.quantity() + "x " + entry.itemId()
                                        + " for " + entry.describePrice(), ChatFormatting.WHITE),
                                line(" ", ChatFormatting.GRAY),
                                line("Removes it from shop.json. Anyone with /buy "
                                        + entry.key() + " in muscle memory gets "
                                        + "'not for sale' instead.", ChatFormatting.GRAY)),
                        List.of()),
                DialogKit.button("Delete it", null, DialogKit.submit(EDIT_DELETE, context)),
                DialogKit.button("Keep it", null, DialogKit.submit(EDIT_REOPEN, context)));

        DialogKit.show(player, dialog);
    }

    private static void handleDelete(ServerPlayer player, CompoundTag tag) {
        String key = tag.getStringOr(CTX_TARGET, "");
        int page = tag.getIntOr(CTX_PAGE, 0);
        if (!catalog.remove(key)) {
            player.sendSystemMessage(Messages.bad("No listing called '" + key + "'."));
        } else {
            config.save(catalog);
            log.admin(player.getName().getString() + " deleted shop entry " + key + " (dialog editor)");
            player.sendSystemMessage(Messages.good("Deleted " + key + "."));
        }
        // Either way the admin needs to land somewhere -- the entry this dialog was
        // about is gone or never existed, so the per-listing screen has nothing left
        // to show. Back to the list, at the page they opened the delete confirm from.
        ShopAdminMenu.open(player, catalog, config, currencies, log, page);
    }

    // ---- routing -------------------------------------------------------------------

    /** Called on the server main thread by {@link DialogRouter}. */
    static boolean handle(ServerPlayer player, String path, CompoundTag tag) {
        if (catalog == null) {
            CobbleEconomyMod.LOG.warn("Dialog action {} arrived before the shop was wired", path);
            return true;
        }
        switch (path) {
            case BUY -> handleBuy(player, tag);
            case BUY_CANCEL -> reopenCategory(player, tag);
            case EDIT_SAVE -> handleSave(player, tag);
            case EDIT_DELETE_ASK -> askDelete(player, tag);
            case EDIT_DELETE -> handleDelete(player, tag);
            case EDIT_REOPEN -> openEditor(player, tag.getStringOr(CTX_TARGET, ""));
            case ASK_TEXT -> handleAskText(player, tag);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- shared ---------------------------------------------------------------------

    private static DialogBody line(String text, ChatFormatting colour) {
        return new PlainMessage(Component.literal(text).withStyle(colour),
                PlainMessage.DEFAULT_WIDTH);
    }

    private static String describe(Map<Currency, Long> total) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Currency, Long> line : total.entrySet()) {
            if (!out.isEmpty()) out.append(" + ");
            out.append(line.getKey().describe(line.getValue()));
        }
        return out.toString();
    }

    /** {@code 12,481}. */
    private static String formatted(long value) {
        return cobbleeconomy.core.Wallet.format(value);
    }
}
