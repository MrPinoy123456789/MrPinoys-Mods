package cobbleeconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The UUID to username directory the spec asks for in section 22, so
 * {@code /pay Steve 500} works while Steve is offline.
 *
 * <p>The account map is keyed by UUID and always will be, because names change. But
 * players type names, so something has to bridge the two. This does, by recording
 * every player who has ever joined.
 *
 * <p><b>Renames are handled by eviction, not by aliasing.</b> When a UUID joins under
 * a new name, the old name is dropped from the lookup entirely. Keeping it would mean
 * {@code /pay OldName} still resolving -- and since Mojang releases old names for
 * reuse, that is a live route to paying the wrong person. An unresolvable name is a
 * rejected command; a stale one is someone else's money.
 *
 * <p>Lookup is case-insensitive because nobody types {@code xX_DragonSlayer_Xx} twice.
 */
public final class NameCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Path temp;
    /** lowercase name to UUID */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    /** UUID to last-seen name, in its original casing, for display */
    private final Map<UUID, String> byId = new ConcurrentHashMap<>();

    public NameCache(Path directory) {
        this.file = directory.resolve("names.json");
        this.temp = directory.resolve("names.json.tmp");
        load();
    }

    /** Record a player. Called on join, and whenever a name is seen alongside a UUID. */
    public void see(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return;

        String previous = byId.put(id, name);
        if (previous != null && !previous.equalsIgnoreCase(name)) {
            byName.remove(previous.toLowerCase(Locale.ROOT));
            CobbleEconomyMod.LOG.info("Player {} renamed: {} -> {}", id, previous, name);
        }
        byName.put(name.toLowerCase(Locale.ROOT), id);

        if (previous == null || !previous.equals(name)) save();
    }

    /** Resolve a typed name, or empty if this server has never seen it. */
    public Optional<UUID> resolve(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** True if this server has already seen the UUID. */
    public boolean knows(UUID id) {
        return byId.containsKey(id);
    }

    /** Last-seen name for a UUID, falling back to the UUID itself for display. */
    public String nameOf(UUID id) {
        String name = byId.get(id);
        return name != null ? name : id.toString().substring(0, 8);
    }

    /** Every known name, for tab completion. */
    public List<String> knownNames() {
        return new ArrayList<>(byId.values());
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    String name = e.getValue().getAsString();
                    byId.put(id, name);
                    byName.put(name.toLowerCase(Locale.ROOT), id);
                } catch (RuntimeException ignored) {
                    // Skip the bad row, keep the rest.
                }
            }
            CobbleEconomyMod.LOG.info("Loaded {} known player names", byId.size());
        } catch (Exception e) {
            // Unlike accounts.json this is rebuildable -- everyone re-registers as
            // they log in -- so a bad file is a warning, not a refusal to start.
            CobbleEconomyMod.LOG.warn("Could not read names.json; it will be rebuilt", e);
        }
    }

    private synchronized void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, String> e : new LinkedHashMap<>(byId).entrySet()) {
            root.addProperty(e.getKey().toString(), e.getValue());
        }
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, out);
                out.flush();
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CobbleEconomyMod.LOG.error("Failed to write names.json", e);
        }
    }
}
