package kamutotems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic JSON persistence under {@code config/kamutotems/}.
 *
 * <p>Writes go to {@code <name>.tmp} and are then renamed over the target, so a
 * crash mid-write costs the last few seconds rather than the file. This is the
 * suite pattern (SUITE_AUDIT section 4.2, pattern 3).
 *
 * <p>Chosen failure directions (SPEC.md section 17):
 * <ul>
 *   <li>Unreadable file -&gt; {@code null}, and the file is renamed
 *       {@code .corrupt} rather than deleted or overwritten. The caller decides
 *       what a missing record means; nothing is silently destroyed.</li>
 *   <li>Failed write -&gt; logged, previous file left intact.</li>
 * </ul>
 */
public final class Persist {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path root;

    private Persist() {}

    public static void init(Path dataDir) {
        root = dataDir;
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            KamuTotemsMod.LOG.error("Could not create data directory {}", root, e);
        }
    }

    public static void save(String relativePath, JsonObject data) {
        if (root == null || data == null) {
            return;
        }
        Path target = root.resolve(relativePath);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            KamuTotemsMod.LOG.error("Failed to write {}; previous file left intact",
                    relativePath, e);
        }
    }

    /** @return the parsed object, or {@code null} if absent or unreadable. */
    public static JsonObject load(String relativePath) {
        if (root == null) {
            return null;
        }
        Path target = root.resolve(relativePath);
        if (!Files.exists(target)) {
            return null;
        }
        try (Reader r = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, JsonObject.class);
        } catch (Exception e) {
            // Rename, never delete. A corrupt record is skipped and the server
            // still boots -- the spiritwolves wolf-record rule.
            Path corrupt = target.resolveSibling(target.getFileName() + ".corrupt");
            try {
                Files.move(target, corrupt, StandardCopyOption.REPLACE_EXISTING);
                KamuTotemsMod.LOG.error("{} was unreadable; renamed to {}",
                        relativePath, corrupt.getFileName(), e);
            } catch (Exception moveFailure) {
                KamuTotemsMod.LOG.error("{} was unreadable and could not be renamed",
                        relativePath, moveFailure);
            }
            return null;
        }
    }
}
