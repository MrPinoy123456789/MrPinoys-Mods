package charter.mc.book;

import charter.id.OrgId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.AdminCommandRunner.Result;
import charter.mc.cmd.Gate;
import charter.npc.NpcBook;
import charter.org.Organization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The operators' menu, opened by {@code /charter admin} on its own: the same tools as the typed commands under it,
 * as screens. Each button runs one of those commands through {@link AdminCommandRunner}, so a rule lives in one
 * place; a page only gathers the values and shows the answer. Pages are built from live state on every open, and
 * every button checks again that its player is still an operator when it is pressed.
 *
 * <p>The testing tools behind {@code devTools} (time travel, backdating, scenarios) are not here; they stay typed.
 */
public final class AdminPages {

    private static final int LINES_MAX = 40;
    private static final int SITES_SHOWN = 12;
    private static final int NAME_MAX = 64;
    private static final int NUMBER_MAX = 12;

    private AdminPages() {
    }

    /** A page that can show what the last command said (null when it opens fresh). */
    private interface Page {
        void open(ServerPlayer player, Result notice);
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    static boolean allowed(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }

    private static void denied(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(Text.get("admin.menu.denied")).withStyle(ChatFormatting.RED));
    }

    /** A page button. A link outlives a demotion, so the player is checked again when it is pressed. */
    private static ActionButton go(ServerPlayer player, String label, String tooltip, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, tooltip, p -> {
            if (allowed(p)) page.accept(p);
            else denied(p);
        });
    }

    /** A form's submit button: the operator check, then the values split and cleaned, then {@code run}. */
    private static ActionButton submit(ServerPlayer player, String label, List<Input> inputs,
                                       BiConsumer<ServerPlayer, List<String>> run) {
        return BookDialogs.submit(player, label, null, inputs, form -> {
            ServerPlayer p = form.player();
            if (!allowed(p)) {
                denied(p);
                return;
            }
            var values = FormValues.split(form.raw(), inputs.size());
            if (values.isEmpty() || !values.get().stream().allMatch(FormValues::clean)) {
                p.sendSystemMessage(Component.literal(Text.get("admin.menu.form_bad")).withStyle(ChatFormatting.RED));
                return;
            }
            run.accept(p, values.get());
        });
    }

    /** Runs a command for the player, then returns to a page with what it said; a long answer gets a page of its own. */
    private static void ran(ServerPlayer player, String command, Page back) {
        Result result = AdminCommandRunner.run(player, command);
        if (result.lines().size() > 2) result(player, result, back);
        else back.open(player, result);
    }

    private static List<DialogBody> with(Result notice, List<DialogBody> body) {
        if (notice == null) return body;
        List<DialogBody> out = new ArrayList<>();
        if (notice.lines().isEmpty()) {
            out.add(BookDialogs.text(Component.literal(Text.get(notice.ok() ? "admin.menu.done" : "admin.menu.failed"))
                    .withStyle(notice.ok() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }
        for (Component line : notice.lines()) out.add(BookDialogs.text(line));
        out.addAll(body);
        return out;
    }

    private static void result(ServerPlayer player, Result result, Page back) {
        List<DialogBody> body = new ArrayList<>();
        int shown = Math.min(result.lines().size(), LINES_MAX);
        for (int i = 0; i < shown; i++) body.add(BookDialogs.text(result.lines().get(i)));
        if (result.lines().size() > shown) {
            body.add(BookDialogs.text(Text.get("admin.menu.more", "count", result.lines().size() - shown)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("admin.menu.result"), body, List.of(),
                BookDialogs.back(player, p -> back.open(p, null))));
    }

    private static List<Organization> kingdoms() {
        return rt().state.orgs().stream()
                .filter(o -> o.isTopLevel() && !o.npc && o.exists())
                .sorted(Comparator.comparing(o -> o.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static List<String> onlineNames(ServerPlayer player) {
        return player.level().getServer().getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getPlainTextName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ---- Home ----

    /** Opens the menu at its first page. */
    public static void open(ServerPlayer player) {
        home(player, null);
    }

    static void home(ServerPlayer player, Result notice) {
        if (!allowed(player)) {
            denied(player);
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("admin.menu.intro")));
        body.add(BookDialogs.text(Text.get("admin.menu.counts", "kingdoms", kingdoms().size(), "sites", rt().state.npc.sites.size())));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.menu.button.kingdoms"), null, p -> kingdomList(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.create"), null, p -> createForm(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.sites"), null, p -> sites(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.tools"), null, p -> tools(p, null)));
        BookDialogs.show(player, BookDialogs.list(Text.get("admin.menu.title"), with(notice, body), buttons, BookDialogs.close()));
    }

    // ---- Kingdoms ----

    static void kingdomList(ServerPlayer player, Result notice) {
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        List<Organization> all = kingdoms();
        if (all.isEmpty()) body.add(BookDialogs.text(Text.get("admin.kingdoms.none")));
        for (Organization org : all) {
            OrgId id = org.id;
            String label = org.name() + (org.adminKingdom ? Text.get("admin.kingdoms.admin_tag") : "");
            buttons.add(go(player, label, null, p -> kingdom(p, id, null)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("admin.kingdoms.title"), with(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    static void kingdom(ServerPlayer player, OrgId id, Result notice) {
        var found = rt().state.org(id);
        if (found.isEmpty() || !found.get().exists()) {
            kingdomList(player, new Result(false, List.of(Component.literal(Text.get("admin.kingdom.gone")).withStyle(ChatFormatting.RED))));
            return;
        }
        Organization org = found.get();
        String name = org.name();
        Page again = (p, n) -> kingdom(p, id, n);
        List<DialogBody> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("admin.kingdom.facts", "members", rt().state.members(id).size(),
                "claims", rt().state.claimsOf(id).size(), "bonus", org.claimBonus)));
        body.add(BookDialogs.text(Text.get(org.adminKingdom ? "admin.kingdom.is_admin" : "admin.kingdom.is_ordinary")));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.kingdom.button.leader"), null, p -> leaderForm(p, id)));
        buttons.add(go(player, Text.get(org.adminKingdom ? "admin.kingdom.button.make_ordinary" : "admin.kingdom.button.make_admin"), null,
                p -> ran(p, "charter admin faction admin " + FormValues.quote(name) + " " + !org.adminKingdom, again)));
        buttons.add(go(player, Text.get("admin.kingdom.button.bonus"), null, p -> bonusForm(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.dissolve"), null, p -> dissolveAsk(p, id)));
        BookDialogs.show(player, BookDialogs.list(name, with(notice, body), buttons,
                BookDialogs.back(player, p -> kingdomList(p, null))));
    }

    private static void leaderForm(ServerPlayer player, OrgId id) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        String name = found.get().name();
        List<String> online = onlineNames(player);
        if (online.isEmpty()) {
            kingdom(player, id, failed(Text.get("admin.menu.nobody")));
            return;
        }
        List<Input> inputs = List.of(BookDialogs.choice("player", Text.get("admin.leader.label"), online, online));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.leader.title"),
                List.of(BookDialogs.text(Text.get("admin.leader.intro", "kingdom", name))), inputs,
                submit(player, Text.get("admin.leader.button"), inputs, (p, values) -> {
                    String who = values.get(0);
                    if (!onlineNames(p).contains(who)) {
                        kingdom(p, id, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, "charter admin faction leader " + FormValues.quote(name) + " " + FormValues.quote(who),
                            (pp, n) -> kingdom(pp, id, n));
                }),
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    private static void bonusForm(ServerPlayer player, OrgId id) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        Organization org = found.get();
        String name = org.name();
        List<Input> inputs = List.of(BookDialogs.textInput("bonus", Text.get("admin.bonus.label"), String.valueOf(org.claimBonus), NUMBER_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.bonus.title"),
                List.of(BookDialogs.text(Text.get("admin.bonus.intro", "kingdom", name, "bonus", org.claimBonus))), inputs,
                submit(player, Text.get("admin.bonus.button"), inputs, (p, values) -> {
                    var bonus = FormValues.integer(values.get(0));
                    if (bonus.isEmpty() || bonus.getAsInt() < -1000 || bonus.getAsInt() > 100000) {
                        kingdom(p, id, failed(Text.get("admin.bonus.bad")));
                        return;
                    }
                    ran(p, "charter admin faction claims " + FormValues.quote(name) + " " + bonus.getAsInt(), (pp, n) -> kingdom(pp, id, n));
                }),
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    private static void dissolveAsk(ServerPlayer player, OrgId id) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        String name = found.get().name();
        BookDialogs.show(player, BookDialogs.confirm(Text.get("admin.dissolve.title", "kingdom", name),
                List.of(BookDialogs.text(Text.get("admin.dissolve.body", "kingdom", name))),
                BookDialogs.act(player, Text.get("admin.dissolve.yes"), null, p -> {
                    if (!allowed(p)) {
                        denied(p);
                        return;
                    }
                    ran(p, "charter admin dissolve " + FormValues.quote(name), AdminPages::kingdomList);
                }),
                BookDialogs.act(player, Text.get("admin.dissolve.no"), null, p -> kingdom(p, id, null))));
    }

    // ---- New kingdom ----

    static void createForm(ServerPlayer player, Result notice) {
        var at = player.blockPosition();
        List<Input> inputs = List.of(
                BookDialogs.textInput("name", Text.get("admin.create.name"), "", NAME_MAX),
                BookDialogs.textInput("x", "X", String.valueOf(at.getX()), NUMBER_MAX),
                BookDialogs.textInput("y", "Y", String.valueOf(at.getY()), NUMBER_MAX),
                BookDialogs.textInput("z", "Z", String.valueOf(at.getZ()), NUMBER_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.create.title"),
                with(notice, List.of(BookDialogs.text(Text.get("admin.create.intro")))), inputs,
                submit(player, Text.get("admin.create.button"), inputs, (p, values) -> {
                    String name = values.get(0);
                    var x = FormValues.integer(values.get(1));
                    var y = FormValues.integer(values.get(2));
                    var z = FormValues.integer(values.get(3));
                    if (name.isBlank()) {
                        createForm(p, failed(Text.get("admin.create.bad_name")));
                        return;
                    }
                    if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
                        createForm(p, failed(Text.get("admin.create.bad_number")));
                        return;
                    }
                    ran(p, "charter admin faction create " + FormValues.quote(name) + " " + x.getAsInt() + " " + y.getAsInt() + " " + z.getAsInt(),
                            (pp, n) -> {
                                if (n != null && n.ok()) kingdomList(pp, n);
                                else createForm(pp, n);
                            });
                }),
                BookDialogs.back(player, p -> home(p, null))));
    }

    // ---- Raider sites ----

    static void sites(ServerPlayer player, Result notice) {
        Page again = AdminPages::sites;
        List<NpcBook.Site> all = rt().state.npc.sites;
        List<DialogBody> body = new ArrayList<>();
        if (all.isEmpty()) body.add(BookDialogs.text(Text.get("admin.sites.none")));
        for (int i = 0; i < Math.min(all.size(), SITES_SHOWN); i++) body.add(BookDialogs.text(siteLine(all.get(i))));
        if (all.size() > SITES_SHOWN) body.add(BookDialogs.text(Text.get("admin.menu.more", "count", all.size() - SITES_SHOWN)));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.sites.button.stronghold"), null, p -> ran(p, "charter admin npc spawn stronghold", again)));
        buttons.add(go(player, Text.get("admin.sites.button.outpost"), null, p -> ran(p, "charter admin npc spawn outpost", again)));
        buttons.add(go(player, Text.get("admin.sites.button.respawn"), null, p -> ran(p, "charter admin npc respawn", again)));
        if (!all.isEmpty()) buttons.add(go(player, Text.get("admin.sites.button.clear"), null, AdminPages::clearForm));
        buttons.add(go(player, Text.get("admin.sites.button.ruins"), null,
                p -> ran(p, "charter admin npc clearruins " + p.blockPosition().getX() + " " + p.blockPosition().getZ(), again)));
        BookDialogs.show(player, BookDialogs.list(Text.get("admin.sites.title"), with(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static String siteLine(NpcBook.Site site) {
        return Text.get("admin.sites.line", "id", site.id(), "kind", site.kind().name().toLowerCase(Locale.ROOT),
                "org", rt().describe.org(site.org()), "x", site.chunk().x() * 16 + 8, "z", site.chunk().z() * 16 + 8);
    }

    private static void clearForm(ServerPlayer player) {
        List<NpcBook.Site> all = rt().state.npc.sites;
        if (all.isEmpty()) {
            sites(player, null);
            return;
        }
        List<String> ids = all.stream().map(s -> String.valueOf(s.id())).toList();
        List<String> shown = all.stream().map(AdminPages::siteLine).toList();
        List<Input> inputs = List.of(BookDialogs.choice("site", Text.get("admin.sites.clear.label"), ids, shown));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.sites.clear.title"),
                List.of(BookDialogs.text(Text.get("admin.sites.clear.intro"))), inputs,
                submit(player, Text.get("admin.sites.clear.button"), inputs, (p, values) -> {
                    var id = FormValues.integer(values.get(0));
                    if (id.isEmpty()) {
                        sites(p, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, "charter admin npc clear " + id.getAsInt(), AdminPages::sites);
                }),
                BookDialogs.back(player, p -> sites(p, null))));
    }

    // ---- Server tools ----

    static void tools(ServerPlayer player, Result notice) {
        Page again = AdminPages::tools;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.tools.button.save"), null, p -> ran(p, "charter admin save", again)));
        buttons.add(go(player, Text.get("admin.tools.button.repair"), null, p -> ran(p, "charter admin repair", again)));
        buttons.add(go(player, Text.get("admin.tools.button.areas"), null, p -> ran(p, "charter admin areas", again)));
        buttons.add(go(player, Text.get("admin.tools.button.clock"), null, p -> ran(p, "charter admin time", again)));
        buttons.add(go(player, Text.get("admin.tools.button.vote"), null, AdminPages::voteForm));
        buttons.add(go(player, Text.get("admin.tools.button.give"), null, AdminPages::giveForm));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_here"), null, p -> ran(p, "charter admin realm-chronicle here", again)));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_remove"), null, p -> ran(p, "charter admin realm-chronicle remove", again)));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_list"), null, p -> ran(p, "charter admin realm-chronicle list", again)));
        BookDialogs.show(player, BookDialogs.list(Text.get("admin.tools.title"), with(notice, List.of()), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static void voteForm(ServerPlayer player) {
        List<Input> inputs = List.of(BookDialogs.textInput("number", Text.get("admin.vote.label"), "", NUMBER_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.vote.title"),
                List.of(BookDialogs.text(Text.get("admin.vote.intro"))), inputs,
                submit(player, Text.get("admin.vote.button"), inputs, (p, values) -> {
                    var number = FormValues.integer(values.get(0));
                    if (number.isEmpty() || number.getAsInt() < 1) {
                        tools(p, failed(Text.get("admin.vote.bad")));
                        return;
                    }
                    ran(p, "charter admin vote close " + number.getAsInt(), AdminPages::tools);
                }),
                BookDialogs.back(player, p -> tools(p, null))));
    }

    private static void giveForm(ServerPlayer player) {
        List<String> things = Gate.devTools() ? List.of("banner", "book", "nexus") : List.of("banner", "book");
        List<String> online = onlineNames(player);
        if (online.isEmpty()) {
            tools(player, failed(Text.get("admin.menu.nobody")));
            return;
        }
        List<Input> inputs = List.of(
                BookDialogs.choice("what", Text.get("admin.give.what"), things, things),
                BookDialogs.choice("player", Text.get("admin.give.player"), online, online));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.give.title"),
                List.of(BookDialogs.text(Text.get("admin.give.intro"))), inputs,
                submit(player, Text.get("admin.give.button"), inputs, (p, values) -> {
                    if (!things.contains(values.get(0)) || !onlineNames(p).contains(values.get(1))) {
                        tools(p, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, "charter admin give " + values.get(0) + " " + FormValues.quote(values.get(1)), AdminPages::tools);
                }),
                BookDialogs.back(player, p -> tools(p, null))));
    }

    private static Result failed(String text) {
        return new Result(false, List.of(Component.literal(text).withStyle(ChatFormatting.RED)));
    }
}
