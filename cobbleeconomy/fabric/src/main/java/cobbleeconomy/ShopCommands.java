package cobbleeconomy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import cobbleeconomy.core.EconomyService;
import cobbleeconomy.core.Shop;
import cobbleeconomy.core.ShopCatalog;
import cobbleeconomy.core.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import cobbleeconomy.core.Currency;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /shop} and {@code /buy}: the currency sink.
 *
 * <p>Everything a player mines eventually becomes money, and without somewhere for
 * that money to go the economy inflates until numbers stop meaning anything. This is
 * where currency leaves the world permanently -- not transferred to an admin account,
 * not held in escrow, deleted.
 *
 * <p>The transaction itself is {@link Shop#buy}, in the core module, under test. This
 * class supplies two things it cannot know: how much room the player has, and how to
 * hand over the goods.
 *
 * <p>{@code /shop} opens the sgui screens in {@link ShopMenu} for a player -- the chat
 * listing below only ever runs for a non-player source (console, a command block), so
 * ops running the shop from a script still get a readable answer.
 */
public final class ShopCommands {

    private final EconomyService economy;
    private final ShopCatalog catalog;
    private final TransactionLog log;
    private final EconomySettings settings;

    public ShopCommands(EconomyService economy, ShopCatalog catalog, TransactionLog log,
                        EconomySettings settings) {
        this.economy = economy;
        this.catalog = catalog;
        this.log = log;
        this.settings = settings;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
                .executes(this::list)
                .then(Commands.literal("help").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSuccess(Messages::rule, false);
                    source.sendSuccess(() -> Messages.title("SHOP HELP"), false);
                    source.sendSuccess(Messages::blank, false);
                    source.sendSuccess(() -> Messages.helpLine("/shop", "Browse the server shop"), false);
                    source.sendSuccess(() -> Messages.helpLine("/buy <item>", "Purchase an item"), false);
                    source.sendSuccess(() -> Messages.helpLine("/bank", "Banking help"), false);
                    source.sendSuccess(() -> Messages.helpLine("/balance", "Check your balances"), false);
                    source.sendSuccess(() -> Messages.helpLine("/pay <player> <amount> <currency>", "Send money"), false);
                    source.sendSuccess(Messages::rule, false);
                    return 1;
                })));
        dispatcher.register(Commands.literal("buy")
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(itemSuggestions())
                        .executes(ctx -> buy(ctx, false))
                        // What the [ Confirm purchase ] button runs. A player who knows
                        // it exists can type it and skip the prompt, which is fine --
                        // typing the word "confirm" is not something anyone does by
                        // accident, and that is the whole bar the prompt has to clear.
                        .then(Commands.literal("confirm")
                                .executes(ctx -> buy(ctx, true)))));
    }

    // ---- /shop --------------------------------------------------------------

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> categories = catalog.categories();

        if (categories.isEmpty()) {
            source.sendFailure(Messages.bad("The server shop is empty."));
            return 0;
        }

        if (source.getEntity() instanceof ServerPlayer player) {
            ShopMenu.openCategories(player, economy, catalog, log, settings);
            return 1;
        }

        source.sendSuccess(Messages::rule, false);
        source.sendSuccess(() -> Messages.title("SERVER SHOP"), false);
        for (String category : categories) {
            source.sendSuccess(Messages::blank, false);
            source.sendSuccess(() -> Component.literal(category)
                    .withStyle(Messages.HEADING), false);
            for (ShopEntry entry : catalog.inCategory(category)) {
                source.sendSuccess(() -> line(entry), false);
            }
        }
        source.sendSuccess(Messages::blank, false);
        source.sendSuccess(() -> Messages.body("/buy <item> to purchase"), false);
        source.sendSuccess(Messages::rule, false);
        return 1;
    }

    /** {@code  64 Ice ............ 640 cobblestone   (/buy ice)} */
    private Component line(ShopEntry entry) {
        String label = entry.quantity() + " " + ShopDisplay.displayName(entry);
        Component priced = ShopDisplay.priceComponent(entry);
        return Component.literal("  " + Messages.padRight(label, 26))
                .withStyle(Messages.BODY)
                .append(priced)
                .append(Component.literal("   /buy " + entry.key()).withStyle(Messages.RULE));
    }

    // ---- /buy ---------------------------------------------------------------

    private int buy(CommandContext<CommandSourceStack> ctx, boolean confirmed)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "item");

        Optional<ShopEntry> found = catalog.find(key);
        if (found.isEmpty() || !found.get().enabled() || !found.get().isValid()) {
            source.sendFailure(Messages.bad("The server does not sell '" + key + "'."));
            source.sendFailure(Messages.body("Use /shop to see what is available."));
            return 0;
        }

        ShopEntry entry = found.get();
        // Only ask about a purchase that would actually go through. Asking first and
        // failing afterwards makes a player approve a spend that was never possible,
        // and hides the real answer -- no money, or no room -- behind an extra click.
        boolean possible = PurchaseConfirm.plannedLots(economy, player, entry, 1) >= 1;
        if (!confirmed && possible && PurchaseConfirm.required(entry, 1, settings)) {
            prompt(player, entry);
            // Zero, not one: nothing was bought. A command block or a script chained
            // off /buy must not read "asked the player" as "the goods were delivered".
            return 0;
        }

        Shop.PurchaseResult result = ShopPurchase.attempt(economy, log, player, entry, true);
        return result.ok() ? 1 : 0;
    }

    /**
     * The chat half of the confirmation, matching the sgui screen in {@link ShopMenu}.
     *
     * <p>A clickable button rather than a "type this again" instruction: the second
     * attempt has to be a deliberate act, not a press of the up arrow.
     */
    private void prompt(ServerPlayer player, ShopEntry entry) {
        player.sendSystemMessage(Messages.body("Buy ")
                .append(Component.literal(entry.quantity() + " " + ShopDisplay.displayName(entry))
                        .withStyle(ChatFormatting.WHITE))
                .append(Messages.body(" for "))
                .append(ShopDisplay.priceComponent(entry))
                .append(Messages.body("?")));

        for (Map.Entry<Currency, Long> line : entry.price().entrySet()) {
            Currency currency = line.getKey();
            long balance = economy.getBalance(player.getUUID(), currency);
            player.sendSystemMessage(Messages.balanceLine(currency, balance)
                    .append(Messages.body("  ->  "))
                    .append(Messages.amount(currency, Math.max(0, balance - line.getValue()))));
        }

        MutableComponent button = Component.literal("[ Confirm purchase ]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/buy " + entry.key() + " confirm"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Spend " + entry.describePrice()))));
        player.sendSystemMessage(button);
    }

    private SuggestionProvider<CommandSourceStack> itemSuggestions() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(catalog.keys(), builder);
    }
}
