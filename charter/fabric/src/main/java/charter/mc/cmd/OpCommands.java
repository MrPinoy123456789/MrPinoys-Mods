package charter.mc.cmd;

import charter.contract.Contract;
import charter.contract.Justice;
import charter.contract.Term;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Deciders;
import charter.gov.Office;
import charter.gov.Vote;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import charter.org.Organization;
import charter.org.PlayerRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The operators' look-up and housekeeping commands, beside the older ones in {@link AdminCommands} and
 * {@link LandCommands} (none of those is renamed). Everything an operator asks about the realm has a one line answer
 * here, and every change goes through an operator action in core and the siege log.
 *
 * <pre>
 *   /charter admin help                          every operator command, one line each
 *   /charter admin kingdoms                      each kingdom: its Lords, members and Nexus
 *   /charter admin keeps                         every keep's Nexus block, with a link to go there
 *   /charter admin goto &lt;faction&gt;               go to a kingdom's Nexus, in any dimension
 *   /charter admin player &lt;player&gt;              kingdom, race, offices, held or banished, last seen
 *   /charter admin votes                         open votes with their numbers, and a link to close each
 *   /charter admin faction remove &lt;faction&gt; &lt;player&gt;   take a member out (a Lord loses the seat too)
 *   /charter admin faction rename &lt;faction&gt; &lt;name&gt;     a new name; the id and everything held stay
 * </pre>
 */
public final class OpCommands {

    private OpCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("help").executes(OpCommands::help))
                        .then(Commands.literal("kingdoms").executes(OpCommands::kingdoms))
                        .then(Commands.literal("keeps").executes(OpCommands::keeps))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("faction", StringArgumentType.string()).suggests(Resolve::factions)
                                        .executes(OpCommands::goTo)))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                        .executes(OpCommands::player)))
                        .then(Commands.literal("votes").executes(OpCommands::votes))
                        .then(Commands.literal("faction")
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("faction", StringArgumentType.string()).suggests(Resolve::factions)
                                                .then(Commands.argument("player", StringArgumentType.string())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                                        .executes(OpCommands::remove))))
                                .then(Commands.literal("rename")
                                        .then(Commands.argument("faction", StringArgumentType.string()).suggests(Resolve::factions)
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .executes(OpCommands::rename))))))));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static boolean running(CommandContext<CommandSourceStack> ctx) {
        if (CharterServer.running()) return true;
        ctx.getSource().sendFailure(Chat.bad(Text.get("book.not_running")));
        return false;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, Component line) {
        ctx.getSource().sendSuccess(() -> line, false);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Chat.good(text), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }

    // ---- help ----

    /** One line per operator command, from the lang file so a server can reword it. */
    static final List<String> HELP = List.of("menu", "help", "kingdoms", "keeps", "goto", "player", "votes", "vote_close",
            "faction_create", "faction_add", "faction_remove", "faction_leader", "faction_coleader", "faction_unlord",
            "faction_rename", "faction_move", "faction_race", "faction_racename", "faction_mixed", "faction_admin",
            "faction_claims", "dissolve", "give", "areas", "time", "save", "repair", "npc", "granary", "supply", "chronicle");

    private static int help(CommandContext<CommandSourceStack> ctx) {
        say(ctx, Chat.header(Text.get("admin.help.header")));
        for (String key : HELP) say(ctx, Chat.line(Text.get("admin.help." + key)));
        return HELP.size();
    }

    // ---- kingdoms and keeps ----

    private static int kingdoms(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        List<Organization> all = Resolve.factions(rt).stream().filter(Organization::isTopLevel).toList();
        say(ctx, Chat.header(Text.get("admin.kingdoms.header", "count", all.size())));
        for (Organization org : all) {
            say(ctx, Chat.line(Text.get("admin.kingdoms.line", "name", org.name(), "lords", lords(rt, org.id),
                    "members", rt.state.members(org.id).size(), "nexus", nexusText(rt, org.id))));
        }
        return all.size();
    }

    private static int keeps(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        int n = 0;
        say(ctx, Chat.header(Text.get("admin.keeps.header")));
        for (Organization org : Resolve.factions(rt)) {
            for (Core core : rt.state.coresOf(org.id)) {
                if (!core.isKeep()) continue;
                n++;
                MutableComponent line = Chat.line(Text.get("admin.keeps.line", "name", org.name(), "nexus", coreText(rt, core)) + " ");
                line.append(Chat.run(Text.get("admin.keeps.go"), "/charter admin goto " + org.id));
                say(ctx, line);
            }
        }
        if (n == 0) say(ctx, Chat.line(Text.get("admin.keeps.none")));
        return n;
    }

    /** "Alice and Bob", "Alice", or "none". */
    static String lords(CharterRuntime rt, OrgId org) {
        List<String> names = new ArrayList<>();
        Deciders.of(rt.state, org).ifPresent(office -> {
            for (Office.Seat seat : office.holders()) names.add(rt.describe.player(seat.player()));
        });
        return names.isEmpty() ? Text.get("admin.none") : String.join(Text.get("book.and"), names);
    }

    /** Where a kingdom's keep Nexus stands, or that it has no keep. */
    static String nexusText(CharterRuntime rt, OrgId org) {
        Optional<Core> keep = rt.state.coresOf(rt.state.root(org)).stream().filter(Core::isKeep).findFirst();
        return keep.map(core -> coreText(rt, core)).orElse(Text.get("admin.kingdoms.no_keep"));
    }

    static String coreText(CharterRuntime rt, Core core) {
        Fixtures.Fixture nexus = Fixtures.of(rt.state).first(core.id, Fixtures.Kind.NEXUS);
        if (nexus == null) {
            return Text.get("admin.keeps.no_nexus", "x", core.center.x() * 16 + 8, "z", core.center.z() * 16 + 8,
                    "dimension", core.center.dimension());
        }
        return Text.get("admin.keeps.at", "x", nexus.x(), "y", nexus.y(), "z", nexus.z(), "dimension", nexus.dimension());
    }

    // ---- going there ----

    private static int goTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!running(ctx)) return 0;
        ServerPlayer op = ctx.getSource().getPlayerOrException();
        CharterRuntime rt = rt();
        String wanted = StringArgumentType.getString(ctx, "faction");
        Optional<Organization> org = Resolve.faction(rt, wanted);
        if (org.isEmpty()) return fail(ctx, Text.get("land.admin.no_faction", "name", wanted));
        Optional<Core> keep = rt.state.coresOf(rt.state.root(org.get().id)).stream().filter(Core::isKeep).findFirst();
        if (keep.isEmpty()) return fail(ctx, Text.get("admin.goto.no_keep", "name", org.get().name()));
        Fixtures.Fixture nexus = Fixtures.of(rt.state).first(keep.get().id, Fixtures.Kind.NEXUS);
        String dimension = nexus != null ? nexus.dimension() : keep.get().center.dimension();
        ServerLevel level = null;
        for (ServerLevel l : op.level().getServer().getAllLevels()) {
            if (Protection.dimension(l).equals(dimension)) level = l;
        }
        if (level == null) return fail(ctx, Text.get("admin.goto.no_world", "dimension", dimension));
        double x, y, z;
        if (nexus != null) {
            x = nexus.x() + 0.5;
            y = nexus.y() + 1.0;
            z = nexus.z() + 0.5;
        } else {
            int bx = keep.get().center.x() * 16 + 8;
            int bz = keep.get().center.z() * 16 + 8;
            level.getChunk(bx >> 4, bz >> 4);
            x = bx + 0.5;
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            z = bz + 0.5;
        }
        op.teleportTo(level, x, y, z, Set.of(), op.getYRot(), op.getXRot(), true);
        return ok(ctx, Text.get("admin.goto.done", "name", org.get().name()));
    }

    // ---- a player ----

    private static int player(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        Optional<Resolve.Who> who = Resolve.player(ctx, rt);
        if (who.isEmpty()) return fail(ctx, Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player")));
        for (String line : playerLines(rt, who.get())) say(ctx, Chat.line(line));
        return 1;
    }

    /** What an operator wants to know about a player, as lines. Also the body of the menu's player page. */
    public static List<String> playerLines(CharterRuntime rt, Resolve.Who who) {
        PlayerId id = who.id();
        long now = rt.clock.now();
        List<String> out = new ArrayList<>();
        long lastSeen = rt.state.knownPlayer(id).map(p -> p.lastSeen).orElse(0L);
        out.add(Text.get(who.online() != null ? "admin.player.online" : "admin.player.offline", "name", who.name(),
                "uuid", id.value(), "ago", Chat.duration(now - lastSeen)));
        var member = rt.state.membership(id);
        if (member.isPresent()) {
            OrgId org = member.get().org();
            List<String> offices = rt.state.offices(org).stream().filter(o -> o.holds(id)).map(o -> o.name).toList();
            out.add(Text.get("admin.player.member", "org", rt.describe.org(org), "time", Chat.duration(now - member.get().joinedAt()),
                    "offices", offices.isEmpty() ? Text.get("admin.none") : String.join(", ", offices)));
        } else {
            out.add(Text.get("admin.player.no_kingdom"));
        }
        out.add(Text.get("admin.player.race", "race", rt.state.raceOf(id).map(rt.state::raceLabel).orElse(Text.get("admin.none"))));
        Optional<Contract> held = Justice.holding(rt.state, rt.knobs, id, now);
        if (held.isPresent()) {
            Contract c = held.get();
            Term term = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
            long left = term == null ? 0 : Justice.until(c, term, rt.knobs) - now;
            out.add(Text.get("admin.player.held", "by", rt.describe.org(c.imposer.org()), "time", Chat.duration(left)));
        }
        for (Organization org : rt.state.orgs()) {
            if (org.isTopLevel() && org.exists() && Justice.banished(rt.state, rt.knobs, id, org.id, now)) {
                out.add(Text.get("admin.player.banished", "org", org.name()));
            }
        }
        return out;
    }

    // ---- votes ----

    private static int votes(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        long now = rt.clock.now();
        List<Vote> open = rt.state.votes().stream().filter(Vote::open).toList();
        say(ctx, Chat.header(Text.get("admin.votes.header", "count", open.size())));
        for (Vote vote : open) {
            long number = Pages.number(vote);
            MutableComponent line = Chat.line(Text.get("admin.votes.line", "number", number, "org", rt.describe.org(vote.org),
                    "about", rt.notices.about(vote), "time", Chat.duration(vote.closesAt - now)) + " ");
            line.append(Chat.run(Text.get("admin.votes.close"), "/charter admin vote close " + number));
            say(ctx, line);
        }
        if (open.isEmpty()) say(ctx, Chat.line(Text.get("admin.votes.none")));
        return open.size();
    }

    // ---- members and names ----

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        String wanted = StringArgumentType.getString(ctx, "faction");
        Optional<Organization> org = Resolve.faction(rt, wanted);
        if (org.isEmpty()) return fail(ctx, Text.get("land.admin.no_faction", "name", wanted));
        Optional<Resolve.Who> who = Resolve.player(ctx, rt);
        if (who.isEmpty()) return fail(ctx, Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player")));
        Outcome outcome = rt.engine.run(new Actor.Admin(ctx.getSource().getTextName()),
                new charter.org.actions.Expel(org.get().id, who.get().id()));
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(ctx, Chat.reason(reason));
        return ok(ctx, Text.get("admin.removed", "player", who.get().name(), "name", org.get().name()));
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        if (!running(ctx)) return 0;
        CharterRuntime rt = rt();
        String wanted = StringArgumentType.getString(ctx, "faction");
        Optional<Organization> org = Resolve.faction(rt, wanted);
        if (org.isEmpty()) return fail(ctx, Text.get("land.admin.no_faction", "name", wanted));
        String before = org.get().name();
        String name = StringArgumentType.getString(ctx, "name");
        Outcome outcome = rt.engine.run(new Actor.Admin(ctx.getSource().getTextName()), new charter.org.actions.Rename(org.get().id, name));
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(ctx, Chat.reason(reason));
        return ok(ctx, Text.get("admin.renamed", "from", before, "to", org.get().name()));
    }

    /** For the menu: the known players, most recently seen first. */
    public static List<PlayerRecord> knownPlayers(CharterRuntime rt) {
        List<PlayerRecord> all = new ArrayList<>(rt.state.allPlayers());
        all.removeIf(p -> p.name == null || p.name.isBlank());
        all.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return all;
    }
}
