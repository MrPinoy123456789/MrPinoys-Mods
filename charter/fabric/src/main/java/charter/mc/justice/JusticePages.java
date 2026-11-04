package charter.mc.justice;

import charter.contract.Basis;
import charter.contract.BasisCaps;
import charter.contract.Contract;
import charter.contract.Custody;
import charter.contract.Justice;
import charter.contract.Term;
import charter.contract.actions.Bail;
import charter.contract.actions.Confine;
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
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookIcons;
import charter.mc.book.BookResult;
import charter.mc.book.LeaderPages;
import charter.mc.cmd.Chat;
import charter.org.MemberRecord;
import charter.time.Durations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Justice as buttons, for players who cannot type {@code /charter justice sentence <player>}, {@code /charter bail} or
 * {@code /charter cell mark <name>}: who is in custody or held, a sentence as a few buttons (each already cut to the
 * caps by the engine), cells marked and removed without typing a name, and bail. Every press runs the same action the
 * chat link ran, as the player, so the engine checks the power and the caps. The safe stays closed, as on the server
 * ({@code /charter safe}), so it has no page.
 */
public final class JusticePages {

    /** The sentence lengths offered; core cuts each to its cap and to what is left of the week. */
    private static final long[] HOLDS = {Durations.hours(1), Durations.hours(6), Durations.hours(24)};
    private static final long[] FINES = {64, 512};
    private static final long EXECUTION_WAIT = Durations.minutes(5);
    private static final long EXECUTION_BANISH = Durations.hours(24);

    private JusticePages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId id(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static OrgId root(ServerPlayer player) {
        CharterRuntime rt = rt();
        return rt.state.membership(id(player)).map(MemberRecord::org).map(rt.state::root).orElse(null);
    }

    private static BookButton go(ServerPlayer player, String label, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    private static void run(ServerPlayer p, Action action, String done, Consumer<String> then) {
        Outcome outcome = rt().engine.run(new Actor.Player(id(p)), action);
        then.accept(outcome instanceof Outcome.Refused(Reason reason) ? JusticeCommands.reasonText(reason) : BookResult.ok(done));
    }

    // ---- who is in custody, who is held ----

    public static void home(ServerPlayer player, String notice) {
        OrgId root = root(player);
        if (root == null) {
            LeaderPages.desk(player, null);
            return;
        }
        CharterRuntime rt = rt();
        long now = rt.clock.now();
        List<BookButton> buttons = new ArrayList<>();
        for (Custody custody : new ArrayList<>(rt.state.contracts.custodies())) {
            if (!rt.state.root(custody.faction()).equals(root) || !Justice.custodyLive(custody, rt.knobs, now)) continue;
            PlayerId prisoner = custody.prisoner();
            buttons.add(go(player, Text.get("book.justice.sentence", "player", BookDialogs.clip(rt.describe.player(prisoner), 15)),
                    p -> sentence(p, prisoner)).icon(BookIcons.JUSTICE));
        }
        for (Contract c : rt.state.contracts.live()) {
            if (c.basis != Basis.CUSTODY || !c.subject.isPlayer() || !rt.state.root(c.imposer.org()).equals(root)) continue;
            if (!c.holdsPlayer() && c.term(Term.Banishment.class) == null) continue;
            PlayerId prisoner = c.subject.player();
            buttons.add(go(player, Text.get("book.justice.held", "player", BookDialogs.clip(rt.describe.player(prisoner), 17)),
                    p -> held(p, prisoner)).icon(BookIcons.JUSTICE));
        }
        buttons.add(go(player, Text.get("book.justice.cells"), p -> cells(p, null)).icon(BookIcons.KEEP));
        if (!bailable(root).isEmpty()) buttons.add(go(player, Text.get("book.justice.bail"), p -> bail(p, null)).icon(BookIcons.TREASURY));
        List<Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get(buttons.size() > 1 ? "book.justice.intro" : "book.justice.nobody")));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.leader.justice"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> LeaderPages.desk(p, null))));
    }

    // ---- a prisoner awaiting sentence ----

    public static void sentence(ServerPlayer judge, PlayerId prisoner) {
        OrgId me = rt().state.membership(id(judge)).map(MemberRecord::org).orElse(null);
        if (me == null) {
            LeaderPages.desk(judge, null);
            return;
        }
        CharterRuntime rt = rt();
        long now = rt.clock.now();
        OrgId root = rt.state.root(me);
        Optional<Custody> custody = Jail.custodyOf(rt, prisoner).filter(c -> rt.state.root(c.faction()).equals(root));
        String name = rt.describe.player(prisoner);
        if (custody.isEmpty()) {
            home(judge, Text.get("justice.not_in_custody", "player", name));
            return;
        }
        List<BookButton> buttons = new ArrayList<>();
        for (CellBook.Cell cell : Cells.ownCells(rt, me)) {
            for (long hold : HOLDS) {
                BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.Confinement(hold, cell.keep, cell.name), rt.knobs);
                if (!checked.ok()) continue;
                String label = Text.get("book.justice.confine", "cell", BookDialogs.clip(cell.name, 10),
                        "time", span(checked.term().durationMillis()));
                buttons.add(go(judge, label, p -> run(p, new Confine(me, prisoner, hold, cell.keep, cell.name),
                        Text.get("justice.done_confine", "player", name, "cell", cell.name), line -> home(p, line))).icon(BookIcons.KEEP));
            }
        }
        if (rt.knobs.labourTerms) {
            for (Core keep : rt.state.cores()) {
                if (!keep.isKeep() || !rt.state.root(keep.owner).equals(root)) continue;
                for (long hold : HOLDS) {
                    BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.Tether(hold, keep.id), rt.knobs);
                    if (!checked.ok()) continue;
                    String label = Text.get("book.justice.bond", "time", span(checked.term().durationMillis()));
                    buttons.add(go(judge, label, p -> run(p, new Sentence(me, prisoner, List.of(new Term.Tether(hold, keep.id))),
                            Text.get("justice.done_bond", "player", name), line -> home(p, line))).icon(BookIcons.JUSTICE));
                }
            }
        }
        BasisCaps.Checked wait = BasisCaps.check(Basis.CUSTODY, new Term.RespawnDelay(EXECUTION_WAIT), rt.knobs);
        BasisCaps.Checked banish = BasisCaps.check(Basis.CUSTODY, new Term.Banishment(root, EXECUTION_BANISH), rt.knobs);
        if (wait.ok() && banish.ok()) {
            buttons.add(go(judge, Text.get("book.justice.execute", "player", BookDialogs.clip(name, 14)), p -> executeConfirm(p, prisoner)).icon(BookIcons.WAR));
        }
        if (charter.mc.Treasury.available(rt)) {
            for (long marks : FINES) {
                BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, new Term.FineMarks(marks), rt.knobs);
                if (!checked.ok()) continue;
                long shown = ((Term.FineMarks) checked.term()).marks();
                buttons.add(go(judge, Text.get("book.justice.fine", "count", charter.api.Coin.words(shown)), p -> run(p, new FineCustody(me, prisoner, marks),
                        Text.get("justice.done_fine", "player", name), line -> home(p, line))).icon(BookIcons.TREASURY));
            }
        }
        List<Component> body = List.of(BookDialogs.text(Text.get("justice.sentence_info",
                "time", Chat.duration(custody.get().since() + Durations.minutes(rt.knobs.custodyLapseMinutes) - now),
                "week", Chat.duration(rt.state.contracts.punishments.remaining(prisoner, now, rt.knobs)))));
        BookDialogs.show(judge, BookDialogs.list(Text.get("justice.sentence_header", "player", name), body, buttons,
                BookDialogs.back(judge, p -> home(p, null))));
    }

    private static void executeConfirm(ServerPlayer judge, PlayerId prisoner) {
        OrgId me = rt().state.membership(id(judge)).map(MemberRecord::org).orElse(null);
        if (me == null) {
            LeaderPages.desk(judge, null);
            return;
        }
        CharterRuntime rt = rt();
        String name = rt.describe.player(prisoner);
        BasisCaps.Checked wait = BasisCaps.check(Basis.CUSTODY, new Term.RespawnDelay(EXECUTION_WAIT), rt.knobs);
        BasisCaps.Checked banish = BasisCaps.check(Basis.CUSTODY, new Term.Banishment(rt.state.root(me), EXECUTION_BANISH), rt.knobs);
        String line = wait.ok() && banish.ok()
                ? Text.get("justice.row_execute", "wait", Chat.duration(wait.term().durationMillis()),
                        "banish", Chat.duration(banish.term().durationMillis()))
                : Text.get("book.justice.execute_refused");
        BookButton yes = go(judge, Text.get("book.justice.execute", "player", BookDialogs.clip(name, 14)), p -> run(p,
                new Execute(me, prisoner, EXECUTION_WAIT, EXECUTION_BANISH), Text.get("justice.done_execute", "player", name), l -> home(p, l)));
        BookDialogs.show(judge, BookDialogs.confirm(Text.get("book.justice.execute_title", "player", name),
                List.of(BookDialogs.text(line)), yes, BookDialogs.back(judge, p -> sentence(p, prisoner))));
    }

    /** A sentence length short enough for a label: "1 hour", "6 hours", otherwise the usual wording. */
    public static String span(long millis) {
        long hours = millis / Durations.hours(1);
        if (hours > 0 && millis % Durations.hours(1) == 0) return hours == 1 ? Text.get("book.time.hour") : Text.get("book.time.hours", "n", hours);
        return Chat.duration(millis);
    }

    // ---- someone already held ----

    private static void held(ServerPlayer judge, PlayerId prisoner) {
        OrgId root = root(judge);
        if (root == null) {
            LeaderPages.desk(judge, null);
            return;
        }
        CharterRuntime rt = rt();
        String name = rt.describe.player(prisoner);
        Optional<Contract> contract = rt.state.contracts.live().stream()
                .filter(c -> c.basis == Basis.CUSTODY && c.subject.isPlayer() && c.subject.player().equals(prisoner)
                        && rt.state.root(c.imposer.org()).equals(root)).findFirst();
        if (contract.isEmpty()) {
            home(judge, Text.get("justice.not_in_custody", "player", name));
            return;
        }
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(judge, Text.get("justice.link_release"), p -> run(p, new Release(root, prisoner),
                Text.get("justice.released", "player", name), l -> home(p, l))));
        buttons.add(go(judge, Text.get("justice.link_pardon"), p -> run(p, new Pardon(root, prisoner),
                Text.get("justice.pardoned", "player", name), l -> home(p, l))));
        BookDialogs.show(judge, BookDialogs.list(BookDialogs.clip(name, 32),
                List.of(BookDialogs.text(Text.get("justice.held", "player", name, "terms", JusticeText.terms(contract.get().terms)))),
                buttons, BookDialogs.back(judge, p -> home(p, null))));
    }

    // ---- bail ----

    /** Members of this nation held under another faction's sentence. */
    private static List<PlayerId> bailable(OrgId root) {
        CharterRuntime rt = rt();
        List<PlayerId> out = new ArrayList<>();
        for (Contract c : rt.state.contracts.live()) {
            if (c.basis != Basis.CUSTODY || !c.subject.isPlayer() || !c.holdsPlayer()) continue;
            if (rt.state.root(c.imposer.org()).equals(root)) continue;
            PlayerId who = c.subject.player();
            if (rt.state.membership(who).map(MemberRecord::org).map(rt.state::root).filter(root::equals).isPresent()) out.add(who);
        }
        return out;
    }

    private static void bail(ServerPlayer judge, String notice) {
        OrgId root = root(judge);
        if (root == null) {
            LeaderPages.desk(judge, null);
            return;
        }
        List<BookButton> buttons = new ArrayList<>();
        for (PlayerId who : bailable(root)) {
            String name = rt().describe.player(who);
            buttons.add(go(judge, Text.get("book.justice.bail_one", "player", BookDialogs.clip(name, 18)), p -> run(p,
                    new Bail(root, who), Text.get("justice.bailed", "player", name), l -> bail(p, l))).icon(BookIcons.TREASURY));
        }
        BookDialogs.show(judge, BookDialogs.list(Text.get("book.justice.bail"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get(buttons.isEmpty() ? "book.justice.bail_none" : "book.justice.bail_intro")))),
                buttons, BookDialogs.back(judge, p -> home(p, null))));
    }

    // ---- cells: named for you, never typed ----

    private static void cells(ServerPlayer judge, String notice) {
        OrgId root = root(judge);
        if (root == null) {
            LeaderPages.desk(judge, null);
            return;
        }
        CharterRuntime rt = rt();
        List<CellBook.Cell> own = Cells.ownCells(rt, root);
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(go(judge, Text.get("book.justice.cell_mark"), p -> {
            Cells.Done done = Cells.mark(p, nextName(root));
            cells(p, done.ok() ? BookResult.ok(done.text()) : done.text());
        }).icon(BookIcons.KEEP));
        for (CellBook.Cell cell : own) {
            buttons.add(go(judge, Text.get("book.justice.cell_remove", "cell", BookDialogs.clip(cell.name, 14)), p -> {
                Cells.Done done = Cells.remove(p, cell.name);
                cells(p, done.ok() ? BookResult.ok(done.text()) : done.text());
            }));
        }
        BookDialogs.show(judge, BookDialogs.list(Text.get("book.justice.cells"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get(own.isEmpty() ? "book.justice.cells_none" : "book.justice.cells_intro")))),
                buttons, BookDialogs.back(judge, p -> home(p, null))));
    }

    /** The first "Cell N" the nation has not used. */
    static String nextName(OrgId root) {
        List<CellBook.Cell> own = Cells.ownCells(rt(), root);
        for (int n = 1; ; n++) {
            String name = Text.get("book.justice.cell_name", "n", n);
            boolean taken = false;
            for (CellBook.Cell cell : own) if (Cells.norm(cell.name).equals(Cells.norm(name))) taken = true;
            if (!taken) return name;
        }
    }
}
