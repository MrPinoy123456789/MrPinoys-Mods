package charter.mc.land;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lifted Nexus lodestones: which keep's lodestone is being carried, by whom, and where it stood. Pure data with no
 * Minecraft types, so the plain Java tests run it. Saved in the world's {@code charter/nexus_lifts.json} on every
 * change, so a crash mid lift still puts the lodestone back on the next start.
 */
public final class LiftBook {

    public static final String FILE_NAME = "nexus_lifts.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * One lift. {@code keep} and {@code org} are ids as text; the position is where the lodestone stood; times are wall
     * milliseconds, {@code offlineSince} zero while the carrier is online.
     */
    public record Lift(String id, String keep, String org, UUID carrier, String dimension, int x, int y, int z,
                       long liftedAt, long offlineSince) {
        public Lift offline(long at) {
            return new Lift(id, keep, org, carrier, dimension, x, y, z, liftedAt, at);
        }
    }

    /** Why a lift ends by itself. */
    public enum End {
        /** The keep is gone: nothing to put back. */
        GONE,
        /** The keep is at war or open to attack: the lodestone goes back at once. */
        EXPOSED,
        /** The carrier logged out and did not come back in time. */
        LOGOUT,
        /** The carrier is online but no longer has the item. */
        LOST,
        /** Carried longer than the knob allows. */
        TIMEOUT
    }

    private final Map<String, Lift> byKeep = new LinkedHashMap<>();

    public boolean isEmpty() {
        return byKeep.isEmpty();
    }

    public void put(Lift lift) {
        byKeep.put(lift.keep(), lift);
    }

    public Lift remove(String keep) {
        return byKeep.remove(keep);
    }

    public Lift byKeep(String keep) {
        return byKeep.get(keep);
    }

    public Lift byId(String id) {
        if (id == null) return null;
        for (Lift lift : byKeep.values()) {
            if (lift.id().equals(id)) return lift;
        }
        return null;
    }

    public Lift byCarrier(UUID carrier) {
        for (Lift lift : byKeep.values()) {
            if (lift.carrier().equals(carrier)) return lift;
        }
        return null;
    }

    public List<Lift> all() {
        return List.copyOf(byKeep.values());
    }

    public int clear() {
        int n = byKeep.size();
        byKeep.clear();
        return n;
    }

    /**
     * Whether a lift ends now, and why, or null while it goes on. Checked in this order: a lost keep, war, logout,
     * the item gone from an online carrier, the time limit.
     */
    public static End end(Lift lift, long now, long maxMillis, long graceMillis, boolean keepExists, boolean exposed,
                          boolean online, boolean carries) {
        if (!keepExists) return End.GONE;
        if (exposed) return End.EXPOSED;
        if (!online) {
            long since = lift.offlineSince() == 0 ? now : lift.offlineSince();
            return now - since >= graceMillis ? End.LOGOUT : null;
        }
        if (!carries) return End.LOST;
        if (now - lift.liftedAt() >= maxMillis) return End.TIMEOUT;
        return null;
    }

    // ---- the file ----

    public void load(Path dir, Consumer<String> warn) {
        byKeep.clear();
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("lifts")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    put(new Lift(o.get("id").getAsString(), o.get("keep").getAsString(), o.get("org").getAsString(),
                            UUID.fromString(o.get("carrier").getAsString()), o.get("dimension").getAsString(),
                            o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                            o.get("liftedAt").getAsLong(), o.has("offlineSince") ? o.get("offlineSince").getAsLong() : 0));
                } catch (RuntimeException bad) {
                    warn.accept("Skipped a lifted Nexus in " + file + " that could not be read: " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); no lifted Nexus is known. The file was left as it is.");
            byKeep.clear();
        }
    }

    public void save(Path dir, Consumer<String> warn) {
        JsonArray list = new JsonArray();
        for (Lift lift : byKeep.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", lift.id());
            o.addProperty("keep", lift.keep());
            o.addProperty("org", lift.org());
            o.addProperty("carrier", lift.carrier().toString());
            o.addProperty("dimension", lift.dimension());
            o.addProperty("x", lift.x());
            o.addProperty("y", lift.y());
            o.addProperty("z", lift.z());
            o.addProperty("liftedAt", lift.liftedAt());
            o.addProperty("offlineSince", lift.offlineSince());
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("lifts", list);
        Path file = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn.accept("Could not save " + file + ": " + e);
        }
    }
}
