package charter.mc.cmd;

import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Vote;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.store.Repairs;
import charter.org.MemberRecord;
import charter.org.PlayerRecord;
import charter.time.Durations;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;
import java.util.Optional;

/**
 * Operator tools under {@code /charter admin} (permission level 2), M7's part of
 * PLAN.md §7.3: the clocks, membership age, closing a vote, repair. Every one is
 * written to the siege log as {@code admin} with its arguments.
 *
 * <pre>
 *   /charter admin time                       show the siege clock and the time scale
 *   /charter admin time advance &lt;duration&gt;    run the siege forward: 90m, 2h, 3d, 1d12h
 *   /charter admin time scale &lt;factor&gt;        until restart; charter.json holds the lasting value
 *   /charter admin age &lt;player&gt; &lt;days&gt;        backdate a membership, to vote at once in a test
 *   /charter admin vote close &lt;number&gt;       count the ballots now
 *   /charter admin repair                     run the load repairs over the live state
 *   /charter admin save                       write state.json now
 * </pre>
 */
final class AdminCommands {

    private AdminCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("admin")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(AdminCommands::menu)
                .then(Commands.literal("time")
                        .executes(AdminCommands::showTime)
                        .then(Commands.literal("advance").requires(Gate::devTools)
                                .then(Commands.argument("duration", StringArgumentType.word()).executes(AdminCommands::advance)))
                        .then(Commands.literal("scale").requires(Gate::devTools)
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg(0.001, 100_000)).executes(AdminCommands::scale))))
                .then(Commands.literal("age").requires(Gate::devTools)
                        .then(Commands.argument("player", StringArgumentType.string())
                                .then(Commands.argument("days", IntegerArgumentType.integer(0, 3650)).executes(AdminCommands::age))))
                .then(Commands.literal("vote")
                        .then(Commands.literal("close")
                                .then(Commands.argument("number", LongArgumentType.longArg(1)).executes(AdminCommands::closeVote))))
                .then(Commands.literal("dissolve")
                        .then(Commands.argument("faction", StringArgumentType.string()).executes(AdminCommands::dissolve)))
                .then(Commands.literal("repair").executes(AdminCommands::repair))
                .then(Commands.literal("areas").executes(AdminCommands::areas))
                .then(Commands.literal("save").executes(AdminCommands::save));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    /** {@code /charter admin} on its own opens the operators' menu; the console is pointed at the typed commands. */
    private static int menu(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("book.not_running"));
        if (ctx.getSource().getPlayer() == null) return fail(ctx, Text.get("admin.menu.console"));
        charter.mc.book.AdminPages.open(ctx.getSource().getPlayer());
        return 1;
    }

    private static void logged(CommandContext<CommandSourceStack> ctx) {
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        logged(ctx);
        ctx.getSource().sendSuccess(() -> Chat.good(text), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }

    private static int showTime(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.time", "siege", Chat.duration(rt().state.siegeClock.now()),
                "scale", rt().knobs.timeScale)), false);
        return 1;
    }

    private static int advance(CommandContext<CommandSourceStack> ctx) {
        long millis = Args.duration(StringArgumentType.getString(ctx, "duration"));
        if (millis <= 0) return fail(ctx, Text.get("admin.bad_duration"));
        charter.mc.granary.Granaries.around(() -> rt().sim.advance(millis));
        return ok(ctx, Text.get("admin.advanced", "by", Chat.duration(millis), "siege", Chat.duration(rt().state.siegeClock.now())));
    }

    private static int scale(CommandContext<CommandSourceStack> ctx) {
        double factor = DoubleArgumentType.getDouble(ctx, "factor");
        rt().knobs.timeScale = factor;
        return ok(ctx, Text.get("admin.scaled", "scale", factor));
    }

    private static int age(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        int days = IntegerArgumentType.getInteger(ctx, "days");
        Optional<PlayerRecord> player = rt().state.allPlayers().stream()
                .filter(p -> p.name.equalsIgnoreCase(name) || p.id.value().toString().equalsIgnoreCase(name)).findFirst();
        Optional<MemberRecord> membership = player.flatMap(p -> rt().state.membership(p.id));
        if (membership.isEmpty()) return fail(ctx, Text.get("admin.not_member", "player", name));
        MemberRecord m = membership.get();
        rt().state.restoreMembership(new MemberRecord(m.player(), m.org(), m.joinedAt() - Durations.days(days), m.roles()));
        return ok(ctx, Text.get("admin.aged", "player", name, "days", days));
    }

    private static int closeVote(CommandContext<CommandSourceStack> ctx) {
        long number = LongArgumentType.getLong(ctx, "number");
        Optional<Vote> vote = rt().state.votes().stream().filter(v -> Pages.number(v) == number).findFirst();
        if (vote.isEmpty()) return fail(ctx, Text.get("admin.no_vote", "number", number));
        Outcome outcome = rt().engine.closeVote(vote.get().id);
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(ctx, Chat.reason(reason));
        return ok(ctx, Text.get("admin.vote_closed", "number", number, "result", Pages.result(vote.get())));
    }

    /** Dissolves a faction and its sub-factions: the world is cleaned first (Nexus blocks, granary links), then the rules run. */
    private static int dissolve(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "faction");
        var found = rt().state.orgs().stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst();
        if (found.isEmpty()) return fail(ctx, Text.get("admin.dissolve_none", "name", name));
        var doomed = charter.org.actions.DissolveOrg.subtree(rt().state, found.get().id);
        int members = 0, keeps = 0, claims = 0;
        List<String> names = new java.util.ArrayList<>();
        var granaries = charter.mc.granary.Granaries.service();
        for (var id : doomed) {
            names.add(rt().describe.org(id));
            members += rt().state.members(id).size();
            claims += rt().state.claimsOf(id).size();
            for (var core : List.copyOf(rt().state.coresOf(id))) {
                if (core.isKeep()) keeps++;
                charter.mc.war.SiegeWorld.physical(core.id, core.center, true, java.util.Map.of());
                if (granaries != null) granaries.book().remove(core.id);
                charter.mc.land.Fixtures.of(rt().state).removeCore(core.id);
            }
        }
        Outcome outcome = rt().engine.run(new charter.engine.Actor.Admin(ctx.getSource().getTextName()),
                new charter.org.actions.DissolveOrg(found.get().id));
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(ctx, Chat.reason(reason));
        rt().claims.rebuild();
        rt().repairNow();
        String told = names.size() == 1 ? names.get(0) : names.get(names.size() - 1) + " and its sub-factions ("
                + String.join(", ", names.subList(0, names.size() - 1)) + ")";
        return ok(ctx, Text.get("admin.dissolved", "orgs", told, "members", members, "keeps", keeps, "claims", claims));
    }

    private static int repair(CommandContext<CommandSourceStack> ctx) {
        Repairs repairs = rt().repairNow();
        return ok(ctx, Text.get("admin.repaired", "count", repairs.count()));
    }

    /** {@code /charter admin areas}: the public land map other mods read, one line per area. */
    private static int areas(CommandContext<CommandSourceStack> ctx) {
        rt().areas.flush();
        var areas = charter.api.CharterAreas.areas();
        ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.areas_head", "count", areas.size(),
                "version", charter.api.CharterAreas.version())), false);
        for (charter.api.Area a : areas) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.areas_line", "kind", a.kind().name().toLowerCase(java.util.Locale.ROOT),
                    "kingdom", a.kingdom(), "dimension", a.dimension(), "chunks", a.chunks().size(),
                    "x1", a.minBlockX(), "z1", a.minBlockZ(), "x2", a.maxBlockX(), "z2", a.maxBlockZ())), false);
        }
        return areas.size();
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        rt().save();
        return ok(ctx, Text.get("admin.saved", "file", rt().store.stateFile()));
    }
}
