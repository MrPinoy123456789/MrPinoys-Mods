package charter.mc;

import charter.mc.book.BookRules;
import charter.mc.land.LiftBook;
import charter.mc.terminal.TerminalBook;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static charter.mc.FabricSuite.check;

/** The pure parts of terminals, the book rules and the lifted Nexus: registries, files and decisions. */
final class TerminalBookTest {

    private TerminalBookTest() {
    }

    static void run() throws Exception {
        terminalsAreKeyedByTheirExactBlock();
        keepTerminalsGoWithTheirKeep();
        theTerminalFileRoundTrips();
        aBadTerminalIsSkipped();
        whoIsHandedTheBook();
        whoseCopyIsIt();
        liftsAreFoundByKeepIdAndCarrier();
        aLiftEndsForTheRightReason();
        theLiftFileRoundTrips();
    }

    private static TerminalBook.Spot spot(int x, int y, int z) {
        return new TerminalBook.Spot("minecraft:overworld", x, y, z);
    }

    static void terminalsAreKeyedByTheirExactBlock() {
        TerminalBook book = new TerminalBook();
        check("terminals: a new book is empty", book.isEmpty() && book.at(spot(1, 64, 1)) == null);
        check("terminals: a block becomes a terminal", book.add(spot(1, 64, 1), null));
        check("terminals: the same block is refused a second one", !book.add(spot(1, 64, 1), "x") && book.size() == 1);
        check("terminals: an operator's terminal has no keep", book.at(spot(1, 64, 1)).core() == null);
        check("terminals: the block beside or above is not the terminal", book.at(spot(2, 64, 1)) == null && book.at(spot(1, 65, 1)) == null);
        check("terminals: the same block in another dimension is not the terminal",
                book.at(new TerminalBook.Spot("minecraft:the_nether", 1, 64, 1)) == null);
        check("terminals: a blank keep counts as none", book.add(spot(3, 64, 3), " ") && book.at(spot(3, 64, 3)).core() == null);
        check("terminals: remove", book.remove(spot(1, 64, 1)) && !book.remove(spot(1, 64, 1)));
        check("terminals: clear says how many", book.clear() == 1 && book.isEmpty());
    }

    static void keepTerminalsGoWithTheirKeep() {
        TerminalBook book = new TerminalBook();
        book.add(spot(1, 64, 1), "keep-a");
        book.add(spot(2, 64, 2), "keep-b");
        book.add(spot(3, 64, 3), null);
        check("terminals: a keep's terminals are found", book.ofCore("keep-a").size() == 1 && book.ofCore("nobody").isEmpty());
        List<TerminalBook.Terminal> gone = book.removeCore("keep-a");
        check("terminals: removing a keep takes only its terminals", gone.size() == 1 && book.size() == 2
                && book.at(spot(1, 64, 1)) == null && book.at(spot(3, 64, 3)) != null);
        check("terminals: a null keep removes nothing", book.removeCore(null).isEmpty() && book.size() == 2);
    }

    static void theTerminalFileRoundTrips() throws Exception {
        Path dir = Files.createTempDirectory("charter-terminals");
        TerminalBook book = new TerminalBook();
        book.add(spot(10, 70, -4), null);
        book.add(new TerminalBook.Spot("minecraft:the_nether", -1, 32, 9), "0b6c3d1e-0000-4000-8000-000000000001");
        List<String> warnings = new ArrayList<>();
        book.save(dir, warnings::add);
        check("terminals: no temporary file is left", !Files.exists(dir.resolve(TerminalBook.FILE_NAME + ".tmp")));
        TerminalBook back = new TerminalBook();
        back.load(dir, warnings::add);
        check("terminals: the file round trips", back.all().equals(book.all()) && warnings.isEmpty());
        TerminalBook none = new TerminalBook();
        none.load(Files.createTempDirectory("charter-terminals-none"), warnings::add);
        check("terminals: no file means no terminals and no warning", none.isEmpty() && warnings.isEmpty());
    }

    static void aBadTerminalIsSkipped() throws Exception {
        Path dir = Files.createTempDirectory("charter-terminals-bad");
        Files.writeString(dir.resolve(TerminalBook.FILE_NAME), "{\"terminals\":[{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3},"
                + "{\"x\":\"nope\"}]}", StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        TerminalBook book = new TerminalBook();
        book.load(dir, warnings::add);
        check("terminals: a bad entry is skipped and the good one kept", book.size() == 1 && warnings.size() == 1);
        Files.writeString(dir.resolve(TerminalBook.FILE_NAME), "not json", StandardCharsets.UTF_8);
        book.load(dir, warnings::add);
        check("terminals: a broken file leaves none and is left alone", book.isEmpty()
                && Files.readString(dir.resolve(TerminalBook.FILE_NAME)).equals("not json"));
    }

    static void whoIsHandedTheBook() {
        check("book: the knob names", BookRules.mode("everyone") == BookRules.Mode.EVERYONE
                && BookRules.mode(" Newcomers ") == BookRules.Mode.NEWCOMERS && BookRules.mode("NONE") == BookRules.Mode.NONE);
        check("book: an unknown or missing knob keeps today's behaviour", BookRules.mode("sometimes") == BookRules.Mode.EVERYONE
                && BookRules.mode(null) == BookRules.Mode.EVERYONE);
        check("book: known names", BookRules.known("none") && !BookRules.known("sometimes") && !BookRules.known(null));
        check("book: everyone gets one", BookRules.gives(BookRules.Mode.EVERYONE, true, true) && BookRules.gives(BookRules.Mode.EVERYONE, false, false));
        check("book: newcomers only before they join or sign on", BookRules.gives(BookRules.Mode.NEWCOMERS, false, false)
                && !BookRules.gives(BookRules.Mode.NEWCOMERS, true, true) && !BookRules.gives(BookRules.Mode.NEWCOMERS, false, true)
                && !BookRules.gives(BookRules.Mode.NEWCOMERS, true, false));
        check("book: none gives nobody one", !BookRules.gives(BookRules.Mode.NONE, false, false));
    }

    static void whoseCopyIsIt() {
        UUID me = UUID.randomUUID();
        UUID you = UUID.randomUUID();
        check("book: my own copy stays", BookRules.verdict(me, me) == BookRules.Verdict.KEEP);
        check("book: an old copy with no owner is adopted", BookRules.verdict(null, me) == BookRules.Verdict.ADOPT);
        check("book: someone else's copy goes", BookRules.verdict(you, me) == BookRules.Verdict.FOREIGN);
    }

    private static LiftBook.Lift lift(String id, String keep, UUID carrier, long at) {
        return new LiftBook.Lift(id, keep, "org-" + keep, carrier, "minecraft:overworld", 8, 70, 8, at, 0);
    }

    static void liftsAreFoundByKeepIdAndCarrier() {
        LiftBook book = new LiftBook();
        UUID carrier = UUID.randomUUID();
        book.put(lift("l1", "k1", carrier, 0));
        check("lift: found by keep, id and carrier", book.byKeep("k1") != null && book.byId("l1") != null
                && book.byCarrier(carrier) != null && book.byId(null) == null && book.byKeep("k2") == null);
        check("lift: offline keeps the rest", book.byKeep("k1").offline(5).offlineSince() == 5
                && book.byKeep("k1").offline(5).id().equals("l1"));
        check("lift: remove", book.remove("k1") != null && book.isEmpty());
    }

    static void aLiftEndsForTheRightReason() {
        UUID carrier = UUID.randomUUID();
        LiftBook.Lift l = lift("l", "k", carrier, 1_000);
        long max = 600_000;
        long grace = 60_000;
        check("lift: goes on while carried in time", LiftBook.end(l, 2_000, max, grace, true, false, true, true) == null);
        check("lift: a lost keep ends it first", LiftBook.end(l, 2_000, max, grace, false, true, false, false) == LiftBook.End.GONE);
        check("lift: war puts it back at once", LiftBook.end(l, 2_000, max, grace, true, true, true, true) == LiftBook.End.EXPOSED);
        check("lift: a short logout is waited for", LiftBook.end(l.offline(10_000), 20_000, max, grace, true, false, false, false) == null);
        check("lift: a long logout puts it back", LiftBook.end(l.offline(10_000), 70_000, max, grace, true, false, false, false) == LiftBook.End.LOGOUT);
        check("lift: an online carrier without the item puts it back", LiftBook.end(l, 2_000, max, grace, true, false, true, false) == LiftBook.End.LOST);
        check("lift: too long puts it back", LiftBook.end(l, 1_000 + max, max, grace, true, false, true, true) == LiftBook.End.TIMEOUT);
        check("lift: no grace ends a logout at once", LiftBook.end(l.offline(10_000), 10_000, max, 0, true, false, false, false) == LiftBook.End.LOGOUT);
    }

    static void theLiftFileRoundTrips() throws Exception {
        Path dir = Files.createTempDirectory("charter-lifts");
        LiftBook book = new LiftBook();
        book.put(lift("l1", "k1", UUID.randomUUID(), 123).offline(456));
        book.put(lift("l2", "k2", UUID.randomUUID(), 789));
        List<String> warnings = new ArrayList<>();
        book.save(dir, warnings::add);
        LiftBook back = new LiftBook();
        back.load(dir, warnings::add);
        check("lift: the file round trips", back.all().equals(book.all()) && warnings.isEmpty());
        Files.writeString(dir.resolve(LiftBook.FILE_NAME), "{\"lifts\":[{\"id\":\"x\"}]}", StandardCharsets.UTF_8);
        back.load(dir, warnings::add);
        check("lift: a bad entry is skipped with a warning", back.isEmpty() && warnings.size() == 1);
    }
}
