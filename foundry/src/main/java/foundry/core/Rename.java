package foundry.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plans a content rename. Ids are stable: a definition file moves and every quoted reference to the old id in any
 * other definition file is rewritten. The plan is pure data; the caller decides where the files live, makes backups
 * and applies the edits. Nothing is touched unless every check passes.
 */
public final class Rename {
    private Rename() {}

    /** A file whose contents are replaced: {@code path} is relative to the content root, forward slashes. */
    public record Edit(String path, String content) {}

    /** A file that moves with no content change of its own beyond what {@link Edit} already covers. */
    public record Move(String from, String to) {}

    public record Plan(Ident oldId, Ident newId, List<Edit> edits, List<Move> moves, int references) {
        public boolean empty() {
            return edits.isEmpty() && moves.isEmpty();
        }
    }

    /** Either a plan, or every reason the rename is refused. */
    public record Result(Plan plan, List<String> refusals) {
        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    /**
     * @param files      every definition file under the content root: relative path (forward slashes) to text
     * @param taken      ids already defined by the loaded set (items and recipes)
     * @param fileExists true when a relative path already exists on disk, for files not in {@code files}
     */
    public static Result plan(Ident oldId, Ident newId, Set<Ident> taken, Map<String, String> files,
            Predicate<String> fileExists) {
        List<String> refusals = new ArrayList<>();
        if (newId == null) {
            refusals.add("The new id is not valid. Use lowercase letters, digits and underscores, namespace:name.");
            return new Result(null, refusals);
        }
        if (!taken.contains(oldId)) {
            refusals.add("Nothing defines " + oldId + ". Check the id with /foundry status.");
        }
        if (taken.contains(newId)) {
            refusals.add(newId + " is already defined. Pick another id or remove it first.");
        }
        if (!refusals.isEmpty()) return new Result(null, refusals);

        // Every kind of file that can define this id (an item and a recipe may share one) moves with it.
        List<String> homes = new ArrayList<>();
        for (String kind : List.of("items", "recipes")) {
            String expected = oldId.namespace() + "/" + kind + "/" + oldId.path() + ".json";
            if (files.containsKey(expected)) homes.add(expected);
        }
        if (homes.isEmpty()) {
            refusals.add("No file under the content folder defines " + oldId + ".");
            return new Result(null, refusals);
        }
        List<Move> moves = new ArrayList<>();
        for (String home : homes) {
            String target = newId.namespace() + "/" + kind(home) + "/" + newId.path() + ".json";
            if (!target.equals(home) && (files.containsKey(target) || fileExists.test(target))) {
                refusals.add("A file already sits at " + target + ". Remove or rename it first.");
                return new Result(null, refusals);
            }
            if (!target.equals(home)) moves.add(new Move(home, target));
        }

        List<Edit> edits = new ArrayList<>();
        int references = 0;
        String needle = "\"" + oldId + "\"";
        for (var e : files.entrySet()) {
            if (!e.getValue().contains(needle)) continue;
            edits.add(new Edit(e.getKey(), e.getValue().replace(needle, "\"" + newId + "\"")));
            // A moved file's own "id" line is the rename itself, not a reference to it.
            references += count(e.getValue(), needle) - (homes.contains(e.getKey()) ? 1 : 0);
        }
        if (edits.isEmpty()) {
            refusals.add(oldId + " is defined but no file text matched it; nothing was changed.");
            return new Result(null, refusals);
        }
        return new Result(new Plan(oldId, newId, edits, moves, references), List.of());
    }

    private static String kind(String home) {
        int slash = home.indexOf('/');
        int next = home.indexOf('/', slash + 1);
        return home.substring(slash + 1, next);
    }

    private static int count(String text, String needle) {
        int n = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }
}
