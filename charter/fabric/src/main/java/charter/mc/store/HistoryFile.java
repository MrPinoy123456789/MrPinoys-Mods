package charter.mc.store;

import charter.history.History;
import charter.history.HistoryEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * {@code history.jsonl}: every recorded event, one JSON object per line, append
 * only and flushed per event (PLAN.md §8.1). Each line holds the time, who caused
 * it, whether an operator did, the event's type name and its fields:
 *
 * <pre>{"t":"2026-10-24T12:00:00Z","actor":"player:...","admin":false,"type":"OrgEvents.Founded","event":{...}}</pre>
 *
 * <p>The file is never rewritten. On start it is read back into the in-memory
 * history, so pages and the Chronicle see events from before the restart. A line
 * whose type this jar does not know is skipped on reading and left in the file.
 */
public final class HistoryFile implements AutoCloseable {

    public static final String NAME = "history.jsonl";

    private final Path file;
    private final CharterJson json;
    private final Consumer<String> warn;
    private BufferedWriter out;

    public HistoryFile(Path dir, CharterJson json, Consumer<String> warn) {
        this.file = dir.resolve(NAME);
        this.json = json;
        this.warn = warn;
    }

    public Path file() {
        return file;
    }

    /** Reads every known line back into {@code into}, without firing anything at the file. Returns lines skipped. */
    public int readInto(History into) {
        if (!Files.exists(file)) return 0;
        int skipped = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    HistoryEvent event = json.compact.fromJson(o.get("event"), HistoryEvent.class);
                    into.record(event, o.get("actor").getAsString(), o.get("admin").getAsBoolean());
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            warn.accept("Could not read " + file + ": " + e.getMessage());
        }
        return skipped;
    }

    public void append(History.Entry entry) {
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(entry.event().at()).toString());
        line.addProperty("actor", entry.actor());
        line.addProperty("admin", entry.admin());
        line.addProperty("type", json.events.tag(entry.event().getClass()).orElse(entry.event().getClass().getName()));
        try {
            line.add("event", json.compact.toJsonTree(entry.event(), HistoryEvent.class));
        } catch (IllegalStateException unregistered) {
            warn.accept(unregistered.getMessage());
            line.add("event", json.compact.toJsonTree(entry.event()));
        }
        try {
            if (out == null) {
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write(json.compact.toJson(line));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            warn.accept("Could not append to " + file + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // closing on shutdown; nothing left to flush
        }
        out = null;
    }
}
