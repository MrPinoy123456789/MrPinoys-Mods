package pocketdungeons;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

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
 * (plans/COMPLETED-MILESTONES.md M9 C4). The themed {@code lootSuffix} variant
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

    /**
     * The equipment slots M13's gear pool is keyed by, and therefore the slots
     * M16's gamble can be asked to roll. {@code weapon} is one slot rather than
     * one per weapon type: a player gambling for "a weapon" wants a weapon, and
     * splitting sword/axe/bow into three would triple the tables to say the
     * same thing.
     */
    static final List<String> GEAR_SLOTS = List.of("helmet", "chestplate", "leggings", "boots", "weapon");

    private static final List<String> ALL = buildAll();

    private static List<String> buildAll() {
        List<String> all = new java.util.ArrayList<>(List.of(
                TIER_1, TIER_2, TIER_3,
                TIER_1_OMINOUS, TIER_2_OMINOUS, TIER_3_OMINOUS,
                SUPPLY_TIER_1, SUPPLY_TIER_2, SUPPLY_TIER_3));
        for (int tier = 1; tier <= 3; tier++) {
            for (String slot : GEAR_SLOTS) {
                all.add(gearTable(slot, tier));
            }
        }
        return List.copyOf(all);
    }

    private LootTables() {}

    /**
     * The slot-keyed gear table for one slot at one tier (M13).
     *
     * <p>Deliberately separate from the {@code chests/tier_*} tables even
     * though both carry the same gear entries: vanilla loot tables have no
     * inheritance, so the duplication is the mechanism, and keeping them apart
     * is what stops M16's gamble from out-producing a run's own chests. A
     * gamble draw is one piece of one slot; a chest roll is whatever the run
     * happened to give you.
     */
    static String gearTable(String slot, int tier) {
        return "gear/" + slot + "_" + clamp(tier);
    }

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
     * Whether {@code key} resolves against the server's loot tables.
     *
     * <p><strong>Not {@code level.registryAccess()}.</strong> Loot tables are a
     * reloadable, datapack-driven registry (like item modifiers and predicates),
     * held on {@link MinecraftServer#reloadableRegistries()}'s
     * {@code ReloadableServerRegistries.Holder}, not on the frozen dynamic
     * registry manager a {@code ServerLevel} exposes through
     * {@code registryAccess()}. The two both being a
     * {@code Registries.LOOT_TABLE}-keyed lookup made this an easy mistake to
     * make and a crash to reproduce: {@code level.registryAccess()
     * .lookupOrThrow(Registries.LOOT_TABLE)} throws {@code IllegalStateException:
     * Missing registry} unconditionally, because that registry key is never
     * present there at all. Also has nothing to do with which dimension is
     * asking, unlike the field this replaces suggested with its {@code level}
     * parameter; loot tables are server-wide.
     */
    static boolean exists(MinecraftServer server, ResourceKey<LootTable> key) {
        return server.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
                .map(lookup -> lookup.get(key).isPresent())
                .orElse(false);
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
        int missing = 0;
        for (String path : ALL) {
            Identifier id = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path);
            if (!exists(server, ResourceKey.create(Registries.LOOT_TABLE, id))) {
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
