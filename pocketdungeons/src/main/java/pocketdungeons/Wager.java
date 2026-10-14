package pocketdungeons;

import java.util.Collection;
import java.util.List;

/**
 * The wager of a floor (docs/wager-draft-2026-10-12.md): the sum of what its affixes wager, and the words the
 * player reads for it. No Minecraft imports, so {@code WagerTest} runs with plain {@code javac}.
 *
 * <p>The player never sees the raw number; the door board and the floor title show it as a loot percentage,
 * {@code 100 + perPoint x wager} ("Loot 200%"). A floor with no affixes shows "Loot 100%". The mechanics behind
 * the number are {@link WagerGear}.
 */
final class Wager {

    /** Percent the display starts from: a floor with no wager reads "Loot 100%". */
    static final int BASE_PERCENT = 100;

    private Wager() {}

    /** The sum of the wagers of {@code ids}; an id with no definition (a removed pack) counts 0. */
    static int sum(Collection<String> ids, List<AffixDefinition> definitions) {
        int total = 0;
        if (ids == null) {
            return 0;
        }
        for (String id : ids) {
            AffixDefinition def = find(id, definitions);
            if (def != null) {
                total += def.wager;
            }
        }
        return total;
    }

    /** The lives the affixes in {@code ids} take when the door is committed (Blood), summed. */
    static int lifeCost(Collection<String> ids, List<AffixDefinition> definitions) {
        int total = 0;
        if (ids == null) {
            return 0;
        }
        for (String id : ids) {
            AffixDefinition def = find(id, definitions);
            if (def != null) {
                total += def.effects.lifeCost;
            }
        }
        return total;
    }

    /** The loot percentage shown for a wager: {@code 100 + perPoint x wager}. */
    static int lootPercent(int wager, int perPoint) {
        return BASE_PERCENT + Math.max(0, wager) * Math.max(0, perPoint);
    }

    /** {@code "Loot 200%"}: the line the door board and the floor title show. */
    static String lootText(int wager, int perPoint) {
        return "Loot " + lootPercent(wager, perPoint) + "%";
    }

    /**
     * The rolls a cleared floor pays: {@code baseChests} scaled by its wager's
     * loot percent, rounded half up. One loot system (PD-207): the percent on
     * the board is the multiplier the barrel pays.
     */
    static int scaledChests(int baseChests, int wager, int perPoint) {
        return Math.round(baseChests * lootPercent(wager, perPoint) / 100f);
    }

    private static AffixDefinition find(String id, List<AffixDefinition> definitions) {
        if (id == null || definitions == null) {
            return null;
        }
        for (AffixDefinition def : definitions) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }
}
