package charter.mc.pad;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.cmd.Teleports;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The operator's pad tools, a separate {@code /charter admin} tree that Brigadier merges with the others.
 *
 * <pre>
 *   /charter admin pad add &lt;kind&gt;   the block you stand on becomes a pad (kinds: keep)
 *   /charter admin pad remove       the pad you stand on, or else the one you look at
 *   /charter admin pad list         every pad, each a link that takes you there
 *   /charter admin pad clear        removes every pad
 * </pre>
 */
public final class PadCommands {

    /** How far an operator may look at a pad to remove it. */
    static final double REACH = 8;

    private PadCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("admin")
                                .requires(charter.mc.CharterPerms::admin)
                                .then(Commands.literal("pad")
                                        .requires(charter.mc.CharterPerms::admin)
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(PadBook.kindNames(), b))
                                                        .executes(PadCommands::add)))
                                        .then(Commands.literal("remove").executes(PadCommands::remove))
                                        .then(Commands.literal("list").executes(PadCommands::list))
                                        .then(Commands.literal("clear").executes(PadCommands::clear))))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        String name = StringArgumentType.getString(ctx, "kind");
        Optional<Teleports.Kind> kind = PadBook.kind(name);
        if (kind.isEmpty()) return fail(ctx, Text.get("pad.bad_kind", "kinds", String.join(", ", PadBook.kindNames())));
        PadBook.Spot spot = Pads.spot(player.level(), Pads.feet(player));
        if (!Pads.add(spot, kind.get())) return fail(ctx, Text.get("pad.taken", "x", spot.x(), "y", spot.y(), "z", spot.z()));
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("pad.added", "kind", kind.get().name().toLowerCase(Locale.ROOT),
                "x", spot.x(), "y", spot.y(), "z", spot.z())), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        PadBook.Spot spot = Pads.spot(player.level(), Pads.feet(player));
        if (Pads.book().at(spot) == null) {
            HitResult hit = player.pick(REACH, 0.0F, false);
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
                spot = Pads.spot(player.level(), block.getBlockPos());
            }
        }
        if (!Pads.remove(spot)) return fail(ctx, Text.get("pad.none_here"));
        PadBook.Spot gone = spot;
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("pad.removed", "x", gone.x(), "y", gone.y(), "z", gone.z())), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        List<PadBook.Pad> pads = Pads.book().all();
        if (pads.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("pad.list_none")), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("pad.list_head", "count", pads.size())), false);
        for (PadBook.Pad pad : pads) {
            PadBook.Spot s = pad.spot();
            String tp = "/execute in " + s.dimension() + " run tp @s " + s.x() + " " + (s.y() + 1) + " " + s.z();
            Component line = Component.literal(Text.get("pad.list_line", "kind", pad.kind().name().toLowerCase(Locale.ROOT),
                            "x", s.x(), "y", s.y(), "z", s.z(), "dimension", s.dimension()))
                    .withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                            .withClickEvent(new ClickEvent.RunCommand(tp))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return pads.size();
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        int n = Pads.clear();
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("pad.cleared", "count", n)), true);
        return Math.max(n, 1);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
