package kamutotems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import kamutotems.core.KamuCatalog;
import kamutotems.core.QuestChain;
import kamutotems.core.QuestProgress;
import kamutotems.core.QuestSegment;
import kamutotems.core.Streak;
import kamutotems.core.StreakState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player daily quest chain and streak persistence.
 *
 * <p>Today's chain is derived from the date; streaks are the only thing stored.
 * Progress is only saved on completion so a lost write rolls back, never forward.
 */
public final class DailyChain {

    private static final Logger LOG = LoggerFactory.getLogger("kamutotems");

    public record PlayerDay(String dateKey, QuestChain chain, QuestProgress progress, boolean completed) {}

    public record PlayerEntry(String name, StreakState streak, int totalDone) {}

    private static final Map<UUID, PlayerDay> TODAY = new HashMap<>();
    private static final Map<UUID, PlayerEntry> ENTRIES = new HashMap<>();

    private static KamuCatalog CATALOG;
    private static long WORLD_SEED;
    private static int ROLLOVER_HOUR;

    private DailyChain() {}

    public static void init(KamuCatalog catalog, long worldSeed, int rolloverHour) {
        CATALOG = catalog;
        WORLD_SEED = worldSeed;
        ROLLOVER_HOUR = rolloverHour;
    }

    public static void load() {
        JsonObject data = Persist.load("daily_state.json");
        if (data == null) {
            return;
        }
        if (data.has("entries") && data.get("entries").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("entries").entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    PlayerEntry entry = readEntry(e.getValue().getAsJsonObject());
                    ENTRIES.put(id, entry);
                } catch (RuntimeException ignored) {}
            }
        }
        if (data.has("progress") && data.get("progress").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("progress").entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    PlayerDay day = readDay(e.getValue().getAsJsonObject());
                    if (day != null && day.dateKey().equals(todayKey())) {
                        TODAY.put(id, day);
                    }
                } catch (RuntimeException ignored) {}
            }
        }
    }

    public static void save() {
        JsonObject data = new JsonObject();

        JsonObject entries = new JsonObject();
        for (Map.Entry<UUID, PlayerEntry> e : ENTRIES.entrySet()) {
            entries.add(e.getKey().toString(), writeEntry(e.getValue()));
        }
        data.add("entries", entries);

        JsonObject progress = new JsonObject();
        for (Map.Entry<UUID, PlayerDay> e : TODAY.entrySet()) {
            progress.add(e.getKey().toString(), writeDay(e.getValue()));
        }
        data.add("progress", progress);

        Persist.save("daily_state.json", data);
    }

    public static String todayKey() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusHours(ROLLOVER_HOUR);
        return now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static QuestChain chainFor(UUID player) {
        return QuestChain.forDate(todayKey(), WORLD_SEED, CATALOG);
    }

    public static PlayerDay dayFor(ServerPlayer player) {
        UUID id = player.getUUID();
        PlayerDay day = TODAY.get(id);
        if (day != null && day.dateKey().equals(todayKey())) {
            return day;
        }

        String today = todayKey();
        QuestChain chain = QuestChain.forDate(today, WORLD_SEED, CATALOG);
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < chain.segments().size(); i++) {
            counts.add(0);
        }
        QuestProgress progress = new QuestProgress(today, counts);

        boolean completed = false;
        PlayerEntry entry = ENTRIES.get(id);
        if (entry != null && today.equals(entry.streak().lastCompletedDateKey())) {
            completed = true;
        }

        day = new PlayerDay(today, chain, progress, completed);
        TODAY.put(id, day);
        return day;
    }

    public static PlayerEntry entryFor(UUID player) {
        return ENTRIES.computeIfAbsent(player, id -> new PlayerEntry(null,
                new StreakState(0, null, new ArrayList<>()), 0));
    }

    public static void seen(ServerPlayer player) {
        PlayerEntry entry = ENTRIES.computeIfAbsent(player.getUUID(),
                id -> new PlayerEntry(null, new StreakState(0, null, new ArrayList<>()), 0));
        if (entry.name() == null || !entry.name().equals(player.getName().getString())) {
            ENTRIES.put(player.getUUID(), new PlayerEntry(
                    player.getName().getString(), entry.streak(), entry.totalDone()));
        }
    }

    /**
     * Attempt to advance the first incomplete segment that matches the event.
     * Returns true if a segment was advanced.
     */
    public static boolean tryAdvance(ServerPlayer player, String kind, Map<String, String> event) {
        PlayerDay day = dayFor(player);
        if (day.completed()) {
            return false;
        }

        List<QuestSegment> segments = day.chain().segments();
        for (int i = 0; i < segments.size(); i++) {
            if (day.progress().segmentComplete(i, day.chain())) {
                continue;
            }
            QuestSegment seg = segments.get(i);
            if (seg.kind().equals(kind) && seg.matcher().matches(event)) {
                return advance(player, i, 1);
            }
        }
        return false;
    }

    public static boolean advance(ServerPlayer player, int segment, int by) {
        PlayerDay day = dayFor(player);
        if (day.completed()) {
            return false;
        }

        QuestProgress next = day.progress().advance(segment, by, day.chain());
        boolean nowComplete = next.complete(day.chain());
        TODAY.put(player.getUUID(), new PlayerDay(day.dateKey(), day.chain(), next, nowComplete));

        boolean segmentJustDone = next.segmentComplete(segment, day.chain())
                && !day.progress().segmentComplete(segment, day.chain());

        if (segmentJustDone) {
            // The chain pays out per step rather than only at the end, so
            // progress is felt while it happens instead of banked invisibly.
            paySegment(player, segment, day);
        } else if (!nowComplete) {
            // A very quiet tick per unit of progress -- "3 of 6 skeletons".
            // Deliberately below the segment chime: this fires often, and a
            // cue you hear constantly stops being information.
            Chime.play(player, SoundEvents.NOTE_BLOCK_HAT, 0.12f, 1.6f);
        }

        if (nowComplete) {
            onComplete(player);
        }
        return true;
    }

    /** Reward for finishing one segment of the chain. */
    private static void paySegment(ServerPlayer player, int segment, PlayerDay day) {
        int perSegment = KamuTotemsConfig.i("quest", "reward_per_segment", 1);
        if (perSegment > 0) {
            ItemStack coin = new ItemStack(Items.DIAMOND, perSegment);
            player.getInventory().add(coin);
            if (!coin.isEmpty()) {
                player.drop(coin, false);
            }
        }
        int total = day.chain().segments().size();
        player.sendSystemMessage(Component.literal(
                        "Step " + (segment + 1) + " of " + total + " done"
                                + (perSegment > 0 ? "   +" + perSegment + " diamond"
                                        + (perSegment == 1 ? "" : "s") : ""))
                .withStyle(ChatFormatting.YELLOW));
        Chime.play(player, SoundEvents.NOTE_BLOCK_HAT, 0.2f, 1.0f);
    }

    public static void onComplete(ServerPlayer player) {
        UUID id = player.getUUID();
        String today = todayKey();

        PlayerEntry entry = entryFor(id);
        StreakState next = Streak.onComplete(entry.streak(), today);
        int base = KamuTotemsConfig.i("quest", "reward_base", 2);
        int perDay = KamuTotemsConfig.i("quest", "reward_per_day", 1);
        int cap = KamuTotemsConfig.i("quest", "reward_cap", 16);
        int diamonds = Streak.rewardDiamonds(next.streak(), base, perDay, cap);

        ENTRIES.put(id, new PlayerEntry(
                player.getName().getString(),
                next,
                entry.totalDone() + 1));

        ItemStack reward = new ItemStack(Items.DIAMOND, diamonds);
        player.getInventory().add(reward);
        if (!reward.isEmpty()) {
            player.drop(reward, false);
            LOG.info("Quest reward for {} would not fit; dropped at feet", player.getName().getString());
        }

        player.sendSystemMessage(Component.literal("Daily chain complete! Streak: " + next.streak()
                        + " day" + (next.streak() == 1 ? "" : "s") + "   +" + diamonds + " diamond"
                        + (diamonds == 1 ? "" : "s"))
                .withStyle(ChatFormatting.GOLD));

        // The last step of the chain hands over the free daily boss, as an
        // item. This is what ties the daily loop to the boss loop: the fight
        // is EARNED by showing up and doing the day's work, rather than being
        // a command you had to already know about. It is also why the shop's
        // sigils start at the Second Trial -- the First is never sold.
        if (KamuTotemsConfig.b("quest", "grant_daily_sigil", true)) {
            ItemStack sigil = Sigil.makeFirstTrial(player);
            if (sigil != null && !sigil.isEmpty()) {
                if (!player.getInventory().add(sigil)) {
                    player.drop(sigil, false);
                    LOG.info("First Trial sigil for {} would not fit; dropped at feet",
                            player.getName().getString());
                }
                player.sendSystemMessage(Component.literal(
                                "A Sigil of the First Trial forms in your pack.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
            }
        }

        // Chime.play(ServerPlayer, Holder<SoundEvent>, float, float).
        Chime.play(player,
                SoundEvents.NOTE_BLOCK_BELL,
                0.35f, 1.0f);

        save();
    }

    public static List<PlayerEntry> top(int limit) {
        return ENTRIES.values().stream()
                .sorted(Comparator.<PlayerEntry>comparingInt(e -> e.streak().streak()).reversed()
                        .thenComparingInt(e -> -e.totalDone())
                        .thenComparing(e -> e.name() == null ? "" : e.name()))
                .limit(limit)
                .toList();
    }

    public static int streakOf(UUID player) {
        return entryFor(player).streak().streak();
    }

    // ---- json round-trip --------------------------------------------------

    private static PlayerEntry readEntry(JsonObject o) {
        String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
        int total = o.has("totalDone") ? o.get("totalDone").getAsInt() : 0;
        StreakState streak = new StreakState(
                o.has("streak") ? o.get("streak").getAsInt() : 0,
                o.has("lastCompletedDateKey") && !o.get("lastCompletedDateKey").isJsonNull()
                        ? o.get("lastCompletedDateKey").getAsString() : null,
                o.has("graceUsedDateKeys") ? readGrace(o.getAsJsonArray("graceUsedDateKeys")) : new ArrayList<>());
        return new PlayerEntry(name, streak, total);
    }

    private static List<String> readGrace(JsonArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement e : array) {
            if (!e.isJsonNull()) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static JsonObject writeEntry(PlayerEntry e) {
        JsonObject o = new JsonObject();
        o.add("name", e.name() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(e.name()));
        o.addProperty("totalDone", e.totalDone());
        o.addProperty("streak", e.streak().streak());
        o.add("lastCompletedDateKey", e.streak().lastCompletedDateKey() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(e.streak().lastCompletedDateKey()));
        JsonArray grace = new JsonArray();
        for (String day : e.streak().graceUsedDateKeys()) {
            grace.add(day);
        }
        o.add("graceUsedDateKeys", grace);
        return o;
    }

    private static PlayerDay readDay(JsonObject o) {
        String dateKey = o.has("dateKey") ? o.get("dateKey").getAsString() : todayKey();
        QuestChain chain = QuestChain.forDate(dateKey, WORLD_SEED, CATALOG);
        List<Integer> counts = new ArrayList<>();
        if (o.has("counts") && o.get("counts").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("counts")) {
                counts.add(e.getAsInt());
            }
        }
        while (counts.size() < chain.segments().size()) {
            counts.add(0);
        }
        boolean completed = o.has("completed") && o.get("completed").getAsBoolean();
        return new PlayerDay(dateKey, chain, new QuestProgress(dateKey, counts), completed);
    }

    private static JsonObject writeDay(PlayerDay d) {
        JsonObject o = new JsonObject();
        o.addProperty("dateKey", d.dateKey());
        JsonArray counts = new JsonArray();
        for (int c : d.progress().counts()) {
            counts.add(c);
        }
        o.add("counts", counts);
        o.addProperty("completed", d.completed());
        return o;
    }
}
