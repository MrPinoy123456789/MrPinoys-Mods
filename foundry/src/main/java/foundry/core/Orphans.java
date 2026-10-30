package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code orphans.json} ledger behind DECISIONS.md D5. It remembers which ids were defined last run and by which
 * file, so an id that vanishes is recorded with where it used to live instead of disappearing silently. Entries heal:
 * an id that comes back leaves the ledger.
 */
public final class Orphans {
    private Orphans() {}

    /** One orphan entry: a defined id that was absent this run, with the file it used to come from. */
    public record Entry(String id, String file, String since) {}

    public record Update(String json, List<Entry> added, List<Entry> healed, List<Entry> active) {}

    /**
     * @param previous the previous file content, or null/blank on first run
     * @param current  every id defined this run and the file that defines it
     * @param now      timestamp written for new orphans
     */
    public static Update update(String previous, Map<Ident, String> current, String now) {
        Map<String, String> known = new LinkedHashMap<>();
        List<Entry> orphans = new ArrayList<>();
        if (previous != null && !previous.isBlank()) {
            try {
                JsonObject o = JsonParser.parseString(previous).getAsJsonObject();
                if (o.has("known")) {
                    for (var e : o.getAsJsonObject("known").entrySet()) known.put(e.getKey(), e.getValue().getAsString());
                }
                if (o.has("orphans")) {
                    for (var e : o.getAsJsonArray("orphans")) {
                        JsonObject x = e.getAsJsonObject();
                        orphans.add(new Entry(x.get("id").getAsString(), x.get("file").getAsString(),
                                x.has("since") ? x.get("since").getAsString() : ""));
                    }
                }
            } catch (RuntimeException ignored) {
                // A corrupt ledger is rebuilt from the current run; the ids it held are lost, not the content.
            }
        }
        Map<String, String> nowKnown = new LinkedHashMap<>();
        current.forEach((id, file) -> nowKnown.put(id.toString(), file));
        List<Entry> added = new ArrayList<>();
        List<Entry> healed = new ArrayList<>();
        List<Entry> active = new ArrayList<>();
        for (Entry e : orphans) {
            if (nowKnown.containsKey(e.id())) healed.add(e);
            else active.add(e);
        }
        for (var e : known.entrySet()) {
            if (!nowKnown.containsKey(e.getKey())
                    && active.stream().noneMatch(x -> x.id().equals(e.getKey()))) {
                Entry entry = new Entry(e.getKey(), e.getValue(), now);
                active.add(entry);
                added.add(entry);
            }
        }
        JsonObject out = new JsonObject();
        JsonObject k = new JsonObject();
        nowKnown.forEach(k::addProperty);
        out.add("known", k);
        JsonArray a = new JsonArray();
        for (Entry e : active) {
            JsonObject x = new JsonObject();
            x.addProperty("id", e.id());
            x.addProperty("file", e.file());
            x.addProperty("since", e.since());
            a.add(x);
        }
        out.add("orphans", a);
        return new Update(out.toString(), List.copyOf(added), List.copyOf(healed), List.copyOf(active));
    }
}
