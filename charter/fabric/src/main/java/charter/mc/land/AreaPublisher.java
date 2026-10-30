package charter.mc.land;

import charter.api.Area;
import charter.api.CharterAreas;
import charter.history.History;
import charter.land.LandEvents;
import charter.org.OrgEvents;
import charter.siege.SiegeEvents;
import charter.state.CharterState;
import charter.mc.store.StoreEvents;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Keeps the public land map ({@link CharterAreas}) and {@code areas.json} current. Land events only mark the map
 * stale; the next tick rebuilds it once, so a burst of changes costs one pass.
 */
public final class AreaPublisher {

    public static final String FILE_NAME = "areas.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final CharterState state;
    private final Path file;
    private final Consumer<String> warn;
    private boolean dirty = true;

    public AreaPublisher(CharterState state, Path dir, Consumer<String> warn) {
        this.state = state;
        this.file = dir.resolve(FILE_NAME);
        this.warn = warn;
    }

    /** History listener: land, siege and kingdom events can move the map. */
    public void onEntry(History.Entry entry) {
        Class<?> owner = entry.event().getClass().getEnclosingClass();
        if (owner == LandEvents.class || owner == SiegeEvents.class || owner == OrgEvents.class
                || entry.event() instanceof StoreEvents.Repaired) {
            dirty = true;
        }
    }

    /** Rebuilds and writes the map if anything changed since the last time. Cheap when nothing did. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        CharterAreas.publish(state);
        write();
    }

    /** The map is only meaningful while the world is up. */
    public void close() {
        CharterAreas.clear();
    }

    private void write() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CharterAreas.version());
        JsonArray list = new JsonArray();
        for (Area area : CharterAreas.areas()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", area.kind().name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("kingdom", area.kingdom());
            o.addProperty("dimension", area.dimension());
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", area.minBlockX());
            bounds.addProperty("minZ", area.minBlockZ());
            bounds.addProperty("maxX", area.maxBlockX());
            bounds.addProperty("maxZ", area.maxBlockZ());
            o.add("bounds", bounds);
            JsonArray chunks = new JsonArray();
            for (Area.Chunk c : area.chunks()) {
                JsonArray pair = new JsonArray();
                pair.add(c.x());
                pair.add(c.z());
                chunks.add(pair);
            }
            o.add("chunks", chunks);
            list.add(o);
        }
        root.add("areas", list);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            warn.accept("Could not write " + file + ": " + e.getMessage());
        }
    }
}
