package kamutotems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public record BossRoll(int tier, List<String> kamuIds) {

    public static BossRoll forSeed(long seed, int tier, KamuCatalog catalog) {
        if (catalog == null) {
            return new BossRoll(tier, List.of());
        }
        if (tier < 1 || tier > 4) {
            return new BossRoll(tier, List.of());
        }

        List<Kamu> pool = catalog.bossPool();
        if (pool.isEmpty()) {
            return new BossRoll(tier, List.of());
        }

        Random r = new Random(seed);
        int count = (tier == 1) ? (1 + r.nextInt(2)) : tier;
        if (count > pool.size()) {
            count = pool.size();
        }

        List<Integer> indices = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            indices.add(i);
        }

        List<String> picked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = r.nextInt(indices.size());
            picked.add(pool.get(indices.remove(idx)).id());
        }

        return new BossRoll(tier, List.copyOf(picked));
    }

    public static BossRoll forDate(String dateKey, long serverSeed, KamuCatalog catalog) {
        long seed = 31L * (dateKey == null ? 0 : dateKey.hashCode()) + serverSeed;
        return forSeed(seed, 1, catalog);
    }

    public String dropKamu(long seed) {
        if (kamuIds == null || kamuIds.isEmpty()) {
            return null;
        }
        Random r = new Random(seed);
        return kamuIds.get(r.nextInt(kamuIds.size()));
    }
}
