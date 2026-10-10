package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Floor and dungeon notes (2026-10-08): the one line on the door board that says why a player would choose this
 * door over another. For every Act 1 and Act 2 dungeon except the Endless Mine (whose floors are all alike):
 * <ul>
 *   <li>the dungeon has a note, and so does every floor that is not its entry floor (the entry floor speaks with
 *       the dungeon's note);</li>
 *   <li>no two floors of a dungeon share a note, no two dungeons share one;</li>
 *   <li>a note is one short sentence group with no dash punctuation (also enforced by the loader);</li>
 *   <li>a note never names an ore the floor cannot deliver: each ore word must be in the node's biased rooms'
 *       declared nodes, or in what the dungeon buries or lists in its palette. A dungeon note may also name what
 *       any room bound to the dungeon declares.</li>
 * </ul>
 * Pure JDK plus Gson: the files are read from the source tree.
 */
public class FloorNotesTest {

    private static final Path DATA = Paths.get("src/main/resources/data/pocketdungeons");

    /** The words a note may use for something mined, mapped to the block name fragment that proves it. */
    private static final Map<String, String> ORE_WORDS = Map.of(
            "coal", "coal", "iron", "iron", "gold", "gold", "lapis", "lapis", "diamond", "diamond",
            "redstone", "redstone", "sand", "sand", "clay", "clay", "bone", "bone_block");

    public static void main(String[] args) throws IOException {
        Map<String, JsonObject> rooms = new HashMap<>();
        try (Stream<Path> walk = Files.walk(DATA.resolve("dungeon_room"))) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".json")).toList()) {
                String name = p.getFileName().toString().replace(".json", "");
                rooms.put(name, read(p));
            }
        }
        Set<String> dungeonNotes = new HashSet<>();
        int floors = 0;
        int dungeons = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(DATA.resolve("dungeon"))) {
            files = walk.filter(f -> f.toString().endsWith(".json")).toList();
        }
        for (Path file : files) {
            JsonObject d = read(file);
            String id = file.getFileName().toString().replace(".json", "");
            int act = d.get("act").getAsInt();
            if (act > 2 || "endless".equals(d.get("kind").getAsString())) {
                continue;
            }
            dungeons++;
            String dungeonNote = text(d, "notes");
            check(!dungeonNote.isEmpty(), id + ": the dungeon has no note");
            check(dungeonNotes.add(dungeonNote), id + ": the dungeon note repeats another dungeon's: " + dungeonNote);
            checkShape(id, dungeonNote);
            Set<String> palette = new HashSet<>();
            for (JsonElement e : d.getAsJsonArray("nodePalette")) {
                palette.add(e.getAsString());
            }
            boolean buries = d.has("hiddenOre");
            Set<String> boundBlocks = new HashSet<>();
            for (Map.Entry<String, JsonObject> room : rooms.entrySet()) {
                if (names(room.getValue(), "dungeons").contains(id) || names(room.getValue(), "theme").contains(id)) {
                    boundBlocks.addAll(blocks(room.getValue()));
                }
            }
            checkOres(id, "dungeon note", dungeonNote, union(palette, boundBlocks));

            Set<String> seen = new HashSet<>();
            for (JsonElement el : d.getAsJsonArray("nodes")) {
                JsonObject node = el.getAsJsonObject();
                String nid = node.get("id").getAsString();
                if (node.get("layer").getAsInt() == 1) {
                    continue;
                }
                floors++;
                String note = text(node, "notes");
                check(!note.isEmpty(), id + "/" + nid + ": the floor has no note");
                check(seen.add(note), id + "/" + nid + ": the floor note repeats a sibling's: " + note);
                checkShape(id + "/" + nid, note);
                Set<String> available = new HashSet<>();
                if (node.has("roomBias")) {
                    for (JsonElement r : node.getAsJsonArray("roomBias")) {
                        JsonObject room = rooms.get(r.getAsString());
                        if (room != null) {
                            available.addAll(blocks(room));
                        }
                    }
                }
                if (buries) {
                    available.addAll(palette);
                }
                checkOres(id + "/" + nid, "floor note", note, available);
            }
        }
        check(dungeons >= 10, "found only " + dungeons + " Act 1 and 2 dungeons");
        System.out.println("FloorNotesTest passed (" + dungeons + " dungeons, " + floors + " floors)");
    }

    private static void checkShape(String where, String note) {
        check(note.length() <= DungeonDef.Node.MAX_NOTES, where + ": too long (" + note.length() + "): " + note);
        check(note.indexOf('—') < 0 && !note.contains("--"), where + ": dash punctuation: " + note);
        check(!note.endsWith(" "), where + ": trailing space");
    }

    /** Every ore word in {@code note} must be backed by a block in {@code available}. */
    private static void checkOres(String where, String kind, String note, Set<String> available) {
        String lower = note.toLowerCase();
        for (Map.Entry<String, String> word : ORE_WORDS.entrySet()) {
            if (lower.matches(".*\\b" + word.getKey() + "\\b.*")) {
                boolean backed = available.stream().anyMatch(block -> block.contains(word.getValue()));
                check(backed, where + ": the " + kind + " names " + word.getKey()
                        + " but nothing on the floor can deliver it: " + note);
            }
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static Set<String> blocks(JsonObject room) {
        Set<String> out = new HashSet<>();
        if (room.has("nodes")) {
            for (JsonElement n : room.getAsJsonArray("nodes")) {
                out.add(n.getAsJsonObject().get("block").getAsString());
            }
        }
        return out;
    }

    private static List<String> names(JsonObject room, String key) {
        List<String> out = new ArrayList<>();
        if (room.has(key) && room.get(key).isJsonArray()) {
            JsonArray array = room.getAsJsonArray(key);
            for (JsonElement e : array) {
                out.add(e.getAsString().replace("pocketdungeons:", ""));
            }
        }
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    private static JsonObject read(Path p) throws IOException {
        try (Reader reader = Files.newBufferedReader(p)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
