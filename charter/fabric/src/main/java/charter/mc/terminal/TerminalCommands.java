package charter.mc.terminal;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * The operator's terminal tools, a separate {@code /charter admin} tree that Brigadier merges with the others.
 *
 * <pre>
 *   /charter admin terminal add      the block you look at becomes a terminal
 *   /charter admin terminal here     puts a lectern where you stand and makes it a terminal
 *   /charter admin terminal remove   the terminal you look at, or else the block under you
 *   /charter admin terminal list     every terminal, each a link that takes you there
 *   /charter admin terminal clear    removes every terminal (the blocks stay)
 *   /charter admin terminal keeps    a lectern beside the Nexus of every keep that has no keep terminal yet
 * </pre>
 */
public final class TerminalCommands {

    /** How far an operator may look at a block to make or remove a terminal. */
    static final double REACH = 8;

    private TerminalCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("admin")
                                .requires(charter.mc.CharterPerms::admin)
                                .then(Commands.literal("terminal")
                                        .requires(charter.mc.CharterPerms::admin)
                                        .then(Commands.literal("add").executes(TerminalCommands::add))
                                        .then(Commands.literal("here").executes(TerminalCommands::here))
                                        .then(Commands.literal("remove").executes(TerminalCommands::remove))
                                        .then(Commands.literal("list").executes(TerminalCommands::list))
                                        .then(Commands.literal("clear").executes(TerminalCommands::clear))
                                        .then(Commands.literal("keeps").executes(TerminalCommands::keeps))))));
    }

    /**
     * Puts a lectern terminal beside the Nexus of every keep that has none, for keeps founded before terminals existed.
     * NPC keeps, keeps whose lodestone is lifted and keeps whose lectern is lifted are skipped. Runs from the command only, never from a chunk event.
     */
    private static int keeps(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        charter.mc.CharterRuntime rt = CharterServer.runtime();
        if (!rt.knobs.keepTerminals) return fail(ctx, Text.get("terminal.keeps_off"));
        int placed = 0;
        int full = 0;
        for (charter.land.Core core : List.copyOf(rt.state.cores())) {
            if (!core.isKeep() || rt.state.org(core.owner).map(o -> o.npc).orElse(true)) continue;
            if (!Terminals.book().ofCore(core.id.value().toString()).isEmpty()) continue;
            // A lifted lectern is still the keep's: it is set down or goes back by itself.
            if (charter.mc.land.LecternLift.lifted(core.id)) continue;
            charter.mc.land.Fixtures.Fixture nexus = charter.mc.land.Fixtures.of(rt.state).first(core.id, charter.mc.land.Fixtures.Kind.NEXUS);
            if (nexus == null) continue;
            net.minecraft.server.level.ServerLevel level = Terminals.levelOf(ctx.getSource().getServer(), nexus.dimension());
            if (level == null) continue;
            if (Terminals.placeBeside(rt, level, new BlockPos(nexus.x(), nexus.y(), nexus.z()), core.id) != null) placed++;
            else full++;
        }
        int done = placed;
        int blocked = full;
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("terminal.keeps_done", "placed", done, "full", blocked)), true);
        return Math.max(placed, 1);
    }

    /** The block the player looks at within reach, or null. */
    private static BlockPos lookedAt(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) return block.getBlockPos();
        return null;
    }

    private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        BlockPos pos = lookedAt(player);
        if (pos == null) return fail(ctx, Text.get("terminal.look_at"));
        TerminalBook.Spot spot = Terminals.spot(player.level(), pos);
        if (!Terminals.add(spot, null)) return fail(ctx, Text.get("terminal.taken", "x", spot.x(), "y", spot.y(), "z", spot.z()));
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("terminal.added", "x", spot.x(), "y", spot.y(), "z", spot.z())), true);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        BlockPos pos = player.blockPosition();
        TerminalBook.Spot spot = Terminals.spot(player.level(), pos);
        if (Terminals.book().at(spot) != null) return fail(ctx, Text.get("terminal.taken", "x", spot.x(), "y", spot.y(), "z", spot.z()));
        if (!player.level().getBlockState(pos).canBeReplaced()) return fail(ctx, Text.get("terminal.here_blocked"));
        // The sloped top faces the way the operator looks, so a player coming from there reads it.
        player.level().setBlockAndUpdate(pos, Blocks.LECTERN.defaultBlockState()
                .setValue(LecternBlock.FACING, player.getDirection().getOpposite()));
        Terminals.add(spot, null);
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("terminal.placed", "x", spot.x(), "y", spot.y(), "z", spot.z())), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        BlockPos pos = lookedAt(player);
        TerminalBook.Spot spot = pos == null ? null : Terminals.spot(player.level(), pos);
        if (spot == null || Terminals.book().at(spot) == null) spot = Terminals.spot(player.level(), player.getOnPos());
        if (!Terminals.remove(spot)) return fail(ctx, Text.get("terminal.none_here"));
        TerminalBook.Spot gone = spot;
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("terminal.removed", "x", gone.x(), "y", gone.y(), "z", gone.z())), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        List<TerminalBook.Terminal> all = Terminals.book().all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("terminal.list_none")), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("terminal.list_head", "count", all.size())), false);
        for (TerminalBook.Terminal t : all) {
            TerminalBook.Spot s = t.spot();
            String tp = "/execute in " + s.dimension() + " run tp @s " + s.x() + " " + (s.y() + 1) + " " + s.z();
            String key = t.core() == null ? "terminal.list_line" : "terminal.list_line_keep";
            Component line = Component.literal(Text.get(key, "x", s.x(), "y", s.y(), "z", s.z(), "dimension", s.dimension()))
                    .withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                            .withClickEvent(new ClickEvent.RunCommand(tp))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return all.size();
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        int n = Terminals.clear();
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("terminal.cleared", "count", n)), true);
        return Math.max(n, 1);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
