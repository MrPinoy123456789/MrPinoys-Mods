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
import java.util.List;
import java.util.stream.Stream;

/**
 * The spawner key share knob (2026-10-08): a trial spawner ejects a vault key or emeralds, weighted in
 * its {@code loot_tables_to_eject}. {@code tools/loot_knobs.json} holds {@code spawnerKeyPercent}
 * (default 60) and {@code python tools/tune_loot.py apply} writes it into the files. This holds every
 * spawner config to it, so a hand edit or a regenerated file that drops below the knob fails here.
 * A share above the knob (tier 1 spawners sit at 80 percent) is allowed: the knob is a floor.
 */
public class SpawnerEjectOddsTest {

    private static final Path KNOBS = Paths.get("tools/loot_knobs.json");
    private static final Path SPAWNERS = Paths.get("src/main/resources/data/pocketdungeons/trial_spawner");

    public static void main(String[] args) throws IOException {
        int percent;
        try (Reader reader = Files.newBufferedReader(KNOBS)) {
            percent = JsonParser.parseReader(reader).getAsJsonObject().get("spawnerKeyPercent").getAsInt();
        }
        check(percent >= 0 && percent <= 100, "spawnerKeyPercent must be 0 to 100, got " + percent);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SPAWNERS)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).toList();
        }
        int checked = 0;
        for (Path file : files) {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(file)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonArray eject = root.has("loot_tables_to_eject") ? root.getAsJsonArray("loot_tables_to_eject") : null;
            if (eject == null) {
                continue;
            }
            int key = 0;
            int emeralds = 0;
            for (JsonElement entry : eject) {
                String data = entry.getAsJsonObject().get("data").getAsString();
                int weight = entry.getAsJsonObject().get("weight").getAsInt();
                if (data.endsWith("spawners/trial_key") || data.endsWith("spawners/ominous_trial_key")) {
                    key += weight;
                } else if (data.endsWith("spawners/emeralds")) {
                    emeralds += weight;
                }
            }
            if (key + emeralds == 0) {
                continue;
            }
            checked++;
            check(key * 100L >= (long) percent * (key + emeralds),
                    file + ": keys " + key + " / emeralds " + emeralds + " is under the " + percent
                            + " percent key knob; run: python tools/tune_loot.py apply");
        }
        check(checked > 100, "found only " + checked + " spawner configs with a key and emerald eject");
        System.out.println("SpawnerEjectOddsTest passed (" + checked + " configs at " + percent + " percent or better)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
