package charter.mc.pad;

import charter.mc.cmd.Teleports;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The teleport pads: a block position in a dimension, and the trip a player walking onto it takes. Pure data, with no
 * Minecraft types, so the plain Java tests can run it. The world's {@code charter/pads.json} holds it, beside the state.
 *
 * <p>A pad is keyed by its exact block, so finding the pad under a player is one hash lookup. Only the kinds in
 * {@link #KINDS} can be made; another trip becomes a pad kind by adding its {@link Teleports.Kind} there.
 */
public final class PadBook {

    public static final String FILE_NAME = "pads.json";

    /** The trips a pad can take. Add HOME, OUTPOST, HUB or SPAWN here to offer them. */
    public static final Set<Teleports.Kind> KINDS = Collections.unmodifiableSet(EnumSet.of(Teleports.Kind.KEEP));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Where a pad is: the block a player stands on. */
    public record Spot(String dimension, int x, int y, int z) {
    }

    /** One pad. */
    public record Pad(Spot spot, Teleports.Kind kind) {
    }

    private final Map<Spot, Pad> pads = new LinkedHashMap<>();

    /** The kind with this name in any case ("keep"), if pads may take that trip. */
    public static Optional<Teleports.Kind> kind(String name) {
        if (name == null) return Optional.empty();
        for (Teleports.Kind kind : KINDS) {
            if (kind.name().equalsIgnoreCase(name.trim())) return Optional.of(kind);
        }
        return Optional.empty();
    }

    /** The lower case names of the kinds, for tab completion. */
    public static List<String> kindNames() {
        List<String> out = new ArrayList<>();
        for (Teleports.Kind kind : KINDS) out.add(kind.name().toLowerCase(Locale.ROOT));
        return out;
    }

    public boolean isEmpty() {
        return pads.isEmpty();
    }

    public int size() {
        return pads.size();
    }

    /** The pad on this block, or null. */
    public Pad at(Spot spot) {
        return pads.get(spot);
    }

    /** Every pad, in the order they were made. */
    public List<Pad> all() {
        return List.copyOf(pads.values());
    }

    /** Makes a pad. False when the block is a pad already (it is left as it was). */
    public boolean add(Spot spot, Teleports.Kind kind) {
        if (pads.containsKey(spot)) return false;
        pads.put(spot, new Pad(spot, kind));
        return true;
    }

    /** True if there was a pad on this block. */
    public boolean remove(Spot spot) {
        return pads.remove(spot) != null;
    }

    /** Removes every pad and says how many there were. */
    public int clear() {
        int n = pads.size();
        pads.clear();
        return n;
    }

    // ---- the file ----

    /** Reads the file over what is held. A bad entry is skipped; a file that cannot be read leaves no pads and is left alone. */
    public void load(Path dir, Consumer<String> warn) {
        pads.clear();
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("pads")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    Spot spot = new Spot(o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                    Optional<Teleports.Kind> kind = kind(o.get("kind").getAsString());
                    if (kind.isEmpty()) {
                        warn.accept("Skipped a pad in " + file + ": no pad takes the trip " + o.get("kind").getAsString() + ".");
                        continue;
                    }
                    add(spot, kind.get());
                } catch (RuntimeException bad) {
                    warn.accept("Skipped a pad in " + file + " that could not be read: " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); there are no pads. The file was left as it is.");
            pads.clear();
        }
    }

    public void save(Path dir, Consumer<String> warn) {
        JsonArray list = new JsonArray();
        for (Pad pad : pads.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", pad.spot().dimension());
            o.addProperty("x", pad.spot().x());
            o.addProperty("y", pad.spot().y());
            o.addProperty("z", pad.spot().z());
            o.addProperty("kind", pad.kind().name().toLowerCase(Locale.ROOT));
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("pads", list);
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
