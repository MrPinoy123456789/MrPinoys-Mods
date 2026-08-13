package wayfarers.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Weighted encounter selection with §5 filters.
 *
 * <p>Templates the current build does not know are silently skipped rather
 * than thrown, and a weight of zero disables an entry.
 */
public final class EncounterPool {

    private final List<EncounterDefinition> definitions;
    private final Set<String> knownTemplates;

    public EncounterPool(List<EncounterDefinition> definitions, Set<String> knownTemplates) {
        this.definitions = definitions == null ? List.of() : List.copyOf(definitions);
        this.knownTemplates = knownTemplates == null ? Set.of() : knownTemplates;
    }

    public List<EncounterDefinition> definitions() {
        return definitions;
    }

    /**
     * Picks one encounter, or returns {@code null} if nothing matches.
     */
    public EncounterDefinition select(TraderRecord record, String biome, boolean night, String weather, Random random) {
        List<EncounterDefinition> candidates = new ArrayList<>();
        int total = 0;
        for (EncounterDefinition d : definitions) {
            if (d.weight() <= 0) continue;
            if (!knownTemplates.contains(d.template())) continue;
            if (d.once() && record != null && record.marvelsSeen().contains(d.id())) continue;
            if (!d.biomes().isEmpty() && !d.biomes().contains(biome)) continue;
            if (d.nightOnly() && !night) continue;
            if (!d.weather().isEmpty() && !d.weather().contains(weather)) continue;
            candidates.add(d);
            total += d.weight();
        }
        if (candidates.isEmpty() || total <= 0) return null;

        int pick = random.nextInt(total);
        int sum = 0;
        for (EncounterDefinition d : candidates) {
            sum += d.weight();
            if (pick < sum) return d;
        }
        return candidates.get(candidates.size() - 1);
    }
}
