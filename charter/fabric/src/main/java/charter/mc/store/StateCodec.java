package charter.mc.store;

import charter.config.Knobs;
import charter.state.CharterState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Turns a whole {@link CharterState} into one JSON object and back: a
 * {@code formatVersion}, then one entry per registered {@link StateBlock}.
 *
 * <p>A block the file has but no feature registered (a downgrade, or a feature
 * switched off) is kept as it was and written back unchanged, so nothing is lost
 * by running an older jar for a while.
 */
public final class StateCodec {

    /** The format this jar writes. Each bump comes with a migration from the one before. */
    public static final int FORMAT_VERSION = 1;

    public final CharterJson json;
    private final List<StateBlock> blocks = new ArrayList<>();
    private final Map<Integer, UnaryOperator<JsonObject>> migrations = new LinkedHashMap<>();
    private final Map<String, JsonElement> unknown = new LinkedHashMap<>();

    public StateCodec(CharterJson json) {
        this.json = json;
    }

    /** The codec with every M1 to M5 block, in reading order. */
    public static StateCodec standard(CharterJson json) {
        StateCodec codec = new StateCodec(json);
        CoreBlocks.register(codec);
        return codec;
    }

    public StateCodec register(StateBlock block) {
        for (StateBlock b : blocks) {
            if (b.key().equals(block.key())) throw new IllegalStateException("state block " + block.key() + " is registered twice");
        }
        blocks.add(block);
        return this;
    }

    /** Registers the step that turns a file of {@code fromVersion} into {@code fromVersion + 1}. */
    public StateCodec migration(int fromVersion, UnaryOperator<JsonObject> step) {
        migrations.put(fromVersion, step);
        return this;
    }

    public List<StateBlock> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    public JsonObject write(CharterState state) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        for (StateBlock block : blocks) root.add(block.key(), block.write(state, json.gson));
        unknown.forEach((key, value) -> {
            if (!root.has(key)) root.add(key, value);
        });
        return root;
    }

    /** Builds a new state from a file's root object, repairing what does not add up. */
    public CharterState read(JsonObject root, Knobs knobs, Repairs repairs) {
        int version = root.has("formatVersion") ? root.get("formatVersion").getAsInt() : 0;
        if (version > FORMAT_VERSION) {
            throw new JsonParseException("state.json is format " + version + ", newer than this jar's " + FORMAT_VERSION
                    + "; refusing to load it rather than lose what it holds");
        }
        while (version < FORMAT_VERSION) {
            UnaryOperator<JsonObject> step = migrations.get(version);
            if (step == null && version != 0) throw new JsonParseException("no migration from format " + version);
            if (step != null) root = step.apply(root);
            version++;
        }

        CharterState state = new CharterState();
        for (StateBlock block : blocks) {
            JsonElement element = root.get(block.key());
            if (element != null && !element.isJsonNull()) block.read(element, state, json.gson, repairs);
        }
        unknown.clear();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().equals("formatVersion")) continue;
            if (blocks.stream().noneMatch(b -> b.key().equals(e.getKey()))) unknown.put(e.getKey(), e.getValue());
        }
        Repair.run(state, knobs, repairs);
        return state;
    }

    /** Blocks found in the last file read that no feature claimed. */
    public Map<String, JsonElement> unknownBlocks() {
        return Collections.unmodifiableMap(unknown);
    }
}
