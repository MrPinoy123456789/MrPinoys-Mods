package pocketdungeons;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a file through a sibling temp file and an atomic move, so a kill part way through leaves the old
 * file whole (or no file) instead of a truncated one. The same discipline as {@code RoomStore},
 * {@code InventoryJournal} and the config writer; the bytes that end up at the target are exactly the
 * bytes the writer produced.
 */
final class AtomicFiles {

    /** Produces the file's content at the path it is given (the temp file). */
    @FunctionalInterface
    interface Writer {
        void writeTo(Path tmp) throws IOException;
    }

    private AtomicFiles() {}

    /** Runs {@code writer} against {@code file}'s sibling {@code .tmp}, then moves it over {@code file}. */
    static void write(Path file, Writer writer) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            writer.writeTo(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // the original failure is the one worth reporting
            }
            throw e;
        }
    }

    /** {@link Files#writeString} of {@code text}, atomically. */
    static void writeString(Path file, String text, Charset charset) throws IOException {
        write(file, tmp -> Files.writeString(tmp, text, charset));
    }
}
