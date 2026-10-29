package foundry.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Reads {@code <root>/<namespace>/items/*.json} into a validated {@link ContentSet}.
 * Files are read in sorted order so results are deterministic.
 */
public final class ContentLoader {
    private ContentLoader() {}

    public static ContentSet load(Path root, Predicate<Ident> itemExists, Predicate<Ident> assetExists) {
        List<Problem> problems = new ArrayList<>();
        List<Validator.Sourced> parsed = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            problems.add(Problem.warning("(none)", root.toString(), "The content folder does not exist.",
                    "Create it and add definitions under <namespace>/items/."));
            return new ContentSet(Map.of(), List.copyOf(problems));
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> jsons = files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("items"))
                    .sorted().toList();
            for (Path p : jsons) {
                String src = root.relativize(p).toString().replace('\\', '/');
                ItemParser.Result r;
                try {
                    r = ItemParser.parse(src, Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    problems.add(Problem.error(src, "file", "Could not read the file: " + e.getMessage(),
                            "Check the file exists and is not locked."));
                    continue;
                }
                problems.addAll(r.problems());
                if (r.def() != null) parsed.add(new Validator.Sourced(src, r.def()));
            }
        } catch (IOException e) {
            problems.add(Problem.error("(none)", root.toString(),
                    "Could not scan the content folder: " + e.getMessage(), "Check the folder permissions."));
        }
        return Validator.validate(parsed, itemExists, assetExists, problems);
    }
}
