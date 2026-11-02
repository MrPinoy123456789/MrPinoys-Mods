package charter.mc.store;

import charter.engine.Action;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.RankEvents;
import charter.org.Ranks;
import charter.state.CharterState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The store names of the rank actions and events, and the {@code ranks} block of state.json: every edited ladder and
 * every rank a player was given. A save from before ranks has no block, so every kingdom reads as the default ladder
 * (Lord, Commoner) with every member at the default rank; nothing is written until a Lord edits.
 *
 * <p>Action names are written into saved votes, so they never change once released.
 */
public final class RankTypes {

    private RankTypes() {
    }

    static void register(TypeRegistry<Action> actions, TypeRegistry<HistoryEvent> events) {
        actions.register("rank.add", charter.org.actions.AddRank.class)
                .register("rank.rename", charter.org.actions.RenameRank.class)
                .register("rank.remove", charter.org.actions.RemoveRank.class)
                .register("rank.move", charter.org.actions.MoveRank.class)
                .register("rank.give", charter.org.actions.GiveRank.class)
                .register("rank.set_power", charter.org.actions.SetRankPower.class);
        events.registerNested(RankEvents.class, HistoryEvent.class);
        Persistence.block(CoreBlocks.block("ranks", RankTypes::write, RankTypes::read));
    }

    private static JsonElement write(CharterState state, Gson gson) {
        JsonObject root = new JsonObject();
        JsonArray ladders = new JsonArray();
        for (var e : state.ranks.ladders().entrySet()) {
            JsonObject o = new JsonObject();
            o.add("kingdom", gson.toJsonTree(e.getKey()));
            o.addProperty("nextId", e.getValue().nextId);
            JsonArray ranks = new JsonArray();
            for (Ranks.Rank r : e.getValue().ranks) {
                JsonObject rank = new JsonObject();
                rank.addProperty("id", r.id());
                rank.addProperty("name", r.name());
                ranks.add(rank);
            }
            o.add("ranks", ranks);
            ladders.add(o);
        }
        root.add("ladders", ladders);
        JsonArray held = new JsonArray();
        for (var e : state.ranks.held().entrySet()) {
            JsonObject o = new JsonObject();
            o.add("player", gson.toJsonTree(e.getKey()));
            o.add("kingdom", gson.toJsonTree(e.getValue().kingdom()));
            o.addProperty("rank", e.getValue().rank());
            held.add(o);
        }
        root.add("held", held);
        return root;
    }

    private static void read(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        if (root.has("ladders") && root.get("ladders").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("ladders")) {
                JsonObject o = e.getAsJsonObject();
                OrgId kingdom = gson.fromJson(o.get("kingdom"), OrgId.class);
                if (kingdom == null || state.orgIncludingDissolved(kingdom).isEmpty()) {
                    repairs.note("ranks_dropped", "the ranks of " + kingdom + ", which is gone");
                    continue;
                }
                List<Ranks.Rank> ranks = new ArrayList<>();
                if (o.has("ranks") && o.get("ranks").isJsonArray()) {
                    for (JsonElement r : o.getAsJsonArray("ranks")) {
                        JsonObject rank = r.getAsJsonObject();
                        ranks.add(new Ranks.Rank(text(rank, "id"), text(rank, "name")));
                    }
                }
                int nextId = o.has("nextId") ? o.get("nextId").getAsInt() : 1;
                for (String mended : state.ranks.restore(kingdom, ranks, nextId)) repairs.note("ranks_mended", kingdom + ": " + mended);
            }
        }
        if (root.has("held") && root.get("held").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("held")) {
                JsonObject o = e.getAsJsonObject();
                PlayerId player = gson.fromJson(o.get("player"), PlayerId.class);
                OrgId kingdom = gson.fromJson(o.get("kingdom"), OrgId.class);
                state.ranks.restoreHeld(player, new Ranks.Held(kingdom, text(o, "rank")));
            }
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
