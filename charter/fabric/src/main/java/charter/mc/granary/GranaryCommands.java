package charter.mc.granary;

import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.GranaryBook.Pos;
import charter.mc.granary.GranaryBook.Site;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Operator tools for the granary under {@code /charter admin granary} (permission
 * level 2), merged into the admin tree M7 made:
 *
 * <pre>
 *   /charter admin granary set &lt;pos&gt;      make the chest or barrel at pos part of the granary or camp store of the keep or camp whose chunk it stands in
 *   /charter admin granary remove &lt;pos&gt;   take that container out of its granary (the block stays)
 *   /charter admin granary list            every granary and camp store, with what is queued for a load
 * </pre>
 *
 * <p>Every one is written to the siege log as {@code admin}. {@code set} skips the
 * banner check a camp store normally needs and the member check a placement needs.
 */
final class GranaryCommands {

    private GranaryCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("charter")
                .then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("granary").requires(charter.mc.cmd.Gate::devTools)
                                .then(Commands.literal("set")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(GranaryCommands::set)))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(GranaryCommands::remove)))
                                .then(Commands.literal("list").executes(GranaryCommands::list)))));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GranaryService service = Granaries.service();
        if (service == null) return fail(ctx, Text.get("granary.not_running"));
        BlockPos at = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        ServerLevel level = ctx.getSource().getLevel();
        String dimension = level.dimension().identifier().toString();
        Optional<Core> core = service.coreAtChunk(new charter.id.ChunkKey(dimension, at.getX() >> 4, at.getZ() >> 4));
        if (core.isEmpty()) return fail(ctx, Text.get("granary.no_core_here"));
        String problem = service.link(core.get(), level, at, false, true);
        if (problem != null) return fail(ctx, Text.get(problem, "max", rt().knobs.granaryMaxChests));
        return ok(ctx, Text.get("granary.set", "x", at.getX(), "y", at.getY(), "z", at.getZ()));
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GranaryService service = Granaries.service();
        if (service == null) return fail(ctx, Text.get("granary.not_running"));
        BlockPos at = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        String dimension = ctx.getSource().getLevel().dimension().identifier().toString();
        Pos pos = new Pos(at.getX(), at.getY(), at.getZ());
        Site site = service.book().at(dimension, pos);
        if (site == null) return fail(ctx, Text.get("granary.not_a_granary"));
        site.containers.remove(pos);
        if (site.containers.isEmpty()) {
            service.book().remove(site.core);
            Granaries.forget(site.core);
        }
        return ok(ctx, Text.get("granary.removed", "x", at.getX(), "y", at.getY(), "z", at.getZ()));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        GranaryService service = Granaries.service();
        if (service == null) return fail(ctx, Text.get("granary.not_running"));
        var sites = service.book().sites();
        if (sites.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(Text.get("granary.none")), false);
            return 1;
        }
        for (Site site : sites) {
            Pos first = site.containers.getFirst();
            String line = Text.get("granary.list_line", "core", site.core, "count", site.containers.size(),
                    "x", first.x(), "y", first.y(), "z", first.z(), "queued", site.queued.values().stream().mapToInt(Integer::intValue).sum());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return sites.size();
    }
}
