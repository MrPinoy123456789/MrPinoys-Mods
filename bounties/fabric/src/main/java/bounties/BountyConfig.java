package bounties;

import bounties.core.BountyDefinition;
import bounties.core.BountyPool;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the bounty pool from disk and generates a default one on first boot.
 */
public final class BountyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private BountyPool pool = BountyPool.empty();

    public BountyConfig(Path dir) {
        this.dir = dir;
    }

    public void reload() {
        try {
            Files.createDirectories(dir);
            pool = new BountyPool(readOrCreate("bounties.json", BountiesFile.class,
                    new BountiesFile(samplePool())).bounties());
        } catch (IOException | RuntimeException e) {
            BountyMod.LOG.error("Failed to load bounty pool, keeping previous values", e);
        }
    }

    public BountyPool pool() {
        return pool;
    }

    private <T> T readOrCreate(String name, Class<T> type, T fallback) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(fallback, w);
            }
            BountyMod.LOG.info("Created default {}", name);
            return fallback;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(r, type);
            return parsed != null ? parsed : fallback;
        }
    }

    private record BountiesFile(List<BountyDefinition> bounties) {
    }

    private static List<BountyDefinition> samplePool() {
        List<BountyDefinition> list = new ArrayList<>();
        list.add(entry("minecraft:zombie", 5, 1, "Zombie", "Kill 5 zombies"));
        list.add(entry("minecraft:skeleton", 5, 1, "Skeleton", "Kill 5 skeletons"));
        list.add(entry("minecraft:spider", 5, 1, "Spider", "Kill 5 spiders"));
        list.add(entry("minecraft:cave_spider", 5, 1, "Cave Spider", "Kill 5 cave spiders"));
        list.add(entry("minecraft:creeper", 4, 2, "Creeper", "Kill 4 creepers"));
        list.add(entry("minecraft:enderman", 4, 2, "Enderman", "Kill 4 endermen"));
        list.add(entry("minecraft:witch", 4, 2, "Witch", "Kill 4 witches"));
        list.add(entry("minecraft:drowned", 5, 1, "Drowned", "Kill 5 drowned"));
        list.add(entry("minecraft:husk", 5, 1, "Husk", "Kill 5 husks"));
        list.add(entry("minecraft:stray", 5, 1, "Stray", "Kill 5 strays"));
        list.add(entry("minecraft:zombie_villager", 5, 1, "Zombie Villager", "Kill 5 zombie villagers"));
        list.add(entry("minecraft:skeleton_horse", 3, 1, "Skeleton Horse", "Kill 3 skeleton horses"));
        list.add(entry("minecraft:blaze", 3, 3, "Blaze", "Kill 3 blazes"));
        list.add(entry("minecraft:ghast", 2, 3, "Ghast", "Kill 2 ghasts"));
        list.add(entry("minecraft:magma_cube", 4, 2, "Magma Cube", "Kill 4 magma cubes"));
        list.add(entry("minecraft:slime", 5, 2, "Slime", "Kill 5 slimes"));
        list.add(entry("minecraft:phantom", 3, 2, "Phantom", "Kill 3 phantoms"));
        list.add(entry("minecraft:pillager", 5, 2, "Pillager", "Kill 5 pillagers"));
        list.add(entry("minecraft:vindicator", 3, 3, "Vindicator", "Kill 3 vindicators"));
        list.add(entry("minecraft:evoker", 2, 4, "Evoker", "Kill 2 evokers"));
        list.add(entry("minecraft:ravager", 2, 5, "Ravager", "Kill 2 ravagers"));
        list.add(entry("minecraft:guardian", 5, 2, "Guardian", "Kill 5 guardians"));
        list.add(entry("minecraft:elder_guardian", 1, 8, "Elder Guardian", "Kill 1 elder guardian"));
        list.add(entry("minecraft:shulker", 3, 3, "Shulker", "Kill 3 shulkers"));
        list.add(entry("minecraft:endermite", 10, 1, "Endermite", "Kill 10 endermites"));
        list.add(entry("minecraft:silverfish", 10, 1, "Silverfish", "Kill 10 silverfish"));
        list.add(entry("minecraft:wolf", 5, 1, "Wolf", "Kill 5 wolves"));
        list.add(entry("minecraft:piglin_brute", 3, 3, "Piglin Brute", "Kill 3 piglin brutes"));
        list.add(entry("minecraft:hoglin", 4, 2, "Hoglin", "Kill 4 hoglins"));
        list.add(entry("minecraft:wither", 1, 10, "Wither", "Kill 1 wither"));
        list.add(entry("minecraft:ender_dragon", 1, 20, "Ender Dragon", "Kill 1 ender dragon"));
        return list;
    }

    private static BountyDefinition entry(String mobId, int kills, int reward,
                                        String label, String description) {
        return new BountyDefinition(mobId, kills, reward, label, description);
    }
}
