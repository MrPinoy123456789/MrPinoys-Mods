package pocketdungeons;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Vertical floors phase 2: the journal's cell key gains a story. A key written before vertical floors ("x,z")
 * must still read back as the same story 0 cell and must still be what a story 0 cell writes. The golden
 * fixture {@code floor_room_cell_keys.txt} holds keys in the old shape; it is never edited.
 *
 * <p>Pure JDK.
 */
public class FloorRoomCellKeyTest {

    public static void main(String[] args) throws IOException {
        String text;
        try (InputStream in = FloorRoomCellKeyTest.class.getClassLoader()
                .getResourceAsStream("floor_room_cell_keys.txt")) {
            if (in == null) {
                throw new AssertionError("missing fixture floor_room_cell_keys.txt");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int checked = 0;
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            PlanCell cell = FloorRooms.parseCellKey(line);
            check(cell != null, "old key reads: " + line);
            check(cell.y() == 0, "old key is story 0: " + line);
            check(FloorRooms.cellKey(cell).equals(line), "story 0 still writes the old shape: " + line);
            check(new FloorRooms.Room("r", "corridor", cell).cellKey().equals(line), "Room.cellKey: " + line);
            checked++;
        }
        check(checked >= 4, "fixture has keys");

        PlanCell down = new PlanCell(3, -1, -7);
        check(FloorRooms.cellKey(down).equals("3,-7,-1"), "story -1 key: " + FloorRooms.cellKey(down));
        check(down.equals(FloorRooms.parseCellKey("3,-7,-1")), "story -1 key round trips");
        check(!FloorRooms.cellKey(down).equals(FloorRooms.cellKey(new PlanCell(3, -7))), "stories keep keys apart");
        check(FloorRooms.parseCellKey("nonsense") == null, "garbage is null");
        check(FloorRooms.parseCellKey("1,2,3,4") == null, "four numbers is null");
        check(FloorRooms.parseCellKey(null) == null, "null is null");
        System.out.println("FloorRoomCellKeyTest passed (" + checked + " old keys)");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
