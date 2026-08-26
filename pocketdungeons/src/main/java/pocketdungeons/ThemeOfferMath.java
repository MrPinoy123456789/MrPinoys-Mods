package pocketdungeons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Deterministic theme dealing for the three keystone doors. */
final class ThemeOfferMath {

    private ThemeOfferMath() {}

    static List<String> pick(UUID owner, int level, List<String> discoverableThemes) {
        if (discoverableThemes == null || discoverableThemes.isEmpty()) {
            return List.of();
        }
        List<String> pool = new ArrayList<>(discoverableThemes);
        Collections.sort(pool);
        Collections.shuffle(pool, new Random(AffixMath.seed(owner, level)));
        List<String> offers = new ArrayList<>(3);
        for (int step = 0; step < 3; step++) {
            offers.add(pool.get(step % pool.size()));
        }
        return List.copyOf(offers);
    }
}
