package bounties;

import bounties.core.AcceptResult;
import bounties.core.AbandonResult;
import bounties.core.Board;
import bounties.core.BountyDefinition;
import bounties.core.BountyMath;
import bounties.core.PlayerBounties;
import bounties.core.ProgressResult;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.UUID;

/**
 * The /bounty command tree.
 */
public final class BountyCommands {

    private BountyCommands() {}

    /** Registers board display, acceptance, abandonment, help, and reload commands. */
    public static void register(BountyConfig config, BountyState state) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("bounty")
                        .executes(ctx -> show(ctx.getSource(), config, state))
                        .then(Commands.literal("accept")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 2))
                                        .executes(ctx -> accept(ctx.getSource(), config, state,
                                                IntegerArgumentType.getInteger(ctx, "slot")))))
                        .then(Commands.literal("abandon")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1, config.maxHeld()))
                                        .executes(ctx -> abandon(ctx.getSource(), state,
                                                IntegerArgumentType.getInteger(ctx, "slot")))))
                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> reload(ctx.getSource(), config)))

                        .then(Commands.literal("help")
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    src.sendSuccess(() -> Component.literal("Bounty Board")
                                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                                    src.sendSuccess(() -> Component.literal("  /bounty  Show the bounty board")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    src.sendSuccess(() -> Component.literal("  /bounty accept <slot>  Accept a bounty")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    src.sendSuccess(() -> Component.literal("  /bounty abandon <slot>  Abandon a held bounty")
                                            .withStyle(ChatFormatting.WHITE), false);
                                    if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src)) {
                                        src.sendSuccess(() -> Component.literal("  /bounty reload  Reload bounty definitions")
                                                .withStyle(ChatFormatting.WHITE), false);
                                    }
                                    return 1;
                                }))));
    }

    private static int show(CommandSourceStack source, BountyConfig config, BountyState state) {
        long now = System.currentTimeMillis();
        Board board = BountyMath.boardAt(config.pool(), now);
        long secondsUntil = (BountyMath.nextRotationAt(now) - now) / 1000L;

        source.sendSuccess(() -> Component.literal("Bounty Board")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> formatSlot(1, board.slot1()), false);
        source.sendSuccess(() -> formatSlot(2, board.slot2()), false);
        source.sendSuccess(() -> Component.literal(
                        "Board refreshes in " + formatDuration(secondsUntil) + ".")
                .withStyle(ChatFormatting.GRAY), false);

        ServerPlayer player = source.getPlayer();
        if (player != null) {
            PlayerBounties held = state.of(player.getUUID());
            source.sendSuccess(() -> Component.literal("Your bounties (" + held.heldCount() + "/"
                            + config.maxHeld() + ")")
                    .withStyle(ChatFormatting.GREEN), false);
            if (held.held().isEmpty()) {
                source.sendSuccess(() -> Component.literal("  None")
                        .withStyle(ChatFormatting.GRAY), false);
            } else {
                int index = 1;
                for (var b : held.held()) {
                    final int slotNumber = index++;
                    final int done = b.progress();
                    final int total = b.definition().requiredKills();
                    final var bounty = b;
                    source.sendSuccess(() -> Component.literal("  [" + slotNumber + "] "
                                    + bounty.definition().displayDescription()
                                    + "  " + done + "/" + total
                                    + "  (" + bounty.definition().rewardDiamonds() + " diamond"
                                    + (bounty.definition().rewardDiamonds() == 1 ? "" : "s") + ")")
                            .withStyle(ChatFormatting.YELLOW), false);
                }
            }
        }
        return 1;
    }

    private static Component formatSlot(int number, BountyDefinition def) {
        if (def == null) {
            return Component.literal("  [" + number + "] (empty)")
                    .withStyle(ChatFormatting.GRAY);
        }
        return Component.literal("  [" + number + "] ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(def.displayDescription())
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  reward: " + def.rewardDiamonds() + " diamond"
                                + (def.rewardDiamonds() == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.AQUA));
    }

    private static int accept(CommandSourceStack source, BountyConfig config,
                              BountyState state, int slot) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        UUID id = player.getUUID();

        Board board = BountyMath.boardAt(config.pool(), System.currentTimeMillis());
        BountyDefinition chosen = slot == 1 ? board.slot1() : board.slot2();
        if (chosen == null) {
            source.sendFailure(Component.literal("That bounty slot is empty."));
            return 0;
        }

        PlayerBounties current = state.of(id);
        AcceptResult result = current.accept(chosen, Instant.now().toEpochMilli(), config.maxHeld());
        if (!result.ok()) {
            source.sendFailure(Component.literal(result.message()).withStyle(ChatFormatting.RED));
            return 0;
        }
        state.set(id, result.state());
        Chime.accepted(player);
        source.sendSuccess(() -> Component.literal(result.message())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int abandon(CommandSourceStack source, BountyState state, int slot) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        UUID id = player.getUUID();

        PlayerBounties current = state.of(id);
        AbandonResult result = current.abandon(slot);
        if (!result.ok()) {
            source.sendFailure(Component.literal(result.message()).withStyle(ChatFormatting.RED));
            return 0;
        }
        state.set(id, result.state());
        source.sendSuccess(() -> Component.literal(result.message())
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int reload(CommandSourceStack source, BountyConfig config) {
        config.reload();
        source.sendSuccess(() -> Component.literal("Reloaded " + config.pool().size()
                        + " bounties.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + "m " + seconds + "s";
    }
}
