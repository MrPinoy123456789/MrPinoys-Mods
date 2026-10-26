package charter.mc.chronicle;

import charter.mc.store.CoreBlocks;
import charter.mc.store.Persistence;
import charter.state.CharterState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where the Realm Chronicle's lecterns stand. An operator places one with
 * {@code /charter admin realm-chronicle here}; the positions are saved in
 * {@code state.json} as the {@code chronicle_realm} block. One registry per
 * {@link CharterState}, as {@code Fixtures} does for the land.
 */
public final class RealmLecterns {

    public record Spot(String dimension, int x, int y, int z) {
        String key() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }

    private static final Map<CharterState, RealmLecterns> BY_STATE = new WeakHashMap<>();

    private final Map<String, Spot> spots = new LinkedHashMap<>();

    private RealmLecterns() {
    }

    public static synchronized RealmLecterns of(CharterState state) {
        return BY_STATE.computeIfAbsent(state, s -> new RealmLecterns());
    }

    public void add(Spot spot) {
        spots.put(spot.key(), spot);
    }

    public void remove(Spot spot) {
        spots.remove(spot.key());
    }

    public boolean has(Spot spot) {
        return spots.containsKey(spot.key());
    }

    /** A copy, so a caller may remove while looping. */
    public List<Spot> all() {
        return new ArrayList<>(spots.values());
    }

    /** Registers the saved block. Called once from {@link Chronicles#register}. */
    public static void registerBlock() {
        Persistence.block(CoreBlocks.block("chronicle_realm", (state, gson) -> {
            JsonArray out = new JsonArray();
            List<Spot> sorted = new ArrayList<>(of(state).spots.values());
            sorted.sort(Comparator.comparing(Spot::key));
            for (Spot s : sorted) {
                JsonObject o = new JsonObject();
                o.addProperty("dimension", s.dimension());
                o.addProperty("x", s.x());
                o.addProperty("y", s.y());
                o.addProperty("z", s.z());
                out.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("lecterns", out);
            return root;
        }, (json, state, gson, repairs) -> {
            RealmLecterns realm = of(state);
            realm.spots.clear();
            for (var e : json.getAsJsonArray("lecterns")) {
                JsonObject o = e.getAsJsonObject();
                realm.add(new Spot(o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
            }
        }));
    }
}
