package charter.mc.book;

import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.Ranks;
import charter.org.actions.AddRank;
import charter.org.actions.GiveRank;
import charter.org.actions.MoveRank;
import charter.org.actions.RemoveRank;
import charter.org.actions.RenameRank;
import charter.org.actions.SetRankPower;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ranks (titles) in the book. The Leader's Desk has a Ranks page for whoever may manage ranks: every rank as a button,
 * Give a rank, Add a rank; a rank's own page renames it, moves it up or down, removes it (asking first) and, for the
 * Lord, sets what it may do (grouped as Give a power is, one button per power that turns it on or off). My faction has
 * a read only list of the ranks and who holds them.
 *
 * <p>Nothing here decides anything: every press runs a rank action through the engine, which checks the player's
 * powers again, and every page is rebuilt from live state. A name is the one thing a player may type, and a dropdown of
 * common names (King, Queen, Princess, Royal Commander, ...) means a console player need not.
 */
public final class RankPages {

    /** The dropdown entry that means "use what I typed". */
    static final String OWN = "-";

    private RankPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static BookButton go(ServerPlayer player, String label, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** The kingdom the player belongs to, or null after sending them to the newcomer home. */
    private static OrgId kingdom(ServerPlayer player) {
        Optional<OrgId> k = Ranks.kingdomOf(rt().state, me(player));
        if (k.isEmpty()) {
            NewcomerPages.home(player, null);
            return null;
        }
        return k.get();
    }

    private static boolean holds(ServerPlayer player, OrgId org, Power power, boolean passable) {
        CharterRuntime rt = rt();
        return new AuthorityResolver(rt.state, rt.clock.now()).resolve(new Who.AsPlayer(me(player)), org, power, ScopeNeed.NONE, passable).ok();
    }

    /** Whether the player may change the kingdom's ranks now. Only decides what is shown; the engine decides the rest. */
    public static boolean manages(ServerPlayer player) {
        Optional<OrgId> k = Ranks.kingdomOf(rt().state, me(player));
        return k.isPresent() && holds(player, k.get(), Power.MANAGE_RANKS, false);
    }

    /** Whether the player is the kingdom's ruler, who alone sets what a rank may do. */
    static boolean rules(ServerPlayer player, OrgId kingdom) {
        return Ranks.isRuler(rt().state, me(player), kingdom);
    }

    private static List<Ranks.Rank> ladder(OrgId kingdom) {
        return rt().state.ranks.ladder(kingdom);
    }

    private static int holders(OrgId kingdom, String rank) {
        return rt().state.ranks.holders(rt().state, kingdom, rank).size();
    }

    // ---- the Leader's Desk: Ranks ----

    public static void ranks(ServerPlayer player, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        if (!manages(player)) {
            LeaderPages.desk(player, Text.get("book.ranks.not_yours"));
            return;
        }
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("book.ranks.give"), p -> give(p, null)).icon(BookIcons.RANKS));
        if (ladder(kingdom).size() < Ranks.MAX_RANKS) buttons.add(go(player, Text.get("book.ranks.add"), p -> add(p, null)).icon(BookIcons.YES));
        for (Ranks.Rank rank : ladder(kingdom)) {
            String id = rank.id();
            String label = Text.get("book.ranks.entry", "rank", BookDialogs.clip(rank.name(), 17), "n", holders(kingdom, id));
            buttons.add(go(player, label, p -> rank(p, id, null)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.ranks.title"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.ranks.intro")))), buttons,
                BookDialogs.back(player, p -> LeaderPages.desk(p, null))));
    }

    /** One rank: what it is, then rename, move, powers and remove. */
    public static void rank(ServerPlayer player, String rankId, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        CharterRuntime rt = rt();
        Optional<Ranks.Rank> found = rt.state.ranks.rank(kingdom, rankId);
        if (found.isEmpty()) {
            ranks(player, notice != null ? notice : Text.get("book.ranks.gone"));
            return;
        }
        Ranks.Rank rank = found.get();
        List<Ranks.Rank> ranks = ladder(kingdom);
        int at = rt.state.ranks.position(kingdom, rankId);
        List<Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("book.ranks.about", "rank", rank.name(), "place", at + 1, "of", ranks.size(),
                "n", holders(kingdom, rankId))));
        if (rankId.equals(Ranks.TOP)) body.add(BookDialogs.text(Text.get("book.ranks.top_note")));
        else if (rankId.equals(Ranks.DEFAULT)) body.add(BookDialogs.text(Text.get("book.ranks.default_note")));
        body.add(BookDialogs.text(powersLine(kingdom, rankId)));

        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(player, Text.get("book.ranks.rename"), p -> rename(p, rankId, null)).icon(BookIcons.RANKS));
        boolean middle = !rankId.equals(Ranks.TOP) && !rankId.equals(Ranks.DEFAULT);
        if (middle && at > 1) buttons.add(go(player, Text.get("book.ranks.up"), p -> move(p, rankId, true)));
        if (middle && at < ranks.size() - 2) buttons.add(go(player, Text.get("book.ranks.down"), p -> move(p, rankId, false)));
        if (!rankId.equals(Ranks.TOP) && rules(player, kingdom)) {
            buttons.add(go(player, Text.get("book.ranks.powers"), p -> powers(p, rankId, null)).icon(BookIcons.LEADER));
        }
        if (middle) buttons.add(go(player, Text.get("book.ranks.remove"), p -> confirmRemove(p, rankId)).icon(BookIcons.NO));
        BookDialogs.show(player, BookDialogs.list(BookDialogs.clip(rank.name(), 32), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> ranks(p, null))));
    }

    /**
     * "Powers: declaring war, claiming land." or "Carries no powers." The top rank's powers come from the Lord's seat,
     * not from grants to the rank, so it says it holds them all.
     */
    public static String powersLine(OrgId kingdom, String rankId) {
        if (rankId.equals(Ranks.TOP)) return Text.get("book.ranks.top_powers");
        Set<Power> powers = Ranks.powers(rt().state, rt().clock.now(), kingdom, rankId);
        if (powers.isEmpty()) return Text.get("book.ranks.no_powers");
        List<String> names = new ArrayList<>();
        for (Power power : powers) names.add(Text.get("book.power." + power.name().toLowerCase(Locale.ROOT)));
        return Text.get("book.ranks.has_powers", "powers", String.join(", ", names));
    }

    private static void move(ServerPlayer p, String rankId, boolean up) {
        OrgId kingdom = kingdom(p);
        if (kingdom == null) return;
        BookRouter.run(p, new MoveRank(kingdom, rankId, up), line -> rank(p, rankId, BookResult.of(line, Text.get("book.ranks.moved"))));
    }

    private static void confirmRemove(ServerPlayer player, String rankId) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        Optional<Ranks.Rank> found = rt().state.ranks.rank(kingdom, rankId);
        if (found.isEmpty()) {
            ranks(player, Text.get("book.ranks.gone"));
            return;
        }
        String name = found.get().name();
        String fallback = ladder(kingdom).get(ladder(kingdom).size() - 1).name();
        BookButton yes = go(player, Text.get("book.ranks.remove_verb", "rank", BookDialogs.clip(name, 16)), p -> {
            OrgId now = kingdom(p);
            if (now == null) return;
            BookRouter.run(p, new RemoveRank(now, rankId), line -> {
                if (line != null) rank(p, rankId, line);
                else ranks(p, BookResult.ok(Text.get("book.ranks.removed", "rank", name)));
            });
        }).icon(BookIcons.NO);
        BookButton no = BookDialogs.back(player, p -> rank(p, rankId, null));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.ranks.remove_title"),
                List.of(BookDialogs.text(Text.get("book.ranks.remove_confirm", "rank", name, "n", holders(kingdom, rankId),
                        "default", fallback))), yes, no));
    }

    // ---- naming: a dropdown of common names, or typed ----

    private static List<String> suggestionIds() {
        List<String> ids = new ArrayList<>();
        ids.add(OWN);
        for (int i = 0; i < Ranks.SUGGESTED.size(); i++) ids.add("s" + i);
        return ids;
    }

    private static List<String> suggestionsShown() {
        List<String> shown = new ArrayList<>();
        shown.add(Text.get("book.ranks.own_name"));
        shown.addAll(Ranks.SUGGESTED);
        return shown;
    }

    /** The name a naming form chose: the picked suggestion, or what was typed when "my own" was left picked. */
    static Optional<String> chosenName(String raw) {
        Optional<List<String>> values = FormValues.split(raw, 2);
        if (values.isEmpty()) return Optional.empty();
        String pick = values.get().get(0);
        if (!pick.equals(OWN)) {
            if (!pick.startsWith("s")) return Optional.empty();
            try {
                int i = Integer.parseInt(pick.substring(1));
                return i >= 0 && i < Ranks.SUGGESTED.size() ? Optional.of(Ranks.SUGGESTED.get(i)) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        String typed = values.get().get(1);
        return typed.isBlank() ? Optional.empty() : Optional.of(typed);
    }

    private static List<BookInput> nameInputs(String initial) {
        return List.of(BookDialogs.choice("pick", Text.get("book.ranks.pick"), suggestionIds(), suggestionsShown()),
                BookDialogs.textInput("name", Text.get("book.ranks.typed"), initial, Ranks.MAX_NAME));
    }

    public static void rename(ServerPlayer player, String rankId, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        Optional<Ranks.Rank> found = rt().state.ranks.rank(kingdom, rankId);
        if (found.isEmpty()) {
            ranks(player, Text.get("book.ranks.gone"));
            return;
        }
        List<BookInput> inputs = nameInputs(found.get().name());
        BookButton submit = BookDialogs.submit(player, Text.get("book.ranks.rename_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            OrgId now = kingdom(p);
            if (now == null) return;
            Optional<String> name = chosenName(form.raw());
            if (name.isEmpty()) {
                rename(p, rankId, Text.get("book.ranks.no_name"));
                return;
            }
            BookRouter.run(p, new RenameRank(now, rankId, name.get()), line -> {
                if (line != null) rename(p, rankId, line);
                else rank(p, rankId, BookResult.ok(Text.get("book.ranks.renamed", "rank", Ranks.clean(name.get()).orElse(name.get()))));
            });
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.ranks.rename_title"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.ranks.name_intro")))), inputs, submit,
                BookDialogs.back(player, p -> rank(p, rankId, null))));
    }

    public static void add(ServerPlayer player, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        List<BookInput> inputs = nameInputs("");
        BookButton submit = BookDialogs.submit(player, Text.get("book.ranks.add_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            OrgId now = kingdom(p);
            if (now == null) return;
            Optional<String> name = chosenName(form.raw());
            if (name.isEmpty()) {
                add(p, Text.get("book.ranks.no_name"));
                return;
            }
            BookRouter.run(p, new AddRank(now, name.get()), line -> {
                if (line != null) add(p, line);
                else ranks(p, BookResult.ok(Text.get("book.ranks.added", "rank", Ranks.clean(name.get()).orElse(name.get()))));
            });
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.ranks.add"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.ranks.name_intro")))), inputs, submit,
                BookDialogs.back(player, p -> ranks(p, null))));
    }

    // ---- giving a rank: pick a member, pick a rank ----

    public static void give(ServerPlayer player, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        CharterRuntime rt = rt();
        BookButton back = BookDialogs.back(player, p -> ranks(p, null));
        List<String> memberIds = new ArrayList<>();
        List<String> memberShown = new ArrayList<>();
        for (PlayerId m : Ranks.members(rt.state, kingdom)) {
            if (Ranks.isRuler(rt.state, m, kingdom)) continue;
            String rank = rt.state.ranks.rankOf(rt.state, m).map(Ranks.Rank::name).orElse("");
            memberIds.add(m.value().toString());
            memberShown.add(BookDialogs.clip(rt.describe.player(m), 16) + " (" + rank + ")");
        }
        List<String> rankIds = new ArrayList<>();
        List<String> rankShown = new ArrayList<>();
        for (Ranks.Rank r : ladder(kingdom)) {
            if (r.id().equals(Ranks.TOP)) continue;
            rankIds.add(r.id());
            rankShown.add(r.name());
        }
        if (memberIds.isEmpty()) {
            BookDialogs.show(player, BookDialogs.list(Text.get("book.ranks.give"),
                    BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.ranks.give_none")))), List.of(), back));
            return;
        }
        List<BookInput> inputs = List.of(BookDialogs.choice("who", Text.get("book.ranks.give_who"), memberIds, memberShown),
                BookDialogs.choice("rank", Text.get("book.ranks.give_rank"), rankIds, rankShown));
        BookButton submit = BookDialogs.submit(player, Text.get("book.ranks.give_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            OrgId now = kingdom(p);
            if (now == null) return;
            Optional<List<String>> values = FormValues.split(form.raw(), 2);
            Optional<PlayerId> who = values.flatMap(v -> parse(v.get(0)));
            if (who.isEmpty()) {
                give(p, Text.get("book.leader.form_bad"));
                return;
            }
            String rankId = values.get().get(1);
            String name = rt().describe.player(who.get());
            BookRouter.run(p, new GiveRank(now, who.get(), rankId), line -> give(p, BookResult.of(line, Text.get("book.ranks.given",
                    "player", name, "rank", rt().state.ranks.rank(now, rankId).map(Ranks.Rank::name).orElse(rankId)))));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.ranks.give"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.ranks.give_intro")))), inputs, submit, back));
    }

    // ---- what a rank may do (the Lord only) ----

    /** The powers the Lord may put on a rank: never a forbidden one, and only those the Lord can pass on. */
    static List<Power> offered(ServerPlayer player, OrgId kingdom, List<Power> powers) {
        List<Power> out = new ArrayList<>();
        for (Power power : powers) {
            if (Ranks.FORBIDDEN.contains(power)) continue;
            if (holds(player, kingdom, power, true)) out.add(power);
        }
        return out;
    }

    public static void powers(ServerPlayer player, String rankId, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        Optional<Ranks.Rank> found = rt().state.ranks.rank(kingdom, rankId);
        if (found.isEmpty()) {
            ranks(player, Text.get("book.ranks.gone"));
            return;
        }
        if (!rules(player, kingdom)) {
            rank(player, rankId, BookRouter.refusal(Ranks.NOT_LORD));
            return;
        }
        List<BookButton> buttons = new ArrayList<>();
        for (LeaderPages.Group group : LeaderPages.GROUPS) {
            if (offered(player, kingdom, group.powers()).isEmpty()) continue;
            buttons.add(go(player, Text.get("book.grant.group." + group.key()), p -> powersIn(p, rankId, group, null)).icon(BookIcons.LEADER));
        }
        List<Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("book.ranks.powers_intro", "rank", found.get().name())));
        body.add(BookDialogs.text(powersLine(kingdom, rankId)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.ranks.powers"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> rank(p, rankId, null))));
    }

    private static void powersIn(ServerPlayer player, String rankId, LeaderPages.Group group, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        Optional<Ranks.Rank> found = rt().state.ranks.rank(kingdom, rankId);
        if (found.isEmpty()) {
            ranks(player, Text.get("book.ranks.gone"));
            return;
        }
        Set<Power> has = Ranks.powers(rt().state, rt().clock.now(), kingdom, rankId);
        List<BookButton> buttons = new ArrayList<>();
        for (Power power : offered(player, kingdom, group.powers())) {
            boolean on = has.contains(power);
            String label = Text.get(on ? "book.ranks.power_on" : "book.ranks.power_off",
                    "power", BookDialogs.clip(Text.get("book.grant.power." + power.name().toLowerCase(Locale.ROOT)), 18));
            buttons.add(go(player, label, p -> toggle(p, rankId, group, power, !on)).icon(on ? BookIcons.YES : BookIcons.NO));
        }
        List<Component> body = List.of(BookDialogs.text(Text.get("book.ranks.toggle_intro", "rank", found.get().name())));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.grant.group." + group.key()), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> powers(p, rankId, null))));
    }

    private static void toggle(ServerPlayer p, String rankId, LeaderPages.Group group, Power power, boolean on) {
        OrgId kingdom = kingdom(p);
        if (kingdom == null) return;
        String what = Text.get("book.grant.power." + power.name().toLowerCase(Locale.ROOT));
        BookRouter.run(p, new SetRankPower(kingdom, rankId, power, on), line -> powersIn(p, rankId, group,
                BookResult.of(line, Text.get(on ? "book.ranks.power_given" : "book.ranks.power_taken", "power", what))));
    }

    // ---- My faction: the ranks, read only ----

    public static void list(ServerPlayer player, String notice) {
        OrgId kingdom = kingdom(player);
        if (kingdom == null) return;
        CharterRuntime rt = rt();
        String mine = rt.state.ranks.rankOf(rt.state, me(player)).map(Ranks.Rank::name).orElse("");
        StringBuilder lines = new StringBuilder();
        for (Ranks.Rank rank : ladder(kingdom)) {
            List<PlayerId> who = rt.state.ranks.holders(rt.state, kingdom, rank.id());
            String held;
            if (who.isEmpty()) held = Text.get("book.ranks.nobody");
            else if (who.size() <= 3) held = String.join(", ", who.stream().map(rt.describe::player).toList());
            else held = String.join(", ", who.subList(0, 2).stream().map(rt.describe::player).toList())
                    + " " + Text.get("book.faction.and_more", "n", who.size() - 2);
            if (lines.length() > 0) lines.append('\n');
            lines.append(Text.get("book.ranks.line", "rank", rank.name(), "who", held));
        }
        List<Component> body = new ArrayList<>();
        body.add(Component.literal(Text.get("book.ranks.yours", "rank", mine)).withStyle(ChatFormatting.GOLD));
        body.add(Component.literal(lines.toString()));
        List<BookButton> buttons = new ArrayList<>();
        if (manages(player)) buttons.add(go(player, Text.get("book.ranks.change"), p -> ranks(p, null)).icon(BookIcons.RANKS));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.ranks.title"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> MemberPages.faction(p, null))));
    }

    private static Optional<PlayerId> parse(String uuid) {
        try {
            return Optional.of(new PlayerId(UUID.fromString(uuid)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
