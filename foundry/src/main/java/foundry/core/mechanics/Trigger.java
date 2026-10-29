package foundry.core.mechanics;

import java.util.Arrays;
import java.util.List;

/** When a mechanic runs. A closed set; definitions can only name these. */
public enum Trigger {
    ON_USE("on_use"),
    ON_HIT_ENTITY("on_hit_entity");

    public final String id;

    Trigger(String id) {
        this.id = id;
    }

    public static Trigger byId(String id) {
        for (Trigger t : values()) if (t.id.equals(id)) return t;
        return null;
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(t -> t.id).toList();
    }
}
