package charter.mc.justice;

import charter.contract.Basis;
import charter.contract.BasisCaps;
import charter.contract.Contract;
import charter.contract.Custody;
import charter.contract.Justice;
import charter.contract.Term;
import charter.contract.actions.Confine;
import charter.contract.actions.DepositPersonal;
import charter.contract.actions.Execute;
import charter.contract.actions.FineCustody;
import charter.contract.actions.Pardon;
import charter.contract.actions.Release;
import charter.contract.actions.Sentence;
import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.MemberRecord;
import charter.time.Durations;
import charter.treasury.Currency;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Justice on the typed surface and the pages (PLAN.md §7.3, §7.4):
 *
 * <pre>
 *   /charter cell mark|remove|list [name]    cells of your keeps
 *   /charter justice                         who is in custody or held, with links
 *   /charter justice sentence &lt;player&gt;       the sentencing page for someone in custody
 *   /charter safe                            your own deposit, where a fine is taken from
 *   /charter safe deposit &lt;diamonds&gt;
 * </pre>
 *
 * <p>Every link runs an action through the engine as the clicking player, so core
 * checks the power, the caps and the week; the page only offers what the caps
 * allow and says what it will do. Nothing here computes a duration or a cap.
 */
public final class JusticeCommands {

    /** The sentence lengths the page offers; core cuts each to its cap and to what is left of the week. */
    private static final long[] HOLDS = {Durations.hours(1), Durations.hours(6), Durations.hours(24)};
    private static final long[] FINES = {4, 16};
    private static final long EXECUTION_WAIT = Durations.minutes(5);
    private static final long EXECUTION_BANISH = Durations.hours(24);

    private JusticeCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("cell")
                                .then(Commands.literal("mark").then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> cell(ctx, true))))
                                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> cell(ctx, false))))
                                .then(Commands.literal("list").executes(JusticeCommands::cells)))
                        .then(Commands.literal("justice")
                                .executes(ctx -> page(ctx, JusticeCommands::justicePage))
                                .then(Commands.literal("sentence").then(Commands.argument("player", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                        .executes(JusticeCommands::sentence))))
                        .then(Commands.literal("safe")
                                .executes(ctx -> page(ctx, JusticeCommands::safePage))
                                .then(Commands.literal("deposit").then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(JusticeCommands::deposit))))));
    }

    private interface PageFn {
        void show(ServerPlayer player, CharterRuntime rt);
    }

    private static int page(CommandContext<CommandSourceStack> ctx, PageFn page) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        page.show(player, CharterServer.runtime());
        return 1;
    }

    private static PlayerId id(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static Optional<OrgId> orgOf(CharterRuntime rt, ServerPlayer player) {
        return rt.state.membership(id(player)).map(MemberRecord::org);
    }

    static String reasonText(Reason reason) {
        String key = "reason." + reason.key();
        return Text.has(key) ? Text.get(key) : reason.text();
    }

    /** Runs an action as the player and says what happened. */
    static boolean run(ServerPlayer player, Action action, String done) {
        CharterRuntime rt = CharterServer.runtime();
        Outcome outcome = rt.engine.run(new Actor.Player(id(player)), action);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            player.sendSystemMessage(Chat.bad(reasonText(reason)));
            return false;
        }
        player.sendSystemMessage(Chat.good(done));
        return true;
    }

    // ---- cells ----

    private static int cell(CommandContext<CommandSourceStack> ctx, boolean mark) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        Cells.Done done = mark ? Cells.mark(player, name) : Cells.remove(player, name);
        player.sendSystemMessage(done.ok() ? Chat.good(done.text()) : Chat.bad(done.text()));
        return done.ok() ? 1 : 0;
    }

    private static int cells(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        if (org.isEmpty()) {
            player.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return 0;
        }
        List<CellBook.Cell> own = Cells.ownCells(rt, org.get());
        if (own.isEmpty()) player.sendSystemMessage(Chat.line(Text.get("cell.none")));
        for (CellBook.Cell cell : own) {
            player.sendSystemMessage(Chat.line(Text.get("cell.line", "name", cell.name, "site", rt.describe.core(cell.keep),
                    "count", cell.blocks.size(), "held", Cells.occupied(rt, cell) ? Text.get("cell.held") : Text.get("cell.empty"))));
        }
        return own.size();
    }

    // ---- the justice page ----

    /** Who is in custody and who is held, with the links a judge needs. */
    public static void justicePage(ServerPlayer player, CharterRuntime rt) {
        Optional<OrgId> org = orgOf(rt, player);
        if (org.isEmpty()) {
            player.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return;
        }
        OrgId root = rt.state.root(org.get());
        long now = rt.clock.now();
        player.sendSystemMessage(Chat.header(Text.get("justice.header", "org", rt.describe.org(root))));
        int shown = 0;
        for (Custody custody : new ArrayList<>(rt.state.contracts.custodies())) {
            if (!rt.state.root(custody.faction()).equals(root) || !Justice.custodyLive(custody, rt.knobs, now)) continue;
            shown++;
            PlayerId prisoner = custody.prisoner();
            player.sendSystemMessage(Chat.line(Text.get("justice.in_custody", "player", rt.describe.player(prisoner),
                    "time", Chat.duration(custodyLeft(rt, custody, now)))).append(Component.literal(" "))
                    .append(Chat.act(player, Text.get("justice.link_sentence"), Text.get("justice.link_sentence_hover"),
                            p -> sentencePage(p, rt, prisoner))));
        }
        for (Contract c : rt.state.contracts.live()) {
            if (c.basis != Basis.CUSTODY || !c.subject.isPlayer() || !rt.state.root(c.imposer.org()).equals(root)) continue;
            if (!c.holdsPlayer() && c.term(Term.Banishment.class) == null) continue;
            shown++;
            PlayerId prisoner = c.subject.player();
            MutableComponent line = Chat.line(Text.get("justice.held", "player", rt.describe.player(prisoner),
                    "terms", JusticeText.terms(c.terms))).append(Component.literal(" "));
            OrgId mine = org.get();
            line.append(Chat.act(player, Text.get("justice.link_release"), Text.get("justice.link_release_hover"),
                    p -> run(p, new Release(mine, prisoner), Text.get("justice.released", "player", rt.describe.player(prisoner)))));
            line.append(Component.literal(" ")).append(Chat.act(player, Text.get("justice.link_pardon"), Text.get("justice.link_pardon_hover"),
                    p -> run(p, new Pardon(mine, prisoner), Text.get("justice.pardoned", "player", rt.describe.player(prisoner)))));
            player.sendSystemMessage(line);
        }
        if (shown == 0) player.sendSystemMessage(Chat.line(Text.get("justice.nobody")));
    }

    private static long custodyLeft(CharterRuntime rt, Custody custody, long now) {
        return custody.since() + Durations.minutes(rt.knobs.custodyLapseMinutes) - now;
    }

    private static int sentence(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        CharterRuntime rt = CharterServer.runtime();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<PlayerId> target = find(rt, ctx.getSource(), name);
        if (target.isEmpty()) {
            player.sendSystemMessage(Chat.bad(Text.get("invite.no_player", "name", name)));
            return 0;
        }
        sentencePage(player, rt, target.get());
        return 1;
    }

    private static Optional<PlayerId> find(CharterRuntime rt, CommandSourceStack source, String name) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online == null) {
            try {
                online = source.getServer().getPlayerList().getPlayer(UUID.fromString(name));
            } catch (IllegalArgumentException notAUuid) {
                // a name, then
            }
        }
        if (online != null) return Optional.of(id(online));
        return rt.state.allPlayers().stream()
                .filter(p -> p.name.equalsIgnoreCase(name) || p.id.value().toString().equalsIgnoreCase(name))
                .findFirst().map(p -> p.id);
    }

    /**
     * The sentencing page for a prisoner in the viewer's faction's custody: every term the custody
     * basis allows, with its cap already applied, as a link. Also sent to an officer right after an arrest.
     */
    public static void sentencePage(ServerPlayer judge, CharterRuntime rt, PlayerId prisoner) {
        Optional<OrgId> org = orgOf(rt, judge);
        if (org.isEmpty()) {
            judge.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return;
        }
        long now = rt.clock.now();
        OrgId root = rt.state.root(org.get());
        Optional<Custody> custody = Jail.custodyOf(rt, prisoner).filter(c -> rt.state.root(c.faction()).equals(root));
        String name = rt.describe.player(prisoner);
        if (custody.isEmpty()) {
            judge.sendSystemMessage(Chat.bad(Text.get("justice.not_in_custody", "player", name)));
            return;
        }
        OrgId me = org.get();
        judge.sendSystemMessage(Chat.header(Text.get("justice.sentence_header", "player", name)));
        judge.sendSystemMessage(Chat.line(Text.get("justice.sentence_info", "time", Chat.duration(custodyLeft(rt, custody.get(), now)),
                "week", Chat.duration(rt.state.contracts.punishments.remaining(prisoner, now, rt.knobs)))));

        // Confinement, one row per cell of the faction.
        for (CellBook.Cell cell : Cells.ownCells(rt, me)) {
            MutableComponent row = Chat.line(Text.get("justice.row_cell", "cell", cell.name, "site", rt.describe.core(cell.keep)) + " ");
            for (long hold : HOLDS) {
                BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.Confinement(hold, cell.keep, cell.name), rt.knobs);
                if (!checked.ok()) continue;
                long shown = checked.term().durationMillis();
                row.append(Chat.act(judge, Chat.duration(shown), Text.get("justice.link_confine_hover", "cell", cell.name, "player", name),
                        p -> run(p, new Confine(me, prisoner, hold, cell.keep, cell.name),
                                Text.get("justice.done_confine", "player", name, "cell", cell.name)))).append(Component.literal(" "));
            }
            judge.sendSystemMessage(row);
        }
        if (rt.knobs.labourTerms) {
            for (Core keep : rt.state.cores()) {
                if (!keep.isKeep() || !rt.state.root(keep.owner).equals(root)) continue;
                MutableComponent row = Chat.line(Text.get("justice.row_bond", "site", rt.describe.core(keep.id)) + " ");
                for (long hold : HOLDS) {
                    BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.Tether(hold, keep.id), rt.knobs);
                    if (!checked.ok()) continue;
                    row.append(Chat.act(judge, Chat.duration(checked.term().durationMillis()), Text.get("justice.link_bond_hover", "player", name),
                            p -> run(p, new Sentence(me, prisoner, List.of(new Term.Tether(hold, keep.id))),
                                    Text.get("justice.done_bond", "player", name)))).append(Component.literal(" "));
                }
                judge.sendSystemMessage(row);
            }
        }
        // Execution: the wait, then banishment, both as capped by core.
        BasisCaps.Checked wait = BasisCaps.check(Basis.CUSTODY, new Term.RespawnDelay(EXECUTION_WAIT), rt.knobs);
        BasisCaps.Checked banish = BasisCaps.check(Basis.CUSTODY, new Term.Banishment(root, EXECUTION_BANISH), rt.knobs);
        if (wait.ok() && banish.ok()) {
            judge.sendSystemMessage(Chat.line(Text.get("justice.row_execute", "wait", Chat.duration(wait.term().durationMillis()),
                    "banish", Chat.duration(banish.term().durationMillis())) + " ")
                    .append(Chat.act(judge, Text.get("justice.link_execute"), Text.get("justice.link_execute_hover", "player", name),
                            p -> run(p, new Execute(me, prisoner, EXECUTION_WAIT, EXECUTION_BANISH),
                                    Text.get("justice.done_execute", "player", name)))));
        }
        // Fines, from the prisoner's own safe.
        MutableComponent fines = Chat.line(Text.get("justice.row_fine") + " ");
        for (long diamonds : FINES) {
            BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.Fine(diamonds), rt.knobs);
            if (!checked.ok()) continue;
            long shown = ((Term.Fine) checked.term()).diamonds();
            fines.append(Chat.act(judge, Text.get("justice.link_fine", "count", shown), Text.get("justice.link_fine_hover", "player", name),
                    p -> run(p, new FineCustody(me, prisoner, diamonds), Text.get("justice.done_fine", "player", name)))).append(Component.literal(" "));
        }
        judge.sendSystemMessage(fines);
    }

    // ---- the safe: a player's own deposit, the only place a fine is taken from ----

    public static void safePage(ServerPlayer player, CharterRuntime rt) {
        long balance = rt.state.contracts.vault(id(player)).balance(Currency.DIAMOND);
        player.sendSystemMessage(Chat.header(Text.get("safe.header")));
        player.sendSystemMessage(Chat.line(Text.get("safe.balance", "count", balance)));
    }

    private static int deposit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        return deposit(player, amount) ? 1 : 0;
    }

    /** Puts diamonds from the player's inventory into their own safe. */
    public static boolean deposit(ServerPlayer player, int amount) {
        int carried = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && CharterServer.itemId(stack).equals(Currency.DIAMOND.itemId)) carried += stack.getCount();
        }
        if (carried < amount) {
            player.sendSystemMessage(Chat.bad(Text.get("vault.not_carried", "count", carried,
                    "currency", charter.mc.Describe.currency(Currency.DIAMOND, carried))));
            return false;
        }
        if (!run(player, new DepositPersonal(id(player), Currency.DIAMOND, amount), Text.get("safe.deposited", "count", amount))) return false;
        int left = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !CharterServer.itemId(stack).equals(Currency.DIAMOND.itemId)) continue;
            int n = Math.min(left, stack.getCount());
            stack.shrink(n);
            left -= n;
        }
        player.getInventory().setChanged();
        return true;
    }
}
