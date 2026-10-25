package charter.mc.store;

import charter.history.History;
import charter.history.HistoryEvent;
import charter.land.CoreKind;
import charter.land.LandEvents;
import charter.siege.SiegeEvents;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The debrief log (PLAN.md §8.3): {@code siege-log/<yyyy-mm-dd>.jsonl}, one JSON
 * object per line, flushed per line. Common fields are {@code t} (UTC), {@code siegeT}
 * (the siege clock), {@code ev}, {@code site}, {@code owner} and {@code attacker};
 * the rest of the event's fields follow.
 *
 * <p>Events are named by the plan's list. Siege and land events the core records
 * today are mapped here; crossings, presence and the hourly drain snapshots arrive
 * with the milestones that detect them (M10, M11) and call {@link #write} directly.
 * Every admin command is written as {@code admin}.
 */
public final class SiegeLog implements AutoCloseable {

    /** Record components that name the site, the owner and the attacker, in the order tried. */
    private static final String[] SITE = {"keep", "camp", "site", "core"};
    private static final String[] OWNER = {"owner", "defender", "org", "from"};
    private static final String[] ATTACKER = {"besieger", "attacker", "by", "raider", "occupier"};
    private static final Set<String> COMMON = Set.of("at", "siegeAt", "type");

    private static final Map<Class<?>, Function<HistoryEvent, String>> NAMES = new LinkedHashMap<>();

    static {
        name(LandEvents.CampPlanted.class, "camp_planted");
        name(SiegeEvents.CampActive.class, "camp_active");
        name(SiegeEvents.CampCutOff.class, "camp_cut_off");
        name(SiegeEvents.CampStoreEmpty.class, "camp_empty");
        name(SiegeEvents.CampExposed.class, "camp_exposed");
        name(SiegeEvents.SiegeBegan.class, "keep_besieged");
        name(SiegeEvents.Encircled.class, "keep_encircled");
        name(SiegeEvents.RingBroken.class, "keep_ring_broken");
        name(SiegeEvents.SiegeLifted.class, "keep_lifted");
        name(SiegeEvents.Starving.class, "keep_starving");
        name(SiegeEvents.KeepFell.class, "keep_fell");
        name(SiegeEvents.Razed.class, "fall_choice");
        name(SiegeEvents.Occupied.class, "fall_choice");
        name(SiegeEvents.OccupationEnded.class, "occupation_ended");
        name(SiegeEvents.Plundered.class, "plunder");
        name(SiegeEvents.FoodDeposited.class, "deposit");
        name(SiegeEvents.HoldEmptied.class, "hold_empty");
        name(SiegeEvents.TruceBegan.class, "truce_started");
        name(LandEvents.Reassigned.class, "claim_reassigned");
        name(LandEvents.Contested.class, "claim_orphaned");
        name(LandEvents.Rescued.class, "grace_saved");
        name(LandEvents.Lapsed.class, "grace_expired");
        NAMES.put(LandEvents.CoreLost.class, e -> ((LandEvents.CoreLost) e).kind() == CoreKind.CAMP ? "camp_destroyed" : null);
    }

    private static void name(Class<? extends HistoryEvent> type, String ev) {
        NAMES.put(type, e -> ev);
    }

    private final Path dir;
    private final CharterJson json;
    private final LongSupplier siegeNow;
    private final Consumer<String> warn;
    private boolean enabled;
    private LocalDate day;
    private BufferedWriter out;

    public SiegeLog(Path dir, CharterJson json, LongSupplier siegeNow, boolean enabled, Consumer<String> warn) {
        this.dir = dir;
        this.json = json;
        this.siegeNow = siegeNow;
        this.enabled = enabled;
        this.warn = warn;
    }

    public Path dir() {
        return dir;
    }

    /** The log name of an event, or null if the debrief view does not follow it. */
    public static String eventName(HistoryEvent event) {
        Function<HistoryEvent, String> f = NAMES.get(event.getClass());
        return f == null ? null : f.apply(event);
    }

    /** History listener: writes the events the debrief follows. */
    public void onEntry(History.Entry entry) {
        String ev = eventName(entry.event());
        if (ev == null) return;
        JsonObject fields = json.compact.toJsonTree(entry.event()).getAsJsonObject();
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(entry.event().at()).toString());
        line.addProperty("siegeT", fields.has("siegeAt") ? fields.get("siegeAt").getAsLong() : siegeNow.getAsLong());
        line.addProperty("ev", ev);
        pick(line, "site", fields, SITE);
        pick(line, "owner", fields, OWNER);
        pick(line, "attacker", fields, ATTACKER);
        for (Map.Entry<String, JsonElement> e : fields.entrySet()) {
            if (!COMMON.contains(e.getKey()) && !line.has(e.getKey())) line.add(e.getKey(), e.getValue());
        }
        line.addProperty("actor", entry.actor());
        write(line, entry.event().at());
    }

    /** Every admin command, with its arguments. */
    public void admin(long now, String who, String command) {
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(now).toString());
        line.addProperty("siegeT", siegeNow.getAsLong());
        line.addProperty("ev", "admin");
        line.addProperty("who", who);
        line.addProperty("command", command);
        write(line, now);
    }

    private static void pick(JsonObject line, String as, JsonObject fields, String[] names) {
        for (String name : names) {
            if (fields.has(name)) {
                line.add(as, fields.get(name));
                return;
            }
        }
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    /** Writes one prepared line to today's file. Its {@code t}, {@code siegeT} and {@code ev} must be set. */
    public void write(JsonObject line, long now) {
        if (!enabled) return;
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        try {
            if (out == null || !today.equals(day)) {
                close();
                Files.createDirectories(dir);
                out = Files.newBufferedWriter(dir.resolve(today + ".jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                day = today;
            }
            out.write(json.compact.toJson(line));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            warn.accept("Could not write the siege log: " + e.getMessage());
        }
    }

    /** Deletes day files older than the retention, on start. Returns how many went. */
    public int prune(long now, int retentionDays) {
        if (!Files.isDirectory(dir)) return 0;
        LocalDate oldest = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().minusDays(retentionDays);
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl")) continue;
                try {
                    if (LocalDate.parse(name.substring(0, name.length() - ".jsonl".length())).isBefore(oldest)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (RuntimeException notADay) {
                    // not one of ours; leave it
                }
            }
        } catch (IOException e) {
            warn.accept("Could not prune the siege log: " + e.getMessage());
        }
        return removed;
    }

    @Override
    public void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing left to flush
        }
        out = null;
    }
}
