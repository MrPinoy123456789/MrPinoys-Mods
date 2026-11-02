package charter.mc.cmd;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.Organization;
import charter.org.Ranks;
import charter.org.actions.AddRank;
import charter.org.actions.GiveRank;
import charter.org.actions.MoveRank;
import charter.org.actions.RemoveRank;
import charter.org.actions.RenameRank;
import charter.org.actions.SetRankPower;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Ranks for those who can type: the same rank actions the book runs, behind the same checks.
 *
 * <pre>
 *   /charter rank [list]                         your kingdom's ranks and who holds them
 *   /charter rank rename &lt;rank&gt; &lt;name&gt;          rename a rank (the top one too: King, Queen)
 *   /charter rank add &lt;name&gt;                     a new rank above the default one
 *   /charter rank remove &lt;rank&gt;                  its holders go back to the default rank
 *   /charter rank up|down &lt;rank&gt;                 move it one place
 *   /charter rank set &lt;player&gt; &lt;rank&gt;           give a member a rank
 *   /charter rank power &lt;rank&gt; &lt;power&gt; on|off   what a rank may do (the Lord only)
 *   /charter admin faction title &lt;kingdom&gt; ...    the same for operators, for any kingdom
 * </pre>
 *
 * A rank is named by its name (quoted when it has spaces) or its id; a name is the rest of the line.
 */
public final class RankCommands {

    private RankCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("charter").then(tree(Commands.literal("rank"), RankCommands::own, false)));
            dispatcher.register(Commands.literal("charter").then(Commands.literal("admin")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("faction").then(Commands.literal("title")
                            .then(tree(Commands.argument("kingdom", StringArgumentType.string())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(kingdoms(), b)), RankCommands::named, true))))));
        });
    }

    /** Finds the kingdom a command is about: the player's own, or the one an operator named. */
    private interface Where extends Function<CommandContext<CommandSourceStack>, Optional<OrgId>> {
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T tree(T root, Where where, boolean admin) {
        SuggestionProvider<CommandSourceStack> ranks = (c, b) -> SharedSuggestionProvider.suggest(rankNames(where, c), b);
        SuggestionProvider<CommandSourceStack> powers = (c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Power.values())
                .filter(p -> !Ranks.FORBIDDEN.contains(p)).map(p -> p.name().toLowerCase(Locale.ROOT)), b);
        return root
                .executes(c -> list(c, where))
                .then(Commands.literal("list").executes(c -> list(c, where)))
                .then(Commands.literal("rename")
                        .then(Commands.argument("rank", StringArgumentType.string()).suggests(ranks)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> act(c, where, admin, k -> new RenameRank(k, rankId(c, k), str(c, "name")),
                                                k -> Text.get("rank.cmd.renamed", "rank", clean(c, "name")))))))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> act(c, where, admin, k -> new AddRank(k, str(c, "name")),
                                        k -> Text.get("rank.cmd.added", "rank", clean(c, "name"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("rank", StringArgumentType.greedyString()).suggests(ranks)
                                .executes(c -> act(c, where, admin, k -> new RemoveRank(k, rankId(c, k)),
                                        k -> Text.get("rank.cmd.removed", "rank", rankArg(c))))))
                .then(Commands.literal("up")
                        .then(Commands.argument("rank", StringArgumentType.greedyString()).suggests(ranks)
                                .executes(c -> act(c, where, admin, k -> new MoveRank(k, rankId(c, k), true),
                                        k -> Text.get("rank.cmd.moved", "rank", rankArg(c))))))
                .then(Commands.literal("down")
                        .then(Commands.argument("rank", StringArgumentType.greedyString()).suggests(ranks)
                                .executes(c -> act(c, where, admin, k -> new MoveRank(k, rankId(c, k), false),
                                        k -> Text.get("rank.cmd.moved", "rank", rankArg(c))))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                .then(Commands.argument("rank", StringArgumentType.greedyString()).suggests(ranks)
                                        .executes(c -> {
                                            Optional<PlayerId> who = player(c);
                                            if (who.isEmpty()) return fail(c, Text.get("land.admin.no_player", "name", str(c, "player")));
                                            return act(c, where, admin, k -> new GiveRank(k, who.get(), rankId(c, k)),
                                                    k -> Text.get("rank.cmd.given", "player", rt().describe.player(who.get()),
                                                            "rank", rt().state.ranks.find(k, rankArg(c)).map(Ranks.Rank::name).orElse(rankArg(c))));
                                        }))))
                .then(Commands.literal("power")
                        .then(Commands.argument("rank", StringArgumentType.string()).suggests(ranks)
                                .then(Commands.argument("power", StringArgumentType.word()).suggests(powers)
                                        .then(Commands.literal("on").executes(c -> power(c, where, admin, true)))
                                        .then(Commands.literal("off").executes(c -> power(c, where, admin, false))))));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static String str(CommandContext<CommandSourceStack> c, String name) {
        return StringArgumentType.getString(c, name);
    }

    /**
     * The rank as typed. A greedy argument keeps the quotes tab completion puts round a name with spaces
     * ("Royal Commander"), so they are taken off here.
     */
    private static String rankArg(CommandContext<CommandSourceStack> c) {
        String typed = str(c, "rank").strip();
        if (typed.length() >= 2 && typed.startsWith("\"") && typed.endsWith("\"")) {
            typed = typed.substring(1, typed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return typed;
    }

    private static String clean(CommandContext<CommandSourceStack> c, String name) {
        return Ranks.clean(str(c, name)).orElse(str(c, name));
    }

    /** The rank id for the typed name or id; an unknown one passes through and the action refuses it. */
    private static String rankId(CommandContext<CommandSourceStack> c, OrgId kingdom) {
        String typed = rankArg(c);
        return rt().state.ranks.find(kingdom, typed).map(Ranks.Rank::id).orElse(typed);
    }

    private static Optional<OrgId> own(CommandContext<CommandSourceStack> c) {
        ServerPlayer player = c.getSource().getPlayer();
        if (player == null || !CharterServer.running()) return Optional.empty();
        return Ranks.kingdomOf(rt().state, new PlayerId(player.getUUID()));
    }

    private static Optional<OrgId> named(CommandContext<CommandSourceStack> c) {
        if (!CharterServer.running()) return Optional.empty();
        String name = str(c, "kingdom");
        return rt().state.orgs().stream().filter(o -> o.isTopLevel() && o.name().equalsIgnoreCase(name)).map(o -> o.id).findFirst();
    }

    private static List<String> kingdoms() {
        if (!CharterServer.running()) return List.of();
        return rt().state.orgs().stream().filter(o -> o.isTopLevel() && !o.npc).map(Organization::name)
                .map(StringArgumentType::escapeIfRequired).toList();
    }

    private static List<String> rankNames(Where where, CommandContext<CommandSourceStack> c) {
        try {
            Optional<OrgId> kingdom = where.apply(c);
            if (kingdom.isEmpty()) return List.of();
            return rt().state.ranks.ladder(kingdom.get()).stream().map(r -> StringArgumentType.escapeIfRequired(r.name())).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Optional<PlayerId> player(CommandContext<CommandSourceStack> c) {
        String name = str(c, "player");
        ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayer(name);
        if (online != null) return Optional.of(new PlayerId(online.getUUID()));
        try {
            return Optional.of(new PlayerId(java.util.UUID.fromString(name)));
        } catch (IllegalArgumentException notUuid) {
            return rt().state.allPlayers().stream().filter(p -> p.name.equalsIgnoreCase(name)).map(p -> p.id).findFirst();
        }
    }

    private static int act(CommandContext<CommandSourceStack> c, Where where, boolean admin,
                           Function<OrgId, Action> make, Function<OrgId, String> done) throws CommandSyntaxException {
        if (!CharterServer.running()) return fail(c, Text.get("book.not_running"));
        Optional<OrgId> kingdom = where.apply(c);
        if (kingdom.isEmpty()) {
            return fail(c, admin ? Text.get("land.admin.no_faction", "name", str(c, "kingdom")) : Text.get("rank.cmd.no_kingdom"));
        }
        Action action = make.apply(kingdom.get());
        if (!admin) {
            ServerPlayer player = c.getSource().getPlayerOrException();
            return CharterCommands.perform(player, action, done.apply(kingdom.get()));
        }
        Outcome outcome = rt().engine.run(new Actor.Admin(c.getSource().getTextName()), action);
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(c, Chat.reason(reason));
        rt().siegeLog.admin(rt().clock.now(), c.getSource().getTextName(), c.getInput());
        String text = done.apply(kingdom.get());
        c.getSource().sendSuccess(() -> Chat.good(text), true);
        return 1;
    }

    private static int power(CommandContext<CommandSourceStack> c, Where where, boolean admin, boolean on) throws CommandSyntaxException {
        Power power;
        try {
            power = Power.valueOf(str(c, "power").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail(c, Text.get("grant.bad_power", "power", str(c, "power")));
        }
        Power chosen = power;
        return act(c, where, admin, k -> new SetRankPower(k, rankId(c, k), chosen, on),
                k -> Text.get(on ? "rank.cmd.power_on" : "rank.cmd.power_off", "rank",
                        rt().state.ranks.find(k, rankArg(c)).map(Ranks.Rank::name).orElse(rankArg(c)),
                        "power", charter.mc.Describe.power(chosen)));
    }

    /** Every rank, top first, with who holds it. */
    private static int list(CommandContext<CommandSourceStack> c, Where where) {
        if (!CharterServer.running()) return fail(c, Text.get("book.not_running"));
        Optional<OrgId> kingdom = where.apply(c);
        if (kingdom.isEmpty()) return fail(c, Text.get("rank.cmd.no_kingdom"));
        CharterRuntime rt = rt();
        OrgId k = kingdom.get();
        c.getSource().sendSuccess(() -> Chat.header(Text.get("rank.cmd.head", "kingdom", rt.describe.org(k))), false);
        int place = 1;
        for (Ranks.Rank rank : rt.state.ranks.ladder(k)) {
            List<String> who = rt.state.ranks.holders(rt.state, k, rank.id()).stream().map(rt.describe::player).toList();
            String powers = charter.mc.book.RankPages.powersLine(k, rank.id());
            String line = Text.get("rank.cmd.line", "n", place++, "rank", rank.name(), "id", rank.id(),
                    "who", who.isEmpty() ? Text.get("book.ranks.nobody") : String.join(", ", who), "powers", powers);
            c.getSource().sendSuccess(() -> Chat.line(line), false);
        }
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
