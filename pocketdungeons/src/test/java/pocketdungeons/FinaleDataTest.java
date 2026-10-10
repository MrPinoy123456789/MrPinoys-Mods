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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Q4 and Q5: the finales and uniforms the dungeon files author. Every Act 1 and Act 2 dungeon that is not a
 * capstone or a two floor shorty ends in a finale; a capstone never has one; from Act 2 up the finale has a
 * named elite; the Copper Works crew wears copper. The shapes are also parsed by {@link DungeonDef}.
 */
public class FinaleDataTest {

    private static final Path DUNGEONS = Paths.get("src/main/resources/data/pocketdungeons/dungeon");
    /** Dungeons of the first two acts that deliberately end plainly: the short Cow Pits. */
    private static final Set<String> NO_FINALE = Set.of("cow_pits", "endless_mine");

    public static void main(String[] args) throws IOException {
        int finales = 0;
        try (Stream<Path> walk = Files.walk(DUNGEONS)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".json")).toList()) {
                String id = file.getFileName().toString().replace(".json", "");
                JsonObject d;
                try (Reader reader = Files.newBufferedReader(file)) {
                    d = JsonParser.parseReader(reader).getAsJsonObject();
                }
                int act = d.get("act").getAsInt();
                String kind = d.get("kind").getAsString();
                boolean has = d.has("finale");
                if (kind.equals("capstone") || kind.equals("endless")) {
                    check(!has, id + ": a " + kind + " takes no finale");
                    continue;
                }
                if (act <= 2 && !NO_FINALE.contains(id)) {
                    check(has, id + ": an Act " + act + " dungeon ends in a finale");
                }
                if (!has) {
                    continue;
                }
                finales++;
                JsonObject f = d.getAsJsonObject("finale");
                JsonArray mobs = f.getAsJsonArray("mobs");
                check(mobs.size() >= 1, id + ": a finale needs mobs");
                int total = 0;
                for (JsonElement e : mobs) {
                    total += e.getAsJsonObject().get("count").getAsInt();
                    check(e.getAsJsonObject().get("type").getAsString().startsWith("minecraft:"), id + ": mob ids are namespaced");
                }
                check(total <= 30, id + ": a finale for one holds at most 30 mobs, found " + total);
                if (act >= 2) {
                    check(f.has("elite"), id + ": from Act 2 the finale has a named elite");
                } else {
                    check(!f.has("elite") || id.equals("kennels"), id + ": Act 1 finales have no elite, except the Alpha");
                }
                if (f.has("elite")) {
                    JsonObject elite = f.getAsJsonObject("elite");
                    String name = elite.get("name").getAsString();
                    check(name.startsWith("The "), id + ": the elite is named like a title: " + name);
                    check(name.indexOf('\u2014') < 0 && !name.contains("--"), id + ": no dash punctuation in the elite name");
                }
            }
        }
        check(finales >= 8, "found only " + finales + " finales");
        JsonObject copper;
        try (Reader reader = Files.newBufferedReader(DUNGEONS.resolve("copper_works.json"))) {
            copper = JsonParser.parseReader(reader).getAsJsonObject();
        }
        check(copper.has("mobUniform"), "the Copper Works crew wears a uniform");
        JsonObject uniform = copper.getAsJsonObject("mobUniform");
        check(uniform.getAsJsonArray("armour").size() == 4, "a full set of four armour pieces to draw from");
        for (JsonElement e : uniform.getAsJsonArray("armour")) {
            check(e.getAsString().contains("copper"), "the uniform is copper: " + e.getAsString());
        }
        System.out.println("FinaleDataTest passed (" + finales + " finales)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
