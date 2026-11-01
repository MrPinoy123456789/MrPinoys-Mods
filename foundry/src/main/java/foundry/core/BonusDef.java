package foundry.core;

import java.util.List;

/**
 * A gathering bonus: a free level of Fortune (blocks) or Looting (mobs) for players who hold a permission node, such
 * as a race. Pure data; the game side reads it when a block breaks or a mob dies.
 *
 * @param id         the definition's own id
 * @param permission the node a player must hold, for example {@code race:azrak}
 * @param kind       FORTUNE applies to broken blocks, LOOTING to killed mobs
 * @param level      the free levels the bonus adds to the player's tool or weapon
 * @param cap        the highest total level the bonus may bring the player to (3 matches vanilla Fortune III)
 * @param targets    block or entity ids, or {@code #tag} ids, the bonus applies to
 * @param onlyItems  when not empty, only extra drops of these item ids are granted
 */
public record BonusDef(Ident id, String permission, Kind kind, int level, int cap, List<String> targets,
        List<String> onlyItems) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_LEVEL = 5;

    public enum Kind {
        FORTUNE("fortune"), LOOTING("looting");

        public final String id;

        Kind(String id) {
            this.id = id;
        }

        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }
}
