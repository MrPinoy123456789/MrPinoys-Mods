package ballot.mc;

import ballot.Poll;
import ballot.PollState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;

/**
 * Watches wall-clock deadlines.
 *
 * <p>Checked once a second rather than every tick — nothing here is urgent to the
 * frame, and a poll that closes a second late is indistinguishable from one that
 * closes on time.
 *
 * <p>Deliberately compares against {@link Instant} rather than the server's tick
 * count. A two-week poll outlives many restarts, and a tick count resets with each
 * one. A deadline that passed while the server was down fires on the next boot: late
 * is correct, skipped is not.
 */
public final class Deadlines {

    private static final int CHECK_EVERY_TICKS = 20;

    /** Announced once each, and recorded in the poll's history so a restart cannot repeat them. */
    private static final long[] WARN_AT_SECONDS = {86_400L, 3_600L};
    private static final String[] WARN_LABELS = {"a day", "an hour"};

    private final PollStore store;
    private int tickCounter;

    public Deadlines(PollStore store) {
        this.store = store;
    }

    public void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;

        long now = Instant.now().getEpochSecond();
        for (Poll poll : store.current().stream().toList()) {
            if (poll.state() != PollState.VOTING) {
                continue;
            }
            if (poll.isOverdue(now)) {
                closeOnTime(server, poll, now);
            } else {
                maybeWarn(server, poll, now);
            }
        }
    }

    private void closeOnTime(MinecraftServer server, Poll poll, long now) {
        if (!poll.close(now).ok()) {
            return;
        }
        store.save(poll);
        Signs.refresh(server, poll);
        Screens.announceResults(server, poll);
        BallotMod.LOG.info("Closed \"{}\" on its deadline", poll.key());
    }

    /**
     * Votes can change here, so a result can swing on the last day. That is intended,
     * but people should be told it is about to be too late rather than discovering it
     * afterwards.
     */
    private void maybeWarn(MinecraftServer server, Poll poll, long now) {
        long remaining = poll.secondsRemaining(now);
        if (remaining < 0) {
            return;
        }
        for (int i = 0; i < WARN_AT_SECONDS.length; i++) {
            long threshold = WARN_AT_SECONDS[i];
            if (remaining > threshold) {
                continue;
            }
            String mark = Long.toString(threshold);
            if (poll.hasWarned(mark)) {
                continue;
            }
            poll.markWarned(mark, now);
            store.save(poll);

            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(poll.title() + " closes in " + WARN_LABELS[i] + ". ")
                            .withStyle(ChatFormatting.GOLD)
                            .append(Screens.button("[ Have a look ]",
                                    "/ballot")),
                    false);
            return;
        }
    }
}
