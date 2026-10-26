package charter.mc;

import charter.id.CoreId;
import charter.mc.justice.CellBook;
import charter.mc.justice.CellBook.Pos;
import charter.mc.justice.CellFill;
import charter.mc.justice.CommandRule;
import charter.mc.store.CharterJson;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.state.CharterState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static charter.mc.FabricSuite.check;

/**
 * M12, plain half: the cell flood fill over a made-up world (a closed room is a
 * cell; an open door, a missing ceiling, a room too big, a room that runs off the
 * keep and a start inside stone are each refused), and the cell book saved,
 * reloaded and written back the same.
 */
final class JusticeTest {

    private JusticeTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        aClosedRoomIsACell();
        openingsAreRefused();
        aRoomOffTheKeepIsRefused();
        theStartMustBeOpenAir();
        bookRoundTrips();
        bookDropsCellsOfGoneKeeps();
        onlyCharterCommandsPassForPrisoners();
    }

    /** A box of air from (0,0,0) to (w-1,h-1,d-1) inside walls; blocks in {@code open} are air, others in the wall shell are solid. */
    private static final class Room implements CellFill.Space {
        final int w, h, d;
        final Set<Pos> open = new HashSet<>();
        final int keepMin, keepMax;

        Room(int w, int h, int d, int keepMin, int keepMax) {
            this.w = w;
            this.h = h;
            this.d = d;
            this.keepMin = keepMin;
            this.keepMax = keepMax;
        }

        @Override
        public boolean passable(int x, int y, int z) {
            boolean inside = x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < d;
            return inside || open.contains(new Pos(x, y, z));
        }

        @Override
        public boolean inKeep(int x, int y, int z) {
            return x >= keepMin && x <= keepMax && y >= -64 && y <= 320 && z >= keepMin && z <= keepMax;
        }
    }

    static void aClosedRoomIsACell() {
        Room room = new Room(3, 2, 3, -10, 100);
        CellFill.Result r = CellFill.fill(room, new Pos(1, 0, 1), 150);
        check("cell: a closed 3 by 2 by 3 room is a cell", r.ok());
        check("cell: it is all 18 blocks of air", r.blocks().size() == 18);
        check("cell: the blocks come back sorted so a saved cell never changes order", r.blocks().equals(r.blocks().stream().sorted().toList()));
        check("cell: the marker's own block is in it", r.blocks().contains(new Pos(1, 0, 1)));
    }

    static void openingsAreRefused() {
        Room door = new Room(3, 2, 3, -10, 100);
        for (int x = 3; x < 200; x++) door.open.add(new Pos(x, 0, 1));
        CellFill.Result viaDoor = CellFill.fill(door, new Pos(1, 0, 1), 150);
        check("cell: a doorway into a passage is refused", !viaDoor.ok());
        check("cell: and it is the open space that is blamed, either as a leak or as too big",
                CellFill.TOO_BIG.equals(viaDoor.refusal()) || CellFill.LEAKS.equals(viaDoor.refusal()));

        Room hole = new Room(3, 2, 3, -10, 100);
        for (int y = 2; y < 400; y++) hole.open.add(new Pos(1, y, 1)); // a shaft to the sky
        CellFill.Result viaCeiling = CellFill.fill(hole, new Pos(1, 0, 1), 150);
        check("cell: a hole in the ceiling to open air is refused", !viaCeiling.ok());

        Room tooBig = new Room(10, 6, 10, -10, 100);
        CellFill.Result big = CellFill.fill(tooBig, new Pos(1, 0, 1), 150);
        check("cell: a 600 block room is too big", !big.ok() && CellFill.TOO_BIG.equals(big.refusal()));
        check("cell: the same room passes when the limit allows it", CellFill.fill(tooBig, new Pos(1, 0, 1), 600).ok());

        Room exact = new Room(5, 3, 10, -10, 100);
        check("cell: exactly the limit is allowed", CellFill.fill(exact, new Pos(1, 0, 1), 150).ok());
        check("cell: one block more is not", CellFill.TOO_BIG.equals(CellFill.fill(exact, new Pos(1, 0, 1), 149).refusal()));
    }

    static void aRoomOffTheKeepIsRefused() {
        // A doorway in the east wall opens onto ground outside the keep.
        Room room = new Room(3, 2, 3, 0, 2);
        room.open.add(new Pos(3, 0, 1));
        CellFill.Result r = CellFill.fill(room, new Pos(1, 0, 1), 150);
        check("cell: a room that runs out of the keep is refused as a leak", !r.ok() && CellFill.LEAKS.equals(r.refusal()));

        // Walled all round just inside the keep edge is fine.
        Room inside = new Room(3, 2, 3, -1, 3);
        check("cell: a room whose walls stand inside the keep is a cell", CellFill.fill(inside, new Pos(1, 0, 1), 150).ok());
    }

    static void theStartMustBeOpenAir() {
        Room room = new Room(3, 2, 3, -10, 100);
        CellFill.Result inStone = CellFill.fill(room, new Pos(8, 0, 8), 150);
        check("cell: standing inside stone is refused", !inStone.ok() && CellFill.START_BLOCKED.equals(inStone.refusal()));
        Room low = new Room(3, 1, 3, -10, 100);
        CellFill.Result noHead = CellFill.fill(low, new Pos(1, 0, 1), 150);
        check("cell: a room with no headroom is refused", !noHead.ok() && CellFill.START_BLOCKED.equals(noHead.refusal()));
        Room offKeep = new Room(3, 2, 3, 5, 9);
        check("cell: a start off the keep is refused", CellFill.START_BLOCKED.equals(CellFill.fill(offKeep, new Pos(1, 0, 1), 150).refusal()));
    }

    static CharterStore store(Path dir) {
        return new CharterStore(dir, Persistence.codec(Persistence.json()), s -> { }, s -> { });
    }

    static void bookRoundTrips() throws Exception {
        FullState f = FullState.build();
        CellBook book = CellBook.of(f.state);
        book.clear();
        book.add(new CellBook.Cell(f.keep.id, "North Cell", FullState.OW, new Pos(3, 64, 5),
                List.of(new Pos(3, 64, 5), new Pos(3, 65, 5), new Pos(4, 64, 5))));
        book.add(new CellBook.Cell(f.keep.id, "Deep", "minecraft:the_nether", new Pos(-1, 10, -1), List.of(new Pos(-1, 10, -1), new Pos(-1, 11, -1))));

        Path dir = Files.createTempDirectory("charter-cells");
        store(dir).save(f.state, f.clock.now());
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        CharterState back = loaded.state();
        CellBook read = CellBook.of(back);
        check("cells: a clean save needs no repair", loaded.repairs().count() == 0);
        check("cells: both read back", read.all().size() == 2);
        CellBook.Cell north = read.get(f.keep.id, "north cell");
        check("cells: a cell is found by name whatever the case", north != null && north.name.equals("North Cell"));
        check("cells: it keeps its blocks, spawn and dimension", north != null && north.blocks.size() == 3
                && north.spawn.equals(new Pos(3, 64, 5)) && north.dimension.equals(FullState.OW));
        check("cells: a block of it is inside", north != null && north.contains(FullState.OW, 3, 65, 5));
        check("cells: the same coordinates in another dimension are not", north != null && !north.contains("minecraft:the_nether", 3, 65, 5));
        check("cells: the keep lists its cells", read.of(f.keep.id).size() == 2);

        CharterJson json = Persistence.json();
        String first = json.gson.toJson(store(dir).codec().write(f.state));
        String second = json.gson.toJson(store(dir).codec().write(back));
        check("cells: the reloaded state writes the same snapshot as the live one", first.equals(second));
        check("cells: the snapshot has a cells block", first.contains("\"cells\""));
    }

    static void bookDropsCellsOfGoneKeeps() throws Exception {
        FullState f = FullState.build();
        CellBook book = CellBook.of(f.state);
        book.clear();
        book.add(new CellBook.Cell(f.keep.id, "Kept", FullState.OW, new Pos(0, 0, 0), List.of(new Pos(0, 0, 0))));
        CoreId gone = f.state.ids.core();
        book.add(new CellBook.Cell(gone, "Orphan", FullState.OW, new Pos(9, 9, 9), List.of(new Pos(9, 9, 9))));
        Path dir = Files.createTempDirectory("charter-cells-gone");
        store(dir).save(f.state, f.clock.now());
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        CellBook read = CellBook.of(loaded.state());
        check("cells: a cell whose keep is gone is dropped on load", read.all().size() == 1 && read.get(gone, "Orphan") == null);
        check("cells: the drop is noted for history", loaded.repairs().count() >= 1);
    }

    static void onlyCharterCommandsPassForPrisoners() {
        check("commands: charter passes", CommandRule.allowed("charter justice"));
        check("commands: charter with a slash passes", CommandRule.allowed("/charter act abc"));
        check("commands: upper case charter passes", CommandRule.allowed("Charter"));
        check("commands: home does not", !CommandRule.allowed("home"));
        check("commands: tp does not", !CommandRule.allowed("/tp 0 64 0"));
        check("commands: a command that merely starts with charter does not", !CommandRule.allowed("charterx"));
    }
}
