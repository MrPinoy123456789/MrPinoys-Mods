package foundry.core.mechanics;

import java.util.Arrays;
import java.util.List;

/** When a mechanic runs. A closed set; definitions can only name these. */
public enum Trigger {
    ON_USE("on_use"),
    ON_HIT_ENTITY("on_hit_entity"),
    /** Left click: a swing that starts from rest, with the item in the main hand. Not a melee hit and not mining. */
    ON_SWING("on_swing"),
    /** A bolt launched by this item landed on a living entity. The mechanic's {@code tag} picks which bolt. */
    ON_BOLT_HIT("on_bolt_hit"),
    /**
     * A parry opened by this item's {@code parry} action stopped a melee hit or a projectile. The attacker is the entity
     * struck ({@code target "hit"}); it runs only when there is a living attacker close enough to answer.
     */
    ON_PARRY("on_parry"),
    /** Once a second while the item is worn in a worn slot (see worn_slots); it carries no hit and no cooldown of its own. */
    WHILE_WORN("while_worn");

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
