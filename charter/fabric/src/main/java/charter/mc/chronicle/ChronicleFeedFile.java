package charter.mc.chronicle;

import charter.chronicle.Calendar;
import charter.chronicle.ChronicleFeed;
import charter.chronicle.ChronicleRenderer;
import charter.chronicle.NameLookup;
import charter.chronicle.TemplateSet;
import charter.history.History;
import charter.state.CharterState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code <world>/charter/chronicle-feed.jsonl}: the public Chronicle as one JSON object
 * per told happening (docs/2026-11-04-chronicle-design.md 5.1), for whoever drafts it
 * on Discord. Append only, never rewritten, no Minecraft types, so a plain test drives it.
 *
 * <p>The cursor is the file itself: on start the last line's {@code n} says how far the
 * feed got, and only later entries are told. A server that gains the feed with a long
 * history behind it starts from now, so the first draft is not five hundred old lines.
 */
public final class ChronicleFeedFile {

    public static final String NAME = "chronicle-feed.jsonl";

    private final Path file;
    private final Consumer<String> warn;
    private int cursor = -1;
    private BufferedWriter out;

    public ChronicleFeedFile(Path dir, Consumer<String> warn) {
        this.file = dir.resolve(NAME);
        this.warn = warn;
    }

    public Path file() {
        return file;
    }

    /** The history index the next write starts from; -1 before {@link #start}. */
    public int cursor() {
        return cursor;
    }

    /**
     * Finds where to resume: after the last fed entry, or at {@code historySize} when there is
     * no feed yet. Public so a gametest can call it on a fresh runtime.
     */
    public void start(int historySize) {
        cursor = historySize;
        if (!Files.exists(file)) return;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int last = -1;
            while ((line = in.readLine()) != null) {
                int n = ChronicleFeed.indexOf(line);
                if (n >= 0) last = n;
            }
            if (last >= 0) cursor = Math.min(last + 1, historySize);
        } catch (IOException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); the feed resumes from now.");
        }
    }

    /**
     * Tells everything recorded since the cursor and appends it. Admin events are never fed, whatever the
     * books show. Returns how many lines were written.
     */
    public int write(CharterState state, TemplateSet templates) {
        if (cursor < 0) start(state.history.size());
        List<History.Entry> all = state.history.entries();
        if (cursor >= all.size()) return 0;
        long origin = all.isEmpty() ? 0 : all.getFirst().event().at();
        Calendar calendar = Calendar.startingAt(origin);
        ChronicleRenderer renderer = new ChronicleRenderer(templates, calendar, NameLookup.from(state), false);
        List<History.Entry> fresh = all.subList(cursor, all.size());
        List<String> lines = ChronicleFeed.lines(renderer, calendar, fresh, cursor);
        int from = cursor;
        cursor = all.size();
        if (lines.isEmpty()) return 0;
        try {
            if (out == null) {
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            for (String line : lines) {
                out.write(line);
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            // Nothing in the game depends on the feed; the lines are told again next time.
            cursor = from;
            warn.accept("Could not append to " + file + " (" + e.getMessage() + "); the feed will try again.");
            return 0;
        }
        return lines.size();
    }

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
