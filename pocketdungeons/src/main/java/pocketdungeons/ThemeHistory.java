package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/** Pure history window operations used by the persistent dungeon log. */
final class ThemeHistory {

    static final int RECENT_LIMIT = 3;

    private ThemeHistory() {}

    static List<String> push(List<String> recent, String theme) {
        if (theme == null || theme.isBlank()) {
            return recent == null ? List.of() : List.copyOf(recent);
        }
        List<String> next = new ArrayList<>(recent == null ? List.of() : recent);
        next.add(theme);
        if (next.size() > RECENT_LIMIT) {
            next = new ArrayList<>(next.subList(next.size() - RECENT_LIMIT, next.size()));
        }
        return List.copyOf(next);
    }
}
