package quizengine.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import quizengine.Command;
import quizengine.Engine;
import quizengine.Event;
import quizengine.Option;
import quizengine.Phase;
import quizengine.Round;
import quizengine.RoundResult;
import quizengine.RoundType;
import quizengine.mc.dialog.DialogKit;
import quizengine.mc.dialog.QuizDialogs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator v1.
 *
 * <p>Owns everything the engine refuses to: when phases advance, who is in the round,
 * how it looks, and what survives a restart. It drives the engine through the same
 * five commands a test harness would, which is what keeps the engine swappable.
 *
 * <p>All state here lives on the server main thread. There is no synchronisation
 * because there is no concurrency — Minecraft ticks single-threaded, and the only
 * background work is the leaderboard's disk flush.
 */
public final class Orchestrator {

    private static final int TICKS_PER_SECOND = 20;

    private final Content content;
    private final Leaderboard leaderboard;
    private final AfkTracker afk = new AfkTracker();

    private Round round;
    private int deadlineTick = -1;
    private int nextAutoStartTick = -1;
    private final Set<UUID> notified = new HashSet<>();

    public Orchestrator(Content content, Leaderboard leaderboard) {
        this.content = content;
        this.leaderboard = leaderboard;
    }

    // ---- state ------------------------------------------------------------

    public boolean isRunning() {
        return round != null && !round.phase().isTerminal();
    }

    public Round current() {
        return round;
    }

    // ---- the tick loop ----------------------------------------------------

    /** Samples activity, drains queued audio, and advances or auto-starts rounds at their deadlines. */
    public void tick(MinecraftServer server) {
        Jingles.tick(server);
        int now = server.getTickCount();
        afk.sample(server, now);

        if (!isRunning()) {
            maybeAutoStart(server, now);
            return;
        }
        if (deadlineTick >= 0 && now >= deadlineTick) {
            advance(server);
        }
    }

    private void maybeAutoStart(MinecraftServer server, int now) {
        int interval = content.timings().autoStartSeconds();
        if (interval <= 0) {
            return;
        }
        if (nextAutoStartTick < 0) {
            nextAutoStartTick = now + interval * TICKS_PER_SECOND;
            return;
        }
        if (now >= nextAutoStartTick) {
            nextAutoStartTick = now + interval * TICKS_PER_SECOND;
            int afkThresholdTicks = content.timings().afkThresholdSeconds() * TICKS_PER_SECOND;
            if (!server.getPlayerList().getPlayers().isEmpty()
                    && !afk.allIdle(server, now, afkThresholdTicks)) {
                start(server, RoundType.TRIVIA);
            }
        }
    }

    /** Fire the next phase command immediately, whatever it is. */
    public void advance(MinecraftServer server) {
        if (!isRunning()) {
            return;
        }
        Content.Timings t = content.timings();

        switch (round.phase()) {
            case SUBMITTING -> {
                if (round.type() == RoundType.TRIVIA) {
                    issue(server, new Command.Settle());
                } else {
                    issue(server, new Command.CloseSubmissions());
                    broadcast(server, Component.literal("Submissions are closed.")
                            .withStyle(ChatFormatting.GRAY));
                    showSubmittedOptions(server);
                    schedule(server, t.quiplashClosedSeconds());
                }
            }
            case CLOSED -> {
                issue(server, new Command.OpenVoting());
                broadcast(server, Component.literal("Voting is open — click an answer below.")
                        .withStyle(ChatFormatting.GOLD));
                Jingles.playToAll(server, Jingles.votingOpen());
                showSubmittedOptions(server);
                schedule(server, t.quiplashVoteSeconds());
            }
            case VOTING -> issue(server, new Command.Settle());
            default -> { }
        }
    }

    // ---- starting ---------------------------------------------------------

    public boolean start(MinecraftServer server, RoundType type) {
        if (isRunning()) {
            return false;
        }
        Round next = type == RoundType.TRIVIA
                ? content.randomTrivia()
                : content.randomQuiplash();
        if (next == null) {
            QuizMod.LOG.warn("No content available for {} round", type);
            return false;
        }

        round = next;
        notified.clear();

        Content.Timings t = content.timings();
        schedule(server, type == RoundType.TRIVIA
                ? t.triviaSeconds()
                : t.quiplashSubmitSeconds());

        announce(server);
        Jingles.playToAll(server, Jingles.roundStart());
        return true;
    }

    public void cancel() {
        round = null;
        deadlineTick = -1;
        Jingles.clear();
    }

    public void cancel(MinecraftServer server) {
        if (isRunning()) {
            broadcast(server, Component.literal("The round was cancelled. No points awarded.")
                    .withStyle(ChatFormatting.RED));
        }
        cancel();
    }

    // ---- player input -----------------------------------------------------

    /** @return a message to show the player, or null if the input was accepted silently. */
    public Component submit(ServerPlayer player, int optionIndex) {
        return apply(player, new Command.Submit(player.getUUID(), optionIndex),
                "Answer locked in.");
    }

    public Component submitText(ServerPlayer player, String text) {
        return apply(player, new Command.SubmitText(player.getUUID(), text),
                "Answer submitted. Nobody can see it until voting opens.");
    }

    public Component vote(ServerPlayer player, int optionIndex) {
        return apply(player, new Command.Vote(player.getUUID(), optionIndex),
                "Vote locked in.");
    }

    private Component apply(ServerPlayer player, Command command, String successText) {
        if (!isRunning()) {
            return Component.literal("No round is running right now.")
                    .withStyle(ChatFormatting.RED);
        }
        // Trivia answers fire far more often than Quiplash submissions/votes now that
        // trivia auto-starts every half hour — skip the jingle for those specifically.
        boolean playJingle = !(command instanceof Command.Submit);
        Engine.Result result = Engine.apply(round, command);
        round = result.round();

        for (Event e : result.events()) {
            if (e instanceof Event.Rejected r) {
                if (playJingle) {
                    Jingles.play(player, Jingles.rejected());
                }
                return Component.literal(explain(r.reason())).withStyle(ChatFormatting.RED);
            }
        }
        if (playJingle) {
            Jingles.play(player, Jingles.accepted());
        }
        return Component.literal(successText).withStyle(ChatFormatting.GREEN);
    }

    private static String explain(Event.Reason reason) {
        return switch (reason) {
            case WRONG_PHASE -> "That isn't open right now.";
            case OPTION_OUT_OF_RANGE -> "That isn't one of the options.";
            case ALREADY_SUBMITTED -> "You've already answered. No changing your mind.";
            case BLANK_SUBMISSION -> "You need to actually write something.";
            case SUBMISSION_TOO_LONG ->
                    "Too long — keep it under " + Engine.MAX_SUBMISSION_LENGTH + " characters.";
            case DUPLICATE_SUBMISSION ->
                    "Someone already wrote that one. Come up with a new one.";
            case ALREADY_VOTED -> "You've already voted.";
            case SELF_VOTE -> "You can't vote for your own pick.";
            case OPTION_NOT_SUBMITTED -> "Nobody picked that one, so it isn't on the ballot.";
            case NOT_APPLICABLE_TO_ROUND_TYPE -> "That doesn't apply to this kind of round.";
        };
    }

    // ---- engine plumbing --------------------------------------------------

    private void issue(MinecraftServer server, Command command) {
        Engine.Result result = Engine.apply(round, command);
        round = result.round();

        for (Event e : result.events()) {
            if (e instanceof Event.Settled settled) {
                payOut(server, settled.result());
                announceResult(server, settled.result());
                deadlineTick = -1;
            } else if (e instanceof Event.Rejected r) {
                QuizMod.LOG.warn("Orchestrator issued a rejected command: {}", r.reason());
            }
        }
    }

    private void schedule(MinecraftServer server, int seconds) {
        deadlineTick = server.getTickCount() + seconds * TICKS_PER_SECOND;
    }

    // ---- payouts ----------------------------------------------------------

    private void payOut(MinecraftServer server, RoundResult result) {
        Set<UUID> winners = winnersOf(result);
        Map<UUID, Integer> diamonds = new HashMap<>();
        Map<UUID, Integer> blocks = new HashMap<>();
        collectItemRewards(result, diamonds, blocks);

        for (Map.Entry<UUID, Integer> payout : result.payouts().entrySet()) {
            UUID id = payout.getKey();
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            String name = online != null ? online.getName().getString() : null;

            leaderboard.award(id, name, payout.getValue(), winners.contains(id));

            if (online == null) {
                // Points still record. Items are skipped — there is no inventory to
                // put them in, and a mail queue is more machinery than this needs.
                continue;
            }

            int diamondCount = diamonds.getOrDefault(id, 0);
            int blockCount = blocks.getOrDefault(id, 0);
            grant(online, Items.DIAMOND, diamondCount);
            grant(online, Items.DIAMOND_BLOCK, blockCount);

            if (blockCount > 0) {
                Jingles.play(online, Jingles.winner());
            } else if (winners.contains(id) || wasRight(result, id)) {
                Jingles.play(online, Jingles.correct());
            } else {
                Jingles.play(online, Jingles.wrong());
            }

            online.sendSystemMessage(Component.literal("+" + payout.getValue() + " points")
                    .withStyle(ChatFormatting.AQUA)
                    .append(itemSummary(diamondCount, blockCount)));
        }
    }

    /** Right in the sense that earns the chime: correct trivia, or backing the winner. */
    private static boolean wasRight(RoundResult result, UUID id) {
        if (result instanceof RoundResult.Trivia t) {
            return t.correctPlayers().contains(id);
        }
        if (result instanceof RoundResult.Quiplash q) {
            return q.winningVoters().contains(id);
        }
        return false;
    }

    /** Mirrors the point payouts: participation, being right, and writing the winner. */
    private void collectItemRewards(RoundResult result,
                                    Map<UUID, Integer> diamonds,
                                    Map<UUID, Integer> blocks) {
        Content.Rewards rw = content.rewards();

        if (result instanceof RoundResult.Trivia t) {
            for (UUID id : round.submissions().keySet()) {
                diamonds.merge(id, rw.participationDiamonds(), Integer::sum);
            }
            for (UUID id : t.correctPlayers()) {
                diamonds.merge(id, rw.triviaCorrectDiamonds(), Integer::sum);
            }
        } else if (result instanceof RoundResult.Quiplash q) {
            for (UUID id : round.submissions().keySet()) {
                diamonds.merge(id, rw.participationDiamonds(), Integer::sum);
            }
            for (UUID id : q.winningVoters()) {
                diamonds.merge(id, rw.correctDiamonds(), Integer::sum);
            }
            for (UUID id : q.writers().values()) {
                blocks.merge(id, rw.winnerDiamondBlocks(), Integer::sum);
            }
        }
    }

    /** Adds to the inventory, dropping at the player's feet if it is full. */
    private static void grant(ServerPlayer player, Item item, int count) {
        int remaining = count;
        while (remaining > 0) {
            int size = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, size);
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= size;
        }
    }

    private static Component itemSummary(int diamonds, int blocks) {
        if (diamonds == 0 && blocks == 0) {
            return Component.empty();
        }
        MutableComponent text = Component.literal("  ");
        if (diamonds > 0) {
            text.append(Component.literal("+" + diamonds + " diamond"
                            + (diamonds == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.AQUA));
        }
        if (blocks > 0) {
            text.append(Component.literal((diamonds > 0 ? ", " : "")
                            + "+" + blocks + " diamond block"
                            + (blocks == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.GOLD));
        }
        return text;
    }

    private static Set<UUID> winnersOf(RoundResult result) {
        if (result instanceof RoundResult.Trivia t) {
            return new HashSet<>(t.correctPlayers());
        }
        if (result instanceof RoundResult.Quiplash q) {
            return new HashSet<>(q.writers().values());
        }
        return Set.of();
    }

    // ---- presentation -----------------------------------------------------

    private void announce(MinecraftServer server) {
        if (round.type() == RoundType.TRIVIA) {
            announceTrivia(server);
        } else {
            announceQuiplash(server);
        }
    }

    /** One combined message — header, prompt, options, and hint in a single broadcast. */
    private void announceTrivia(MinecraftServer server) {
        MutableComponent message = Component.literal("Trivia! ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(round.prompt()).withStyle(ChatFormatting.WHITE));
        for (Option option : round.options()) {
            message.append(Component.literal("\n"))
                    .append(clickable(option.index(), option.text(), "answer"));
        }
        message.append(Component.literal("\nClick an option to lock it in, or ")
                        .withStyle(ChatFormatting.GRAY))
                .append(opens("[ Open the question ]", QuizDialogs.answer(round)));
        broadcast(server, message);
    }

    private void announceQuiplash(MinecraftServer server) {
        broadcast(server, Component.empty());
        broadcast(server, Component.literal("Hot take!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        broadcast(server, Component.literal(round.prompt())
                .withStyle(ChatFormatting.WHITE));
        // The dialog leads, because typing the answer into chat is how it gets spoiled:
        // one forgotten "/quiz submit" broadcasts it to the whole server.
        broadcast(server, Component.literal("  ")
                .append(opens("[ Write your answer ]", QuizDialogs.write(round)))
                .append(Component.literal("  or type ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/quiz submit <your answer>")
                        .withStyle(ChatFormatting.YELLOW)));
    }

    /** Between submissions closing and voting opening: show what people picked. */
    private void showSubmittedOptions(MinecraftServer server) {
        List<Integer> submitted = round.submittedOptions();
        if (submitted.isEmpty()) {
            broadcast(server, Component.literal("Nobody wrote anything. Round skipped.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        broadcast(server, Component.literal("On the ballot:")
                .withStyle(ChatFormatting.GOLD));
        for (int index : submitted) {
            broadcast(server, clickable(index, round.optionText(index), "vote"));
        }
        // Only once voting is actually open. This same list is also shown in the pause
        // between submissions closing and the ballot opening, where there is nothing
        // yet to click.
        if (round.phase() == Phase.VOTING) {
            broadcast(server, Component.literal("  ")
                    .append(opens("[ Open the ballot ]", QuizDialogs.ballot(round))));
        }
    }

    private void announceResult(MinecraftServer server, RoundResult result) {
        if (result instanceof RoundResult.Skipped s) {
            broadcast(server, Component.literal("Round skipped — " + s.reason() + ".")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (result instanceof RoundResult.Trivia t) {
            broadcast(server, Component.literal("The answer was: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(round.optionText(t.correctOption()))
                            .withStyle(ChatFormatting.GREEN)));
            broadcast(server, Component.literal(t.correctPlayers().size() + " got it right.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (result instanceof RoundResult.Quiplash q) {
            for (int option : q.winningOptions()) {
                UUID writerId = q.writers().get(option);
                ServerPlayer writer = writerId == null
                        ? null : server.getPlayerList().getPlayer(writerId);
                String writerName = writer != null
                        ? writer.getName().getString() : "someone offline";

                broadcast(server, Component.literal("Winner: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(round.optionText(option))
                                .withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(
                                        "  — called by " + writerName
                                                + ", " + q.winningVoteCount() + " votes")
                                .withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    /**
     * One numbered, clickable option line. The click runs a command, which is
     * delivered to the server privately — other players never see the choice.
     */
    private static Component clickable(int index, String text, String verb) {
        String command = "/quiz " + verb + " " + (index + 1);
        return Component.literal("  [" + (index + 1) + "] ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(text)
                        .withStyle(style -> style
                                .withColor(ChatFormatting.YELLOW)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(command))));
    }

    /**
     * A chat button that opens a dialog. The dialog travels inside the component, so
     * there is no command behind it and no packet to send — and the surrounding
     * broadcast is unchanged, which is the point. Nobody's screen is seized; a player
     * who would rather type still can.
     */
    private static Component opens(String label, Dialog dialog) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(DialogKit.open(dialog)));
    }

    private void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    /** A player who joins mid-round gets the prompt once. */
    public void greet(ServerPlayer player) {
        if (!isRunning() || round.phase() != Phase.SUBMITTING) {
            return;
        }
        if (notified.add(player.getUUID())) {
            player.sendSystemMessage(Component.literal("A round is running: " + round.prompt())
                    .withStyle(ChatFormatting.GOLD));
            if (round.type() == RoundType.TRIVIA) {
                for (Option option : round.options()) {
                    player.sendSystemMessage(clickable(option.index(), option.text(), "answer"));
                }
                player.sendSystemMessage(Component.literal("  ")
                        .append(opens("[ Open the question ]", QuizDialogs.answer(round))));
            } else {
                player.sendSystemMessage(Component.literal("  ")
                        .append(opens("[ Write your answer ]", QuizDialogs.write(round)))
                        .append(Component.literal("  or type ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("/quiz submit <your answer>")
                                .withStyle(ChatFormatting.YELLOW)));
            }
        }
    }
}