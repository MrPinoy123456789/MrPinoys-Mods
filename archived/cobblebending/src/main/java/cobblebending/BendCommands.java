package cobblebending;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * /cobblebending admin command tree.
 */
public final class BendCommands {

    private BendCommands() {}

    /** Registers the operator-only give, reload, and cleanup command branches. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("cobblebending")

                        .then(Commands.literal("give")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("focus", StringArgumentType.word())
                                                .executes(ctx -> give(EntityArgument.getPlayers(ctx, "targets"), ctx.getArgument("focus", String.class))))
                                        .executes(ctx -> give(EntityArgument.getPlayers(ctx, "targets"), "hurl"))))

                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    CobbleBendingMod.config().reload();
                                    return 1;
                                }))

                        .then(Commands.literal("clear")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> {
                                    BentBlocks.clearAll();
                                    return 1;
                                }))));
    }

    private static int give(Collection<ServerPlayer> targets, String focusId) {
        ItemStack stack;
        if ("wall".equals(focusId)) {
            stack = Focus.wall();
        } else if ("hurl".equals(focusId)) {
            stack = Focus.hurl();
        } else {
            return 0;
        }
        for (ServerPlayer target : targets) {
            if (!target.getInventory().add(stack.copy())) {
                target.drop(stack.copy(), false);
            }
            target.sendSystemMessage(Component.literal("You received a " + focusId + " focus.")
                    .withStyle(ChatFormatting.AQUA));
        }
        return targets.size();
    }
}
