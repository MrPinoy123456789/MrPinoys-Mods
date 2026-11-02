package charter.mc;

import charter.mc.cmd.Teleports;
import charter.mc.pad.PadBook;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static charter.mc.FabricSuite.check;

/** The teleport pad registry: keys, kinds and the file. */
final class PadBookTest {

    private PadBookTest() {
    }

    static void run() throws Exception {
        padsAreKeyedByTheirExactBlock();
        onlyOfferedKindsAreNamed();
        removeAndClear();
        theFileRoundTrips();
        aBadEntryIsSkippedAndABrokenFileLeavesNoPads();
    }

    private static PadBook.Spot spot(int x, int y, int z) {
        return new PadBook.Spot("minecraft:overworld", x, y, z);
    }

    static void padsAreKeyedByTheirExactBlock() {
        PadBook book = new PadBook();
        check("pads: a new book is empty", book.isEmpty() && book.at(spot(1, 64, 1)) == null);
        check("pads: a block becomes a pad", book.add(spot(1, 64, 1), Teleports.Kind.KEEP));
        check("pads: the same block is refused a second pad", !book.add(spot(1, 64, 1), Teleports.Kind.KEEP) && book.size() == 1);
        check("pads: the pad is found by an equal key", book.at(new PadBook.Spot("minecraft:overworld", 1, 64, 1)) != null
                && book.at(spot(1, 64, 1)).kind() == Teleports.Kind.KEEP);
        check("pads: the block above, below or beside is not the pad", book.at(spot(1, 65, 1)) == null
                && book.at(spot(1, 63, 1)) == null && book.at(spot(2, 64, 1)) == null);
        check("pads: the same block in another dimension is not the pad",
                book.at(new PadBook.Spot("minecraft:the_nether", 1, 64, 1)) == null);
    }

    static void onlyOfferedKindsAreNamed() {
        check("pads: keep is a kind in any case", PadBook.kind("keep").orElse(null) == Teleports.Kind.KEEP
                && PadBook.kind(" KEEP ").orElse(null) == Teleports.Kind.KEEP);
        check("pads: a trip not offered is not a kind yet", PadBook.kind("home").isEmpty() && PadBook.kind("hub").isEmpty()
                && PadBook.kind("nonsense").isEmpty() && PadBook.kind(null).isEmpty());
        check("pads: tab completion names keep", PadBook.kindNames().equals(List.of("keep")));
    }

    static void removeAndClear() {
        PadBook book = new PadBook();
        book.add(spot(0, 70, 0), Teleports.Kind.KEEP);
        book.add(spot(5, 70, 0), Teleports.Kind.KEEP);
        check("pads: removing a block that is no pad says so", !book.remove(spot(9, 70, 0)) && book.size() == 2);
        check("pads: a pad is removed", book.remove(spot(0, 70, 0)) && book.at(spot(0, 70, 0)) == null && book.size() == 1);
        check("pads: a removed block can be a pad again", book.add(spot(0, 70, 0), Teleports.Kind.KEEP));
        check("pads: clear says how many it removed", book.clear() == 2 && book.isEmpty());
    }

    static void theFileRoundTrips() throws Exception {
        Path dir = Files.createTempDirectory("charter-pads").resolve("charter");
        PadBook book = new PadBook();
        book.add(spot(-12, 70, 340), Teleports.Kind.KEEP);
        book.add(new PadBook.Spot("minecraft:the_nether", 3, 40, -9), Teleports.Kind.KEEP);
        List<String> warnings = new ArrayList<>();
        book.save(dir, warnings::add);
        check("pads: saving writes the file", Files.exists(dir.resolve(PadBook.FILE_NAME)) && warnings.isEmpty());
        PadBook again = new PadBook();
        again.load(dir, warnings::add);
        check("pads: the file reads back every pad in order", again.all().equals(book.all()) && warnings.isEmpty());
        PadBook none = new PadBook();
        none.load(Files.createTempDirectory("charter-pads"), warnings::add);
        check("pads: no file means no pads and no warning", none.isEmpty() && warnings.isEmpty());
    }

    static void aBadEntryIsSkippedAndABrokenFileLeavesNoPads() throws Exception {
        Path dir = Files.createTempDirectory("charter-pads");
        Files.writeString(dir.resolve(PadBook.FILE_NAME), "{ \"pads\": ["
                + "{ \"dimension\": \"minecraft:overworld\", \"x\": 1, \"y\": 2, \"z\": 3, \"kind\": \"keep\" },"
                + "{ \"dimension\": \"minecraft:overworld\", \"x\": 4, \"y\": 5, \"z\": 6, \"kind\": \"moon\" },"
                + "{ \"dimension\": \"minecraft:overworld\", \"kind\": \"keep\" } ] }", StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        PadBook book = new PadBook();
        book.load(dir, warnings::add);
        check("pads: good entries load beside bad ones", book.size() == 1 && book.at(spot(1, 2, 3)) != null);
        check("pads: each bad entry is warned about", warnings.size() == 2);

        Files.writeString(dir.resolve(PadBook.FILE_NAME), "not json at all {", StandardCharsets.UTF_8);
        warnings.clear();
        book.load(dir, warnings::add);
        check("pads: a broken file leaves no pads and a warning", book.isEmpty() && warnings.size() == 1);
        check("pads: a broken file is left as it was", Files.readString(dir.resolve(PadBook.FILE_NAME)).startsWith("not json"));
    }
}
