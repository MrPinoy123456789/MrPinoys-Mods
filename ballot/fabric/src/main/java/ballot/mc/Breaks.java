package ballot.mc;

import ballot.Entry;
import ballot.Poll;
import ballot.PollState;
import ballot.Station;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the poll honest when someone breaks a bound block.
 *
 * <p>Without this, breaking a ballot box leaves an entry nobody can vote for and a
 * station pointing at air — invisible until the vote goes wrong. Bindings should not
 * outlive the blocks they describe.
 */
public final class Breaks {

    private Breaks() {}

    public static void register(PollStore store) {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            Optional<Poll> found = store.current();
            if (found.isEmpty()) {
                return;
            }
            Poll poll = found.get();
            String dimension = level.dimension().identifier().toString();

            Optional<Station> station =
                    poll.stationAt(dimension, pos.getX(), pos.getY(), pos.getZ());
            if (station.isEmpty()) {
                return;
            }
            handle(store, serverPlayer, poll, station.get(), dimension, pos);
        });
    }

    private static void handle(PollStore store, ServerPlayer player, Poll poll,
                               Station station, String dimension, BlockPos pos) {
        long now = Instant.now().getEpochSecond();
        poll.unbindStation(dimension, pos.getX(), pos.getY(), pos.getZ(), now);

        if (station.isLectern()) {
            player.sendSystemMessage(Screens.dim("Noticeboard unbound."));
            store.save(poll);
            return;
        }

        if (station.isBallot()) {
            // The poll's one voting box. No entry goes with it — place another to
            // give people somewhere to vote again.
            store.save(poll);
            player.sendSystemMessage(Screens.bad(
                    "That was the ballot box. Nobody can vote until you place another."));
            return;
        }

        if (station.isSign()) {
            player.sendSystemMessage(Screens.dim(
                    "Sign unbound from plot " + station.entryId() + "."));
            store.save(poll);
            return;
        }

        // A ballot box. Before voting the entry goes with it; afterwards it cannot,
        // because votes have already been cast for it.
        Optional<Entry> entry = poll.entry(station.entryId());
        if (poll.state() == PollState.DRAFT || poll.state() == PollState.OPEN) {
            String owner = entry.map(Entry::ownerName).orElse(null);
            poll.removeEntry(station.entryId(), now);
            store.save(poll);

            player.sendSystemMessage(Screens.dim("Plot " + station.entryId() + " removed."));
            if (owner != null) {
                // Silently deleting somebody's claimed plot is how organisers lose trust.
                player.sendSystemMessage(Screens.bad("That plot was claimed by " + owner + "."));
                notifyOwner(player, entry.get(), poll);
            }
            return;
        }

        store.save(poll);
        player.sendSystemMessage(Screens.bad(
                "Voting has started, so plot " + station.entryId()
                        + " still exists — but it has nowhere to vote now."));
        player.sendSystemMessage(Screens.dim(
                "Take another plot kit from the noticeboard to give it a box back."));
    }

    private static void notifyOwner(ServerPlayer breaker, Entry entry, Poll poll) {
        try {
            java.util.UUID owner = java.util.UUID.fromString(entry.owner());
            ServerPlayer online = breaker.level().getServer().getPlayerList().getPlayer(owner);
            if (online != null && !online.getUUID().equals(breaker.getUUID())) {
                online.sendSystemMessage(Screens.bad(
                        "Your plot in \"" + poll.title() + "\" was removed."));
            }
        } catch (RuntimeException ignored) {
            // A malformed owner id is not worth failing a block break over.
        }
    }
}
