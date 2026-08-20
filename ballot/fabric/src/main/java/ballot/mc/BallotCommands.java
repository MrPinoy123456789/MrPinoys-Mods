package ballot.mc;

import ballot.Outcome;
import ballot.Poll;
import ballot.PollState;
import ballot.Rules;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.Optional;

/**
 * The whole typed surface.
 *
 * <pre>
 *   /ballot                        the vote's page                 everyone
 *   /ballot wand                   the organiser stick             op
 *   /ballot create poll            noticeboard + ballot box        op
 *   /ballot create buildoff          noticeboard (build competition)  op
 *   /ballot _ ...                  every button target
 * </pre>
 *
 * <p>Four commands, because everything else is walking up to a block. The kind of vote
 * is decided by which command created it rather than by a setting, so there is nothing
 * to leave unset. Plots and options are added from the noticeboard, not by typing.
 *
 * <p>The {@code _} node exists because Brigadier suggests every runnable command and
 * chat buttons must be runnable. Nesting them under one opaque literal is as quiet as
 * suggestions get — one entry instead of fifteen.
 */
public final class BallotCommands {

    private BallotCommands() {}

    /** Registers the public ballot commands and the hidden targets used by chat buttons. */
    public static void register(PollStore store) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("ballot")

                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Optional<Poll> poll = store.current();
                            if (poll.isEmpty()) {
                                player.sendSystemMessage(Screens.dim(isOp(ctx)
                                        ? "No vote running. /ballot create poll"
                                        : "No vote running right now."));
                                return 1;
                            }
                            Screens.show(player, poll.get(), isOp(ctx));
                            return 1;
                        })

                        .then(Commands.literal("wand")
                                .requires(BallotCommands::sourceIsOp)
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    give(player, BallotItems.wand());
                                    return succeed(ctx,
                                            "Right-click a poll block with it to manage it.");
                                }))

                        .then(Commands.literal("create")
                                .requires(BallotCommands::sourceIsOp)
                                .then(Commands.literal("poll")
                                        .executes(ctx -> create(ctx, store, false)))
                                .then(Commands.literal("buildoff")
                                        .executes(ctx -> create(ctx, store, true))))

                        .then(Commands.literal("help")
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    boolean op = isOp(ctx);
                                    src.sendSuccess(() -> Screens.header("Ballot"), false);
                                    src.sendSuccess(() -> Screens.dim("  /ballot  The vote's page"), false);
                                    if (op) {
                                        src.sendSuccess(() -> Screens.dim("  /ballot wand  Get the organiser stick"), false);
                                        src.sendSuccess(() -> Screens.dim("  /ballot create poll  Create a poll"), false);
                                        src.sendSuccess(() -> Screens.dim("  /ballot create buildoff  Create a build competition"), false);
                                    }
                                    return 1;
                                }))

                        .then(hidden(store))));
    }

    // ---- the public actions -------------------------------------------------

    /**
     * One vote at a time, and its kind is decided here rather than by a setting.
     *
     * <p>Which command you ran <em>is</em> the choice, so there is no "kind" field to
     * leave unset and no way to open a buildoff that is secretly a poll.
     *
     * <p>A finished vote is archived out of the way automatically; a running one is
     * not, because reaching for a new vote should never be able to kill a live one.
     */
    private static int create(CommandContext<CommandSourceStack> ctx, PollStore store,
                              boolean competition) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long now = now();

        Optional<Poll> live = store.current();
        if (live.isPresent()) {
            Poll poll = live.get();
            if (poll.state() != PollState.CLOSED) {
                player.sendSystemMessage(Screens.bad(
                        "\"" + poll.title() + "\" is still running."));
                player.sendSystemMessage(Component.literal("  ")
                        .append(Screens.button("[ Close it now ]", "/ballot _ close")));
                return 0;
            }
            if (!store.archive(poll.key(), now)) {
                return fail(ctx, "Couldn't archive the finished vote; no new vote was created.");
            }
            player.sendSystemMessage(Screens.dim("Archived \"" + poll.title() + "\"."));
        }

        Optional<Poll> created = store.create(
                competition ? Rules.forClaimable() : Rules.forOwnerless(), now);
        if (created.isEmpty()) {
            return fail(ctx, "Couldn't create it.");
        }
        Poll poll = created.get();

        // Hand over the whole kit, so nobody has to work out what is missing.
        give(player, BallotItems.lectern(poll.key(), poll.name()));
        if (!competition) {
            give(player, BallotItems.ballotBox(poll.key()));
        }

        Screens.settings(player, poll);
        player.sendSystemMessage(Screens.dim(competition
                ? "Place the noticeboard, then add plots from it."
                : "Place the noticeboard and the ballot box, then add options."));
        return 1;
    }

    // ---- everything the buttons call ----------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> hidden(PollStore store) {
        return Commands.literal("_")

                .then(Commands.literal("settings")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> withPoll(ctx, store, poll -> {
                            Screens.settings(ctx.getSource().getPlayerOrException(), poll);
                            return 1;
                        })))

                .then(Commands.literal("preview")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> withPoll(ctx, store, poll -> {
                            Screens.preview(ctx.getSource().getPlayerOrException(), poll);
                            return 1;
                        })))

                .then(Commands.literal("show").executes(ctx -> withPoll(ctx, store, poll -> {
                    Screens.show(ctx.getSource().getPlayerOrException(), poll, isOp(ctx));
                    return 1;
                })))

                // ---- settings ----
                .then(Commands.literal("name")
                        .requires(BallotCommands::sourceIsOp)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> withPoll(ctx, store, poll -> after(ctx, store,
                                        poll, poll.setName(
                                                StringArgumentType.getString(ctx, "value"),
                                                now()))))))
                .then(Commands.literal("desc")
                        .requires(BallotCommands::sourceIsOp)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> withPoll(ctx, store, poll -> after(ctx, store,
                                        poll, poll.setDescription(
                                                StringArgumentType.getString(ctx, "value"),
                                                now()))))))
                .then(Commands.literal("deadline")
                        .requires(BallotCommands::sourceIsOp)
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> withPoll(ctx, store, poll -> after(ctx, store,
                                        poll, poll.setDeadline(now()
                                                + IntegerArgumentType.getInteger(ctx, "days")
                                                * 86400L, now()))))))
                .then(Commands.literal("set")
                        .requires(BallotCommands::sourceIsOp)
                        .then(Commands.argument("switch", StringArgumentType.word())
                                .executes(ctx -> withPoll(ctx, store, poll -> flip(ctx, store,
                                        poll, StringArgumentType.getString(ctx, "switch"))))))

                // ---- state ----
                .then(state("open", PollState.OPEN, store))
                .then(state("voting", PollState.VOTING, store))
                .then(Commands.literal("close")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> withPoll(ctx, store, poll ->
                                closeAndAnnounce(ctx, store, poll))))
                .then(Commands.literal("archive")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> withPoll(ctx, store, poll -> {
                            if (!store.archive(poll.key(), now())) {
                                return fail(ctx, "Only a closed vote can be archived.");
                            }
                            return succeed(ctx, "Archived.");
                        })))
                .then(Commands.literal("delete")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> confirmDelete(ctx, store))
                        .then(Commands.literal("confirm").executes(ctx -> doDelete(ctx, store))))

                // ---- entries ----
                .then(Commands.literal("remove")
                        .requires(BallotCommands::sourceIsOp)
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .executes(ctx -> withPoll(ctx, store, poll -> after(ctx, store,
                                        poll, poll.removeEntry(
                                                IntegerArgumentType.getInteger(ctx, "entry"),
                                                now()))))))

                // ---- players ----
                .then(Commands.literal("take")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .executes(ctx -> withPoll(ctx, store, poll -> {
                                    ServerPlayer player =
                                            ctx.getSource().getPlayerOrException();
                                    Outcome outcome = poll.claim(
                                            IntegerArgumentType.getInteger(ctx, "entry"),
                                            player.getUUID().toString(),
                                            player.getName().getString(), now());
                                    if (!outcome.ok()) {
                                        return fail(ctx, outcome.message());
                                    }
                                    store.save(poll);
                                    Signs.refresh(ctx.getSource().getServer(), poll);
                                    player.sendSystemMessage(Screens.good(outcome.message()));
                                    player.sendSystemMessage(Component.literal("  ").append(
                                            Screens.suggest("[ Name your piece ]",
                                                    "/ballot _ label ")));
                                    return 1;
                                }))))
                .then(Commands.literal("label")
                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                .executes(ctx -> withPoll(ctx, store, poll -> {
                                    ServerPlayer player =
                                            ctx.getSource().getPlayerOrException();
                                    return after(ctx, store, poll, poll.rename(
                                            player.getUUID().toString(),
                                            StringArgumentType.getString(ctx, "title"), now()));
                                }))))
                .then(Commands.literal("letgo").executes(ctx -> withPoll(ctx, store, poll -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return after(ctx, store, poll,
                            poll.release(player.getUUID().toString(), now()));
                })))
                .then(Commands.literal("cast")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .executes(ctx -> withPoll(ctx, store, poll -> {
                                    ServerPlayer player =
                                            ctx.getSource().getPlayerOrException();
                                    Outcome outcome = poll.vote(player.getUUID().toString(),
                                            IntegerArgumentType.getInteger(ctx, "entry"), now());
                                    if (!outcome.ok()) {
                                        return fail(ctx, outcome.message());
                                    }
                                    store.save(poll);
                                    Chime.voteCast(player);
                                    return succeed(ctx, outcome.message());
                                }))))
                .then(Commands.literal("uncast").executes(ctx -> withPoll(ctx, store, poll -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    Outcome outcome = poll.withdraw(player.getUUID().toString(), now());
                    if (!outcome.ok()) {
                        return fail(ctx, outcome.message());
                    }
                    store.save(poll);
                    return succeed(ctx, outcome.message());
                })))

                .then(Commands.literal("kit")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> withPoll(ctx, store, poll -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (poll.rules().claimable()) {
                                give(player, BallotItems.plotBox(poll.key()));
                                give(player, BallotItems.plotSign(poll.key()));
                                return succeed(ctx,
                                        "Place the box at a plot, then the sign beside it.");
                            }
                            give(player, BallotItems.ballotBox(poll.key()));
                            return succeed(ctx, "Place it where people gather.");
                        })))

                .then(Commands.literal("reload")
                        .requires(BallotCommands::sourceIsOp)
                        .executes(ctx -> {
                            store.loadAll();
                            store.current().ifPresent(poll ->
                                    Signs.refresh(ctx.getSource().getServer(), poll));
                            return succeed(ctx, "Reloaded from disk.");
                        }));
    }

    // ---- helpers ------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> state(String literal,
                                                                    PollState target,
                                                                    PollStore store) {
        return Commands.literal(literal)
                .requires(BallotCommands::sourceIsOp)
                .executes(ctx -> withPoll(ctx, store, poll ->
                        after(ctx, store, poll, poll.moveTo(target, now()))));
    }

    /** There is only ever one poll, so this is a null check with a message. */
    private static int withPoll(CommandContext<CommandSourceStack> ctx, PollStore store,
                                PollAction action) throws CommandSyntaxException {
        Optional<Poll> poll = store.current();
        if (poll.isEmpty()) {
            return fail(ctx, "No vote running.");
        }
        return action.run(poll.get());
    }

    @FunctionalInterface
    private interface PollAction {
        int run(Poll poll) throws CommandSyntaxException;
    }

    /** Applies, persists, redraws the signs, and returns an organiser to settings. */
    private static int after(CommandContext<CommandSourceStack> ctx, PollStore store,
                             Poll poll, Outcome outcome) throws CommandSyntaxException {
        if (!outcome.ok()) {
            return fail(ctx, outcome.message());
        }
        store.save(poll);
        Signs.refresh(ctx.getSource().getServer(), poll);

        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && sourceIsOp(ctx.getSource())) {
            Screens.settings(player, poll);
            return 1;
        }
        return succeed(ctx, outcome.message());
    }

    private static int flip(CommandContext<CommandSourceStack> ctx, PollStore store,
                            Poll poll, String key) throws CommandSyntaxException {
        Rules r = poll.rules();
        Rules next = switch (key.toLowerCase()) {
            // Choosing the kind is also what marks it as chosen.
            case "kind" -> new Rules(true, !r.claimable(), r.allowVoteChange(),
                    r.blockSelfVote(), r.claimable(), r.maxVotesPerPlayer());
            case "chatvoting" -> new Rules(r.kindChosen(), r.claimable(), r.allowVoteChange(),
                    r.blockSelfVote(), !r.voteFromChat(), r.maxVotesPerPlayer());
            case "changevotes" -> new Rules(r.kindChosen(), r.claimable(), !r.allowVoteChange(),
                    r.blockSelfVote(), r.voteFromChat(), r.maxVotesPerPlayer());
            case "selfvote" -> new Rules(r.kindChosen(), r.claimable(), r.allowVoteChange(),
                    !r.blockSelfVote(), r.voteFromChat(), r.maxVotesPerPlayer());
            default -> null;
        };
        if (next == null) {
            return fail(ctx, "Not a setting.");
        }
        return after(ctx, store, poll, poll.setRules(next, now()));
    }

    private static int closeAndAnnounce(CommandContext<CommandSourceStack> ctx,
                                        PollStore store, Poll poll) {
        Outcome outcome = poll.close(now());
        if (!outcome.ok()) {
            return fail(ctx, outcome.message());
        }
        store.save(poll);
        Signs.refresh(ctx.getSource().getServer(), poll);
        Screens.announceResults(ctx.getSource().getServer(), poll);
        return 1;
    }

    private static int confirmDelete(CommandContext<CommandSourceStack> ctx, PollStore store)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Poll> poll = store.current();
        if (poll.isEmpty()) {
            return fail(ctx, "No vote running.");
        }
        player.sendSystemMessage(Screens.bad(
                "Delete \"" + poll.get().title() + "\" and its whole history?"));
        player.sendSystemMessage(Component.literal("  ")
                .append(Screens.button("[ Yes, delete it ]", "/ballot _ delete confirm")));
        return 1;
    }

    private static int doDelete(CommandContext<CommandSourceStack> ctx, PollStore store) {
        Optional<Poll> poll = store.current();
        if (poll.isEmpty() || !store.delete(poll.get().key())) {
            return fail(ctx, "Couldn't delete that.");
        }
        return succeed(ctx, "Deleted.");
    }

    static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static int succeed(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Screens.good(message), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Screens.bad(message));
        return 0;
    }

    private static boolean sourceIsOp(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    private static boolean isOp(CommandContext<CommandSourceStack> ctx) {
        return sourceIsOp(ctx.getSource());
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
