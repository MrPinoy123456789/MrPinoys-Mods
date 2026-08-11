package wondrous;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import wondrous.api.WondrousGive;
import wondrous.api.WondrousItem;

import java.util.Collection;
import java.util.Optional;

/**
 * {@code /wondrous list} -- open, it is a catalogue.
 * {@code /wondrous give <player> <id> [count]} -- gated, see {@link Gate}.
 *
 * <p>The command tree is built as named locals rather than one chained expression.
 * Brigadier trees nest deeply enough that a single chain ends in a wall of closing
 * parentheses that nobody can count, including whoever wrote it.
 */
public final class WondrousCommands {

    private WondrousCommands() {}

    public static void register(ItemRegistry registry) {
        SuggestionProvider<CommandSourceStack> idSuggestions =
                (context, builder) -> SharedSuggestionProvider.suggest(registry.ids(), builder);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            var countArgument = Commands.argument("count", IntegerArgumentType.integer(1, 64))
                    .executes(context -> give(
                            context.getSource(),
                            registry,
                            EntityArgument.getPlayers(context, "targets"),
                            StringArgumentType.getString(context, "id"),
                            IntegerArgumentType.getInteger(context, "count")));

            var idArgument = Commands.argument("id", StringArgumentType.word())
                    .suggests(idSuggestions)
                    .executes(context -> give(
                            context.getSource(),
                            registry,
                            EntityArgument.getPlayers(context, "targets"),
                            StringArgumentType.getString(context, "id"),
                            1))
                    .then(countArgument);

            var targetsArgument = Commands.argument("targets", EntityArgument.players())
                    .then(idArgument);

            var giveBranch = Commands.literal("give")
                    .requires(Gate::mayAdminister)
                    .then(targetsArgument);

            var listBranch = Commands.literal("list")
                    .executes(context -> {
                        list(context.getSource(), registry);
                        return 1;
                    });

            var helpBranch = Commands.literal("help")
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        send(source, Component.literal("Wondrous Items")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                        send(source, Component.literal("  /wondrous list  Browse the catalogue")
                                .withStyle(ChatFormatting.WHITE));
                        if (Gate.mayAdminister(source)) {
                            send(source, Component.literal("  /wondrous give <player> <id> [count]  Give items")
                                    .withStyle(ChatFormatting.WHITE));
                        }
                        return 1;
                    });

            var root = Commands.literal("wondrous")
                    .executes(context -> {
                        list(context.getSource(), registry);
                        return 1;
                    })
                    .then(listBranch)
                    .then(helpBranch)
                    .then(giveBranch);

            dispatcher.register(root);
        });
    }

    private static void list(CommandSourceStack source, ItemRegistry registry) {
        send(source, Component.empty());
        send(source, Component.literal("Wondrous Items")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        boolean canGive = Gate.mayAdminister(source);
        ServerPlayer self = source.getPlayer();

        for (WondrousItem item : registry.all()) {
            MutableComponent line = Component.literal("  " + item.id() + "  ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(item.displayName());

            if (canGive && self != null) {
                String name = self.getName().getString();
                line = line.append(Component.literal("  [give]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.YELLOW)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/wondrous give " + name + " " + item.id()))));
            }
            send(source, line);
        }
    }

    /**
     * CommandSourceStack has sendSuccess and sendFailure; sendSystemMessage lives on
     * CommandSource, which is a different type. false = do not echo to other ops.
     */
    private static void send(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    private static int give(CommandSourceStack source,
                            ItemRegistry registry,
                            Collection<ServerPlayer> targets,
                            String id,
                            int count) {

        Optional<WondrousItem> item = registry.byId(id);
        if (item.isEmpty()) {
            source.sendFailure(Component.literal("No wondrous item called '" + id + "'."));
            return 0;
        }

        for (ServerPlayer target : targets) {
            giveOrDrop(target, item.get().createStack(count));
            // The boots may have landed straight in the feet slot; re-evaluate
            // rather than wait out the poll.
            FlyingBoots.evaluate(target);
            Chime.itemGiven(target);
        }

        int given = targets.size();
        source.sendSuccess(() -> Component.literal(
                "Gave " + count + "x " + id + " to " + given
                        + (given == 1 ? " player." : " players.")), true);
        return given;
    }

    /** Delegates to the API so dailyquests and quizengine share one implementation. */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        WondrousGive.giveOrDrop(player, stack);
    }
}
