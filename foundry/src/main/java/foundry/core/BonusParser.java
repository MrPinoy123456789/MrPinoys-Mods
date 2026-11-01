package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Parses and validates {@code <namespace>/bonuses/*.json}. Reports every problem with a fix. */
public final class BonusParser {
    private BonusParser() {}

    public record Result(BonusDef def, List<Problem> problems) {}

    /** Everything found under a content root: valid bonuses by id, and every problem. */
    public record Loaded(Map<Ident, BonusDef> bonuses, List<Problem> problems) {}

    public static Result parse(String source, String json) {
        List<Problem> out = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                out.add(Problem.error(source, "file", "The file is not a JSON object.", "Wrap the definition in { } braces."));
                return new Result(null, out);
            }
            o = root.getAsJsonObject();
        } catch (RuntimeException e) {
            out.add(Problem.error(source, "file", "The file is not valid JSON: " + e.getMessage(),
                    "Check for a missing comma, quote or bracket."));
            return new Result(null, out);
        }
        if (o.has("schema_version") && number(o, "schema_version", 1, source, out) != BonusDef.SCHEMA_VERSION) {
            out.add(Problem.error(source, "schema_version", "Unsupported schema version.", "Use schema_version 1."));
        }
        Ident id = Ident.parse(text(o, "id", source, out));
        if (id == null && o.has("id")) {
            out.add(Problem.error(source, "id", "id is not a valid identifier.", "Use the form namespace:name, lowercase."));
        }
        String permission = text(o, "require_permission", source, out);
        if (permission != null && Ident.parse(permission) == null) {
            out.add(Problem.error(source, "require_permission", "\"" + permission + "\" is not a valid permission node.",
                    "Use the form race:azrak."));
            permission = null;
        }
        BonusDef.Kind kind = null;
        String kindText = text(o, "kind", source, out);
        if (kindText != null) {
            kind = BonusDef.Kind.byId(kindText);
            if (kind == null) {
                out.add(Problem.error(source, "kind", "Unknown kind \"" + kindText + "\".", "Use \"fortune\" or \"looting\"."));
            }
        }
        int level = o.has("level") ? number(o, "level", 1, source, out) : 1;
        int cap = o.has("cap") ? number(o, "cap", 3, source, out) : 3;
        if (level < 1 || level > BonusDef.MAX_LEVEL) {
            out.add(Problem.error(source, "level", "level must be 1 to " + BonusDef.MAX_LEVEL + ".", "Use 1 for Fortune I or Looting I."));
        }
        if (cap < 1 || cap > BonusDef.MAX_LEVEL) {
            out.add(Problem.error(source, "cap", "cap must be 1 to " + BonusDef.MAX_LEVEL + ".", "Use 3 to match vanilla's top level."));
        }
        List<String> targets = idList(o, "targets", true, source, out);
        List<String> onlyItems = idList(o, "only_items", false, source, out);
        if (targets != null && targets.isEmpty()) {
            out.add(Problem.error(source, "targets", "targets is empty.", "List at least one block or entity id, or a #tag."));
        }
        if (onlyItems != null) {
            for (String item : onlyItems) {
                if (item.startsWith("#")) {
                    out.add(Problem.error(source, "only_items", "only_items cannot hold a tag (" + item + ").", "List item ids."));
                }
            }
        }
        boolean bad = out.stream().anyMatch(Problem::blocking);
        if (bad || id == null || permission == null || kind == null || targets == null) return new Result(null, out);
        return new Result(new BonusDef(id, permission, kind, level, cap, List.copyOf(targets),
                onlyItems == null ? List.of() : List.copyOf(onlyItems)), out);
    }

    /** Reads every {@code <root>/<namespace>/bonuses/*.json}. Two files with one id are an error. */
    public static Loaded load(Path root) {
        Map<Ident, BonusDef> found = new LinkedHashMap<>();
        Map<Ident, String> sources = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        if (!Files.isDirectory(root)) return new Loaded(found, problems);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Path rel = root.relativize(p);
                if (rel.getNameCount() < 3 || !rel.getName(1).toString().equals("bonuses")) continue;
                String src = rel.toString().replace('\\', '/');
                Result r = parse(src, Files.readString(p, StandardCharsets.UTF_8));
                problems.addAll(r.problems());
                if (r.def() == null) continue;
                if (sources.containsKey(r.def().id())) {
                    problems.add(Problem.error(src, "id", "Another file already defines " + r.def().id() + " (" + sources.get(r.def().id()) + ").",
                            "Give each bonus its own id."));
                    continue;
                }
                sources.put(r.def().id(), src);
                found.put(r.def().id(), r.def());
            }
        } catch (IOException e) {
            problems.add(Problem.error(root.toString(), "folder", "Could not read the content folder: " + e.getMessage(),
                    "Check the folder permissions."));
        }
        return new Loaded(found, problems);
    }

    private static String text(JsonObject o, String key, String src, List<Problem> out) {
        if (!o.has(key)) {
            out.add(Problem.error(src, key, "Missing required field " + key + ".", "Add " + key + " as text."));
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be text.", "Put the value in quotes."));
            return null;
        }
    }

    private static int number(JsonObject o, String key, int fallback, String src, List<Problem> out) {
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            out.add(Problem.error(src, key, key + " must be a whole number.", "Write it without quotes, for example 1."));
            return fallback;
        }
    }

    private static List<String> idList(JsonObject o, String key, boolean required, String src, List<Problem> out) {
        if (!o.has(key)) {
            if (required) out.add(Problem.error(src, key, "Missing required field " + key + ".", "Add " + key + " as a list of ids."));
            return null;
        }
        if (!o.get(key).isJsonArray()) {
            out.add(Problem.error(src, key, key + " must be a list.", "Use [ \"minecraft:iron_ore\" ]."));
            return null;
        }
        JsonArray arr = o.getAsJsonArray(key);
        List<String> list = new ArrayList<>();
        for (JsonElement e : arr) {
            String s;
            try {
                s = e.getAsString();
            } catch (RuntimeException ex) {
                out.add(Problem.error(src, key, key + " holds a value that is not text.", "Quote each id."));
                continue;
            }
            if (Ident.parse(s.startsWith("#") ? s.substring(1) : s) == null) {
                out.add(Problem.error(src, key, "\"" + s + "\" is not a valid id.", "Use namespace:name, or #namespace:tag."));
                continue;
            }
            list.add(s);
        }
        return list;
    }
}
