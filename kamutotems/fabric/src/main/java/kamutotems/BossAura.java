package kamutotems;

import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import kamutotems.core.Polarity;
import kamutotems.core.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Picks the boss's aura from its carried kamu, seeded for reproducibility.
 *
 * <p>{@code wither} is tier-capped via {@code boss.wither_aura_cap} so the
 * aura never becomes anti-heal without an upper bound (PLAN_COMBAT L24).
 */
public final class BossAura {

    private BossAura() {}

    public static AuraSpec forBoss(int tier, List<Kamu> carried, long seed, KamuCatalog catalog) {
        if (carried == null || carried.isEmpty()) {
            return AuraSpec.none();
        }

        List<Kamu> modifiers = new ArrayList<>();
        for (Kamu k : carried) {
            if (k != null && k.polarity() != Polarity.NONE) {
                modifiers.add(k);
            }
        }
        if (modifiers.isEmpty()) {
            return AuraSpec.none();
        }

        Random r = new Random(seed);
        AuraKind[] kinds = AuraKind.values();
        AuraKind kind = kinds[r.nextInt(kinds.length)];
        Kamu modifier = modifiers.get(r.nextInt(modifiers.size()));

        int auraTier = Math.min(tier, Slot.MAX_TIER);
        if ("wither".equals(modifier.id())) {
            int cap = KamuTotemsConfig.i("boss", "wither_aura_cap", 1);
            auraTier = Math.min(auraTier, cap);
        }

        return new AuraSpec(kind, modifier.id(), auraTier);
    }
}
