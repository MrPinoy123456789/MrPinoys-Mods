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
 * <p>Pages are reached by what an operator looks up: a player, a kingdom, the keeps, the votes. Players and kingdoms
 * are passed to commands by UUID and id, never by name. Anything that cannot be undone asks first, with the verb on the
 * button. The testing tools behind {@code devTools} (time travel, backdating, scenarios) are not here; they stay typed.
 */
public final class AdminPages {

    private static final int LINES_MAX = 40;
    private static final int SITES_SHOWN = 12;
    private static final int NAME_MAX = 64;
    private static final int NUMBER_MAX = 12;
    /** Rows per page of a long operator list (players, members): more than a player screen, since operators play on Java. */
    static final int OP_PAGE = 10;

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
    private static BookButton go(ServerPlayer player, String label, String tooltip, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, tooltip, p -> {
            if (allowed(p)) page.accept(p);
            else denied(p);
        });
    }

    /** A form's submit button: the operator check, then the values split and cleaned, then {@code run}. */
    private static BookButton submit(ServerPlayer player, String label, List<BookInput> inputs,
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

    private static List<net.minecraft.network.chat.Component> with(Result notice, List<net.minecraft.network.chat.Component> body) {
        if (notice == null) return body;
        List<net.minecraft.network.chat.Component> out = new ArrayList<>();
        if (notice.lines().isEmpty()) {
            out.add(BookDialogs.text(Component.literal(Text.get(notice.ok() ? "admin.menu.done" : "admin.menu.failed"))
                    .withStyle(notice.ok() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }
        for (Component line : notice.lines()) out.add(BookDialogs.text(line));
        out.addAll(body);
        return out;
    }

    private static void result(ServerPlayer player, Result result, Page back) {
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        int shown = Math.min(result.lines().size(), LINES_MAX);
        for (int i = 0; i < shown; i++) body.add(BookDialogs.text(result.lines().get(i)));
        if (result.lines().size() > shown) {
            body.add(BookDialogs.text(Text.get("admin.menu.more", "count", result.lines().size() - shown)));
        }
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.menu.result"), body, List.of(),
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
        long votes = rt().state.votes().stream().filter(charter.gov.Vote::open).count();
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("admin.menu.intro")));
        body.add(BookDialogs.text(Text.get("admin.menu.status", "kingdoms", kingdoms().size(),
                "online", player.level().getServer().getPlayerList().getPlayerCount(), "votes", votes, "sites", rt().state.npc.sites.size())));
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.menu.button.players"), null, p -> players(p, 0, null)));
        buttons.add(go(player, Text.get("admin.menu.button.kingdoms"), null, p -> kingdomList(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.keeps"), null, p -> keeps(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.votes", "n", votes), null, p -> votes(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.sites"), null, p -> sites(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.create"), null, p -> createForm(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.tools"), null, p -> tools(p, null)));
        buttons.add(go(player, Text.get("admin.menu.button.help"), null, p -> ran(p, "charter admin help", AdminPages::home)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.menu.title"), with(notice, body), buttons, BookDialogs.close()));
    }

    // ---- Kingdoms ----

    static void kingdomList(ServerPlayer player, Result notice) {
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        List<BookButton> buttons = new ArrayList<>();
        List<Organization> all = kingdoms();
        if (all.isEmpty()) body.add(BookDialogs.text(Text.get("admin.kingdoms.none")));
        for (Organization org : all) {
            OrgId id = org.id;
            String label = org.name() + (org.adminKingdom ? Text.get("admin.kingdoms.admin_tag") : "");
            buttons.add(go(player, label, null, p -> kingdom(p, id, null)));
        }
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.kingdoms.title"), with(notice, body), buttons,
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
        String ref = FormValues.quote(id.toString());
        Page again = (p, n) -> kingdom(p, id, n);
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("admin.kingdom.facts", "members", rt().state.members(id).size(),
                "claims", rt().state.claimsOf(id).size(), "bonus", org.claimBonus)));
        body.add(BookDialogs.text(Text.get("admin.kingdom.lords", "lords", charter.mc.cmd.OpCommands.lords(rt(), id),
                "nexus", charter.mc.cmd.OpCommands.nexusText(rt(), id))));
        body.add(BookDialogs.text(Text.get("admin.kingdom.race", "race", org.raceLabel(),
                "mixed", Text.get(org.mixedRace ? "admin.kingdom.mixed_on" : "admin.kingdom.mixed_off"))));
        body.add(BookDialogs.text(Text.get(org.adminKingdom ? "admin.kingdom.is_admin" : "admin.kingdom.is_ordinary")));
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.kingdom.button.goto"), null, p -> ran(p, "charter admin goto " + ref, again)));
        buttons.add(go(player, Text.get("admin.kingdom.button.members", "n", rt().state.members(id).size()), null, p -> members(p, id, 0)));
        buttons.add(go(player, Text.get("admin.kingdom.button.add"), null, p -> addMemberForm(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.leader"), null, p -> leaderForm(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.coleader"), null, p -> coleaderForm(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.unlord"), null, p -> unlordForm(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.rename"), null, p -> textForm(p, id, "rename", name)));
        buttons.add(go(player, Text.get("admin.kingdom.button.racename"), null, p -> textForm(p, id, "racename", org.raceLabel())));
        buttons.add(go(player, Text.get(org.mixedRace ? "admin.kingdom.button.mixed_off" : "admin.kingdom.button.mixed_on"), null,
                p -> ran(p, "charter admin faction mixed " + ref + " " + !org.mixedRace, again)));
        buttons.add(go(player, Text.get(org.adminKingdom ? "admin.kingdom.button.make_ordinary" : "admin.kingdom.button.make_admin"), null,
                p -> ran(p, "charter admin faction admin " + ref + " " + !org.adminKingdom, again)));
        buttons.add(go(player, Text.get("admin.kingdom.button.bonus"), null, p -> bonusForm(p, id)));
        if (org.isTopLevel()) buttons.add(go(player, Text.get("admin.kingdom.button.move"), null, p -> moveAsk(p, id)));
        buttons.add(go(player, Text.get("admin.kingdom.button.dissolve"), null, p -> dissolveAsk(p, id)));
        BookDialogs.show(player, BookDialogs.operatorList(name, with(notice, body), buttons,
                BookDialogs.back(player, p -> kingdomList(p, null))));
    }

    /** Online players as a dropdown: the value is the UUID, so two players with one name are never confused. */
    private static List<String> onlineIds(ServerPlayer player) {
        return player.level().getServer().getPlayerList().getPlayers().stream()
                .sorted(Comparator.comparing(ServerPlayer::getPlainTextName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> p.getUUID().toString()).toList();
    }

    private static List<String> onlineShown(ServerPlayer player, List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            ServerPlayer p = player.level().getServer().getPlayerList().getPlayer(java.util.UUID.fromString(id));
            out.add(p == null ? id : p.getPlainTextName());
        }
        return out;
    }

    /** A form with one dropdown of online players that runs {@code command} with the picked UUID appended. */
    private static void pickOnline(ServerPlayer player, OrgId id, String titleKey, String introKey, String buttonKey, String command) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        List<String> ids = onlineIds(player);
        if (ids.isEmpty()) {
            kingdom(player, id, failed(Text.get("admin.menu.nobody")));
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("player", Text.get("admin.leader.label"), ids, onlineShown(player, ids)));
        BookDialogs.show(player, BookDialogs.form(Text.get(titleKey),
                List.of(BookDialogs.text(Text.get(introKey, "kingdom", found.get().name()))), inputs,
                submit(player, Text.get(buttonKey), inputs, (p, values) -> {
                    if (!onlineIds(p).contains(values.get(0))) {
                        kingdom(p, id, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, command + " " + values.get(0), (pp, n) -> kingdom(pp, id, n));
                }),
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    private static void addMemberForm(ServerPlayer player, OrgId id) {
        pickOnline(player, id, "admin.add.title", "admin.add.intro", "admin.add.button", "charter admin faction add " + FormValues.quote(id.toString()));
    }

    /** Rename, or the race's name: one text box. Ops type here; it is the one place a name has to be typed. */
    private static void textForm(ServerPlayer player, OrgId id, String what, String current) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.textInput("name", Text.get("admin." + what + ".label"), current, NAME_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin." + what + ".title"),
                List.of(BookDialogs.text(Text.get("admin." + what + ".intro", "kingdom", found.get().name()))), inputs,
                submit(player, Text.get("admin." + what + ".button"), inputs, (p, values) -> {
                    String name = values.get(0).strip();
                    if (name.isEmpty()) {
                        kingdom(p, id, failed(Text.get("admin.create.bad_name")));
                        return;
                    }
                    ran(p, "charter admin faction " + what + " " + FormValues.quote(id.toString()) + " " + FormValues.quote(name), (pp, n) -> kingdom(pp, id, n));
                }),
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    /** Moving a keep is not undone by a press, so it asks first and says where. */
    private static void moveAsk(ServerPlayer player, OrgId id) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        var at = player.blockPosition();
        String name = found.get().name();
        BookDialogs.show(player, BookDialogs.confirm(Text.get("admin.move.title", "kingdom", name),
                List.of(BookDialogs.text(Text.get("admin.move.body", "kingdom", name, "x", at.getX(), "y", at.getY(), "z", at.getZ()))),
                go(player, Text.get("admin.move.yes"), null,
                        p -> ran(p, "charter admin faction move " + FormValues.quote(id.toString()) + " " + at.getX() + " " + at.getY() + " " + at.getZ(), (pp, n) -> kingdom(pp, id, n))),
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    /** Every member, a page at a time, rebuilt from live state for each page; each opens their player page. */
    static void members(ServerPlayer player, OrgId id, int index) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdomList(player, null);
            return;
        }
        List<charter.org.MemberRecord> all = new ArrayList<>(rt().state.members(id));
        all.sort(Comparator.comparing(m -> rt().describe.player(m.player()).toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (all.size() + OP_PAGE - 1) / OP_PAGE);
        int at = Math.max(0, Math.min(index, pages - 1));
        List<BookButton> buttons = new ArrayList<>();
        for (charter.org.MemberRecord m : all.subList(at * OP_PAGE, Math.min(all.size(), (at + 1) * OP_PAGE))) {
            java.util.UUID uuid = m.player().value();
            buttons.add(go(player, rt().describe.player(m.player()), null, p -> playerPage(p, uuid, null)));
        }
        if (at > 0) buttons.add(go(player, Text.get("book.page.prev"), null, p -> members(p, id, at - 1)));
        if (at < pages - 1) buttons.add(go(player, Text.get("book.page.next"), null, p -> members(p, id, at + 1)));
        List<net.minecraft.network.chat.Component> body = List.of(BookDialogs.text(Text.get("admin.members.intro",
                "kingdom", found.get().name(), "count", all.size(), "page", at + 1, "pages", pages)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.members.title", "kingdom", found.get().name()), body, buttons,
                BookDialogs.back(player, p -> kingdom(p, id, null))));
    }

    // ---- Players ----

    /** Who is online, a page at a time, and a way to find anyone the server has seen. */
    static void players(ServerPlayer player, int index, Result notice) {
        List<ServerPlayer> all = new ArrayList<>(player.level().getServer().getPlayerList().getPlayers());
        all.sort(Comparator.comparing(ServerPlayer::getPlainTextName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (all.size() + OP_PAGE - 1) / OP_PAGE);
        int at = Math.max(0, Math.min(index, pages - 1));
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.players.find"), null, AdminPages::findForm));
        for (ServerPlayer p : all.subList(at * OP_PAGE, Math.min(all.size(), (at + 1) * OP_PAGE))) {
            java.util.UUID uuid = p.getUUID();
            String kingdom = rt().state.membership(new charter.id.PlayerId(uuid)).map(m -> rt().describe.org(rt().state.root(m.org())))
                    .orElse(Text.get("admin.players.no_kingdom"));
            buttons.add(go(player, Text.get("admin.players.entry", "name", p.getPlainTextName(), "kingdom", kingdom), null,
                    q -> playerPage(q, uuid, null)));
        }
        if (at > 0) buttons.add(go(player, Text.get("book.page.prev"), null, p -> players(p, at - 1, null)));
        if (at < pages - 1) buttons.add(go(player, Text.get("book.page.next"), null, p -> players(p, at + 1, null)));
        List<net.minecraft.network.chat.Component> body = List.of(BookDialogs.text(Text.get("admin.players.intro",
                "count", all.size(), "page", at + 1, "pages", pages)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.players.title"), with(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    /** Anyone the server has seen, by name or UUID: an operator types here, since an offline player has no button. */
    private static void findForm(ServerPlayer player) {
        List<BookInput> inputs = List.of(BookDialogs.textInput("who", Text.get("admin.find.label"), "", NAME_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.find.title"), List.of(BookDialogs.text(Text.get("admin.find.intro"))), inputs,
                submit(player, Text.get("admin.find.button"), inputs, (p, values) -> {
                    var who = charter.mc.cmd.Resolve.player(p.level().getServer(), rt(), values.get(0));
                    if (who.isEmpty()) {
                        players(p, 0, failed(Text.get("land.admin.no_player", "name", values.get(0))));
                        return;
                    }
                    playerPage(p, who.get().id().value(), null);
                }),
                BookDialogs.back(player, p -> players(p, 0, null))));
    }

    /** Everything about one player, and everything an operator may do to them. Driven by UUID throughout. */
    /** Opens a player's page (the menu's own entry, and for gametests). */
    public static void playerPage(ServerPlayer player, java.util.UUID uuid) {
        if (allowed(player)) playerPage(player, uuid, null);
        else denied(player);
    }

    static void playerPage(ServerPlayer player, java.util.UUID uuid, Result notice) {
        var who = charter.mc.cmd.Resolve.player(player.level().getServer(), rt(), uuid.toString());
        if (who.isEmpty()) {
            players(player, 0, failed(Text.get("land.admin.no_player", "name", uuid)));
            return;
        }
        charter.id.PlayerId id = who.get().id();
        String ref = uuid.toString();
        Page again = (p, n) -> playerPage(p, uuid, n);
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        for (String line : charter.mc.cmd.OpCommands.playerLines(rt(), who.get())) body.add(BookDialogs.text(line));
        List<BookButton> buttons = new ArrayList<>();
        ServerPlayer online = who.get().online();
        if (online != null && online != player) {
            buttons.add(go(player, Text.get("admin.player.button.goto"), null, p -> ran(p, "tp " + ref, again)));
            buttons.add(go(player, Text.get("admin.player.button.bring"), null, p -> ran(p, "tp " + ref + " " + p.getUUID(), again)));
        }
        var member = rt().state.membership(id);
        if (member.isEmpty()) {
            buttons.add(go(player, Text.get("admin.player.button.add"), null, p -> kingdomPick(p, uuid, "join")));
        } else {
            OrgId org = member.get().org();
            String orgRef = FormValues.quote(org.toString());
            boolean lord = charter.gov.Deciders.of(rt().state, org).map(o -> o.holds(id)).orElse(false);
            buttons.add(go(player, Text.get("admin.player.button.kingdom", "kingdom", rt().describe.org(org)), null, p -> kingdom(p, org, null)));
            if (!lord) {
                buttons.add(go(player, Text.get("admin.player.button.leader"), null, p -> ran(p, "charter admin faction leader " + orgRef + " " + ref, again)));
                buttons.add(go(player, Text.get("admin.player.button.coleader"), null, p -> ran(p, "charter admin faction coleader " + orgRef + " " + ref, again)));
            } else {
                buttons.add(go(player, Text.get("admin.player.button.unlord"), null, p -> ran(p, "charter admin faction unlord " + orgRef + " " + ref, again)));
            }
            buttons.add(go(player, Text.get("admin.player.button.remove"), null, p -> removeAsk(p, uuid, org)));
        }
        buttons.add(go(player, Text.get("admin.player.button.race"), null, p -> kingdomPick(p, uuid, "race")));
        if (online != null) {
            buttons.add(go(player, Text.get("admin.player.button.book"), null, p -> ran(p, "charter admin give book " + ref, again)));
            if (AdminBook.allowed(online)) {
                buttons.add(go(player, Text.get("admin.player.button.adminbook"), null, p -> ran(p, "charter admin give adminbook " + ref, again)));
            }
        }
        BookDialogs.show(player, BookDialogs.operatorList(who.get().name(), with(notice, body), buttons,
                BookDialogs.back(player, p -> players(p, 0, null))));
    }

    /** Taking a member out is not undone by a press: it asks, with the verb and the name on the button. */
    private static void removeAsk(ServerPlayer player, java.util.UUID uuid, OrgId org) {
        String name = rt().describe.player(new charter.id.PlayerId(uuid));
        String kingdom = rt().describe.org(org);
        BookDialogs.show(player, BookDialogs.confirm(Text.get("admin.remove.title"),
                List.of(BookDialogs.text(Text.get("admin.remove.body", "player", name, "kingdom", kingdom))),
                go(player, Text.get("admin.remove.yes", "player", name), null,
                        p -> ran(p, "charter admin faction remove " + FormValues.quote(org.toString()) + " " + uuid, (pp, n) -> playerPage(pp, uuid, n))),
                BookDialogs.back(player, p -> playerPage(p, uuid, null))));
    }

    /** A kingdom for this player: to join it ({@code join}) or to take its race ({@code race}, with None). */
    private static void kingdomPick(ServerPlayer player, java.util.UUID uuid, String what) {
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        if (what.equals("race")) {
            ids.add("none");
            shown.add(Text.get("admin.race.none"));
        }
        for (Organization org : kingdoms()) {
            if (!org.isTopLevel()) continue;
            ids.add(org.id.toString());
            shown.add(what.equals("race") ? org.raceLabel() : org.name());
        }
        if (ids.isEmpty()) {
            playerPage(player, uuid, failed(Text.get("admin.kingdoms.none")));
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("kingdom", Text.get("admin." + what + ".label"), ids, shown));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin." + what + ".title"),
                List.of(BookDialogs.text(Text.get("admin." + what + ".intro", "player", rt().describe.player(new charter.id.PlayerId(uuid))))), inputs,
                submit(player, Text.get("admin." + what + ".button"), inputs, (p, values) -> {
                    String pick = values.get(0);
                    if (!ids.contains(pick)) {
                        playerPage(p, uuid, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    String command;
                    if (what.equals("race")) {
                        String race = pick.equals("none") ? "none"
                                : charter.mc.cmd.Resolve.faction(rt(), pick).map(o -> FormValues.quote(o.raceLabel())).orElse("none");
                        command = "charter admin faction race " + uuid + " " + race;
                    } else {
                        command = "charter admin faction add " + FormValues.quote(pick) + " " + uuid;
                    }
                    ran(p, command, (pp, n) -> playerPage(pp, uuid, n));
                }),
                BookDialogs.back(player, p -> playerPage(p, uuid, null))));
    }

    // ---- Keeps, land and votes ----

    static void keeps(ServerPlayer player, Result notice) {
        Page again = AdminPages::keeps;
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        List<BookButton> buttons = new ArrayList<>();
        for (Organization org : kingdoms()) {
            for (charter.land.Core core : rt().state.coresOf(org.id)) {
                if (!core.isKeep()) continue;
                body.add(BookDialogs.text(Text.get("admin.keeps.line", "name", org.name(), "nexus", charter.mc.cmd.OpCommands.coreText(rt(), core))));
                String ref = FormValues.quote(org.id.toString());
                buttons.add(go(player, Text.get("admin.keeps.button", "name", org.name()), null, p -> ran(p, "charter admin goto " + ref, again)));
            }
        }
        if (body.isEmpty()) body.add(BookDialogs.text(Text.get("admin.keeps.none")));
        buttons.add(go(player, Text.get("admin.tools.button.areas"), null, p -> ran(p, "charter admin areas", again)));
        buttons.add(go(player, Text.get("admin.keeps.button.supply"), null, p -> ran(p, "charter admin supply show", again)));
        buttons.add(go(player, Text.get("admin.keeps.button.granaries"), null, p -> ran(p, "charter admin granary list", again)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.keeps.title"), with(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    static void votes(ServerPlayer player, Result notice) {
        long now = rt().clock.now();
        List<BookButton> buttons = new ArrayList<>();
        List<charter.gov.Vote> open = rt().state.votes().stream().filter(charter.gov.Vote::open).toList();
        for (charter.gov.Vote vote : open) {
            charter.id.VoteId vid = vote.id;
            buttons.add(go(player, Text.get("admin.votes.entry", "org", rt().describe.org(vote.org), "about", rt().notices.about(vote),
                    "time", charter.mc.cmd.Chat.duration(vote.closesAt - now)), null, p -> voteAsk(p, vid)));
        }
        List<net.minecraft.network.chat.Component> body = List.of(BookDialogs.text(Text.get(open.isEmpty() ? "admin.votes.none" : "admin.votes.intro")));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.votes.title"), with(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    /** Closing a vote counts it now; that cannot be taken back, so it asks. */
    private static void voteAsk(ServerPlayer player, charter.id.VoteId vid) {
        var vote = rt().state.vote(vid);
        if (vote.isEmpty() || !vote.get().open()) {
            votes(player, failed(Text.get("book.vote.gone")));
            return;
        }
        long number = vid.value().getLeastSignificantBits();
        BookDialogs.show(player, BookDialogs.confirm(Text.get("admin.votes.ask_title"),
                List.of(BookDialogs.text(Text.get("admin.votes.ask", "about", rt().notices.about(vote.get()),
                        "yes", vote.get().count(charter.gov.Vote.Choice.YES), "no", vote.get().count(charter.gov.Vote.Choice.NO)))),
                go(player, Text.get("admin.votes.yes"), null, p -> ran(p, "charter admin vote close " + number, AdminPages::votes)),
                BookDialogs.back(player, p -> votes(p, null))));
    }

    private static void leaderForm(ServerPlayer player, OrgId id) {
        pickOnline(player, id, "admin.leader.title", "admin.leader.intro", "admin.leader.button", "charter admin faction leader " + FormValues.quote(id.toString()));
    }

    /** A second Lord, from the players online: the command does the rest and refuses a third. */
    private static void coleaderForm(ServerPlayer player, OrgId id) {
        pickOnline(player, id, "admin.coleader.title", "admin.coleader.intro", "admin.coleader.button", "charter admin faction coleader " + FormValues.quote(id.toString()));
    }

    /** Remove one Lord, picked from those seated (online or not). */
    private static void unlordForm(ServerPlayer player, OrgId id) {
        var found = rt().state.org(id);
        if (found.isEmpty()) {
            kingdom(player, id, null);
            return;
        }
        String name = found.get().name();
        List<String> lords = new ArrayList<>();
        List<String> lordNames = new ArrayList<>();
        charter.gov.Deciders.of(rt().state, id).ifPresent(office -> {
            for (charter.gov.Office.Seat seat : office.holders()) {
                lords.add(seat.player().value().toString());
                lordNames.add(rt().describe.player(seat.player()));
            }
        });
        if (lords.isEmpty()) {
            kingdom(player, id, failed(Text.get("admin.unlord.none", "kingdom", name)));
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("player", Text.get("admin.leader.label"), lords, lordNames));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.unlord.title"),
                List.of(BookDialogs.text(Text.get("admin.unlord.intro"))), inputs,
                submit(player, Text.get("admin.unlord.button"), inputs, (p, values) -> {
                    String who = values.get(0);
                    if (!lords.contains(who)) {
                        kingdom(p, id, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, "charter admin faction unlord " + FormValues.quote(id.toString()) + " " + who,
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
        List<BookInput> inputs = List.of(BookDialogs.textInput("bonus", Text.get("admin.bonus.label"), String.valueOf(org.claimBonus), NUMBER_MAX));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.bonus.title"),
                List.of(BookDialogs.text(Text.get("admin.bonus.intro", "kingdom", name, "bonus", org.claimBonus))), inputs,
                submit(player, Text.get("admin.bonus.button"), inputs, (p, values) -> {
                    var bonus = FormValues.integer(values.get(0));
                    if (bonus.isEmpty() || bonus.getAsInt() < -1000 || bonus.getAsInt() > 100000) {
                        kingdom(p, id, failed(Text.get("admin.bonus.bad")));
                        return;
                    }
                    ran(p, "charter admin faction claims " + FormValues.quote(id.toString()) + " " + bonus.getAsInt(), (pp, n) -> kingdom(pp, id, n));
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
                    ran(p, "charter admin dissolve " + FormValues.quote(id.toString()), AdminPages::kingdomList);
                }),
                BookDialogs.act(player, Text.get("admin.dissolve.no"), null, p -> kingdom(p, id, null))));
    }

    // ---- New kingdom ----

    static void createForm(ServerPlayer player, Result notice) {
        var at = player.blockPosition();
        List<BookInput> inputs = List.of(
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
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        if (all.isEmpty()) body.add(BookDialogs.text(Text.get("admin.sites.none")));
        for (int i = 0; i < Math.min(all.size(), SITES_SHOWN); i++) body.add(BookDialogs.text(siteLine(all.get(i))));
        if (all.size() > SITES_SHOWN) body.add(BookDialogs.text(Text.get("admin.menu.more", "count", all.size() - SITES_SHOWN)));
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.sites.button.stronghold"), null, p -> ran(p, "charter admin npc spawn stronghold", again)));
        buttons.add(go(player, Text.get("admin.sites.button.outpost"), null, p -> ran(p, "charter admin npc spawn outpost", again)));
        buttons.add(go(player, Text.get("admin.sites.button.respawn"), null, p -> ran(p, "charter admin npc respawn", again)));
        if (!all.isEmpty()) buttons.add(go(player, Text.get("admin.sites.button.clear"), null, AdminPages::clearForm));
        buttons.add(go(player, Text.get("admin.sites.button.ruins"), null,
                p -> ran(p, "charter admin npc clearruins " + p.blockPosition().getX() + " " + p.blockPosition().getZ(), again)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.sites.title"), with(notice, body), buttons,
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
        List<BookInput> inputs = List.of(BookDialogs.choice("site", Text.get("admin.sites.clear.label"), ids, shown));
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
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("admin.tools.button.save"), null, p -> ran(p, "charter admin save", again)));
        buttons.add(go(player, Text.get("admin.tools.button.repair"), null, p -> ran(p, "charter admin repair", again)));
        buttons.add(go(player, Text.get("admin.tools.button.areas"), null, p -> ran(p, "charter admin areas", again)));
        buttons.add(go(player, Text.get("admin.tools.button.clock"), null, p -> ran(p, "charter admin time", again)));
        buttons.add(go(player, Text.get("admin.tools.button.vote"), null, AdminPages::voteForm));
        buttons.add(go(player, Text.get("admin.tools.button.give"), null, AdminPages::giveForm));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_here"), null, p -> ran(p, "charter admin realm-chronicle here", again)));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_remove"), null, p -> ran(p, "charter admin realm-chronicle remove", again)));
        buttons.add(go(player, Text.get("admin.tools.button.chronicle_list"), null, p -> ran(p, "charter admin realm-chronicle list", again)));
        BookDialogs.show(player, BookDialogs.operatorList(Text.get("admin.tools.title"), with(notice, List.of()), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static void voteForm(ServerPlayer player) {
        List<BookInput> inputs = List.of(BookDialogs.textInput("number", Text.get("admin.vote.label"), "", NUMBER_MAX));
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
        List<String> things = Gate.devTools() ? List.of("banner", "book", "adminbook", "nexus") : List.of("banner", "book", "adminbook");
        List<String> online = onlineIds(player);
        if (online.isEmpty()) {
            tools(player, failed(Text.get("admin.menu.nobody")));
            return;
        }
        List<BookInput> inputs = List.of(
                BookDialogs.choice("what", Text.get("admin.give.what"), things, things),
                BookDialogs.choice("player", Text.get("admin.give.player"), online, onlineShown(player, online)));
        BookDialogs.show(player, BookDialogs.form(Text.get("admin.give.title"),
                List.of(BookDialogs.text(Text.get("admin.give.intro"))), inputs,
                submit(player, Text.get("admin.give.button"), inputs, (p, values) -> {
                    if (!things.contains(values.get(0)) || !onlineIds(p).contains(values.get(1))) {
                        tools(p, failed(Text.get("admin.menu.form_bad")));
                        return;
                    }
                    ran(p, "charter admin give " + values.get(0) + " " + values.get(1), AdminPages::tools);
                }),
                BookDialogs.back(player, p -> tools(p, null))));
    }

    private static Result failed(String text) {
        return new Result(false, List.of(Component.literal(text).withStyle(ChatFormatting.RED)));
    }
}
