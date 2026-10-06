package pocketdungeons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The pure half of dealing affixes to a door (design item 3, no identical doors).
 * No Minecraft imports, so {@code DoorAffixesTest} runs with plain {@code javac}.
 *
 * <p>Doors behind one edge lead to the same floor. Variant 0 is today's set:
 * {@link AffixMath#effective} (the level's seeded affixes plus the door's own),
 * finished by {@code finish} (the node's dark-floor rule). A later copy draws its
 * seeded affixes again with {@link AffixMath#seededFor(UUID, int, List, long)} under
 * a salt, retrying salts until the finished set differs from every earlier copy's,
 * up to {@link #MAX_ATTEMPTS}; the last try stands if none differs. The count is
 * whatever the level gives, so under a level that seeds none (compass 5) every copy
 * keeps the same empty set and the doors differ by step only.
 */
final class DoorAffixes {

    /** Most salts tried for one copy. */
    static final int MAX_ATTEMPTS = 16;

    private DoorAffixes() {}

    /**
     * The final affix set for the door of {@code variant} on {@code dungeonId}'s
     * {@code nodeId}. {@code offered} is the door's own affixes (the node's signature).
     * {@code pathLength} comes from the door so a preview and its commit agree.
     */
    static Set<String> deal(UUID owner, int level, Set<String> offered, List<AffixDefinition> definitions,
                            String dungeonId, String nodeId, int variant, int pathLength,
                            UnaryOperator<Set<String>> finish) {
        Set<String> first = finished(AffixMath.effective(owner, level, offered, definitions), finish);
        if (variant <= 0) {
            return first;
        }
        List<Set<String>> earlier = new ArrayList<>();
        earlier.add(first);
        Set<String> last = first;
        for (int copy = 1; copy <= variant; copy++) {
            last = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                long salt = TripDoors.seed(owner, dungeonId, nodeId, pathLength, 10 + attempt);
                Set<String> candidate = AffixMath.seededFor(owner, level, definitions, salt);
                if (offered != null) {
                    candidate.addAll(offered);
                }
                candidate = finished(candidate, finish);
                last = candidate;
                if (!earlier.contains(candidate)) {
                    break;
                }
            }
            earlier.add(last);
        }
        return last;
    }

    private static Set<String> finished(Set<String> set, UnaryOperator<Set<String>> finish) {
        Set<String> out = finish == null ? set : finish.apply(set);
        return new LinkedHashSet<>(out == null ? Set.of() : out);
    }
}
