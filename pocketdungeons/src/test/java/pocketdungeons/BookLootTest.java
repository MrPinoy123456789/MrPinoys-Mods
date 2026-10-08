package pocketdungeons;

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
 * PD-175: a loot entry that names {@code minecraft:enchanted_book} and enchants it with
 * {@code enchant_with_levels} rolls a book with no stored enchantments (the function only
 * turns a plain {@code minecraft:book} into an enchanted one). The two spur vault tables did
 * exactly that. No shipped loot table may enchant an already enchanted book this way.
 *
 * <p>Pure JDK plus Gson: the tables are read from the source tree.
 */
public class BookLootTest {

    private static final Path ROOT = Paths.get("src/main/resources/data/pocketdungeons/loot_table");

    public static void main(String[] args) throws IOException {
        List<Path> tables;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            tables = walk.filter(p -> p.toString().endsWith(".json")).toList();
        }
        check(!tables.isEmpty(), "no loot tables found under " + ROOT);
        for (Path table : tables) {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(table)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            scan(root, table);
        }
        System.out.println("BookLootTest passed (" + tables.size() + " tables)");
    }

    private static void scan(JsonElement node, Path table) {
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (object.has("name") && object.get("name").isJsonPrimitive()
                    && "minecraft:enchanted_book".equals(object.get("name").getAsString())
                    && object.has("functions") && object.get("functions").toString().contains("enchant_with_levels")) {
                throw new AssertionError(table + ": minecraft:enchanted_book enchanted with enchant_with_levels "
                        + "rolls a blank book; name minecraft:book instead");
            }
            object.entrySet().forEach(e -> scan(e.getValue(), table));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(e -> scan(e, table));
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
