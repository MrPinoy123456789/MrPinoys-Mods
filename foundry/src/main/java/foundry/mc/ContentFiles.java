package foundry.mc;

import foundry.core.Rename;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** File IO for {@link Rename} plans. Every touched file gets a {@code .bak-<millis>} backup before it changes. */
final class ContentFiles {
    private ContentFiles() {}

    /** Every {@code .json} under root as {@code relative/path.json} to text. */
    static Map<String, String> readAll(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                    .sorted().toList()) {
                out.put(rel(root, p), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static boolean exists(Path root, String rel) {
        return Files.exists(root.resolve(rel));
    }

    /** Backs up then applies every edit and move. Returns a short summary for the command reply. */
    static String apply(Path root, Rename.Plan plan) throws IOException {
        String stamp = String.valueOf(System.currentTimeMillis());
        for (Rename.Edit e : plan.edits()) {
            Path p = root.resolve(e.path());
            if (Files.isRegularFile(p)) backup(p, stamp);
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.content(), StandardCharsets.UTF_8);
        }
        for (Rename.Move m : plan.moves()) {
            Path from = root.resolve(m.from());
            Path to = root.resolve(m.to());
            if (Files.isRegularFile(from)) backup(from, stamp);
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        return plan.edits().size() + " file(s) rewritten, " + plan.moves().size() + " moved, "
                + plan.references() + " reference(s) updated (backups: *.bak-" + stamp + ")";
    }

    private static void backup(Path file, String stamp) throws IOException {
        Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-" + stamp),
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
