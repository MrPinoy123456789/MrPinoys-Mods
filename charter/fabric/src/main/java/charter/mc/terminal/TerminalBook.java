package charter.mc.terminal;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Charter terminals: block positions that open the Charter menu when right-clicked. Pure data, with no Minecraft
 * types, so the plain Java tests can run it. The world's {@code charter/terminals.json} holds it, beside the state.
 *
 * <p>A terminal is keyed by its exact block. One that Charter placed itself beside a keep's Nexus remembers that keep
 * ({@link Terminal#core}, the core's id as text), so moving or losing the keep takes it away; an operator's own
 * terminals have no keep and stay until an operator removes them.
 */
public final class TerminalBook {

    public static final String FILE_NAME = "terminals.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Where a terminal is: the block a player right-clicks. */
    public record Spot(String dimension, int x, int y, int z) {
    }

    /** One terminal. {@code core} is the keep it was placed for, or null for an operator's terminal. */
    public record Terminal(Spot spot, String core) {
    }

    private final Map<Spot, Terminal> terminals = new LinkedHashMap<>();

    public boolean isEmpty() {
        return terminals.isEmpty();
    }

    public int size() {
        return terminals.size();
    }

    /** The terminal on this block, or null. */
    public Terminal at(Spot spot) {
        return terminals.get(spot);
    }

    /** Every terminal, in the order they were made. */
    public List<Terminal> all() {
        return List.copyOf(terminals.values());
    }

    /** Makes a terminal. False when the block is a terminal already (it is left as it was). */
    public boolean add(Spot spot, String core) {
        if (terminals.containsKey(spot)) return false;
        terminals.put(spot, new Terminal(spot, core == null || core.isBlank() ? null : core));
        return true;
    }

    /** True if there was a terminal on this block. */
    public boolean remove(Spot spot) {
        return terminals.remove(spot) != null;
    }

    /** The terminals placed for this keep. */
    public List<Terminal> ofCore(String core) {
        List<Terminal> out = new ArrayList<>();
        for (Terminal t : terminals.values()) {
            if (core != null && core.equals(t.core())) out.add(t);
        }
        return out;
    }

    /** Removes the terminals placed for this keep and returns them. */
    public List<Terminal> removeCore(String core) {
        List<Terminal> gone = ofCore(core);
        for (Terminal t : gone) terminals.remove(t.spot());
        return gone;
    }

    /** Removes every terminal and says how many there were. */
    public int clear() {
        int n = terminals.size();
        terminals.clear();
        return n;
    }

    // ---- the file ----

    /** Reads the file over what is held. A bad entry is skipped; a file that cannot be read leaves no terminals and is left alone. */
    public void load(Path dir, Consumer<String> warn) {
        terminals.clear();
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("terminals")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    Spot spot = new Spot(o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                    String core = o.has("core") && !o.get("core").isJsonNull() ? o.get("core").getAsString() : null;
                    add(spot, core);
                } catch (RuntimeException bad) {
                    warn.accept("Skipped a terminal in " + file + " that could not be read: " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); there are no terminals. The file was left as it is.");
            terminals.clear();
        }
    }

    /** Writes every terminal to a temporary file, then moves it over the old one. */
    public void save(Path dir, Consumer<String> warn) {
        JsonArray list = new JsonArray();
        for (Terminal t : terminals.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", t.spot().dimension());
            o.addProperty("x", t.spot().x());
            o.addProperty("y", t.spot().y());
            o.addProperty("z", t.spot().z());
            if (t.core() != null) o.addProperty("core", t.core());
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("terminals", list);
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
