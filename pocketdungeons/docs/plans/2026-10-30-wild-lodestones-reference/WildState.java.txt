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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything wild mode must remember across a restart (decision 2026-10-30): which lodestones exist, where, which dungeon
 * each holds, and where each stands in its cycle.
 *
 * <p>Pure: no Minecraft imports, so {@code WildStateTest} runs with plain javac. Times are wall clock epoch seconds, so a
 * stopped server does not freeze a cooldown or a lifespan. The live dungeon itself is never saved; a restart resets every
 * lodestone that was in progress or cleared (see the plan, "Restart and persistence").
 */
final class WildState {

    static final int VERSION = 1;

    /** Where a lodestone stands in its cycle. */
    enum Phase {
        /** No live dungeon; the next entry builds one. */
        READY,
        /** A live dungeon nobody has cleared. */
        IN_PROGRESS,
        /** Cleared; the exit block stands and the cooldown runs until {@link Lodestone#resetAt}. */
        CLEARED
    }

    /** One wild lodestone. Fields that never change are final; the rest move with the cycle. */
    static final class Lodestone {
        final String id;
        final String dimension;
        final int x;
        final int y;
        final int z;
        final String dungeon;
        final int level;
        final long createdAt;
        final long expiresAt;
        Phase phase = Phase.READY;
        long clearedAt;
        long resetAt;
        /** Chests placed at collapse for items left on the floor, as {x, y, z} triples. */
        final List<int[]> lostAndFound = new ArrayList<>();

        Lodestone(String id, String dimension, int x, int y, int z, String dungeon, int level,
                  long createdAt, long expiresAt) {
            this.id = id;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dungeon = dungeon;
            this.level = level;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        boolean at(String dimension, int x, int y, int z) {
            return this.dimension.equals(dimension) && this.x == x && this.y == y && this.z == z;
        }

        /** Whether the lifespan has run out and the dungeon should collapse. */
        boolean expired(long now) {
            return now >= expiresAt;
        }

        /** Whether a cleared dungeon's cooldown has run out. */
        boolean resetDue(long now) {
            return phase == Phase.CLEARED && now >= resetAt;
        }

        /** Seconds until the reset, or 0 when nothing is cooling. */
        long secondsToReset(long now) {
            return phase == Phase.CLEARED ? Math.max(0L, resetAt - now) : 0L;
        }

        /** Seconds until the collapse, never negative. */
        long secondsToCollapse(long now) {
            return Math.max(0L, expiresAt - now);
        }

        void markInProgress() {
            phase = Phase.IN_PROGRESS;
            clearedAt = 0L;
            resetAt = 0L;
        }

        /** The clear: the cooldown starts here. */
        void markCleared(long now, long cooldownSeconds) {
            phase = Phase.CLEARED;
            clearedAt = now;
            resetAt = now + Math.max(0L, cooldownSeconds);
        }

        void markReady() {
            phase = Phase.READY;
            clearedAt = 0L;
            resetAt = 0L;
        }
    }

    private final Map<String, Lodestone> lodestones = new LinkedHashMap<>();
    private final Map<UUID, String> lastLodestone = new HashMap<>();
    private long nextId = 1L;

    /** A fresh id, never reused within this state. */
    String nextId() {
        return "w" + nextId++;
    }

    void add(Lodestone lodestone) {
        lodestones.put(lodestone.id, lodestone);
    }

    Lodestone remove(String id) {
        return lodestones.remove(id);
    }

    Lodestone get(String id) {
        return lodestones.get(id);
    }

    /** The lodestone whose entrance block is exactly here, or {@code null}. */
    Lodestone atEntrance(String dimension, int x, int y, int z) {
        for (Lodestone lodestone : lodestones.values()) {
            if (lodestone.at(dimension, x, y, z)) {
                return lodestone;
            }
        }
        return null;
    }

    Collection<Lodestone> all() {
        return Collections.unmodifiableCollection(lodestones.values());
    }

    int size() {
        return lodestones.size();
    }

    /** Remembers where a player last went in, so a login inside a gone dungeon can send them home. */
    void rememberPlayer(UUID player, String lodestoneId) {
        lastLodestone.put(player, lodestoneId);
    }

    String lastLodestoneOf(UUID player) {
        return lastLodestone.get(player);
    }

    void forgetPlayer(UUID player) {
        lastLodestone.remove(player);
    }

    // ---- JSON ----

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("nextId", nextId);
        JsonArray list = new JsonArray();
        for (Lodestone l : lodestones.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id);
            o.addProperty("dimension", l.dimension);
            o.addProperty("x", l.x);
            o.addProperty("y", l.y);
            o.addProperty("z", l.z);
            o.addProperty("dungeon", l.dungeon);
            o.addProperty("level", l.level);
            o.addProperty("createdAt", l.createdAt);
            o.addProperty("expiresAt", l.expiresAt);
            o.addProperty("phase", l.phase.name());
            o.addProperty("clearedAt", l.clearedAt);
            o.addProperty("resetAt", l.resetAt);
            JsonArray chests = new JsonArray();
            for (int[] c : l.lostAndFound) {
                JsonArray triple = new JsonArray();
                triple.add(c[0]);
                triple.add(c[1]);
                triple.add(c[2]);
                chests.add(triple);
            }
            o.add("lostAndFound", chests);
            list.add(o);
        }
        root.add("lodestones", list);
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, String> e : lastLodestone.entrySet()) {
            players.addProperty(e.getKey().toString(), e.getValue());
        }
        root.add("players", players);
        return root.toString();
    }

    /** @throws IllegalStateException when the text is not a wild state file */
    static WildState fromJson(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            int version = root.get("version").getAsInt();
            if (version != VERSION) {
                throw new IllegalStateException("unsupported wild state version " + version);
            }
            WildState state = new WildState();
            state.nextId = root.get("nextId").getAsLong();
            for (JsonElement element : root.getAsJsonArray("lodestones")) {
                JsonObject o = element.getAsJsonObject();
                Lodestone l = new Lodestone(o.get("id").getAsString(), o.get("dimension").getAsString(),
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.get("dungeon").getAsString(), o.get("level").getAsInt(),
                        o.get("createdAt").getAsLong(), o.get("expiresAt").getAsLong());
                l.phase = Phase.valueOf(o.get("phase").getAsString());
                l.clearedAt = o.get("clearedAt").getAsLong();
                l.resetAt = o.get("resetAt").getAsLong();
                for (JsonElement chest : o.getAsJsonArray("lostAndFound")) {
                    JsonArray t = chest.getAsJsonArray();
                    l.lostAndFound.add(new int[] {t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()});
                }
                state.lodestones.put(l.id, l);
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                state.lastLodestone.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            }
            return state;
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException ise && ise.getMessage() != null
                    && ise.getMessage().startsWith("unsupported")) {
                throw ise;
            }
            throw new IllegalStateException("not a wild state file: " + e.getMessage(), e);
        }
    }

    /** Loads the file, or an empty state when it does not exist yet. */
    static WildState load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new WildState();
        }
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes the file through {@link AtomicFiles}, so a kill part way leaves the old file whole. */
    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        AtomicFiles.writeString(file, toJson(), StandardCharsets.UTF_8);
    }
}
