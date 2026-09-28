package pocketdungeons;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lemon's commands: the player's {@code /lemon}, the agent's console-side
 * {@code dungeon lemon ...} driver, and {@code dungeon admin context}. The
 * two {@code dungeon} subtrees are attached by {@link DungeonCommands}, the
 * driver gated like the admin tree (operators, the console and RCON).
 */
final class LemonCommands {

    private LemonCommands() {}

    /** Registers {@code /lemon}. */
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("lemon")
                        .executes(ctx -> talk(ctx.getSource().getPlayerOrException(), ""))
                        .then(Commands.literal("quiet")
                                .executes(ctx -> quiet(ctx.getSource().getPlayerOrException(), true)))
                        .then(Commands.literal("on")
                                .executes(ctx -> quiet(ctx.getSource().getPlayerOrException(), false)))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> talk(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "text"))))));
    }

    private static int talk(ServerPlayer player, String text) {
        Lemon.heard(player, text.trim());
        return 1;
    }

    private static int quiet(ServerPlayer player, boolean quiet) {
        Lemon.setQuiet(player, quiet);
        player.sendSystemMessage(Component.literal(quiet
                        ? "Lemon keeps to itself unless you ask. /lemon on to undo."
                        : "Lemon will speak up again when it has something to say.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * {@code dungeon lemon say|ask|quiet|mode <player> ...}: the agent's
     * driver. Each reply is one line saying what happened to the request.
     */
    static LiteralArgumentBuilder<CommandSourceStack> driverNode() {
        return Commands.literal("lemon")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("say")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> deliver(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "target"),
                                                StringArgumentType.getString(ctx, "text"), false)))))
                .then(Commands.literal("ask")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> deliver(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "target"),
                                                StringArgumentType.getString(ctx, "text"), true)))))
                .then(Commands.literal("reply")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            Lemon.reply(target, StringArgumentType.getString(ctx, "text"));
                                            reply(ctx.getSource(), "Replied to " + target.getName().getString() + ".");
                                            return 1;
                                        }))))
                .then(Commands.literal("think")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            Lemon.think(target, StringArgumentType.getString(ctx, "text"));
                                            reply(ctx.getSource(), "Thinking line shown to "
                                                    + target.getName().getString() + ".");
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    Lemon.think(target, "");
                                    reply(ctx.getSource(), "Thinking line shown to "
                                            + target.getName().getString() + ".");
                                    return 1;
                                })))
                .then(Commands.literal("quiet")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    Lemon.quietUntilSpoken(target);
                                    reply(ctx.getSource(), "Lemon hushed for " + target.getName().getString()
                                            + "; unprompted lines wait until they speak to Lemon.");
                                    return 1;
                                })))
                .then(Commands.literal("mode")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.literal("guide")
                                        .executes(ctx -> mode(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "target"), false)))
                                .then(Commands.literal("llm")
                                        .executes(ctx -> mode(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "target"), true)))));
    }

    private static int deliver(CommandSourceStack source, ServerPlayer target, String text, boolean ask) {
        Lemon.Delivery delivery = ask ? Lemon.ask(target, text) : Lemon.say(target, text);
        String who = target.getName().getString();
        reply(source, switch (delivery) {
            case SHOWN -> (ask ? "Asked " : "Said to ") + who + ".";
            case DEFERRED_FIGHT -> "Held: " + who + " is in a fight. Lemon speaks when it ends (2 minutes at most).";
            case HELD_QUIET -> "Not shown: " + who + " has Lemon quiet and did not ask.";
        });
        return delivery == Lemon.Delivery.SHOWN ? 1 : 0;
    }

    private static int mode(CommandSourceStack source, ServerPlayer target, boolean llm) {
        Lemon.setMode(target, llm);
        reply(source, llm
                ? "LLM mode for " + target.getName().getString() + " for "
                        + PocketDungeonsConfig.lemonLlmLapseSeconds() + "s; refresh before it lapses."
                : "Guide mode for " + target.getName().getString() + ".");
        return 1;
    }

    /** {@code dungeon admin context <player>}: the snapshot as one line of JSON. */
    static LiteralArgumentBuilder<CommandSourceStack> contextNode() {
        return Commands.literal("context")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                            String json = PlayerContext.toJson(
                                    PlayerContext.gather(ctx.getSource().getServer(), target)).toString();
                            reply(ctx.getSource(), json);
                            return 1;
                        }));
    }

    private static void reply(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    /** {@code /dungeon report <text>}: a note for the playtest journal. */
    static LiteralArgumentBuilder<CommandSourceStack> reportNode() {
        return Commands.literal("report")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String text = StringArgumentType.getString(ctx, "text");
                            PlaytestJournal.report(player, text);
                            PocketDungeonsMod.LOG.info("Report from {}: {}", player.getName().getString(), text);
                            player.sendSystemMessage(Component.literal("Noted. Thank you.")
                                    .withStyle(ChatFormatting.GRAY));
                            return 1;
                        }));
    }
}
