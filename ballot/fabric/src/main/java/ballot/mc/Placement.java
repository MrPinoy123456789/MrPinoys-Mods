package ballot.mc;

import ballot.Outcome;
import ballot.Poll;
import ballot.PollState;
import ballot.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.time.Instant;
import java.util.Optional;

/**
 * Placement is binding.
 *
 * <p>Every piece an organiser is handed binds itself the moment it is put down. The
 * alternative — place it, then remember to click it — is a step people forget, and a
 * half-configured gallery is worse than no gallery.
 *
 * <p>Kept out of the mixin on purpose: the mixin is the piece most likely to break on
 * a version bump, and a thin one is far easier to diagnose.
 */
public final class Placement {

    private Placement() {}

    /** How far a plot sign may sit from the box it belongs to. */
    private static final int SIGN_RANGE = 12;

    /** Called from the mixin with the item data read before the stack was consumed. */
    public static void onPlaced(Player player, Level level, BlockPos pos,
                                String kind, String pollKey) {
        if (!(player instanceof ServerPlayer serverPlayer) || kind == null) {
            return;
        }
        PollStore store = BallotMod.store();
        if (store == null) {
            return;
        }
        Optional<Poll> found = store.get(pollKey);
        if (found.isEmpty()) {
            serverPlayer.sendSystemMessage(Screens.bad(
                    "That vote no longer exists, so this is just a block."));
            return;
        }
        Poll poll = found.get();
        String dimension = level.dimension().identifier().toString();
        long now = Instant.now().getEpochSecond();

        switch (kind) {
            case "lectern" -> lectern(serverPlayer, store, poll, dimension, pos, now);
            case "ballot_box" -> ballotBox(serverPlayer, store, poll, dimension, pos, now);
            case "plot_box" -> plotBox(serverPlayer, store, poll, dimension, pos, now);
            case "plot_sign" -> plotSign(serverPlayer, store, poll, dimension, pos, now);
            default -> { }
        }
    }

    private static void lectern(ServerPlayer player, PollStore store, Poll poll,
                                String dimension, BlockPos pos, long now) {
        poll.bindLectern(dimension, pos.getX(), pos.getY(), pos.getZ(), now);
        store.save(poll);
        player.sendSystemMessage(Screens.good("Noticeboard bound to " + poll.title() + "."));
        player.sendSystemMessage(Screens.dim(
                "Right-click it for the vote, or hold the wand for settings."));
    }

    private static void ballotBox(ServerPlayer player, PollStore store, Poll poll,
                                  String dimension, BlockPos pos, long now) {
        boolean moved = poll.hasBallotBox();
        poll.bindBallotBox(dimension, pos.getX(), pos.getY(), pos.getZ(), now);
        store.save(poll);
        player.sendSystemMessage(Screens.good(moved
                ? "Ballot box moved here."
                : "Ballot box placed. People vote here."));
    }

    /** Placing the box is what creates the plot — there is no id and no binding step. */
    private static void plotBox(ServerPlayer player, PollStore store, Poll poll,
                                String dimension, BlockPos pos, long now) {
        if (poll.state() != PollState.DRAFT && poll.state() != PollState.OPEN) {
            player.sendSystemMessage(Screens.bad(
                    "Plots are locked once voting starts — that's just a jukebox."));
            return;
        }
        Outcome created = poll.addEntry(null, now);
        if (!created.ok()) {
            player.sendSystemMessage(Screens.bad(created.message()));
            return;
        }
        int id = created.value();
        poll.bindStation(id, Station.BOX, dimension, pos.getX(), pos.getY(), pos.getZ(), now);
        store.save(poll);

        player.sendSystemMessage(Screens.good("Plot " + id + " created."));
        player.sendSystemMessage(Screens.dim("Now place its sign beside it."));
    }

    /**
     * Binds to whichever plot box is nearest and still unsigned. The two arrive
     * together and are placed together, so pointing at the sign beside the box is
     * already saying which plot is meant.
     */
    private static void plotSign(ServerPlayer player, PollStore store, Poll poll,
                                 String dimension, BlockPos pos, long now) {
        Optional<Integer> nearest = poll.nearestBoxEntry(
                dimension, pos.getX(), pos.getY(), pos.getZ(), SIGN_RANGE);

        Optional<Integer> unsigned = nearest.filter(id -> poll.signFor(id).isEmpty());
        if (unsigned.isEmpty()) {
            player.sendSystemMessage(Screens.bad(nearest.isEmpty()
                    ? "No plot ballot box within " + SIGN_RANGE + " blocks."
                    : "That plot already has a sign."));
            return;
        }

        int id = unsigned.get();
        poll.bindStation(id, Station.SIGN, dimension, pos.getX(), pos.getY(), pos.getZ(), now);
        store.save(poll);
        Signs.refresh(player.level().getServer(), poll);

        player.sendSystemMessage(Screens.good("Sign bound to plot " + id + "."));
        var missing = poll.whatsMissing();
        if (!missing.isEmpty()) {
            player.sendSystemMessage(Screens.dim("Still needed: " + String.join(", ", missing)));
        }
    }

    /** The item kind an itemstack represents, for the mixin to read before placement. */
    public static String kindOf(ItemStack stack) {
        return BallotItems.kindOf(stack);
    }

    public static String pollKeyOf(ItemStack stack, String kind) {
        return switch (kind) {
            case "lectern" -> BallotItems.lecternPollKey(stack);
            case "ballot_box" -> BallotItems.ballotBoxPollKey(stack);
            case "plot_box" -> BallotItems.plotBoxPollKey(stack);
            case "plot_sign" -> BallotItems.plotSignPollKey(stack);
            default -> null;
        };
    }
}
