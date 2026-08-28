package thingy;

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
import thingy.api.Give;
import thingy.api.VirtualItem;

import java.util.Collection;
import java.util.Optional;

/**
 * {@code /thingy list}: open, it is a catalogue.
 * {@code /thingy give <player> <id> [count]}: gated, see {@link Gate}.
 *
 * <p>Ported from {@code wondrous.WondrousCommands} (PLAN.md Phase 1). The
 * {@code id} argument stays a bare id ({@code "flying_boots"}, not
 * {@code "wondrous:flying_boots"}) because Phase 1 hosts exactly one
 * namespace and the admin muscle memory from {@code /wondrous give} carries
 * over unchanged; a namespaced id argument becomes worth it once a second
 * namespace exists to disambiguate (Phase 5).
 *
 * <p>The command tree is built as named locals rather than one chained
 * expression. Brigadier trees nest deeply enough that a single chain ends in
 * a wall of closing parentheses that nobody can count, including whoever
 * wrote it.
 */
public final class ThingyCommands {

    private ThingyCommands() {}

    public static void register(ItemRegistry registry) {
        SuggestionProvider<CommandSourceStack> idSuggestions =
                (context, builder) -> SharedSuggestionProvider.suggest(registry.bareIds(), builder);

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
                        send(source, Component.literal("Thingy")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                        send(source, Component.literal("  /thingy list  Browse the catalogue")
                                .withStyle(ChatFormatting.WHITE));
                        if (Gate.mayAdminister(source)) {
                            send(source, Component.literal("  /thingy give <player> <id> [count]  Give items")
                                    .withStyle(ChatFormatting.WHITE));
                            send(source, Component.literal("  /thingy links [player]  List active machine links")
                                    .withStyle(ChatFormatting.WHITE));
                        }
                        return 1;
                    });

            var linksBranch = Commands.literal("links")
                    .requires(Gate::mayAdminister)
                    .executes(context -> {
                        links(context.getSource(), null);
                        return 1;
                    })
                    .then(Commands.argument("player", EntityArgument.player())
                            .executes(context -> {
                                links(context.getSource(), EntityArgument.getPlayer(context, "player"));
                                return 1;
                            }));

            var confirmVoidBranch = Commands.literal("void_confirm")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        VoidBin.confirm(player);
                        return 1;
                    });

            var cancelVoidBranch = Commands.literal("void_cancel")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        VoidBin.cancel(player);
                        return 1;
                    });

            var root = Commands.literal("thingy")
                    .executes(context -> {
                        list(context.getSource(), registry);
                        return 1;
                    })
                    .then(listBranch)
                    .then(helpBranch)
                    .then(giveBranch)
                    .then(linksBranch)
                    .then(confirmVoidBranch)
                    .then(cancelVoidBranch);

            dispatcher.register(root);
        });
    }

    private static void list(CommandSourceStack source, ItemRegistry registry) {
        send(source, Component.empty());
        send(source, Component.literal("Thingy")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        boolean canGive = Gate.mayAdminister(source);
        ServerPlayer self = source.getPlayer();

        for (VirtualItem item : registry.all()) {
            MutableComponent line = Component.literal("  " + item.id() + "  ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(item.displayName());

            if (canGive && self != null) {
                String name = self.getName().getString();
                String bareId = item.id().substring(item.id().indexOf(':') + 1);
                line = line.append(Component.literal("  [give]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.YELLOW)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/thingy give " + name + " " + bareId))));
            }
            send(source, line);
        }
    }

    private static void links(CommandSourceStack source, ServerPlayer target) {
        WondrousState state = WondrousState.forServer(source.getServer());
        java.util.UUID filter = target != null ? target.getUUID() : null;

        send(source, Component.literal("Active links")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        boolean found = false;
        for (java.util.Map.Entry<WondrousState.PosKey, WondrousState.Link> e : state.allLinks().entrySet()) {
            if (filter != null && !e.getValue().owner().equals(filter)) {
                continue;
            }
            found = true;
            WondrousState.PosKey key = e.getKey();
            WondrousState.Link link = e.getValue();
            String dim = key.dimension().toString();
            String line = String.format("%s: [%d,%d,%d] -> [%d,%d,%d] (%s)",
                    dim,
                    key.pos().getX(), key.pos().getY(), key.pos().getZ(),
                    link.dest().getX(), link.dest().getY(), link.dest().getZ(),
                    link.owner());
            send(source, Component.literal(line).withStyle(ChatFormatting.WHITE));
        }

        if (!found) {
            send(source, Component.literal("No active links.").withStyle(ChatFormatting.GRAY));
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

        Optional<VirtualItem> item = registry.byBareId(id);
        if (item.isEmpty()) {
            source.sendFailure(Component.literal("No item called '" + id + "'."));
            return 0;
        }

        for (ServerPlayer target : targets) {
            giveOrDrop(target, item.get().createStack(count));
            // Aura will pick up the boots at the next server tick.
            Chime.itemGiven(target);
        }

        int given = targets.size();
        source.sendSuccess(() -> Component.literal(
                "Gave " + count + "x " + id + " to " + given
                        + (given == 1 ? " player." : " players.")), true);
        return given;
    }

    /** Delegates to the API so other mods share one implementation. */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        Give.giveOrDrop(player, stack);
    }
}
