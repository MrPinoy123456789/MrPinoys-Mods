package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Q1: the data the Astrolabe Room draws from. Every dungeon names its doormat token (a distinct block, so a
 * door is told apart by the floor in front of it), no act shows more doors than the row holds, and only the
 * Endless Mine stands outside the acts.
 */
public class HallDataTest {

    private static final Path DUNGEONS = Paths.get("src/main/resources/data/pocketdungeons/dungeon");

    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(DUNGEONS)) {
            files = walk.filter(f -> f.toString().endsWith(".json")).toList();
        }
        Map<Integer, Integer> perAct = new HashMap<>();
        Set<String> mats = new HashSet<>();
        int specials = 0;
        for (Path file : files) {
            String id = file.getFileName().toString().replace(".json", "");
            JsonObject d;
            try (Reader reader = Files.newBufferedReader(file)) {
                d = JsonParser.parseReader(reader).getAsJsonObject();
            }
            check(d.has("token") && d.getAsJsonObject("token").has("mat"), id + ": no token.mat");
            String mat = d.getAsJsonObject("token").get("mat").getAsString();
            check(mat.startsWith("minecraft:"), id + ": token.mat must be a block id: " + mat);
            check(mats.add(mat), id + ": the doormat " + mat + " is already another dungeon's");
            String hall = d.has("hall") ? d.get("hall").getAsString() : HallLayout.HALL_ACT;
            boolean endless = "endless".equals(d.get("kind").getAsString());
            check(hall.equals(HallLayout.HALL_ACT) || hall.equals(HallLayout.HALL_SPECIAL), id + ": bad hall " + hall);
            check(endless == hall.equals(HallLayout.HALL_SPECIAL),
                    id + ": exactly the Endless Mine stands outside the acts");
            if (hall.equals(HallLayout.HALL_SPECIAL)) {
                specials++;
            } else {
                perAct.merge(d.get("act").getAsInt(), 1, Integer::sum);
            }
        }
        for (Map.Entry<Integer, Integer> e : perAct.entrySet()) {
            check(e.getValue() <= HallLayout.MAX_ACT_DOORS,
                    "act " + e.getKey() + " has " + e.getValue() + " dungeons; the row holds " + HallLayout.MAX_ACT_DOORS);
        }
        check(specials <= HallLayout.MAX_SPECIALS, "too many special doors: " + specials);
        check(files.size() >= 19, "found only " + files.size() + " dungeons");
        System.out.println("HallDataTest passed (" + files.size() + " dungeons, " + specials + " special, acts "
                + new java.util.TreeMap<>(perAct) + ")");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
