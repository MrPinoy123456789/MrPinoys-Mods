package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dungeon structure W5 (node palettes): a {@code nodePalette} holds resource blocks only,
 * because every interior block of a palette block is a mineable node. The denylist of common
 * structural blocks, the pack validator finding, and every shipped dungeon file are pinned here.
 */
public class NodePaletteTest {

    private static final Path DUNGEONS = Path.of("src/main/resources/data/pocketdungeons/dungeon");

    public static void main(String[] args) throws IOException {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testDenylist();
        testValidatorFinding();
        testShippedPalettes();
        System.out.println("NodePaletteTest passed");
    }

    private static void testDenylist() {
        for (String id : List.of("stone", "cobblestone", "deepslate", "tuff", "ice", "packed_ice", "blue_ice",
                "end_stone", "prismarine", "prismarine_bricks", "dark_prismarine", "blackstone", "basalt",
                "netherrack", "stone_bricks", "deepslate_bricks", "deepslate_tiles", "oak_planks",
                "crimson_planks", "bamboo_planks")) {
            check(DungeonDef.isStructuralBlock(id), id + " is structural");
            check(DungeonDef.isStructuralBlock("minecraft:" + id), "minecraft:" + id + " is structural");
        }
        for (String id : List.of("coal_ore", "iron_ore", "deepslate_iron_ore", "deepslate_diamond_ore",
                "nether_gold_ore", "nether_quartz_ore", "ancient_debris", "oak_log", "crimson_stem",
                "amethyst_cluster", "gilded_blackstone", "clay", "gravel", "warped_stem", "crimson_stem",
                "warped_wart_block", "nether_wart_block")) {
            check(!DungeonDef.isStructuralBlock(id), id + " is a resource, not structural");
        }
        check(!DungeonDef.isStructuralBlock("otherpack:stone"), "another namespace's stone is not the vanilla block");
        check(!DungeonDef.isStructuralBlock(null), "null is not structural");
    }

    private static void testValidatorFinding() {
        String json = "{\"name\": \"T\", \"act\": 1, \"baseLevel\": 1, \"kind\": \"dungeon\", \"mainTheme\": \"infestation\","
                + " \"lootBand\": {\"min\": 1, \"max\": 2}, \"nodePalette\": [\"coal_ore\", \"stone\", \"oak_planks\"],"
                + " \"nodes\": [{\"id\": \"a\", \"name\": \"A\", \"layer\": 1}, {\"id\": \"b\", \"name\": \"B\", \"layer\": 2},"
                + " {\"id\": \"c\", \"name\": \"C\", \"layer\": 3, \"final\": true}],"
                + " \"edges\": [{\"from\": \"a\", \"to\": \"b\"}, {\"from\": \"b\", \"to\": \"c\"}]}";
        DungeonDef def = DungeonDef.fromJson("pocketdungeons:t", JsonParser.parseString(json).getAsJsonObject());
        List<PackValidator.Finding> findings = PackValidator.dungeonFindings(List.of(def),
                List.of("pocketdungeons:infestation"), a -> true, r -> true);
        int palette = 0;
        for (PackValidator.Finding finding : findings) {
            if ("nodePalette".equals(finding.field())) {
                palette++;
                check(finding.cause().contains("minecraft:stone") || finding.cause().contains("minecraft:oak_planks"),
                        "the finding names the block: " + finding.cause());
            }
        }
        check(palette == 2, "stone and oak_planks are findings, coal_ore is not: " + findings);

        String clean = json.replace("\"coal_ore\", \"stone\", \"oak_planks\"", "\"coal_ore\", \"iron_ore\"");
        DungeonDef ok = DungeonDef.fromJson("pocketdungeons:t", JsonParser.parseString(clean).getAsJsonObject());
        check(PackValidator.dungeonFindings(List.of(ok), List.of("pocketdungeons:infestation"), a -> true, r -> true)
                .stream().noneMatch(f -> "nodePalette".equals(f.field())), "a resource palette has no palette finding");
    }

    private static void testShippedPalettes() throws IOException {
        int files = 0;
        try (Stream<Path> stream = Files.list(DUNGEONS)) {
            List<Path> paths = new ArrayList<>(stream.filter(p -> p.toString().endsWith(".json")).sorted().toList());
            for (Path path : paths) {
                files++;
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                String name = path.getFileName().toString();
                int act = obj.get("act").getAsInt();
                JsonArray palette = obj.getAsJsonArray("nodePalette");
                check(palette != null && palette.size() > 0, name + " has a palette");
                for (JsonElement element : palette) {
                    String block = element.getAsString();
                    check(!DungeonDef.isStructuralBlock(block), name + " palette holds a structural block: " + block);
                    // Act 1 carries no diamond; deepslate diamond is an Act 2 and later resource.
                    if (act == 1) {
                        check(!block.contains("diamond"), name + " is Act 1 and must not list " + block);
                    }
                    check(block.endsWith("_ore") || block.endsWith("_log") || block.endsWith("_stem")
                                    || block.endsWith("_wart_block") || block.equals("ancient_debris") || block.equals("gilded_blackstone")
                                    || block.equals("clay") || block.equals("amethyst_cluster")
                                    || block.equals("sand") || block.equals("red_sand"),
                            name + " palette entry is not a known resource shape: " + block);
                }
            }
        }
        check(files == 19, "all nineteen shipped dungeon files were read, got " + files);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
