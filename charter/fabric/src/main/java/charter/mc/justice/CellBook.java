package charter.mc.justice;

import charter.id.CoreId;
import charter.mc.store.CoreBlocks;
import charter.mc.store.Persistence;
import charter.mc.store.StateBlock;
import charter.state.CharterState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The cells a faction has marked in its keeps (PLAN.md §7.4): which blocks make up
 * each one, and where a prisoner is put. Fabric state, not a rule; core only knows
 * a confinement names a keep and a cell by name. Saved in {@code state.json} as the
 * {@code cells} block, with no Minecraft types so a plain test round trips it.
 */
public final class CellBook {

    public static final String KEY = "cells";
    private static final Map<CharterState, CellBook> BOOKS = Collections.synchronizedMap(new WeakHashMap<>());

    /** A block position without the Minecraft type; sorted so a saved cell never changes order. */
    public record Pos(int x, int y, int z) implements Comparable<Pos> {
        @Override
        public int compareTo(Pos o) {
            if (x != o.x) return Integer.compare(x, o.x);
            if (y != o.y) return Integer.compare(y, o.y);
            return Integer.compare(z, o.z);
        }
    }

    /** One cell: the blocks a prisoner may stand in, and the block they are put in. */
    public static final class Cell {
        public final CoreId keep;
        public final String name;
        public final String dimension;
        public final Pos spawn;
        public final List<Pos> blocks;
        private final Set<Pos> lookup;

        public Cell(CoreId keep, String name, String dimension, Pos spawn, List<Pos> blocks) {
            this.keep = keep;
            this.name = name;
            this.dimension = dimension;
            this.spawn = spawn;
            this.blocks = List.copyOf(blocks);
            this.lookup = new HashSet<>(blocks);
        }

        public boolean contains(String dimension, int x, int y, int z) {
            return this.dimension.equals(dimension) && lookup.contains(new Pos(x, y, z));
        }
    }

    private final Map<String, Cell> cells = new LinkedHashMap<>();

    public static CellBook of(CharterState state) {
        return BOOKS.computeIfAbsent(state, s -> new CellBook());
    }

    /** Registers the block with the store; safe to call again. */
    public static void register() {
        Persistence.block(block());
    }

    private static String key(CoreId keep, String name) {
        return keep + "|" + name.toLowerCase(java.util.Locale.ROOT);
    }

    public Cell get(CoreId keep, String name) {
        return keep == null || name == null ? null : cells.get(key(keep, name));
    }

    public void add(Cell cell) {
        cells.put(key(cell.keep, cell.name), cell);
    }

    public boolean remove(CoreId keep, String name) {
        return cells.remove(key(keep, name)) != null;
    }

    public List<Cell> all() {
        return new ArrayList<>(cells.values());
    }

    public List<Cell> of(CoreId keep) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells.values()) {
            if (c.keep.equals(keep)) out.add(c);
        }
        return out;
    }

    public void clear() {
        cells.clear();
    }

    private static JsonArray xyz(Pos p) {
        JsonArray a = new JsonArray();
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    private static Pos pos(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new Pos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static StateBlock block() {
        return CoreBlocks.block(KEY, (state, gson) -> {
            JsonArray out = new JsonArray();
            for (Cell c : of(state).cells.values()) {
                JsonObject o = new JsonObject();
                o.add("keep", gson.toJsonTree(c.keep));
                o.addProperty("name", c.name);
                o.addProperty("dimension", c.dimension);
                o.add("spawn", xyz(c.spawn));
                JsonArray blocks = new JsonArray();
                for (Pos p : c.blocks) blocks.add(xyz(p));
                o.add("blocks", blocks);
                out.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("cells", out);
            return root;
        }, (root, state, gson, repairs) -> {
            CellBook book = of(state);
            // A load can be retried from the .bak, so start clean each time.
            book.cells.clear();
            if (!root.has("cells")) return;
            for (JsonElement e : root.getAsJsonArray("cells")) {
                JsonObject o = e.getAsJsonObject();
                CoreId keep = gson.fromJson(o.get("keep"), CoreId.class);
                String name = o.get("name").getAsString();
                if (state.core(keep).isEmpty()) {
                    repairs.note("cell_dropped", name + " belonged to " + keep + ", which is gone");
                    continue;
                }
                List<Pos> blocks = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray("blocks")) blocks.add(pos(p));
                book.add(new Cell(keep, name, o.get("dimension").getAsString(), pos(o.get("spawn")), blocks));
            }
        });
    }
}
