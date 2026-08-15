package kamutotems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public record QuestChain(String dateKey, List<QuestSegment> segments) {

    public static QuestChain forDate(String dateKey, long serverSeed, KamuCatalog catalog) {
        long seed = 31L * (dateKey == null ? 0 : dateKey.hashCode()) + serverSeed;
        Random r = new Random(seed);

        List<String> mobs = List.of(
                "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
                "minecraft:spider", "minecraft:husk");
        List<String> items = List.of(
                "minecraft:wheat", "minecraft:iron_ingot", "minecraft:cobblestone",
                "minecraft:oak_log", "minecraft:coal");
        List<String> blocks = List.of(
                "minecraft:stone", "minecraft:iron_ore", "minecraft:oak_log",
                "minecraft:diamond_ore", "minecraft:wheat");
        List<String> kinds = List.of("kill", "turn_in", "scan");

        List<QuestSegment> segs = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            String kind = kinds.get(r.nextInt(kinds.size()));
            int count = 3 + r.nextInt(4);
            EventMatcher matcher;
            String text;
            if ("kill".equals(kind)) {
                String mob = mobs.get(r.nextInt(mobs.size()));
                matcher = new EventMatcher("entity_killed", Map.of("entity", mob), count);
                text = "Put down " + count + " " + mob;
            } else if ("turn_in".equals(kind)) {
                String item = items.get(r.nextInt(items.size()));
                matcher = new EventMatcher("item_turned_in", Map.of("item", item), count);
                text = "Hand over " + count + " " + item;
            } else {
                String block = blocks.get(r.nextInt(blocks.size()));
                matcher = new EventMatcher("block_scanned", Map.of("block", block), count);
                text = "Scan " + count + " " + block;
            }
            segs.add(new QuestSegment(kind, matcher, text));
        }

        return new QuestChain(dateKey, List.copyOf(segs));
    }
}
