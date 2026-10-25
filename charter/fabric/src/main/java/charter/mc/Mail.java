package charter.mc;

import charter.id.PlayerId;
import charter.mc.store.CoreBlocks;
import charter.mc.store.StateBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What waits for players who were away (PLAN.md §8.1, "pending offline messages"):
 * the last few notices, read out on join, and items the treasury owes them (a
 * withdrawal a vote passed while they were offline, or that did not fit in their
 * inventory). Saved in {@code state.json} as the {@code mail} block.
 *
 * <p>This is fabric state, not a rule, so it lives here rather than in CharterState.
 */
public final class Mail {

    public record Message(long at, String text) {
    }

    private static final Type ITEMS = new TypeToken<Map<String, Integer>>() { }.getType();

    private final Map<PlayerId, Deque<Message>> messages = new LinkedHashMap<>();
    private final Map<PlayerId, Map<String, Integer>> owed = new LinkedHashMap<>();
    private int kept;

    public Mail(int kept) {
        this.kept = Math.max(1, kept);
    }

    public void setKept(int newKept) {
        kept = Math.max(1, newKept);
    }

    /** Keeps a message for later; only the newest {@code kept} are held per player. */
    public void post(PlayerId player, long at, String text) {
        Deque<Message> box = messages.computeIfAbsent(player, p -> new ArrayDeque<>());
        box.addLast(new Message(at, text));
        while (box.size() > kept) box.removeFirst();
    }

    /** Takes every waiting message, oldest first. */
    public List<Message> take(PlayerId player) {
        Deque<Message> box = messages.remove(player);
        return box == null ? List.of() : List.copyOf(box);
    }

    public List<Message> peek(PlayerId player) {
        Deque<Message> box = messages.get(player);
        return box == null ? List.of() : List.copyOf(box);
    }

    public void owe(PlayerId player, String itemId, int count) {
        if (count > 0) owed.computeIfAbsent(player, p -> new TreeMap<>()).merge(itemId, count, Integer::sum);
    }

    public Map<String, Integer> owedTo(PlayerId player) {
        Map<String, Integer> items = owed.get(player);
        return items == null ? Map.of() : Map.copyOf(items);
    }

    /** Takes everything owed; the caller hands it over and owes back what did not fit. */
    public Map<String, Integer> takeOwed(PlayerId player) {
        Map<String, Integer> items = owed.remove(player);
        return items == null ? Map.of() : items;
    }

    public StateBlock block() {
        return CoreBlocks.block("mail", (state, gson) -> {
            JsonArray boxes = new JsonArray();
            messages.forEach((player, box) -> {
                JsonObject o = new JsonObject();
                o.add("player", gson.toJsonTree(player));
                o.add("messages", gson.toJsonTree(new ArrayList<>(box)));
                boxes.add(o);
            });
            JsonArray debts = new JsonArray();
            owed.forEach((player, items) -> {
                JsonObject o = new JsonObject();
                o.add("player", gson.toJsonTree(player));
                o.add("items", gson.toJsonTree(items, ITEMS));
                debts.add(o);
            });
            JsonObject root = new JsonObject();
            root.add("messages", boxes);
            root.add("owed", debts);
            return root;
        }, (root, state, gson, repairs) -> {
            // A load can be retried from the .bak, so start clean each time.
            messages.clear();
            owed.clear();
            for (JsonElement e : root.getAsJsonArray("messages")) {
                JsonObject o = e.getAsJsonObject();
                PlayerId player = gson.fromJson(o.get("player"), PlayerId.class);
                for (JsonElement m : o.getAsJsonArray("messages")) {
                    Message message = gson.fromJson(m, Message.class);
                    messages.computeIfAbsent(player, p -> new ArrayDeque<>()).addLast(message);
                }
            }
            for (JsonElement e : root.getAsJsonArray("owed")) {
                JsonObject o = e.getAsJsonObject();
                Map<String, Integer> items = gson.fromJson(o.get("items"), ITEMS);
                owed.put(gson.fromJson(o.get("player"), PlayerId.class), new TreeMap<>(items));
            }
        });
    }
}
