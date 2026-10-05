package pocketdungeons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Dungeon structure W4 (design D17, D21): the pure rules of the darkness pass, no
 * Minecraft imports so a test runs them headless. {@link NodeStamper} applies them
 * to a stamped cell.
 *
 * <p>Three levels: {@code lit} (nothing removed), {@code dim} (every second light
 * source removed, by position, deterministically) and {@code dark} (every light
 * source removed). A floor's node can set a minimum darkness for every room on it
 * ({@link DungeonDef.Node#light()}); a room that {@code requiresLight} is never
 * darkened, by its own field or by the node.
 */
final class DungeonLight {

    private DungeonLight() {}

    private static int rank(String light) {
        if (DungeonRoomMeta.LIGHT_DARK.equals(light)) {
            return 2;
        }
        if (DungeonRoomMeta.LIGHT_DIM.equals(light)) {
            return 1;
        }
        return 0;
    }

    /**
     * The darkness a room is stamped with: the darker of its own {@code light} and
     * the node's, unless the room {@code requiresLight}, which is always lit.
     */
    static String effective(String roomLight, String nodeLight, boolean requiresLight) {
        if (requiresLight) {
            return DungeonRoomMeta.LIGHT_LIT;
        }
        return rank(nodeLight) > rank(roomLight)
                ? (nodeLight == null ? DungeonRoomMeta.LIGHT_LIT : nodeLight)
                : (roomLight == null ? DungeonRoomMeta.LIGHT_LIT : roomLight);
    }

    /**
     * Whether a block with registry path {@code path} (namespace dropped) is a
     * light source the pass removes: torches (wall and soul variants included),
     * lanterns (soul, copper and jack o' lantern included), glowstone, sea
     * lanterns and redstone lamps. A redstone torch is a mechanic, not a light, and
     * stays.
     */
    static boolean isLightSource(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith("redstone_")) {
            return path.equals("redstone_lamp");
        }
        return path.equals("glowstone")
                || path.equals("shroomlight")
                || path.endsWith("torch")
                || path.endsWith("lantern");
    }

    /**
     * Whether the block is a full cube that must be replaced by a solid neighbour
     * when removed (a ceiling lamp), rather than by air (a torch or a lantern).
     */
    static boolean isSolidLight(String path) {
        return "glowstone".equals(path) || "sea_lantern".equals(path) || "shroomlight".equals(path)
                || "jack_o_lantern".equals(path) || "redstone_lamp".equals(path);
    }

    /**
     * Which of {@code lights} a {@code dim} cell loses: sorted by x, then y, then z,
     * every second one (the 2nd, 4th and so on) is removed, so half remain and the
     * same cell always dims the same way. Each entry is {@code {x, y, z}}.
     */
    static List<int[]> dimRemovals(List<int[]> lights) {
        List<int[]> sorted = new ArrayList<>(lights);
        sorted.sort(Comparator.<int[]>comparingInt(p -> p[0])
                .thenComparingInt(p -> p[1]).thenComparingInt(p -> p[2]));
        List<int[]> removed = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i += 2) {
            removed.add(sorted.get(i));
        }
        return removed;
    }

    /**
     * The lights a cell of {@code level} (a {@code DungeonRoomMeta} light value)
     * loses out of {@code lights}: all of them dark, every second one dim, none lit.
     */
    static List<int[]> removals(String level, List<int[]> lights) {
        if (DungeonRoomMeta.LIGHT_DARK.equals(level)) {
            return new ArrayList<>(lights);
        }
        if (DungeonRoomMeta.LIGHT_DIM.equals(level)) {
            return dimRemovals(lights);
        }
        return List.of();
    }

    /**
     * The Feral affix is not dealt on a dark node: returns {@code affixes} without
     * every affix {@code isFeral} accepts when {@code nodeLight} is {@code dark},
     * otherwise {@code affixes} itself.
     */
    static Set<String> withoutFeralOnDark(Set<String> affixes, String nodeLight, Predicate<String> isFeral) {
        if (!DungeonRoomMeta.LIGHT_DARK.equals(nodeLight) || affixes == null || affixes.isEmpty()) {
            return affixes;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String id : affixes) {
            if (!isFeral.test(id)) {
                kept.add(id);
            }
        }
        return kept.size() == affixes.size() ? affixes : kept;
    }
}
