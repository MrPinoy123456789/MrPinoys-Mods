package charter.mc.cmd;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.LecternMove;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Moving a keep's lectern by command, as its own {@code /charter} trees that Brigadier merges with the others.
 *
 * <pre>
 *   /charter lectern move                                   the Lord: the keep lectern moves to where you stand, inside the core chunk
 *   /charter admin faction lectern "&lt;faction&gt;" [&lt;x&gt; &lt;y&gt; &lt;z&gt;]   an operator: that keep's lectern moves to your feet, or there
 * </pre>
 */
public final class LecternCommands {

    private LecternCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("lectern").then(Commands.literal("move").executes(LecternCommands::move)))
                        .then(Commands.literal("admin")
                                .requires(charter.mc.CharterPerms::admin)
                                .then(Commands.literal("faction")
                                        .then(Commands.literal("lectern")
                                                .then(Commands.argument("faction", StringArgumentType.string()).suggests(Resolve::factions)
                                                        .executes(ctx -> moveFor(ctx, false))
                                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                                .executes(ctx -> moveFor(ctx, true)))))))))));
    }

    private static int move(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String problem = LecternMove.move(player);
        if (problem != null) {
            player.sendSystemMessage(Chat.bad(problem));
            return 0;
        }
        player.sendSystemMessage(Chat.good(Text.get("lectern_move.done")));
        return 1;
    }

    /** With no coordinates the lectern goes to the operator's feet, facing them; from the console they are needed. */
    private static int moveFor(CommandContext<CommandSourceStack> ctx, boolean coordinates) {
        if (!CharterServer.running()) return fail(ctx, Text.get("nexus_move.not_running"));
        var rt = CharterServer.runtime();
        String name = StringArgumentType.getString(ctx, "faction");
        var found = Resolve.faction(rt, name).filter(o -> o.isTopLevel());
        if (found.isEmpty()) return fail(ctx, Text.get("land.admin.no_faction", "name", name));
        ServerPlayer player = ctx.getSource().getPlayer();
        ServerLevel level;
        BlockPos at;
        Direction facing = null;
        if (coordinates) {
            level = player != null ? player.level() : ctx.getSource().getServer().overworld();
            at = new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"),
                    IntegerArgumentType.getInteger(ctx, "z"));
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        } else {
            if (player == null) return fail(ctx, Text.get("lectern_move.admin_console"));
            level = player.level();
            at = player.blockPosition();
            facing = player.getDirection().getOpposite();
        }
        String problem = LecternMove.moveFor(level, found.get(), at, facing, ctx.getSource().getTextName());
        if (problem != null) return fail(ctx, problem);
        var spot = charter.mc.terminal.Terminals.book().ofCore(rt.state.coresOf(found.get().id).stream()
                .filter(charter.land.Core::isKeep).findFirst().orElseThrow().id.value().toString()).get(0).spot();
        rt.siegeLog.admin(rt.clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        String done = Text.get("lectern_move.admin_done", "name", found.get().name(), "x", spot.x(), "y", spot.y(), "z", spot.z());
        ctx.getSource().sendSuccess(() -> Chat.good(done), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
