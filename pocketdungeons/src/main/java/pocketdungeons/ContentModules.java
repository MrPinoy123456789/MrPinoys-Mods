package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 (D41): the content module switchboard. A module is a named bundle of
 * data that is either on or off: loot that only lands while it is on, items
 * its allow list adds to the loot rules, stations, merchant stock lines and
 * bags that only exist while it is on. Cut systems (alchemy, trims,
 * redstone, gardening, decor, extra bags) ship as modules so the owner can
 * try one again by toggling it on, per plan section L2: "so we can try
 * adding alchemy in the future and just toggle on the alchemy loot tables".
 *
 * <p>A module is a data file at {@code data/<namespace>/content_module/
 * <name>.json} plus whatever it references (loot tables under
 * {@code loot_table/modules/<name>/}, bags, stations). The manifest record
 * is {@link Module}; loading lives in {@link ContentModuleLoader}; this
 * class holds the parsed set plus the operator's overrides and answers the
 * gate questions ({@link #enabled}, {@link #stationEnabled},
 * {@link #bagEnabled}, {@link #merchantItemEnabled}).
 *
 * <p><b>Pure.</b> No Minecraft imports: the rule a test or a tool needs
 * (which modules are on, what they allow) lives here, while the resource
 * scan and the loot-table hook that consume it live in
 * {@link ContentModuleLoader}.
 *
 * <h2>State resolution</h2>
 *
 * <p>{@code pocketdungeons.json} carries a {@code "modules"} object of
 * {@code id: true|false} entries. A missing entry means the manifest's own
 * {@code "default"}; an entry overrides it. An override naming a module no
 * manifest declares is kept (the config round-trips) but has no effect and
 * is warned about at load. {@link #enabled} answers the resolved state for
 * a bare or namespaced id; unknown ids are simply off.
 */
public final class ContentModules {

    /**
     * One parsed {@code content_module/*.json} file.
     *
     * @param id            namespaced id, the file name under
     *                      {@code content_module/} (the manifest's own
     *                      {@code "id"} field must resolve to it)
     * @param label         display name for {@code /dungeon admin modules}
     * @param description   one-line description for the same listing
     * @param defaultEnabled state when the config names no override
     * @param loot          target table id to module table id; while the
     *                      module is enabled each roll of the target table
     *                      also rolls the module table and merges its drops
     * @param allow         item ids this module adds to the loot allow list
     *                      (consumed by the loot rule checks, plan section K)
     * @param stations      station block names gated on this module
     * @param merchantStock item ids merchant stock may list only while the
     *                      module is enabled
     * @param bags          bag ids that only exist while the module is on
     */
    public record Module(
            String id,
            String label,
            String description,
            boolean defaultEnabled,
            Map<String, String> loot,
            List<String> allow,
            List<String> stations,
            List<String> merchantStock,
            List<String> bags) {

        /** Parses one manifest; throws naming {@code fileIdentity} on any violation. */
        static Module fromJson(JsonObject obj, String fileIdentity) {
            JsonPackSupport.parseVersion(obj, fileIdentity);
            String id = JsonPackSupport.requiredString(obj, "id");
            String qualified = JsonPackSupport.qualify(id);
            if (!qualified.equals(fileIdentity)) {
                throw new IllegalArgumentException(fileIdentity + ": \"id\" " + id
                        + " does not match the file name");
            }
            String label = JsonPackSupport.requiredString(obj, "label");
            String description = JsonPackSupport.requiredString(obj, "description");
            boolean defaultEnabled = obj.has("default") && obj.get("default").isJsonPrimitive()
                    && obj.get("default").getAsJsonPrimitive().isBoolean()
                    ? obj.get("default").getAsBoolean() : false;
            Map<String, String> loot = new LinkedHashMap<>();
            JsonElement lootEl = obj.get("loot");
            if (lootEl != null && lootEl.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : lootEl.getAsJsonObject().entrySet()) {
                    loot.put(JsonPackSupport.qualify(entry.getKey()),
                            JsonPackSupport.qualify(entry.getValue().getAsString()));
                }
            }
            return new Module(qualified, label, description, defaultEnabled,
                    Map.copyOf(loot),
                    stringList(obj, "allow", fileIdentity),
                    stringList(obj, "stations", fileIdentity),
                    stringList(obj, "merchantStock", fileIdentity),
                    qualifiedList(obj, "bags", fileIdentity));
        }

        private static List<String> stringList(JsonObject obj, String key, String fileIdentity) {
            JsonElement element = obj.get(key);
            if (element == null || element.isJsonNull()) {
                return List.of();
            }
            if (!element.isJsonArray()) {
                throw new IllegalArgumentException(fileIdentity + ": \"" + key
                        + "\" must be an array of strings");
            }
            List<String> values = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                values.add(entry.getAsString());
            }
            return List.copyOf(values);
        }

        private static List<String> qualifiedList(JsonObject obj, String key, String fileIdentity) {
            return stringList(obj, key, fileIdentity).stream()
                    .map(JsonPackSupport::qualify)
                    .toList();
        }
    }

    private static volatile ContentModules current = new ContentModules(Map.of(), List.of());
    private static volatile Map<String, Boolean> overrides = Map.of();

    private final Map<String, Module> byId;
    private final List<Module> modules;
    private final List<String> rejections;

    private ContentModules(Map<String, Module> byId, List<String> rejections) {
        this.byId = Map.copyOf(byId);
        this.modules = List.copyOf(byId.values());
        this.rejections = List.copyOf(rejections);
    }

    /** Package-private test and loader factory. */
    static ContentModules create(Map<String, Module> modules, List<String> rejections) {
        return new ContentModules(modules, rejections);
    }

    /** Commits a resolved manifest as the live {@link #current}. */
    static void publish(ContentModules manifest) {
        current = manifest;
    }

    static ContentModules current() {
        return current;
    }

    /** Replaces the operator overrides, from config load or the admin command. */
    static void setOverrides(Map<String, Boolean> resolved) {
        overrides = Map.copyOf(resolved);
    }

    /** Sets or clears one override; {@code null} clears back to the manifest default. */
    static void setOverride(String id, Boolean on) {
        Map<String, Boolean> next = new LinkedHashMap<>(overrides);
        String key = JsonPackSupport.qualify(id);
        if (on == null) {
            next.remove(key);
        } else {
            next.put(key, on);
        }
        overrides = Map.copyOf(next);
    }

    /** The raw override map, for the admin listing and config persistence. */
    static Map<String, Boolean> overrides() {
        return overrides;
    }

    /**
     * Whether {@code id} names an enabled module: the config override if one
     * exists, else the manifest's {@code "default"}. Bare ids resolve to the
     * {@code pocketdungeons} namespace. An id no loaded manifest declares is
     * off regardless of any override.
     */
    public static boolean enabled(String id) {
        Module module = current.byId.get(JsonPackSupport.qualify(id));
        if (module == null) {
            return false;
        }
        Boolean override = overrides.get(module.id());
        return override != null ? override : module.defaultEnabled();
    }

    /** The module for {@code id}, bare or namespaced, or {@code null}. */
    static Module module(String id) {
        return current.byId.get(JsonPackSupport.qualify(id));
    }

    /** The loaded modules in load order. */
    static List<Module> modules() {
        return current.modules;
    }

    static List<String> rejections() {
        return current.rejections;
    }

    /**
     * Target table id to the module table ids enabled modules merge into it.
     * {@link ContentModuleLoader}'s loot hook consumes this once per roll.
     */
    static Map<String, List<String>> enabledLootTargets() {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        for (Module module : current.modules) {
            if (!enabled(module.id())) {
                continue;
            }
            for (Map.Entry<String, String> entry : module.loot().entrySet()) {
                targets.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        return targets;
    }

    /**
     * Every item id enabled modules' allow lists contribute. The loot rule
     * checks treat a module table as allowed when its entries sit in the
     * core allow list plus its own module's {@code allow} (plan L2).
     */
    static Set<String> enabledAllowItems() {
        Set<String> allowed = new LinkedHashSet<>();
        for (Module module : current.modules) {
            if (enabled(module.id())) {
                allowed.addAll(module.allow());
            }
        }
        return allowed;
    }

    /**
     * Whether a station block gated by modules may operate: {@code false}
     * when a loaded module claims it and that module is off, {@code true}
     * when no module claims it or a claiming module is on.
     */
    static boolean stationEnabled(String name) {
        for (Module module : current.modules) {
            if (module.stations().contains(name)) {
                return enabled(module.id());
            }
        }
        return true;
    }

    /**
     * Whether {@code itemId} may appear in merchant stock: {@code false}
     * when only disabled modules list it in {@code merchantStock},
     * {@code true} when no module lists it or an enabled one does.
     */
    static boolean merchantItemEnabled(String itemId) {
        boolean claimed = false;
        for (Module module : current.modules) {
            if (module.merchantStock().contains(itemId)) {
                if (enabled(module.id())) {
                    return true;
                }
                claimed = true;
            }
        }
        return !claimed;
    }

    /**
     * Whether a bag id is usable: {@code false} when a loaded module claims
     * it and that module is off, so a disabled module's bags vanish from
     * the picker and the manifest without needing file moves.
     */
    static boolean bagEnabled(String bagId) {
        String qualified = JsonPackSupport.qualify(bagId);
        boolean claimed = false;
        for (Module module : current.modules) {
            if (module.bags().contains(qualified)) {
                if (enabled(module.id())) {
                    return true;
                }
                claimed = true;
            }
        }
        return !claimed;
    }

    /** The bag ids disabled modules claim; {@link BagManifest} skips them at load. */
    static Set<String> disabledBagIds() {
        return current.disabledBags();
    }

    /**
     * Instance form of {@link #disabledBagIds()} used by
     * {@link ContentSnapshot#build}: the candidate filters its bag parse
     * against itself rather than the last published manifest.
     */
    Set<String> disabledBags() {
        Set<String> disabled = new LinkedHashSet<>();
        for (Module module : modules) {
            Boolean override = overrides.get(module.id());
            boolean on = override != null ? override : module.defaultEnabled();
            if (!on) {
                disabled.addAll(module.bags());
            }
        }
        return disabled;
    }
}
