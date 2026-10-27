package charter.mc.scenario;

import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scenario commands (PLAN.md §12.3), for an operator setting up a playtest:
 *
 * <pre>
 *   /charter admin scenario list
 *   /charter admin scenario run &lt;name&gt; [with &lt;player&gt; ...]          around the operator's chunk
 *   /charter admin scenario runat &lt;x&gt; &lt;z&gt; &lt;name&gt; [with &lt;player&gt; ...]   around a block, in the overworld (for the console)
 *   /charter admin scenario clear                                    take down every faction a scenario made
 *   /charter admin note &lt;text&gt;                                        a check-in, written to the siege log with the time
 * </pre>
 *
 * Players are named for the scenario's roles in order; a role with nobody named is
 * an offline stand-in. Every run is written to the siege log as {@code admin}.
 */
public final class ScenarioCommands {

    private ScenarioCommands() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(CharterMod.MOD_ID).resolve("scenarios");
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("note")
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ScenarioCommands::note)))
                        .then(Commands.literal("scenario")
                                .then(Commands.literal("list").executes(ScenarioCommands::list))
                                .then(Commands.literal("clear").executes(ScenarioCommands::clear))
                                .then(Commands.literal("run").then(named(false)))
                                .then(Commands.literal("runat")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(named(true)))))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> named(boolean at) {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(scenarios().keySet(), b))
                .executes(ctx -> run(ctx, at, ""))
                .then(Commands.literal("with")
                        .then(Commands.argument("players", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, at, StringArgumentType.getString(ctx, "players")))));
    }

    private static Map<String, Scenario> scenarios() {
        ScenarioFiles.writeDefaults(dir(), CharterMod.LOG::warn);
        return ScenarioFiles.all(dir(), CharterMod.LOG::warn);
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Map<String, Scenario> all = scenarios();
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("scenario.list_header", "count", all.size())), false);
        for (Map.Entry<String, Scenario> e : all.entrySet()) {
            String line = Text.get("scenario.list_line", "name", e.getKey(), "roles", String.join(" ", e.getValue().castOrder()), "setup", e.getValue().setup);
            ctx.getSource().sendSuccess(() -> Chat.line(line), false);
        }
        return all.size();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean at, String players) {
        if (!CharterServer.running()) return 0;
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        Scenario scenario = scenarios().get(name);
        if (scenario == null) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("scenario.unknown", "name", name)));
            return 0;
        }
        ServerLevel level;
        int chunkX;
        int chunkZ;
        if (at) {
            level = ctx.getSource().getServer().overworld();
            chunkX = IntegerArgumentType.getInteger(ctx, "x") >> 4;
            chunkZ = IntegerArgumentType.getInteger(ctx, "z") >> 4;
        } else {
            level = ctx.getSource().getLevel();
            chunkX = (int) Math.floor(ctx.getSource().getPosition().x) >> 4;
            chunkZ = (int) Math.floor(ctx.getSource().getPosition().z) >> 4;
        }
        List<String> names = players.isBlank() ? List.of() : Arrays.stream(players.trim().split("\\s+")).toList();
        // A scenario that names its player roles (the tutorial) is for whoever ran it: without a name, that is the operator.
        if (names.isEmpty() && !scenario.players.isEmpty() && ctx.getSource().getPlayer() != null) {
            names = List.of(ctx.getSource().getPlayer().getPlainTextName());
        }
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ScenarioRunner.Report report = new ScenarioRunner(ctx.getSource().getServer(), level, chunkX, chunkZ, ctx.getSource().getTextName())
                .run(scenario, names);
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("scenario.header", "name", name, "x", chunkX, "z", chunkZ)), true);
        for (String line : report.lines()) ctx.getSource().sendSuccess(() -> Chat.line("  " + line), false);
        if (!report.ok()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("scenario.stopped")));
            return 0;
        }
        if (!scenario.tries.isBlank()) ctx.getSource().sendSuccess(() -> Chat.good(Text.get("scenario.tries", "tries", scenario.tries)), false);
        return 1;
    }

    /** The operator's check-in notes (PLAN.md §12.4), so the debrief lines them up with the siege. */
    private static int note(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        long now = rt().clock.now();
        com.google.gson.JsonObject line = new com.google.gson.JsonObject();
        line.addProperty("t", java.time.Instant.ofEpochMilli(now).toString());
        line.addProperty("siegeT", rt().state.siegeClock.now());
        line.addProperty("ev", "note");
        line.addProperty("who", ctx.getSource().getTextName());
        line.addProperty("text", StringArgumentType.getString(ctx, "text"));
        rt().siegeLog.write(line, now);
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("scenario.noted")), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return 0;
        int n = ScenarioRunner.clear(rt());
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("scenario.cleared", "count", n)), true);
        return n;
    }
}
