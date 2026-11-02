package pocketdungeons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure regression for {@link RetiredRooms}: the four "return chest" rooms (2026-11-02) are never offered by the
 * pool, stay resolvable by name for saved dungeons, and the retired set names only rooms that still exist.
 */
public class RetiredRoomsTest {

    private static final Set<String> EXPECTED = Set.of("frame_lock", "item_plate", "flow_puzzle", "sorting_floor");

    public static void main(String[] args) throws Exception {
        check(RetiredRooms.IDS.equals(EXPECTED), "the retired set is exactly the four return chest rooms");
        testPoolNeverOffersRetiredRooms();
        testKeptRoomsAreStillOffered();
        testEveryRetiredRoomStillHasAManifest();
        System.out.println("RetiredRoomsTest passed");
    }

    private static RoomManifest.Entry entry(String name) {
        DungeonRoomMeta meta = new DungeonRoomMeta(name, 1, 1, List.of("corridor"), 1, 0, -1, null,
                List.of(), null, 1, List.of(), List.of(), null, DungeonRoomMeta.ACCESS_OPEN,
                DungeonRoomMeta.WINDOW_BARS, 1);
        return new RoomManifest.Entry(name, meta, 5);
    }

    private static void testPoolNeverOffersRetiredRooms() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        for (String id : RetiredRooms.IDS) {
            entries.add(entry("pocketdungeons:" + id));
            entries.add(entry(id));
        }
        RoomManifest manifest = RoomManifest.create(entries, List.of());
        for (String role : new String[]{null, "corridor"}) {
            check(manifest.queryAnyRotation(5, role).isEmpty(), "retired rooms offered for role " + role);
            check(manifest.queryAnyRotation(5, role, null, Set.of()).isEmpty(), "retired rooms offered with tags");
        }
        check(manifest.byName("frame_lock") != null, "a retired room still resolves by name");
        check(manifest.byName("pocketdungeons:sorting_floor") != null, "a qualified retired room still resolves");
    }

    private static void testKeptRoomsAreStillOffered() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.add(entry("pocketdungeons:hold_the_plate"));
        entries.add(entry("pocketdungeons:gallery"));
        entries.add(entry("pocketdungeons:frame_lock"));
        RoomManifest manifest = RoomManifest.create(entries, List.of());
        check(!manifest.queryAnyRotation(5, "corridor").isEmpty(), "kept rooms are offered");
        for (RoomManifest.Match m : manifest.queryAnyRotation(5, "corridor")) {
            check(!RetiredRooms.isRetired(m.entry().name), "only kept rooms come back");
        }
    }

    private static void testEveryRetiredRoomStillHasAManifest() throws Exception {
        Path dir = Path.of("src/main/resources/data/pocketdungeons/dungeon_room");
        for (String id : RetiredRooms.IDS) {
            check(Files.exists(dir.resolve(id + ".json")), "retired room " + id + " has no manifest file (typo?)");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
