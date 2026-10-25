package charter.mc.land;

import charter.id.CoreId;
import charter.mc.store.CoreBlocks;
import charter.mc.store.Persistence;
import charter.state.CharterState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Where a core's real objects stand in the world: the nexus, the war banner and
 * its signal campfire (PLAN.md §7.1). Core knows a core by its chunk only; the
 * block position lives here so a break can be told apart from any other block.
 * Saved in {@code state.json} as the {@code land_fixtures} block.
 *
 * <p>One registry per {@link CharterState}, found with {@link #of}, so the saved
 * block and the live handlers always agree on which world they mean.
 */
public final class Fixtures {

    public enum Kind { NEXUS, BANNER, CAMPFIRE }

    public record Fixture(Kind kind, String dimension, int x, int y, int z, CoreId core) {
        String key() {
            return Fixtures.key(dimension, x, y, z);
        }
    }

    private static final Map<CharterState, Fixtures> BY_STATE = new WeakHashMap<>();

    private final Map<String, Fixture> byPosition = new LinkedHashMap<>();

    private Fixtures() {
    }

    public static synchronized Fixtures of(CharterState state) {
        return BY_STATE.computeIfAbsent(state, s -> new Fixtures());
    }

    static String key(String dimension, int x, int y, int z) {
        return dimension + "@" + x + "," + y + "," + z;
    }

    public void add(Fixture fixture) {
        byPosition.put(fixture.key(), fixture);
    }

    public Fixture at(String dimension, int x, int y, int z) {
        return byPosition.get(key(dimension, x, y, z));
    }

    public List<Fixture> ofCore(CoreId core) {
        List<Fixture> out = new ArrayList<>();
        for (Fixture f : byPosition.values()) {
            if (f.core().equals(core)) out.add(f);
        }
        return out;
    }

    public Fixture first(CoreId core, Kind kind) {
        for (Fixture f : byPosition.values()) {
            if (f.core().equals(core) && f.kind() == kind) return f;
        }
        return null;
    }

    public void remove(Fixture fixture) {
        byPosition.remove(fixture.key());
    }

    public void removeCore(CoreId core) {
        byPosition.values().removeIf(f -> f.core().equals(core));
    }

    public int size() {
        return byPosition.size();
    }

    /** Registers the saved block. Called once from {@link LandMod#register}. */
    public static void registerBlock() {
        Persistence.block(CoreBlocks.block("land_fixtures", (state, gson) -> {
            JsonArray out = new JsonArray();
            Fixtures fixtures = of(state);
            List<Fixture> sorted = new ArrayList<>(fixtures.byPosition.values());
            sorted.sort(java.util.Comparator.comparing(Fixture::key));
            for (Fixture f : sorted) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", f.kind().name());
                o.addProperty("dimension", f.dimension());
                o.addProperty("x", f.x());
                o.addProperty("y", f.y());
                o.addProperty("z", f.z());
                o.addProperty("core", f.core().value().toString());
                out.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("fixtures", out);
            return root;
        }, (json, state, gson, repairs) -> {
            Fixtures fixtures = of(state);
            fixtures.byPosition.clear();
            for (var e : json.getAsJsonArray("fixtures")) {
                JsonObject o = e.getAsJsonObject();
                CoreId core = new CoreId(UUID.fromString(o.get("core").getAsString()));
                if (state.core(core).isEmpty()) {
                    repairs.note("fixture_dropped", "a " + o.get("kind").getAsString() + " pointed at core " + core + ", which is gone");
                    continue;
                }
                fixtures.add(new Fixture(Kind.valueOf(o.get("kind").getAsString()), o.get("dimension").getAsString(),
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), core));
            }
        }));
    }
}
