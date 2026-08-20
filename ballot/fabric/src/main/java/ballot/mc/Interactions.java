package ballot.mc;

import ballot.Entry;
import ballot.Outcome;
import ballot.Poll;
import ballot.PollState;
import ballot.Station;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.time.Instant;
import java.util.Optional;

/**
 * Right-clicks.
 *
 * <p>One rule everywhere: <strong>wand in hand means organiser, anything else —
 * including nothing — means participant.</strong> Holding the wand is the mode, so
 * there is no invisible state to forget, and an organiser who puts it away is an
 * ordinary voter again.
 *
 * <p>Bound blocks consume the click, which also stops a bound jukebox behaving like a
 * jukebox. Unbound ones pass straight through and behave normally.
 */
public final class Interactions {

    private Interactions() {}

    /** Registers participant and organiser handling for right-clicks on bound blocks. */
    public static void register(PollStore store) {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            // Fires once per hand; without this every interaction happens twice.
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return handle(store, serverPlayer, level, hit.getBlockPos());
        });
    }

    private static InteractionResult handle(PollStore store, ServerPlayer player,
                                            Level level, BlockPos pos) {
        Object be = level.getBlockEntity(pos);
        boolean jukebox = be instanceof JukeboxBlockEntity;
        boolean lectern = be instanceof LecternBlockEntity;
        boolean sign = be instanceof SignBlockEntity;
        if (!jukebox && !lectern && !sign) {
            return InteractionResult.PASS;
        }

        Optional<Poll> current = store.current();
        if (current.isEmpty()) {
            return InteractionResult.PASS;
        }
        Poll poll = current.get();

        String dimension = level.dimension().identifier().toString();
        Optional<Station> station =
                poll.stationAt(dimension, pos.getX(), pos.getY(), pos.getZ());
        if (station.isEmpty()) {
            return InteractionResult.PASS;   // somebody's ordinary block
        }

        boolean wand = BallotItems.isWand(player.getMainHandItem());
        boolean isOp = BallotMod.isOp(player.level().getServer(), player);

        if (wand && isOp) {
            return organiser(store, player, poll, station.get(), dimension, pos);
        }
        return participant(store, player, poll, station.get());
    }

    // ---- organiser ----------------------------------------------------------

    private static InteractionResult organiser(PollStore store, ServerPlayer player, Poll poll,
                                               Station station, String dimension, BlockPos pos) {
        if (player.isShiftKeyDown()) {
            Outcome outcome = poll.unbindStation(dimension, pos.getX(), pos.getY(), pos.getZ(),
                    Instant.now().getEpochSecond());
            store.save(poll);
            player.sendSystemMessage(outcome.ok()
                    ? Screens.good("Unbound.") : Screens.bad(outcome.message()));
            return InteractionResult.SUCCESS_SERVER;
        }

        if (station.isLectern() || station.isBallot()) {
            Menus.openPollSettings(player, poll, store);
            return InteractionResult.SUCCESS_SERVER;
        }

        // A plot's box or its sign both lead to that plot's settings.
        Optional<Entry> entry = poll.entry(station.entryId());
        if (entry.isPresent()) {
            Menus.openEntry(player, poll, entry.get(), store);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    // ---- participant --------------------------------------------------------

    private static InteractionResult participant(PollStore store, ServerPlayer player,
                                                 Poll poll, Station station) {
        if (station.isLectern()) {
            Screens.show(player, poll, BallotMod.isOp(player.level().getServer(), player));
            return InteractionResult.SUCCESS_SERVER;
        }
        if (station.isBallot()) {
            return pollBallot(player, poll, store);
        }
        if (station.isSign()) {
            return plotSign(player, poll, station);
        }
        return plotBox(player, poll, station);
    }

    /** A poll's one box: every option inside, pick one. */
    private static InteractionResult pollBallot(ServerPlayer player, Poll poll,
                                                PollStore store) {
        if (poll.state() != PollState.VOTING) {
            player.sendSystemMessage(Screens.dim(switch (poll.state()) {
                case DRAFT, OPEN -> "Voting hasn't opened yet.";
                default -> "Voting is over.";
            }));
            if (poll.state().isFinished()) {
                Screens.show(player, poll, false);
            }
            return InteractionResult.SUCCESS_SERVER;
        }
        Menus.openVoting(player, poll, store);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** A plot's box: one build, vote for it or don't. */
    private static InteractionResult plotBox(ServerPlayer player, Poll poll, Station station) {
        Optional<Entry> found = poll.entry(station.entryId());
        if (found.isEmpty()) {
            return InteractionResult.PASS;
        }
        Entry entry = found.get();
        String uuid = player.getUUID().toString();

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Screens.header(entry.label()));
        if (entry.isClaimed()) {
            player.sendSystemMessage(Screens.dim("  by " + entry.ownerName()));
        }

        if (poll.state() != PollState.VOTING) {
            player.sendSystemMessage(Screens.dim(switch (poll.state()) {
                case OPEN -> "  Voting hasn't opened yet. Claim a plot at its sign.";
                case DRAFT -> "  This vote isn't open yet.";
                default -> "  " + poll.tally().getOrDefault(entry.id(), 0) + " votes.";
            }));
            return InteractionResult.SUCCESS_SERVER;
        }

        if (poll.rules().blockSelfVote() && entry.isOwnedBy(uuid)) {
            player.sendSystemMessage(Screens.dim("  This one's yours — you can't vote for it."));
            return InteractionResult.SUCCESS_SERVER;
        }

        offerVote(player, poll, entry, uuid);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Never votes on the click itself. A misclick while walking a gallery should not
     * silently change somebody's mind, so the block asks and the chat confirms.
     */
    private static void offerVote(ServerPlayer player, Poll poll, Entry entry, String uuid) {
        Optional<Integer> current = poll.voteOf(uuid);
        String confirm = "/ballot _ cast " + entry.id();

        if (current.isPresent() && current.get() == entry.id()) {
            player.sendSystemMessage(Screens.good("  You voted for this one."));
            if (poll.rules().allowVoteChange()) {
                player.sendSystemMessage(Component.literal("  ")
                        .append(Screens.button("[ Withdraw my vote ]", "/ballot _ uncast")));
            }
            return;
        }

        if (current.isPresent()) {
            String backing = poll.entry(current.get()).map(Entry::label).orElse("something else");
            if (!poll.rules().allowVoteChange()) {
                player.sendSystemMessage(Screens.dim(
                        "  You've already voted for " + backing + ", and votes are final."));
                return;
            }
            player.sendSystemMessage(Screens.dim("  You currently back " + backing + "."));
            player.sendSystemMessage(Component.literal("  ")
                    .append(Screens.button("[ Switch to this one ]", confirm))
                    .append(Component.literal("  "))
                    .append(Component.literal("[ Leave it ]")
                            .withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        player.sendSystemMessage(Component.literal("  ")
                .append(Screens.button("[ Vote for this ]", confirm))
                .append(Component.literal("  "))
                .append(Component.literal("[ Cancel ]").withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** A plot's sign: claiming, naming, releasing. Voting lives at the box. */
    private static InteractionResult plotSign(ServerPlayer player, Poll poll, Station station) {
        Optional<Entry> found = poll.entry(station.entryId());
        if (found.isEmpty()) {
            return InteractionResult.PASS;
        }
        Entry entry = found.get();
        String uuid = player.getUUID().toString();

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Screens.header("Plot " + entry.id() + " · " + poll.title()));

        if (poll.state() == PollState.OPEN) {
            if (entry.isOwnedBy(uuid)) {
                player.sendSystemMessage(Screens.good("  Yours."
                        + (entry.hasLabel() ? "  \"" + entry.label() + "\"" : "  Not named yet.")));
                player.sendSystemMessage(Component.literal("  ")
                        .append(Screens.suggest("[ Name it ]", "/ballot _ label "))
                        .append(Component.literal("  "))
                        .append(Screens.button("[ Release it ]", "/ballot _ letgo")));
            } else if (entry.isClaimed()) {
                player.sendSystemMessage(Screens.dim("  Taken by " + entry.ownerName() + "."));
            } else {
                player.sendSystemMessage(Screens.dim("  Free to claim."));
                player.sendSystemMessage(Component.literal("  ")
                        .append(Screens.button("[ Claim this plot ]",
                                "/ballot _ take " + entry.id())));
            }
            return InteractionResult.SUCCESS_SERVER;
        }

        player.sendSystemMessage(Screens.plain("  " + entry.label()
                + (entry.isClaimed() ? "  — " + entry.ownerName() : "")));
        player.sendSystemMessage(Screens.dim(switch (poll.state()) {
            case VOTING -> "  Voting is open — cast it at the ballot box.";
            case DRAFT -> "  Not open yet.";
            default -> "  " + poll.tally().getOrDefault(entry.id(), 0) + " votes.";
        }));
        return InteractionResult.SUCCESS_SERVER;
    }
}
