package charter.mc.store;

import charter.config.Knobs;
import charter.state.CharterState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Saves and loads the whole {@link CharterState} as one JSON snapshot in the world
 * folder (DESIGN.md §20, PLAN.md §8.1), beside the append-only history file.
 *
 * <pre>
 *   &lt;world&gt;/charter/state.json        the snapshot
 *   &lt;world&gt;/charter/state.json.bak    the snapshot before it, kept on every write
 *   &lt;world&gt;/charter/backups/          one copy an hour, the last {@value #BACKUPS_KEPT}
 *   &lt;world&gt;/charter/history.jsonl     every event, one per line (HistoryFile)
 * </pre>
 *
 * <p>A write goes to {@code state.json.tmp}, is forced to disk, and is moved over
 * {@code state.json} after the old one is copied to {@code .bak}, so a crash at any
 * point leaves a whole file behind. A {@code state.json} that does not read is
 * copied to {@code state.json.broken-<time>} and the load falls back to {@code .bak},
 * then to the newest backup. It never silently starts empty: if there are saves and
 * none of them reads, {@link #load} throws and the server does not start.
 */
public final class CharterStore {

    public static final String STATE = "state.json";
    public static final int BACKUPS_KEPT = 24;
    private static final long BACKUP_EVERY_MILLIS = 60 * 60 * 1000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Where a load came from. */
    public enum Source { FRESH, STATE, BAK, BACKUP }

    public record Loaded(CharterState state, Repairs repairs, Source source, Path file) {
    }

    /** No save on disk can be read. The server must not start over an empty state. */
    public static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final StateCodec codec;
    private final Consumer<String> info;
    private final Consumer<String> warn;

    public CharterStore(Path dir, StateCodec codec, Consumer<String> info, Consumer<String> warn) {
        this.dir = dir;
        this.codec = codec;
        this.info = info;
        this.warn = warn;
    }

    public Path dir() {
        return dir;
    }

    public Path stateFile() {
        return dir.resolve(STATE);
    }

    public Path bakFile() {
        return dir.resolve(STATE + ".bak");
    }

    public Path backupDir() {
        return dir.resolve("backups");
    }

    public StateCodec codec() {
        return codec;
    }

    // ---- loading ----

    public Loaded load(Knobs knobs, long now) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(stateFile());
        candidates.add(bakFile());
        candidates.addAll(backupsNewestFirst());
        boolean anySave = candidates.stream().anyMatch(Files::exists);
        if (!anySave) {
            info.accept("No Charter save in " + dir + "; starting a new state.");
            return new Loaded(new CharterState(), new Repairs(now), Source.FRESH, null);
        }

        for (int i = 0; i < candidates.size(); i++) {
            Path file = candidates.get(i);
            if (!Files.exists(file)) continue;
            Repairs repairs = new Repairs(now);
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                CharterState state = codec.read(root, knobs, repairs);
                Source source = i == 0 ? Source.STATE : i == 1 ? Source.BAK : Source.BACKUP;
                if (source != Source.STATE) warn.accept("Loaded Charter state from " + file + " instead of " + STATE + ".");
                else info.accept("Loaded Charter state from " + file + ".");
                if (repairs.count() > 0) warn.accept("Repaired " + repairs.count() + " record(s) on load; see history.jsonl (actor repair).");
                return new Loaded(state, repairs, source, file);
            } catch (Exception e) {
                warn.accept("Could not read " + file + ": " + e);
                if (i == 0) keepBroken(file, now);
            }
        }
        throw new Unreadable("No Charter save in " + dir + " can be read (" + STATE + ", its .bak and every backup failed). "
                + "Refusing to start over an empty state. Fix or remove the files, then start again.");
    }

    private void keepBroken(Path file, long now) {
        Path broken = dir.resolve(STATE + ".broken-" + STAMP.format(Instant.ofEpochMilli(now)));
        try {
            Files.copy(file, broken, StandardCopyOption.REPLACE_EXISTING);
            warn.accept("Kept the unreadable file as " + broken + ".");
        } catch (IOException e) {
            warn.accept("Could not keep a copy of " + file + ": " + e.getMessage());
        }
    }

    private List<Path> backupsNewestFirst() {
        if (!Files.isDirectory(backupDir())) return List.of();
        try (Stream<Path> files = Files.list(backupDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- saving ----

    /** Writes the snapshot atomically. Call on the server thread. */
    public void save(CharterState state, long now) throws IOException {
        String text = codec.json.gson.toJson(codec.write(state)) + "\n";
        Files.createDirectories(dir);
        Path tmp = dir.resolve(STATE + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        if (Files.exists(stateFile())) Files.copy(stateFile(), bakFile(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING);
        }
        backup(now);
    }

    private void backup(long now) {
        try {
            List<Path> backups = backupsNewestFirst();
            String newest = backups.isEmpty() ? null : backups.get(0).getFileName().toString();
            String stamp = STAMP.format(Instant.ofEpochMilli(now));
            String stampDue = STAMP.format(Instant.ofEpochMilli(now - BACKUP_EVERY_MILLIS));
            if (newest != null && newest.compareTo("state-" + stampDue + ".json") > 0) return;
            Files.createDirectories(backupDir());
            Files.copy(stateFile(), backupDir().resolve("state-" + stamp + ".json"), StandardCopyOption.REPLACE_EXISTING);
            List<Path> all = backupsNewestFirst();
            for (int i = BACKUPS_KEPT; i < all.size(); i++) Files.deleteIfExists(all.get(i));
        } catch (IOException e) {
            warn.accept("Could not write a Charter backup: " + e.getMessage());
        }
    }
}
