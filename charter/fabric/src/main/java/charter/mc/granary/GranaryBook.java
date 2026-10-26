package charter.mc.granary;

import charter.id.CoreId;
import charter.mc.store.CoreBlocks;
import charter.mc.store.Persistence;
import charter.mc.store.StateBlock;
import charter.state.CharterState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Where each keep's granary and each camp's store physically stand, and what the
 * sim has taken from them while their chunk was not loaded (PLAN.md §4.5, §6.1).
 * Fabric state, not a rule: the food itself is {@code state.siege.stores}, which
 * this only mirrors. Saved in {@code state.json} as the {@code granary} block.
 *
 * <p>No Minecraft types, so a plain test round trips it. One book per
 * {@link CharterState}; {@link #of} finds it.
 */
public final class GranaryBook {

    public static final String KEY = "granary";
    private static final Type ITEMS = new TypeToken<Map<String, Integer>>() { }.getType();
    private static final Map<CharterState, GranaryBook> BOOKS = Collections.synchronizedMap(new WeakHashMap<>());

    /** A block position, without the Minecraft type. */
    public record Pos(int x, int y, int z) {
        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }
    }

    /** One granary (several chests) or one camp store (one container). */
    public static final class Site {
        public final CoreId core;
        public String dimension;
        /** Chest positions in link order; the first carries the display. */
        public final List<Pos> containers = new ArrayList<>();
        /** Removals the sim made while the chunk was unloaded, applied to the real items on load. */
        public final Map<String, Integer> queued = new TreeMap<>();

        public Site(CoreId core, String dimension) {
            this.core = core;
            this.dimension = dimension;
        }
    }

    private final Map<CoreId, Site> sites = new LinkedHashMap<>();

    public static GranaryBook of(CharterState state) {
        return BOOKS.computeIfAbsent(state, s -> new GranaryBook());
    }

    /** Registers the block with the store; safe to call again. */
    public static void register() {
        Persistence.block(block());
    }

    public Site site(CoreId core) {
        return sites.get(core);
    }

    public Site create(CoreId core, String dimension) {
        return sites.computeIfAbsent(core, c -> new Site(c, dimension));
    }

    public void remove(CoreId core) {
        sites.remove(core);
    }

    public List<Site> sites() {
        return new ArrayList<>(sites.values());
    }

    /** The site that owns a container at this position, if any. */
    public Site at(String dimension, Pos pos) {
        for (Site site : sites.values()) {
            if (site.dimension.equals(dimension) && site.containers.contains(pos)) return site;
        }
        return null;
    }

    public void clear() {
        sites.clear();
    }

    public static StateBlock block() {
        return CoreBlocks.block(KEY, (state, gson) -> {
            GranaryBook book = of(state);
            JsonArray out = new JsonArray();
            for (Site site : book.sites.values()) {
                JsonObject o = new JsonObject();
                o.add("core", gson.toJsonTree(site.core));
                o.addProperty("dimension", site.dimension);
                JsonArray at = new JsonArray();
                for (Pos p : site.containers) {
                    JsonArray xyz = new JsonArray();
                    xyz.add(p.x());
                    xyz.add(p.y());
                    xyz.add(p.z());
                    at.add(xyz);
                }
                o.add("containers", at);
                o.add("queued", gson.toJsonTree(site.queued, ITEMS));
                out.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("sites", out);
            return root;
        }, (root, state, gson, repairs) -> {
            GranaryBook book = of(state);
            // A load can be retried from the .bak, so start clean each time.
            book.sites.clear();
            if (!root.has("sites")) return;
            for (JsonElement e : root.getAsJsonArray("sites")) {
                JsonObject o = e.getAsJsonObject();
                CoreId core = gson.fromJson(o.get("core"), CoreId.class);
                if (state.core(core).isEmpty()) {
                    repairs.note("granary_dropped", core + " is gone");
                    continue;
                }
                Site site = new Site(core, o.get("dimension").getAsString());
                for (JsonElement p : o.getAsJsonArray("containers")) {
                    JsonArray xyz = p.getAsJsonArray();
                    site.containers.add(new Pos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                }
                Map<String, Integer> queued = gson.fromJson(o.get("queued"), ITEMS);
                if (queued != null) site.queued.putAll(new TreeMap<>(queued));
                book.sites.put(core, site);
            }
        });
    }
}
