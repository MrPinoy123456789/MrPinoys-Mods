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

    /** One rung on the streak reward ladder: the reward from this day onward. */
    public record StreakReward(int day, int diamonds) {}

    /**
     * @param rolloverHourUtc hour of the UTC day a new quest appears, 0–23
     * @param streakDiamondCap most diamonds a streak can pay; the streak itself keeps
     *                         counting past this, it just stops paying more
     * @param announceOnJoin whether joining players are shown today's riddle
     * @param streakRewards ladder of {@link StreakReward}; if absent, a flat ladder is
     *                      synthesised from {@code streakDiamondCap} for migration
     * @param milestones extra streak days that trigger a broadcast; every multiple of 30
     *                   is also treated as a milestone
     * @param streakGraceDays how long a streak survives without a turn-in. One means
     *                        strictly consecutive days; seven means you keep the streak
     *                        as long as you show up once a week
     */
    public record Settings(int rolloverHourUtc, int streakDiamondCap, boolean announceOnJoin,
                           List<StreakReward> streakRewards, List<Integer> milestones,
                           int streakGraceDays) {
        public static Settings defaults() {
            return new Settings(0, 3, true,
                    List.of(new StreakReward(1, 3), new StreakReward(7, 5),
                            new StreakReward(14, 8), new StreakReward(30, 12)),
                    List.of(7, 14), 7);
        }

        /** Grace window in days, falling back to a week for files written before it existed. */
        public int graceDays() {
            return streakGraceDays > 0 ? streakGraceDays : 7;
        }

        /** The active reward ladder, falling back to a flat cap for old files. */
        public List<StreakReward> rewards() {
            if (streakRewards != null && !streakRewards.isEmpty()) {
                return streakRewards;
            }
            List<StreakReward> flat = new ArrayList<>();
            for (int i = 1; i <= streakDiamondCap; i++) {
                flat.add(new StreakReward(i, i));
            }
            return flat;
        }

        /** The configured milestones, never null. */
        public List<Integer> milestoneList() {
            return milestones != null ? milestones : List.of();
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

    /**
     * The starting pool. Asks are deliberately small — a daily is a reason to log in,
     * not an evening's work — and the pool is long enough that a riddle does not come
     * back around within a fortnight.
     */
    private static List<Quest> sampleQuests() {
        List<Quest> list = new ArrayList<>();
        list.add(new Quest(
                "I am gold that no furnace made, and I ripen in rows. Bring me sixteen.",
                "minecraft:wheat", 16, "Wheat"));
        list.add(new Quest(
                "I burn without wood and I am pulled from something already burning. "
                        + "Bring me five.",
                "minecraft:blaze_rod", 5, "Blaze rods"));
        list.add(new Quest(
                "I am a bone the sea forgot. Guardians keep me. Bring me eight.",
                "minecraft:prismarine_shard", 8, "Prismarine shards"));
        list.add(new Quest(
                "I glow in the dark and I grow in the deep, but I am not a torch. "
                        + "Bring me twelve.",
                "minecraft:glow_berries", 12, "Glow berries"));
        list.add(new Quest(
                "I fall from the sky's own thief, and I carry you where you point. "
                        + "Bring me six.",
                "minecraft:ender_pearl", 6, "Ender pearls"));
        list.add(new Quest(
                "I walk at night and clatter when I fall. What is left of me makes "
                        + "gardens grow. Bring me twelve.",
                "minecraft:bone", 12, "Bones"));
        list.add(new Quest(
                "I am green, I am rude, and I bounce when you cut me down. Bring me eight.",
                "minecraft:slime_ball", 8, "Slime balls"));
        list.add(new Quest(
                "I am the last breath of something that crept up behind you. Bring me eight.",
                "minecraft:gunpowder", 8, "Gunpowder"));
        list.add(new Quest(
                "I grow in geodes and I ring like glass when you take me. Bring me eight.",
                "minecraft:amethyst_shard", 8, "Amethyst shards"));
        list.add(new Quest(
                "I go green with age above ground, but I come out of the rock the colour "
                        + "of a sunset. Bring me twelve.",
                "minecraft:copper_ingot", 12, "Copper ingots"));
        list.add(new Quest(
                "I sleep in the stone until iron wakes me, and then I will not stop "
                        + "glowing. Bring me sixteen.",
                "minecraft:redstone", 16, "Redstone dust"));
        list.add(new Quest(
                "I am stolen from a house of a thousand workers, and I smell of summer. "
                        + "Bring me six.",
                "minecraft:honeycomb", 6, "Honeycomb"));
        list.add(new Quest(
                "Eight legs made me, and I catch what walks into me. Bring me twelve.",
                "minecraft:string", 12, "String"));
        list.add(new Quest(
                "I am the thing that circles you when you will not sleep. Bring me four.",
                "minecraft:phantom_membrane", 4, "Phantom membranes"));
        return list;
    }
}
