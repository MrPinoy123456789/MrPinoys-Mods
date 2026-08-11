package ballot.mc;

import ballot.Entry;
import ballot.Poll;
import ballot.PollState;
import ballot.Station;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Plot signs.
 *
 * <p>Four lines of roughly fifteen characters is not much, so the sign carries state
 * and identity while the chat carries detail. Organisers never edit these by hand — the
 * mod rewrites every bound sign whenever anything about the poll changes.
 *
 * <p>Long titles are truncated with an ellipsis rather than silently cut, so a player
 * can see that there is more and go read it in chat.
 */
public final class Signs {

    private Signs() {}

    private static final int LINE_WIDTH = 15;

    /** Rewrites every sign bound to this poll. Cheap: a handful of block updates. */
    public static void refresh(MinecraftServer server, Poll poll) {
        if (server == null) {
            return;
        }
        for (Station station : poll.stationsOfKind(Station.SIGN)) {
            poll.entry(station.entryId())
                    .ifPresent(entry -> write(server, poll, entry, station));
        }
    }

    private static void write(MinecraftServer server, Poll poll, Entry entry, Station station) {
        ServerLevel level = levelOf(server, station.dimension());
        if (level == null) {
            return;
        }
        BlockPos pos = new BlockPos(station.x(), station.y(), station.z());
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return;   // somebody broke it; the binding is harmless until rebound
        }

        String[] lines = linesFor(poll, entry);
        ChatFormatting colour = colourFor(poll, entry);

        SignText text = new SignText();
        for (int i = 0; i < 4; i++) {
            text = text.setMessage(i, Component.literal(lines[i]).withStyle(colour));
        }
        sign.setText(text, true);
        sign.setText(text, false);

        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }

    /**
     * The state machine, in four lines.
     *
     * <p>Deliberately says what to <em>do</em> rather than what the state is called.
     * "Right-click to claim" is worth more to a passing player than "OPEN".
     */
    private static String[] linesFor(Poll poll, Entry entry) {
        boolean winner = poll.state().isFinished() && poll.winners().contains(entry.id());
        int votes = poll.tally().getOrDefault(entry.id(), 0);

        return switch (poll.state()) {
            case DRAFT -> new String[]{
                    "[ Plot " + entry.id() + " ]", "not open", "yet", ""};

            case OPEN -> entry.isClaimed()
                    ? new String[]{
                            "[ Plot " + entry.id() + " ]",
                            fit(entry.label()),
                            fit(entry.ownerName()),
                            "claimed"}
                    : new String[]{
                            "[ Plot " + entry.id() + " ]",
                            "unclaimed",
                            "right-click",
                            "to claim"};

            case VOTING -> new String[]{
                    "[ Plot " + entry.id() + " ]",
                    fit(entry.label()),
                    fit(entry.isClaimed() ? entry.ownerName() : ""),
                    "vote at the box"};

            case CLOSED, ARCHIVED -> new String[]{
                    winner ? "[ WINNER ]" : "[ Plot " + entry.id() + " ]",
                    fit(entry.label()),
                    fit(entry.isClaimed() ? entry.ownerName() : ""),
                    votes + (votes == 1 ? " vote" : " votes")};
        };
    }

    private static ChatFormatting colourFor(Poll poll, Entry entry) {
        if (poll.state().isFinished()) {
            return poll.winners().contains(entry.id())
                    ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY;
        }
        if (poll.state() == PollState.OPEN && !entry.isClaimed()) {
            return ChatFormatting.DARK_GREEN;
        }
        return ChatFormatting.BLACK;
    }

    /** Visible truncation: a player should be able to tell there is more to read. */
    private static String fit(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= LINE_WIDTH
                ? text
                : text.substring(0, LINE_WIDTH - 1) + "\u2026";
    }

    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    Identifier.parse(dimension)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String dimensionOf(Level level) {
        return level.dimension().identifier().toString();
    }
}
