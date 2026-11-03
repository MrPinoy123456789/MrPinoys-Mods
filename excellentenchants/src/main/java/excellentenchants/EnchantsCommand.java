package excellentenchants;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /enchants [page]} lists every enchantment, five to a page; {@code /enchants info <name>} explains one.
 * Anyone may use it. Names and the page arrows are clickable on Java; every line also says what to type, so
 * Bedrock players, whose chat may not carry clicks, can follow along.
 */
public final class EnchantsCommand {

    private EnchantsCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("enchants")
                .executes(ctx -> page(ctx.getSource(), 1))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Catalog.ALL.stream().map(Catalog.Entry::id), builder))
                                .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> page(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))));
    }

    /** Sends one page of the list; returns the page actually shown. */
    public static int page(CommandSourceStack source, int requested) {
        int page = Catalog.clampPage(requested);
        int pages = Catalog.pages();
        send(source, Component.literal("Excellent Enchants, page " + page + " of " + pages).withStyle(ChatFormatting.GOLD));
        for (Catalog.Entry e : Catalog.page(page)) {
            String info = "/enchants info " + e.id();
            MutableComponent line = Component.literal(e.name() + " " + Catalog.roman(e.maxLevel()))
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand(info))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(info))));
            line.append(Component.literal(e.table() ? " [table] " : " [treasure] ")
                    .withStyle(e.table() ? ChatFormatting.GREEN : ChatFormatting.LIGHT_PURPLE));
            line.append(Component.literal(capital(e.effect())).withStyle(ChatFormatting.GRAY));
            send(source, line);
        }
        MutableComponent nav = Component.empty();
        if (page > 1) nav.append(arrow("< Prev", page - 1)).append(Component.literal(" "));
        if (page < pages) nav.append(arrow("Next >", page + 1)).append(Component.literal(" "));
        String hint = page < pages ? "Type /enchants " + (page + 1) + " for the next page, " : "";
        nav.append(Component.literal(hint + "/enchants info <name> for details.").withStyle(ChatFormatting.DARK_GRAY));
        send(source, nav);
        return page;
    }

    /** Sends the details of one enchantment; returns 1, or 0 when the name is not one of them. */
    public static int info(CommandSourceStack source, String name) {
        var entry = Catalog.find(name);
        if (entry.isEmpty()) {
            source.sendFailure(Component.literal("No enchantment called \"" + name + "\". Type /enchants for the list."));
            return 0;
        }
        var lines = Catalog.details(entry.get());
        send(source, Component.literal(lines.get(0)).withStyle(ChatFormatting.AQUA));
        for (int i = 1; i < lines.size(); i++) send(source, Component.literal(lines.get(i)).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static MutableComponent arrow(String text, int page) {
        String cmd = "/enchants " + page;
        return Component.literal("[" + text + "]").withStyle(s -> s.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd))));
    }

    private static void send(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
