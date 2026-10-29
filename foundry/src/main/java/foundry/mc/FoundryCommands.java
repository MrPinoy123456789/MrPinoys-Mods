package foundry.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import foundry.core.ContentSet;
import foundry.core.Ident;
import foundry.core.Problem;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** {@code /foundry help|status|validate|give}. Operator only. */
final class FoundryCommands {
    private FoundryCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("foundry").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> help(ctx.getSource()))
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("validate").executes(ctx -> validate(ctx.getSource())))
                        .then(Commands.literal("give")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "id"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 99))
                                                        .executes(ctx -> give(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "target"),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))));
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(
                "Foundry: /foundry status, /foundry validate, /foundry give <player> <id> [count]"), false);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        ContentSet set = FoundryMod.loaded();
        long errors = set.problems().stream().filter(Problem::blocking).count();
        src.sendSuccess(() -> Component.literal("Foundry: " + FoundryItems.all().size() + " items registered, "
                + errors + " errors, " + (set.problems().size() - errors) + " warnings at last load."), false);
        return 1;
    }

    /** Re-reads the files and reports. New or changed items still need a restart to take effect. */
    private static int validate(CommandSourceStack src) {
        ContentSet set = FoundryMod.loadContent();
        if (set.problems().isEmpty()) {
            src.sendSuccess(() -> Component.literal("Foundry: content is valid (" + set.items().size() + " items)."), false);
            return 1;
        }
        for (Problem p : set.problems()) src.sendSuccess(() -> Component.literal(p.toString()), false);
        long registered = set.items().keySet().stream().filter(i -> FoundryItems.get(i) == null).count();
        if (registered > 0) {
            src.sendSuccess(() -> Component.literal("Foundry: " + registered
                    + " item(s) are new since startup. Restart the server to register them."), false);
        }
        return set.hasErrors() ? 0 : 1;
    }

    private static int give(CommandSourceStack src, ServerPlayer target, String id, int count) {
        Ident ident = Ident.parse(id);
        Item item = ident == null ? null : FoundryItems.get(ident);
        if (item == null) {
            src.sendFailure(Component.literal("Foundry: no registered item " + id
                    + ". Run /foundry validate; new items need a restart."));
            return 0;
        }
        target.getInventory().placeItemBackInInventory(new ItemStack(item, count));
        src.sendSuccess(() -> Component.literal("Gave " + count + " " + id + " to " + target.getName().getString()), true);
        return 1;
    }
}
