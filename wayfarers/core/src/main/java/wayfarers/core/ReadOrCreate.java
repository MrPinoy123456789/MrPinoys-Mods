package wayfarers.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The suite's {@code readOrCreate} semantics (DESIGN.md §4.5), written
 * once so they can be tested without Minecraft or Gson on the classpath.
 *
 * <p>The three rules, in order of how much damage getting them wrong does:
 * <ol>
 *   <li>Missing file: write the defaults, log it, return the defaults.</li>
 *   <li>File that <em>fails to parse</em>: return the defaults but <strong>never
 *       write</strong>. An operator's broken-but-recoverable edit is worth more
 *       than a tidy file -- overwriting it destroys the only copy of their work.</li>
 *   <li>File that parses: return it.</li>
 * </ol>
 *
 * <p>Callers supply the actual parse and serialise steps as lambdas, which is
 * what keeps Gson out of {@code core}.
 */
public final class ReadOrCreate {

    private ReadOrCreate() {}

    /** Parses text into a value, or throws if the text is not valid. */
    @FunctionalInterface
    public interface Parser<T> {
        T parse(String text) throws Exception;
    }

    /** Renders a value as the text to write on first boot. */
    @FunctionalInterface
    public interface Renderer<T> {
        String render(T value);
    }

    /** What happened, so the caller can log it and tests can assert on it. */
    public enum Outcome {
        /** File was absent; defaults were written to disk. */
        CREATED,
        /** File parsed cleanly. */
        LOADED,
        /** File exists but did not parse; defaults used in memory, disk untouched. */
        KEPT_BROKEN
    }

    public record Result<T>(T value, Outcome outcome) {}

    public static <T> Result<T> load(Path file, T defaults, Parser<T> parser,
                                     Renderer<T> renderer, Consumer<String> log) {
        try {
            if (!Files.exists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, renderer.render(defaults));
                log.accept("Created default " + file.getFileName());
                return new Result<>(defaults, Outcome.CREATED);
            }

            String text = Files.readString(file);
            T parsed;
            try {
                parsed = parser.parse(text);
            } catch (Exception e) {
                log.accept("Could not parse " + file.getFileName() + " (" + e.getMessage()
                        + ") -- using defaults and leaving your file alone");
                return new Result<>(defaults, Outcome.KEPT_BROKEN);
            }
            if (parsed == null) {
                log.accept(file.getFileName() + " was empty -- using defaults, file left alone");
                return new Result<>(defaults, Outcome.KEPT_BROKEN);
            }
            return new Result<>(parsed, Outcome.LOADED);

        } catch (IOException e) {
            log.accept("Could not read " + file.getFileName() + " (" + e.getMessage()
                    + ") -- using defaults");
            return new Result<>(defaults, Outcome.KEPT_BROKEN);
        }
    }
}
