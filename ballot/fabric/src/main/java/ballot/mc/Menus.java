package ballot.mc;

import ballot.Entry;
import ballot.Outcome;
import ballot.Poll;
import ballot.PollState;
import ballot.Rules;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * The organiser's menus.
 *
 * <p>Chest GUIs, opened by right-clicking a poll block while holding the wand. The
 * settings that used to be a wall of chat lines become items you click, and naming
 * uses a real text field via the anvil screen rather than a typed command.
 *
 * <p>This layer owns no rules. Every action calls the same core methods the chat
 * buttons call, so the two interfaces cannot disagree — and the chat versions are all
 * still there, which is what keeps SGUI additive rather than load-bearing.
 *
 * <p>All of it is server-side; a vanilla client sees an ordinary chest window.
 */
public final class Menus {

    private Menus() {}

    // Middle row of a 9x3 chest, which reads as a row of buttons rather than a grid.
    private static final int ROW = 9;

    public static void openPollSettings(ServerPlayer player, Poll poll, PollStore store) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal(poll.isUnnamed() ? "New vote" : poll.title()));
        drawPollSettings(gui, player, poll, store);
        gui.open();
    }

    private static void drawPollSettings(SimpleGui gui, ServerPlayer player, Poll poll,
                                         PollStore store) {
        Rules r = poll.rules();
        boolean draft = poll.state() == PollState.DRAFT;

        // Top row: the things that must be decided before it can open.
        gui.setSlot(0, field(Items.NAME_TAG, "Name",
                poll.isUnnamed() ? null : poll.name(),
                "Click to type a name",
                () -> askText(player, "Name this vote", poll.name(), value ->
                        apply(player, poll, store, poll.setName(value, now())))));

        gui.setSlot(1, field(Items.WRITABLE_BOOK, "Description",
                poll.description().isBlank() ? null : poll.description(),
                "Click to type a description",
                () -> askText(player, "Describe it", poll.description(), value ->
                        apply(player, poll, store, poll.setDescription(value, now())))));

        gui.setSlot(2, field(Items.CLOCK, "Closes",
                poll.closesAt() > 0 ? Screens.deadlineText(poll) : null,
                "Click for 1 / 3 / 7 / 14 days",
                () -> cycleDeadline(player, poll, store)));

        if (r.claimable()) {
            gui.setSlot(3, field(Items.JUKEBOX, "Plots",
                    poll.entries().size() + (poll.entries().size() == 1 ? " plot" : " plots"),
                    "Click for a ballot box and a sign",
                    () -> {
                        // The pair always goes together, so hand over both at once
                        // rather than making anyone work out what is missing.
                        BallotCommands.give(player, BallotItems.plotBox(poll.key()));
                        BallotCommands.give(player, BallotItems.plotSign(poll.key()));
                        player.sendSystemMessage(Screens.good(
                                "Place the box at a plot, then the sign beside it."));
                        gui.close();
                    }));
        } else {
            gui.setSlot(3, field(Items.PAPER, "Options",
                    poll.entries().size() + (poll.entries().size() == 1 ? " option" : " options"),
                    "Click to add one",
                    () -> askText(player, "Add an option", "", value ->
                            apply(player, poll, store, poll.addEntry(value, now())))));

            gui.setSlot(4, field(Items.JUKEBOX, "Ballot box",
                    poll.hasBallotBox() ? "placed" : null,
                    "Click for one to place",
                    () -> {
                        BallotCommands.give(player, BallotItems.ballotBox(poll.key()));
                        player.sendSystemMessage(Screens.good(
                                "Place it where people gather."));
                        gui.close();
                    }));
        }

        // Second row: switches that have sensible defaults and rarely change.
        gui.setSlot(ROW, toggle(Items.LECTERN, "Voting",
                r.voteFromChat()
                        ? "From chat or at the box"
                        : (r.claimable() ? "At the plot boxes only" : "At the ballot box only"),
                r.voteFromChat(),
                () -> apply(player, poll, store, poll.setRules(new Rules(
                        r.kindChosen(), r.claimable(), r.allowVoteChange(), r.blockSelfVote(),
                        !r.voteFromChat(), r.maxVotesPerPlayer()), now()))));

        gui.setSlot(ROW + 1, toggle(Items.COMPARATOR, "Changing votes",
                r.allowVoteChange() ? "Allowed" : "Final once cast",
                r.allowVoteChange(),
                () -> apply(player, poll, store, poll.setRules(new Rules(
                        r.kindChosen(), r.claimable(), !r.allowVoteChange(), r.blockSelfVote(),
                        r.voteFromChat(), r.maxVotesPerPlayer()), now()))));

        gui.setSlot(ROW + 2, toggle(Items.SHIELD, "Self-votes",
                r.blockSelfVote() ? "Blocked" : "Allowed",
                r.blockSelfVote(),
                () -> apply(player, poll, store, poll.setRules(new Rules(
                        r.kindChosen(), r.claimable(), r.allowVoteChange(), !r.blockSelfVote(),
                        r.voteFromChat(), r.maxVotesPerPlayer()), now()))));

        // Bottom row: the one-way doors, kept apart from everything else.
        gui.setSlot(ROW * 2 + 4, stateItem(player, poll, store, gui));

        gui.setSlot(ROW * 2 + 8, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Delete this vote")
                        .withStyle(ChatFormatting.RED))
                .setLore(List.of(dim("Removes it and its whole history"),
                        dim("Shift-click to confirm")))
                .setCallback((index, type, action, g) -> {
                    if (!type.shift) {
                        player.sendSystemMessage(Screens.bad("Shift-click to confirm."));
                        return;
                    }
                    store.delete(poll.key());
                    g.close();
                    player.sendSystemMessage(Screens.dim("Deleted."));
                })
                .build());
    }

    /**
     * The state item is deliberately the only green thing on the screen, and it says
     * what is missing rather than simply refusing.
     */
    private static eu.pb4.sgui.api.elements.GuiElement stateItem(ServerPlayer player, Poll poll,
                                                                 PollStore store, SimpleGui gui) {
        return switch (poll.state()) {
            case DRAFT -> {
                java.util.List<String> missing = poll.whatsMissing();
                boolean ready = missing.isEmpty();
                // Assigned first: a ternary inside the constructor call makes the
                // GuiElementBuilder overloads ambiguous.
                Item icon = ready ? Items.EMERALD : Items.BARRIER;
                GuiElementBuilder b = new GuiElementBuilder(icon)
                        .setName(Component.literal("Open it")
                                .withStyle(ready ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                if (ready) {
                    b.setLore(List.of(dim(poll.rules().claimable()
                                    ? "Players can claim plots and build"
                                    : "People can start voting")))
                            .setCallback((i, t, a, g) ->
                                    move(player, poll, store, g, PollState.OPEN));
                } else {
                    b.setLore(missing.stream().map(m -> (Component) dim("Needs " + m)).toList());
                }
                yield b.build();
            }
            case OPEN -> new GuiElementBuilder(Items.EMERALD)
                    .setName(Component.literal("Start voting").withStyle(ChatFormatting.GREEN))
                    .setLore(List.of(
                            dim(poll.rules().claimable()
                                    ? "Locks plots, claims and names"
                                    : "Locks the options"),
                            dim(poll.entries().size()
                                    + (poll.rules().claimable() ? " plots" : " options"))))
                    .setCallback((i, t, a, g) -> move(player, poll, store, g, PollState.VOTING))
                    .build();
            case VOTING -> new GuiElementBuilder(Items.REDSTONE_BLOCK)
                    .setName(Component.literal("Close voting").withStyle(ChatFormatting.RED))
                    .setLore(List.of(dim(poll.votes().size() + " votes cast"),
                            dim("Shift-click to confirm")))
                    .setCallback((i, t, a, g) -> {
                        if (!t.shift) {
                            player.sendSystemMessage(Screens.bad("Shift-click to confirm."));
                            return;
                        }
                        Outcome outcome = poll.close(now());
                        if (!outcome.ok()) {
                            player.sendSystemMessage(Screens.bad(outcome.message()));
                            return;
                        }
                        store.save(poll);
                        Signs.refresh(player.level().getServer(), poll);
                        Screens.announceResults(player.level().getServer(), poll);
                        g.close();
                    })
                    .build();
            case CLOSED -> new GuiElementBuilder(Items.CHEST)
                    .setName(Component.literal("Archive and start a new one")
                            .withStyle(ChatFormatting.GOLD))
                    .setCallback((i, t, a, g) -> {
                        g.close();
                        player.sendSystemMessage(Screens.dim(
                                "Run /ballot create poll to start the next one."));
                    })
                    .build();
            case ARCHIVED -> new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("Archived").withStyle(ChatFormatting.DARK_GRAY))
                    .build();
        };
    }

    // ---- voting -------------------------------------------------------------

    /**
     * A poll's ballot box. Every option in one window, one click to vote.
     *
     * <p>Counts are deliberately absent: showing them mid-vote creates bandwagons and
     * makes a late option look dead on arrival.
     */
    public static void openVoting(ServerPlayer player, Poll poll, PollStore store) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal(poll.title()));

        String uuid = player.getUUID().toString();
        int slot = 0;
        for (Entry entry : poll.entries()) {
            if (slot >= 27) {
                break;
            }
            boolean mine = poll.voteOf(uuid).map(v -> v == entry.id()).orElse(false);
            boolean own = poll.rules().blockSelfVote() && entry.isOwnedBy(uuid);

            GuiElementBuilder b = new GuiElementBuilder(mine ? Items.EMERALD : Items.PAPER)
                    .setName(Component.literal(entry.label())
                            .withStyle(mine ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            if (mine) {
                b.setLore(List.of(dim("Your vote"),
                        dim(poll.rules().allowVoteChange()
                                ? "Click another to change it" : "Votes are final")));
                b.glow();
            } else if (own) {
                b.setLore(List.of(dim("Yours — you can't vote for it")));
            } else {
                b.setLore(List.of(dim("Click to vote")));
                b.setCallback((i, t, a, g) -> {
                    Outcome outcome = poll.vote(uuid, entry.id(), now());
                    if (!outcome.ok()) {
                        player.sendSystemMessage(Screens.bad(outcome.message()));
                        return;
                    }
                    store.save(poll);
                    g.close();
                    player.sendSystemMessage(Screens.good("Voted for " + entry.label() + "."));
                });
            }
            gui.setSlot(slot++, b.build());
        }
        gui.open();
    }

    // ---- entry menu ---------------------------------------------------------

    public static void openEntry(ServerPlayer player, Poll poll, Entry entry, PollStore store) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Entry " + entry.id()));

        gui.setSlot(0, field(Items.NAME_TAG, "Name",
                entry.hasLabel() ? entry.label() : null,
                "Click to type a name",
                () -> askText(player, "Name this entry", entry.label(), value -> {
                    Outcome outcome = entry.isClaimed()
                            ? poll.rename(entry.owner(), value, now())
                            : forceLabel(poll, entry, value);
                    apply(player, poll, store, outcome);
                })));

        gui.setSlot(1, field(Items.PLAYER_HEAD, "Claimed by",
                entry.isClaimed() ? entry.ownerName() : null,
                entry.isClaimed() ? "Click to clear the claim" : "Nobody yet",
                entry.isClaimed() ? () -> {
                    apply(player, poll, store, poll.release(entry.owner(), now()));
                    gui.close();
                } : null));

        gui.setSlot(2, field(Items.PAPER, "Votes",
                Integer.toString(poll.tally().getOrDefault(entry.id(), 0)),
                "Hidden from players until the vote closes", null));

        gui.setSlot(ROW * 2 + 8, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Remove this entry").withStyle(ChatFormatting.RED))
                .setLore(List.of(dim("Shift-click to confirm")))
                .setCallback((i, t, a, g) -> {
                    if (!t.shift) {
                        player.sendSystemMessage(Screens.bad("Shift-click to confirm."));
                        return;
                    }
                    apply(player, poll, store, poll.removeEntry(entry.id(), now()));
                    g.close();
                })
                .build());

        gui.open();
    }

    /** An unclaimed entry has no owner to rename through, so the label is set directly. */
    private static Outcome forceLabel(Poll poll, Entry entry, String value) {
        return poll.labelEntry(entry.id(), value, now());
    }

    // ---- text entry ---------------------------------------------------------

    /**
     * The anvil screen as a text field. This is the main thing SGUI buys over chat
     * buttons: a real input box, on a vanilla client, with no command to type.
     */
    private static void askText(ServerPlayer player, String prompt, String initial,
                                Consumer<String> onDone) {
        AnvilInputGui input = new AnvilInputGui(player, false);
        input.setTitle(Component.literal(prompt));
        input.setDefaultInputValue(initial == null || initial.isBlank() ? " " : initial);

        // Taken on the accept click rather than on close, so backing out of the screen
        // cancels cleanly instead of silently committing whatever was half-typed.
        input.setSlot(2, new GuiElementBuilder(Items.WRITABLE_BOOK)
                .setName(Component.literal("Accept").withStyle(ChatFormatting.GREEN))
                .setLore(List.of(dim("Type above, then click here")))
                .setCallback((i, t, a, g) -> {
                    String value = input.getInput();
                    g.close();
                    if (value == null || value.isBlank()) {
                        player.sendSystemMessage(Screens.bad("Nothing typed."));
                        return;
                    }
                    onDone.accept(value.trim());
                })
                .build());
        input.open();
    }

    // ---- shared -------------------------------------------------------------

    private static void cycleDeadline(ServerPlayer player, Poll poll, PollStore store) {
        long remaining = poll.closesAt() <= 0 ? 0 : poll.closesAt() - now();
        int days = remaining <= 0 ? 1
                : remaining <= 86400L ? 3
                : remaining <= 3 * 86400L ? 7
                : remaining <= 7 * 86400L ? 14
                : 1;
        apply(player, poll, store, poll.setDeadline(now() + days * 86400L, now()));
    }

    private static void move(ServerPlayer player, Poll poll, PollStore store,
                             eu.pb4.sgui.api.gui.SlotBasedGui gui, PollState target) {
        Outcome outcome = poll.moveTo(target, now());
        if (!outcome.ok()) {
            player.sendSystemMessage(Screens.bad(outcome.message()));
            return;
        }
        store.save(poll);
        Signs.refresh(player.level().getServer(), poll);
        gui.close();
        player.sendSystemMessage(Screens.good(outcome.message()));
    }

    /** Saves, redraws signs, and reopens so the change is visible where it was made. */
    private static void apply(ServerPlayer player, Poll poll, PollStore store, Outcome outcome) {
        if (!outcome.ok()) {
            player.sendSystemMessage(Screens.bad(outcome.message()));
            return;
        }
        store.save(poll);
        Signs.refresh(player.level().getServer(), poll);
        openPollSettings(player, poll, store);
    }

    private static eu.pb4.sgui.api.elements.GuiElement field(Item item, String label,
                                                             String value, String hint,
                                                             Runnable onClick) {
        GuiElementBuilder b = new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(ChatFormatting.WHITE))
                .setLore(List.of(
                        value == null
                                ? Component.literal("not set").withStyle(ChatFormatting.DARK_RED)
                                : Component.literal(value).withStyle(ChatFormatting.YELLOW),
                        dim(hint)));
        if (onClick != null) {
            b.setCallback((i, t, a, g) -> onClick.run());
        }
        return b.build();
    }

    private static eu.pb4.sgui.api.elements.GuiElement toggle(Item item, String label,
                                                              String value, boolean on,
                                                              Runnable onClick) {
        GuiElementBuilder b = new GuiElementBuilder(item)
                .setName(Component.literal(label).withStyle(ChatFormatting.WHITE))
                .setLore(List.of(Component.literal(value)
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                        dim("Click to change")))
                .setCallback((i, t, a, g) -> onClick.run());
        if (on) {
            b.glow();
        }
        return b.build();
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
