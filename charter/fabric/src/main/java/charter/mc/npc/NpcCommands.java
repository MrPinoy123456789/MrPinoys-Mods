package charter.mc.npc;

import charter.id.ChunkKey;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.land.Protection;
import charter.npc.NpcBook;
import charter.npc.NpcPlacement;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Locale;
import java.util.Random;

/**
 * <pre>
 *   /charter admin npc spawn &lt;stronghold|outpost&gt;   raise one in the chunk the operator stands in (the tier follows the distance from spawn)
 *   /charter admin npc list                          every site that stands
 *   /charter admin npc clear &lt;id&gt;                    take one down, with its faction and defenders
 *   /charter admin npc respawn                       run the scheduler once
 * </pre>
 * Operators only. Every use is written to the siege log like the other admin commands.
 */
public final class NpcCommands {

    private NpcCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("npc")
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"stronghold", "outpost"}, b))
                                                .executes(NpcCommands::spawn)))
                                .then(Commands.literal("list").executes(NpcCommands::list))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(NpcCommands::clear)))
                                .then(Commands.literal("respawn").executes(NpcCommands::respawn))))));
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        CharterServer.runtime().siegeLog.admin(CharterServer.runtime().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Chat.good(text), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        NpcBook.Kind kind;
        try {
            kind = NpcBook.Kind.valueOf(StringArgumentType.getString(ctx, "kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail(ctx, Text.get("admin.npc_kind"));
        }
        var at = ctx.getSource().getPosition();
        ChunkKey chunk = new ChunkKey(Protection.dimension(ctx.getSource().getLevel()), ((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4);
        if (!NpcPlacement.acceptable(chunk, CharterServer.runtime().state, CharterServer.runtime().knobs, java.util.List.of())) {
            return fail(ctx, Text.get("admin.npc_no_room"));
        }
        String done = NpcWorld.raise(ctx.getSource().getServer(), kind, chunk, false);
        return ok(ctx, done);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var book = CharterServer.runtime().state.npc;
        if (book.sites.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.npc_none", "waiting", book.respawns.size())), false);
            return 1;
        }
        for (NpcBook.Site site : book.sites) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.npc_line", "id", site.id(),
                    "kind", site.kind().name().toLowerCase(Locale.ROOT), "org", CharterServer.runtime().describe.org(site.org()),
                    "x", site.chunk().x() * 16 + 8, "z", site.chunk().z() * 16 + 8, "tier", site.tier())), false);
        }
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        long id = LongArgumentType.getLong(ctx, "id");
        String name = NpcWorld.clear(ctx.getSource().getServer(), id);
        if (name == null) return fail(ctx, Text.get("admin.npc_missing", "id", id));
        return ok(ctx, Text.get("admin.npc_cleared", "org", name));
    }

    private static int respawn(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        return ok(ctx, NpcWorld.runOnce(ctx.getSource().getServer(), new Random()));
    }
}
