package pocketdungeons;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Named constants for the loot tables {@link TrialContent} resolves by tier
 * and ominous flag, plus a startup check that every one of them actually
 * exists in the registry.
 *
 * <p>Before this class, {@code TrialContent} built these paths by string
 * concatenation at three call sites ({@code applyLoot}'s vault and supply
 * chests, {@code placeCompletionChests}) and never checked the result
 * resolved to anything. A typo or a deleted table surfaced as a silently
 * empty chest at play time, with no log line anywhere pointing at the cause
 * (plans/M9-cleanup.md C4). The themed {@code lootSuffix} variant
 * ({@code chests/tier_1_drowned} and its kin) is deliberately not in
 * {@link #ALL}: it is optional by design -- {@link TrialContent#applyLoot}'s
 * {@code resolveLootTable} already falls back to the base table when a
 * themed one is not loaded -- so a missing suffixed table is not a bug.
 */
final class LootTables {

    static final String TIER_1 = "chests/tier_1";
    static final String TIER_2 = "chests/tier_2";
    static final String TIER_3 = "chests/tier_3";
    static final String TIER_1_OMINOUS = "chests/tier_1_ominous";
    static final String TIER_2_OMINOUS = "chests/tier_2_ominous";
    static final String TIER_3_OMINOUS = "chests/tier_3_ominous";
    static final String SUPPLY_TIER_1 = "chests/supply_tier_1";
    static final String SUPPLY_TIER_2 = "chests/supply_tier_2";
    static final String SUPPLY_TIER_3 = "chests/supply_tier_3";

    private static final List<String> ALL = List.of(
            TIER_1, TIER_2, TIER_3,
            TIER_1_OMINOUS, TIER_2_OMINOUS, TIER_3_OMINOUS,
            SUPPLY_TIER_1, SUPPLY_TIER_2, SUPPLY_TIER_3);

    private LootTables() {}

    /** The vault/reward-chest table for this tier (clamped to 1-3) and ominous flag. */
    static String tierTable(int tier, boolean ominous) {
        return switch (clamp(tier)) {
            case 1 -> ominous ? TIER_1_OMINOUS : TIER_1;
            case 2 -> ominous ? TIER_2_OMINOUS : TIER_2;
            default -> ominous ? TIER_3_OMINOUS : TIER_3;
        };
    }

    /** The free, ungated supply-chest table for this tier (clamped to 1-3). */
    static String supplyTable(int tier) {
        return switch (clamp(tier)) {
            case 1 -> SUPPLY_TIER_1;
            case 2 -> SUPPLY_TIER_2;
            default -> SUPPLY_TIER_3;
        };
    }

    private static int clamp(int tier) {
        return Math.max(1, Math.min(3, tier));
    }

    /**
     * Logs an {@code ERROR} for every table in {@link #ALL} that does not
     * resolve in the registry, instead of leaving the first player to hit it
     * with a silently empty chest. Run once, at {@code SERVER_STARTED}
     * (alongside the other startup loads in {@code Instances.register}), and
     * again whenever a {@code /reload} lands, for the same reason
     * {@code RoomManifest}'s rejections are re-checked there.
     */
    static void validateAtStartup(MinecraftServer server) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.LOOT_TABLE);
        int missing = 0;
        for (String path : ALL) {
            Identifier id = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path);
            if (registry.getValue(id) == null) {
                PocketDungeonsMod.LOG.error(
                        "Loot table {} is missing; whatever chest or vault would draw from it "
                                + "will come up empty with no other warning", id);
                missing++;
            }
        }
        if (missing == 0) {
            PocketDungeonsMod.LOG.info("All {} core loot tables verified present", ALL.size());
        }
    }
}
