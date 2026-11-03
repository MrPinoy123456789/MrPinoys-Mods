package fortune.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes one JSON file the way Charter writes terminals.json: the whole text to a temporary file beside it,
 * then a move over the old one, so a crash mid write leaves either the old file or the new one, never half of each.
 * Writes are synchronous: a spin is saved before it is paid or animated.
 */
final class AtomicJson {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private AtomicJson() {
    }

    /**
     * The file's contents, or null when there is no file. Throws when it exists but cannot be read, so the caller
     * never writes an empty state over a file somebody can still fix.
     */
    static <T> T read(Path file, Class<T> type) throws IOException {
        Path temp = temp(file);
        if (!Files.exists(file) && Files.exists(temp)) {
            FortuneMod.LOG.warn("{} is missing but its temporary file exists; recovering from it", file.getFileName());
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        }
        if (!Files.exists(file)) return null;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(in, type);
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = temp(file);
        Files.writeString(temp, GSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path temp(Path file) {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }
}
