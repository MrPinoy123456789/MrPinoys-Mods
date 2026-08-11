package ballot.mc;

import ballot.Entry;
import ballot.Poll;
import ballot.PollState;
import ballot.Rules;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything a player reads.
 *
 * <p>Three constraints shape all of it. Chat scrolls, so every screen must be
 * re-summonable and nothing may depend on the reader still being able to see something
 * from earlier. There is no cursor, so selection happens by clicking words. And a
 * phone-sized chat window shows about ten lines, so anything longer is scrolled past
 * rather than read.
 *
 * <p>Hence the hard limits observed here: never more than four buttons in a row, never
 * more than roughly eight lines, and a refresh on every page.
 */
public final class Screens {

    private Screens() {}

    private static final int MAX_ROWS = 8;

    // ---- a poll page ------------------------------------------------------

    public static void show(ServerPlayer player, Poll poll, boolean isOp) {
        String uuid = player.getUUID().toString();

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(header(poll.title()));
        if (!poll.description().isBlank()) {
            player.sendSystemMessage(plain(poll.description()));
        }
        player.sendSystemMessage(dim(statusLine(poll)));

        if (poll.entries().isEmpty()) {
            player.sendSystemMessage(dim(isOp
                    ? (poll.rules().claimable()
                            ? "No plots yet. Add them from the noticeboard."
                            : "No options yet. Add them from the noticeboard.")
                    : "Nothing to vote on yet."));
        } else {
            Map<Integer, Integer> tally = poll.tally();
            int shown = 0;
            for (Entry entry : poll.entries()) {
                if (shown++ >= MAX_ROWS) {
                    player.sendSystemMessage(dim("...and "
                            + (poll.entries().size() - MAX_ROWS) + " more"));
                    break;
                }
                player.sendSystemMessage(entryRow(poll, entry, uuid, tally));
            }
        }

        MutableComponent actions = Component.literal("  ");
        actions.append(button("[ Refresh ]", "/ballot _ show"));
        actions.append(Component.literal("  "));
        actions.append(button("[ Back ]", "/ballot"));
        player.sendSystemMessage(actions);

        // Deliberately no organiser row here. State changes are one-way doors, and the
        // wand is what says "I mean to act as the organiser" — an op glancing at the
        // page should see exactly what everyone else sees.
        if (isOp) {
            player.sendSystemMessage(dim("  Hold the wand and right-click a poll block "
                    + "to manage this."));
        }
    }

    private static Component entryRow(Poll poll, Entry entry, String uuid,
                                      Map<Integer, Integer> tally) {
        MutableComponent row = Component.literal("  " + entry.id() + ". ")
                .withStyle(ChatFormatting.DARK_GRAY);

        row.append(Component.literal(entry.label()).withStyle(ChatFormatting.WHITE));

        if (entry.isClaimed()) {
            row.append(dim("  " + entry.ownerName()));
        }

        switch (poll.state()) {
            case OPEN -> {
                if (poll.rules().claimable() && !entry.isClaimed()) {
                    row.append(Component.literal("  "));
                    row.append(button("[ Claim ]", "/ballot _ take " + entry.id()));
                }
            }
            case VOTING -> {
                boolean mine = poll.voteOf(uuid).map(v -> v == entry.id()).orElse(false);
                if (mine) {
                    row.append(Component.literal("  ← your vote")
                            .withStyle(ChatFormatting.GREEN));
                } else if (poll.rules().voteFromChat()) {
                    row.append(Component.literal("  "));
                    row.append(button("[ Vote ]", "/ballot _ cast " + entry.id()));
                }
            }
            case CLOSED, ARCHIVED -> {
                int count = tally.getOrDefault(entry.id(), 0);
                boolean won = poll.winners().contains(entry.id());
                row.append(Component.literal("  " + count + (count == 1 ? " vote" : " votes"))
                        .withStyle(won ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
            }
            default -> { }
        }
        return row;
    }

    private static String statusLine(Poll poll) {
        return switch (poll.state()) {
            case DRAFT -> "Draft — not open yet.";
            case OPEN -> poll.rules().claimable()
                    ? "Claim a plot and build. Voting hasn't started."
                    : "Voting hasn't started yet.";
            case VOTING -> poll.rules().voteFromChat()
                    ? "Voting is open." + remaining(poll)
                    : "Voting is open — cast it at the "
                            + (poll.rules().claimable() ? "plot boxes." : "ballot box.")
                            + remaining(poll);
            case CLOSED, ARCHIVED -> {
                List<Integer> winners = poll.winners();
                if (winners.isEmpty()) {
                    yield "Closed. Nobody voted.";
                }
                String names = winners.stream()
                        .map(id -> poll.entry(id).map(Entry::label).orElse("?"))
                        .reduce((a, b) -> a + " and " + b).orElse("?");
                yield (winners.size() > 1 ? "Tied: " : "Winner: ") + names;
            }
        };
    }

    /** "· 3d left". Empty when there is no deadline, so it can be appended blindly. */
    static String remaining(Poll poll) {
        if (poll.closesAt() <= 0) {
            return "";
        }
        long seconds = poll.closesAt() - Instant.now().getEpochSecond();
        if (seconds <= 0) {
            return " · closing";
        }
        Duration d = Duration.ofSeconds(seconds);
        if (d.toDays() >= 1) {
            return " · " + d.toDays() + "d left";
        }
        if (d.toHours() >= 1) {
            return " · " + d.toHours() + "h left";
        }
        return " · " + Math.max(1, d.toMinutes()) + "m left";
    }

    // ---- results ----------------------------------------------------------

    /**
     * Sent to everyone when a vote closes. Closing is the moment the whole server
     * cares about, so it goes out unprompted rather than waiting to be looked up.
     */
    public static void announceResults(MinecraftServer server, Poll poll) {
        Map<Integer, Integer> tally = poll.tally();
        List<Integer> winners = poll.winners();

        broadcast(server, Component.empty());
        broadcast(server, header(poll.title() + " — result"));
        Chime.resultsAnnounced(server);

        if (winners.isEmpty()) {
            broadcast(server, dim("Closed with no votes cast."));
            return;
        }

        for (int id : winners) {
            Entry entry = poll.entry(id).orElse(null);
            if (entry == null) {
                continue;
            }
            MutableComponent line = Component.literal(winners.size() > 1 ? "  Tied: " : "  Winner: ")
                    .withStyle(ChatFormatting.GOLD);
            line.append(Component.literal(entry.label()).withStyle(ChatFormatting.WHITE));
            if (entry.isClaimed()) {
                line.append(dim("  by " + entry.ownerName()));
            }
            line.append(dim("  " + tally.getOrDefault(id, 0) + " votes"));
            broadcast(server, line);
        }

        // The runners-up, so a close result reads as close.
        int shown = 0;
        for (Map.Entry<Integer, Integer> row : poll.ranked()) {
            if (winners.contains(row.getKey()) || row.getValue() == 0) {
                continue;
            }
            if (shown++ >= 3) {
                break;
            }
            Entry entry = poll.entry(row.getKey()).orElse(null);
            if (entry != null) {
                broadcast(server, dim("    " + entry.label()
                        + (entry.isClaimed() ? " (" + entry.ownerName() + ")" : "")
                        + " — " + row.getValue()));
            }
        }

        broadcast(server, dim("  " + poll.votes().size() + " votes cast in total"));
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    // ---- settings: the organiser's one screen -----------------------------

    /**
     * Everything an organiser can change, on one page, each row clickable.
     *
     * <p>This exists because the alternative was a dozen memorised commands. A field
     * whose value has not been decided says so plainly rather than showing a plausible
     * default, since a default that looks like a choice is worse than an obvious blank.
     */
    public static void settings(ServerPlayer player, Poll poll) {
        Rules r = poll.rules();
        String id = poll.key();

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(header(poll.title()));
        player.sendSystemMessage(dim("  " + (r.claimable() ? "Buildoff (build competition)" : "Poll")
                + " · " + poll.state().name().toLowerCase()
                + " · " + poll.entries().size()
                + (r.claimable() ? " plots" : " options")));
        player.sendSystemMessage(Component.empty());

        field(player, "Name", poll.isUnnamed() ? null : poll.title(),
                suggest("[ set ]", "/ballot _ name "));
        field(player, "Description", poll.description().isBlank() ? null : poll.description(),
                suggest("[ set ]", "/ballot _ desc "));

        field(player, "Closes", poll.closesAt() > 0 ? deadlineText(poll) : null,
                suggest("[ set ]", "/ballot _ deadline "));
        field(player, "Voting", r.voteFromChat() ? "chat or boxes" : "boxes only",
                button("[ change ]", "/ballot _ set chatvoting"));
        field(player, "Changing votes", r.allowVoteChange() ? "allowed" : "final once cast",
                button("[ change ]", "/ballot _ set changevotes"));
        field(player, "Self-votes", r.blockSelfVote() ? "blocked" : "allowed",
                button("[ change ]", "/ballot _ set selfvote"));
        field(player, r.claimable() ? "Plots" : "Options",
                Integer.toString(poll.entries().size()),
                button(r.claimable() ? "[ get a plot kit ]" : "[ get the ballot box ]",
                        "/ballot _ kit"));

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(settingsActions(poll, id));
    }

    /** One row: label, value or a plain "not set", and the control. */
    private static void field(ServerPlayer player, String label, String value,
                              Component control) {
        MutableComponent row = Component.literal("  " + label + ": ")
                .withStyle(ChatFormatting.GRAY);
        row.append(value == null
                ? Component.literal("not set").withStyle(ChatFormatting.DARK_RED)
                : Component.literal(value).withStyle(ChatFormatting.WHITE));
        row.append(Component.literal("  ")).append(control);
        player.sendSystemMessage(row);
    }

    /** At most three buttons, and the state action always comes first. */
    private static Component settingsActions(Poll poll, String id) {
        MutableComponent row = Component.literal("  ");
        switch (poll.state()) {
            case DRAFT -> row.append(button("[ Open it ]", "/ballot _ open"));
            case OPEN -> row.append(button("[ Start voting ]", "/ballot _ voting"));
            case VOTING -> row.append(button("[ Close voting ]", "/ballot _ close"));
            case CLOSED -> row.append(button("[ Archive ]", "/ballot _ archive"));
            case ARCHIVED -> row.append(dim("Archived."));
        }
        row.append(Component.literal("  "))
                .append(button("[ Preview ]", "/ballot _ preview"))
                .append(Component.literal("  "))
                .append(Component.literal("[ Delete ]").withStyle(style -> style
                        .withColor(ChatFormatting.DARK_RED)
                        .withClickEvent(new ClickEvent.RunCommand("/ballot _ delete"))));
        return row;
    }

    // ---- preview ----------------------------------------------------------

    /**
     * Everything the organiser chose, restated in one place in plain language. Opening
     * is the point of no return for entries, so this is where mistakes get caught —
     * and it is the highest-value screen in the mod for exactly that reason.
     */
    public static void preview(ServerPlayer player, Poll poll) {
        Rules r = poll.rules();

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(header(poll.title() + " · preview"));

        int entries = poll.entries().size();
        if (r.claimable()) {
            int boxed = entries - poll.entriesWithoutStations().size();
            player.sendSystemMessage(dim("  " + entries + (entries == 1 ? " plot" : " plots")
                    + ", " + boxed + " with a ballot box"));
        } else {
            player.sendSystemMessage(dim("  " + entries
                    + (entries == 1 ? " option" : " options")
                    + (poll.hasBallotBox() ? ", ballot box placed" : ", no ballot box yet")));
        }
        player.sendSystemMessage(dim("  " + (r.claimable()
                ? "Buildoff — a build competition where players claim plots and build"
                : "Poll — you wrote the options")));
        player.sendSystemMessage(dim("  Voting: " + (r.voteFromChat()
                ? "from chat or at a box" : "at a ballot box only")));
        player.sendSystemMessage(dim("  " + (r.allowVoteChange()
                ? "Voters may change their mind" : "Votes are final once cast")));
        player.sendSystemMessage(dim("  Closes: " + deadlineText(poll)));

        player.sendSystemMessage(Component.literal("  ")
                .append(button("[ Looks right — open it ]", "/ballot _ open"))
                .append(Component.literal("  "))
                .append(button("[ Settings ]", "/ballot _ settings")));
    }

    static String deadlineText(Poll poll) {
        if (poll.closesAt() <= 0) {
            return "no deadline — you'll close it by hand";
        }
        return java.time.Instant.ofEpochSecond(poll.closesAt())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
                + remaining(poll);
    }

    // ---- pieces -----------------------------------------------------------

    /**
     * Puts a half-written command in the player's chat box instead of running one.
     * The right shape when the player has to supply the rest — a title, say.
     */
    public static MutableComponent suggest(String label, String command) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(command)));
    }

    public static MutableComponent button(String label, String command) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    public static Component header(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    public static Component plain(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    public static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    public static Component good(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    public static Component bad(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }
}
