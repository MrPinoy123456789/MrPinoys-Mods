package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reloadable loader for {@code data/pocketdungeons/diary/*.json}, Alex's seven
 * diary entries (M26). Same shape as {@link AdventureGraphs}: a
 * {@code volatile current} holder, sorted resource entries, one rejection per
 * malformed file rather than one failure for the whole pack. One file per
 * entry, holding that entry's title, its pages, and the intensifier band that
 * drops it.
 *
 * <p>The band-to-entry mapping is content, not code: journals unlock in their
 * own numeric order as the ladder climbs (Entry 1 is the first one found,
 * Entry 7 the last), but they are spread thin across the twelve intensifier
 * bands rather than packed into the first seven, so a run's worth of levels
 * does not clear the whole set. See each {@code entry_N.json}'s {@code band}
 * field for the actual assignment, and {@code docs/reference/LORE-DIARIES.md}
 * for why the pages themselves still read out of order once found: a book's
 * physical page order is shuffled at drop time ({@code RunLifecycle}), while
 * this loader's own {@link Entry#pages} stays the canonical, correctly
 * ordered text the lodestone reader shows.
 */
final class Diaries {

    private static volatile Diaries current = new Diaries(List.of(), List.of());

    private final List<Entry> entries;
    private final List<String> rejections;
    private final Map<Integer, Entry> byBand;
    private final Map<Integer, Entry> byNumber;

    private Diaries(List<Entry> entries, List<String> rejections) {
        this.entries = List.copyOf(entries);
        this.rejections = List.copyOf(rejections);
        Map<Integer, Entry> bandIndex = new HashMap<>();
        Map<Integer, Entry> numberIndex = new HashMap<>();
        for (Entry entry : this.entries) {
            bandIndex.put(entry.band(), entry);
            numberIndex.put(entry.number(), entry);
        }
        this.byBand = Map.copyOf(bandIndex);
        this.byNumber = Map.copyOf(numberIndex);
    }

    /** One authored diary entry: a title, its pages in canonical order, and the band that drops it. */
    record Entry(String id, int number, int band, String title, List<String> pages, String unlockShell) {
        Entry {
            pages = List.copyOf(pages);
            unlockShell = unlockShell == null ? "" : unlockShell;
        }
    }

    static Diaries load(MinecraftServer server) {
        List<Entry> parsed = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = server.getResourceManager().listResources(
                "diary", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = baseName(resource.getKey());
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                Entry entry = parseEntry(id, JsonParser.parseReader(reader).getAsJsonObject());
                parsed.add(entry);
            } catch (Exception exception) {
                String reason = resource.getKey() + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected diary entry '{}': {}", id, reason, exception);
            }
        }

        // Two collisions a datapack author could still create by hand: two
        // files claiming the same band (the drop would be ambiguous about
        // which book to hand over) or the same display number (the lodestone
        // reader's ordering would be ambiguous too). Both drop every entry
        // involved rather than silently picking one -- the same "reject
        // loudly" discipline AdventureGraphs uses for a dangling transition.
        Map<Integer, List<Entry>> byBandSeen = new HashMap<>();
        Map<Integer, List<Entry>> byNumberSeen = new HashMap<>();
        for (Entry entry : parsed) {
            byBandSeen.computeIfAbsent(entry.band(), k -> new ArrayList<>()).add(entry);
            byNumberSeen.computeIfAbsent(entry.number(), k -> new ArrayList<>()).add(entry);
        }
        List<Entry> valid = new ArrayList<>();
        for (Entry entry : parsed) {
            List<Entry> sameBand = byBandSeen.get(entry.band());
            List<Entry> sameNumber = byNumberSeen.get(entry.number());
            if (sameBand.size() > 1) {
                rejections.add(entry.id() + " - band " + entry.band() + " is also claimed by "
                        + sameBand.stream().map(Entry::id).filter(other -> !other.equals(entry.id())).toList());
            } else if (sameNumber.size() > 1) {
                rejections.add(entry.id() + " - entry number " + entry.number() + " is also claimed by "
                        + sameNumber.stream().map(Entry::id).filter(other -> !other.equals(entry.id())).toList());
            } else {
                valid.add(entry);
            }
        }
        valid.sort(Comparator.comparingInt(Entry::number));

        Diaries loaded = new Diaries(valid, rejections);
        current = loaded;
        PocketDungeonsMod.LOG.info("Loaded {} diary entry(ies) ({} rejected)",
                valid.size(), rejections.size());
        return loaded;
    }

    private static Entry parseEntry(String id, JsonObject obj) {
        int number = requiredInt(obj, "number");
        int band = requiredInt(obj, "band");
        String title = requiredString(obj, "title");
        JsonElement pagesElement = obj.get("pages");
        if (pagesElement == null || !pagesElement.isJsonArray() || pagesElement.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("pages must be a non-empty array");
        }
        List<String> pages = new ArrayList<>();
        for (JsonElement page : pagesElement.getAsJsonArray()) {
            String text = page.getAsString();
            if (text.isBlank()) {
                throw new IllegalArgumentException("a page must not be blank");
            }
            pages.add(text);
        }
        String unlockShell = "";
        JsonElement unlockElement = obj.get("unlock_shell");
        if (unlockElement != null && !unlockElement.isJsonNull()) {
            unlockShell = unlockElement.getAsString().trim();
        }
        return new Entry(id, number, band, title, pages, unlockShell);
    }

    private static int requiredInt(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return element.getAsInt();
    }

    private static String requiredString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank: " + key);
        }
        return value;
    }

    static Diaries current() {
        return current;
    }

    /** All loaded entries, sorted by their display number ascending. */
    List<Entry> entries() {
        return entries;
    }

    /** The entry a given intensifier band ({@link AffixMath#intensifierBandIndex}) drops, or {@code null}. */
    Entry byBand(int band) {
        return byBand.get(band);
    }

    /** The entry with a given display number (1-7), or {@code null}. */
    Entry byNumber(int number) {
        return byNumber.get(number);
    }

    List<String> rejections() {
        return rejections;
    }

    private static String baseName(Identifier location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.substring(0, name.length() - 5);
    }
}
