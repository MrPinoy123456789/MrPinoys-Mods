package chartercurrency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One JSON state file written the way {@link JsonAccountStore} writes accounts.json:
 * temp file then atomic move, coalesced onto a background thread every 15 seconds, one
 * last write on clean shutdown, and a refusal to start over a file that cannot be read.
 *
 * <p>The state itself belongs to a core service; this class only reads it at boot and
 * asks a serializer (which takes the service's lock) for the text to write. Renown
 * balances and treasuries are money, so the refuse-to-load rule applies to them too.
 */
final class JsonStateFile<T> {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long FLUSH_SECONDS = 15;

    private final Path file;
    private final Path temp;
    private final Class<T> type;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile Supplier<String> snapshot = () -> "{}";
    private final ScheduledExecutorService writer;

    JsonStateFile(Path directory, String name, Class<T> type) {
        this.file = directory.resolve(name);
        this.temp = directory.resolve(name + ".tmp");
        this.type = type;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            CharterCurrencyMod.LOG.error("Could not create {}", directory, e);
        }
        writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chartercurrency-" + name);
            t.setDaemon(true);
            return t;
        });
        writer.scheduleWithFixedDelay(this::flushIfDirty, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Read the file, or a fresh empty state if there is none. Throws if it exists but
     * cannot be understood: loading an empty state would look fine until the next write
     * replaced everything.
     */
    T load() {
        if (!Files.exists(file) && Files.exists(temp)) {
            CharterCurrencyMod.LOG.warn("{} is missing but a temp file exists; recovering from it", file.getFileName());
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("Could not recover " + file, e);
            }
        }
        if (!Files.exists(file)) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T loaded = GSON.fromJson(in, type);
            if (loaded == null) {
                return type.getDeclaredConstructor().newInstance();
            }
            return loaded;
        } catch (Exception e) {
            CharterCurrencyMod.LOG.error("{} is unreadable: REFUSING to overwrite it. Fix or remove the file and restart.",
                    file.getFileName(), e);
            throw new IllegalStateException("Corrupt " + file, e);
        }
    }

    /** What to write: JSON text produced under the owning service lock (see toJson). */
    void bind(Supplier<String> snapshot) {
        this.snapshot = snapshot;
    }

    /** Serialise a state object; call it while holding the owning service's lock. */
    static String toJson(Object state) {
        return GSON.toJson(state);
    }

    /** Ask for a write soon. Cheap; safe from the server thread. */
    void markDirty() {
        dirty.set(true);
    }

    void flushNow() {
        flushIfDirty();
    }

    private void flushIfDirty() {
        if (!dirty.compareAndSet(true, false)) return;
        try {
            // Serialise under the owning service's lock; write outside it.
            String json = snapshot.get();
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                out.write(json);
                out.flush();
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            dirty.set(true);
            CharterCurrencyMod.LOG.error("Failed to write {}", file.getFileName(), e);
        }
    }

    void close() {
        writer.shutdown();
        flushIfDirty();
    }
}
