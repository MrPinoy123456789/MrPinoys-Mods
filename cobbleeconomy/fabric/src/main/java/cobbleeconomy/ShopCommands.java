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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
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

    public ShopCommands(EconomyService economy, ShopCatalog catalog, TransactionLog log) {
        this.economy = economy;
        this.catalog = catalog;
        this.log = log;
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
                        .executes(this::buy)));
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
            ShopMenu.openCategories(player, economy, catalog, log);
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

    private int buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandSourceStack source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "item");

        Optional<ShopEntry> found = catalog.find(key);
        if (found.isEmpty() || !found.get().enabled() || !found.get().isValid()) {
            source.sendFailure(Messages.bad("The server does not sell '" + key + "'."));
            source.sendFailure(Messages.body("Use /shop to see what is available."));
            return 0;
        }

        Shop.PurchaseResult result = ShopPurchase.attempt(economy, log, player, found.get(), true);
        return result.ok() ? 1 : 0;
    }

    private SuggestionProvider<CommandSourceStack> itemSuggestions() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(catalog.keys(), builder);
    }
}
