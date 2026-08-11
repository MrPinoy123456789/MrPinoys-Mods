package dailyquests;

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
 * The quest pool and settings, read from disk and never written back.
 *
 * <p>A quest is a riddle plus what it secretly asks for. The riddle is the whole
 * puzzle — there is no answer to type, because turning in the right item <em>is</em>
 * the answer. That also means a player who already knows the item can skip straight
 * to the turn-in, which is fine: they solved it faster.
 */
public final class Quests {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private List<Quest> quests = List.of();
    private Settings settings = Settings.defaults();

    public Quests(Path dir) {
        this.dir = dir;
    }

    /**
     * @param riddle what players see
     * @param item   namespaced id, e.g. {@code minecraft:wheat}
     * @param count  how many are consumed on turn-in
     * @param answer shown only after a successful turn-in, so the riddle stays intact
     */
    public record Quest(String riddle, String item, int count, String answer) {}

    private record QuestFile(List<Quest> quests) {}

    /**
     * @param rolloverHourUtc hour of the UTC day a new quest appears, 0–23
     * @param streakDiamondCap most diamonds a streak can pay; the streak itself keeps
     *                         counting past this, it just stops paying more
     * @param announceOnJoin whether joining players are shown today's riddle
     */
    public record Settings(int rolloverHourUtc, int streakDiamondCap, boolean announceOnJoin) {
        public static Settings defaults() {
            return new Settings(0, 3, true);
        }
    }

    public void reload() {
        try {
            Files.createDirectories(dir);
            quests = readOrCreate("quests.json", QuestFile.class,
                    new QuestFile(sampleQuests())).quests();
            settings = readOrCreate("settings.json", Settings.class, Settings.defaults());
        } catch (IOException e) {
            DailyQuestsMod.LOG.error("Failed to load quests, keeping previous values", e);
        }
    }

    private <T> T readOrCreate(String name, Class<T> type, T fallback) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(fallback, w);
            }
            DailyQuestsMod.LOG.info("Created default {}", name);
            return fallback;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(r, type);
            return parsed != null ? parsed : fallback;
        }
    }

    /**
     * Which quest belongs to a given day. Derived from the day itself rather than
     * chosen at random, so a restart mid-day cannot change today's riddle.
     */
    public Quest forDay(String dayKey) {
        if (quests.isEmpty()) {
            return null;
        }
        int index = Math.floorMod(dayKey.hashCode(), quests.size());
        return quests.get(index);
    }

    public Settings settings() {
        return settings;
    }

    public int size() {
        return quests.size();
    }

    private static List<Quest> sampleQuests() {
        List<Quest> list = new ArrayList<>();
        list.add(new Quest(
                "I am gold that no furnace made, and I ripen in rows. Bring me thirty.",
                "minecraft:wheat", 30, "Wheat"));
        list.add(new Quest(
                "I burn without wood and I am pulled from something already burning. "
                        + "Bring me eight.",
                "minecraft:blaze_rod", 8, "Blaze rods"));
        list.add(new Quest(
                "I am a bone the sea forgot. Guardians keep me. Bring me sixteen.",
                "minecraft:prismarine_shard", 16, "Prismarine shards"));
        list.add(new Quest(
                "I glow in the dark and I grow in the deep, but I am not a torch. "
                        + "Bring me thirty-two.",
                "minecraft:glow_berries", 32, "Glow berries"));
        list.add(new Quest(
                "I fall from the sky's own thief, and I carry you where you point. "
                        + "Bring me twelve.",
                "minecraft:ender_pearl", 12, "Ender pearls"));
        return list;
    }
}
