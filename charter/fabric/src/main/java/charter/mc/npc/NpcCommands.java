package charter.mc.npc;

import charter.id.ChunkKey;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.land.Protection;
import charter.npc.NpcBook;
import charter.npc.NpcPlacement;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 *   /charter admin npc clearruins &lt;x&gt; &lt;z&gt;           clear what a fallen site left behind, outside every claim
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
                                .then(Commands.literal("respawn").executes(NpcCommands::respawn))
                                .then(Commands.literal("clearruins")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(NpcCommands::clearRuins))))))));
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
                    "x", site.chunk().x() * 16 + 8, "z", site.chunk().z() * 16 + 8, "tier", site.tier(),
                    "supply", charter.npc.NpcScheduler.live(book, site, CharterServer.runtime().knobs).supply,
                    "pool", charter.npc.NpcScheduler.live(book, site, CharterServer.runtime().knobs).pool)), false);
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

    private static final int RUIN_RADIUS = 10;

    /** Clears the raiders' blocks around a point, but never inside a claim. */
    private static int clearRuins(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        var level = ctx.getSource().getLevel();
        var rt = CharterServer.runtime();
        String dimension = Protection.dimension(level);
        for (int cx = (x - RUIN_RADIUS) >> 4; cx <= (x + RUIN_RADIUS) >> 4; cx++) {
            for (int cz = (z - RUIN_RADIUS) >> 4; cz <= (z + RUIN_RADIUS) >> 4; cz++) {
                if (rt.claims.at(dimension, cx, cz) != null) return fail(ctx, Text.get("admin.npc_ruins_claimed", "x", x, "z", z));
            }
        }
        level.getChunk(x >> 4, z >> 4);
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        int cleared = NpcWorld.clearPalette(level, x, z, RUIN_RADIUS, top - 14, top + 8);
        if (cleared == 0) return fail(ctx, Text.get("admin.npc_ruins_none", "x", x, "z", z));
        return ok(ctx, Text.get("admin.npc_ruins_done", "count", cleared, "x", x, "z", z));
    }

    private static int respawn(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        return ok(ctx, NpcWorld.runOnce(ctx.getSource().getServer(), new Random()));
    }
}
