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
 * ({@code chests/tier_1_drowned} and its kin, and the matching
 * {@code vaults/tier_1_drowned}) is deliberately not in {@link #ALL}: it is
 * optional by design: {@link TrialContent#applyLoot}'s
 * {@code resolveLootTable} already falls back to the base table when a
 * themed one is not loaded, so a missing suffixed table is not a bug.
 */
final class LootTables {

    static final String TIER_1 = "chests/tier_1";
    static final String TIER_2 = "chests/tier_2";
    static final String TIER_3 = "chests/tier_3";
    static final String TIER_4 = "chests/tier_4";
    static final String TIER_1_OMINOUS = "chests/tier_1_ominous";
    static final String TIER_2_OMINOUS = "chests/tier_2_ominous";
    static final String TIER_3_OMINOUS = "chests/tier_3_ominous";
    static final String TIER_4_OMINOUS = "chests/tier_4_ominous";
    static final String SUPPLY_TIER_1 = "chests/supply_tier_1";
    static final String SUPPLY_TIER_2 = "chests/supply_tier_2";
    static final String SUPPLY_TIER_3 = "chests/supply_tier_3";
    static final String SUPPLY_TIER_4 = "chests/supply_tier_4";
    static final String VAULT_TIER_1 = "vaults/tier_1";
    static final String VAULT_TIER_2 = "vaults/tier_2";
    static final String VAULT_TIER_3 = "vaults/tier_3";
    static final String VAULT_TIER_4 = "vaults/tier_4";
    static final String VAULT_TIER_1_OMINOUS = "vaults/tier_1_ominous";
    static final String VAULT_TIER_2_OMINOUS = "vaults/tier_2_ominous";
    static final String VAULT_TIER_3_OMINOUS = "vaults/tier_3_ominous";
    static final String VAULT_TIER_4_OMINOUS = "vaults/tier_4_ominous";

    /** The highest loot tier: the netherite set. Tier 3 is the diamond set before it. */
    static final int MAX_TIER = 4;

    /** M25: the Pocket2 child's loose-chest table: shell tokens, fuel, valuables. */
    static final String POCKET2 = "chests/pocket2";

    /** M35: the anomaly room's loose-chest table: shell tokens, rare materials. */
    static final String ANOMALY = "chests/anomaly";

    /**
     * K3: the barter table that pays out when a piglin barters inside the
     * dungeon dimension. Vanilla's own {@code gameplay/piglin_bartering} is
     * untouched; {@code DungeonDrops} reroutes the roll per dimension.
     */
    static final String PIGLIN_BARTER = "gameplay/piglin_bartering";

    /**
     * M49: the bag tables of SITUATIONS_SPEC 3.2, one per archetype under
     * {@code bags/}. Listed in {@link #ALL} rather than treated as optional the
     * way the themed {@code _drowned} suffix is: a missing themed table falls
     * back to its base, but a missing bag table means a player walks into a run
     * carrying nothing at all, which is a worse failure than an empty chest and
     * has no fallback to find.
     *
     * <p>The paths come from {@link Bags}, which is where the ids live. That
     * direction is deliberate and one-way: nothing in {@code Bags}'s
     * construction may call back into this class, or the two class initialisers
     * read each other half-built.
     */
    static String bagTable(String bagId) {
        return "bags/" + bagId;
    }

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
                TIER_1, TIER_2, TIER_3, TIER_4,
                TIER_1_OMINOUS, TIER_2_OMINOUS, TIER_3_OMINOUS, TIER_4_OMINOUS,
                SUPPLY_TIER_1, SUPPLY_TIER_2, SUPPLY_TIER_3, SUPPLY_TIER_4,
                VAULT_TIER_1, VAULT_TIER_2, VAULT_TIER_3, VAULT_TIER_4,
                VAULT_TIER_1_OMINOUS, VAULT_TIER_2_OMINOUS, VAULT_TIER_3_OMINOUS,
                VAULT_TIER_4_OMINOUS,
                POCKET2, ANOMALY, PIGLIN_BARTER));
        all.addAll(Bags.paths());
        for (int tier = 1; tier <= MAX_TIER; tier++) {
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

    /**
     * The reward-chest table for this tier (clamped to 1-4) and ominous flag:
     * the loot-cell supply chests' key-gated sibling and the terminal cell's
     * three completion chests. Not the vault; see {@link #vaultTable}.
     */
    static String tierTable(int tier, boolean ominous) {
        return switch (clamp(tier)) {
            case 1 -> ominous ? TIER_1_OMINOUS : TIER_1;
            case 2 -> ominous ? TIER_2_OMINOUS : TIER_2;
            case 3 -> ominous ? TIER_3_OMINOUS : TIER_3;
            default -> ominous ? TIER_4_OMINOUS : TIER_4;
        };
    }

    /**
     * The vault table for this tier (clamped to 1-4) and ominous flag.
     *
     * <p>Deliberately not {@link #tierTable}, for the same reason
     * {@link #gearTable} is not: a vault is not a chest. A chest holds its roll
     * across 27 slots and the player takes what they want out of it; a vault
     * ejects every stack it rolls onto the floor as a separate item entity. The
     * chest tables guarantee 13 to 22 rolls, which reads fine in a container and
     * buries the room when it comes out of a vault, with the two stacks that
     * mattered lost somewhere in the dirt and saplings.
     *
     * <p>The vault tables pay the treasure a key deserves in a handful of
     * stacks: guaranteed gear, rare food, valuables and a trim shot, roughly
     * 3 to 6 stacks rather than 13 to 22. Under K the chests stopped carrying
     * gear at all, so there is nothing left to derive from:
     * {@code tools/gen_vault_tables.py} synthesizes each tier from an explicit
     * pool now. Rerun it after changing the vault policy.
     */
    static String vaultTable(int tier, boolean ominous) {
        return switch (clamp(tier)) {
            case 1 -> ominous ? VAULT_TIER_1_OMINOUS : VAULT_TIER_1;
            case 2 -> ominous ? VAULT_TIER_2_OMINOUS : VAULT_TIER_2;
            case 3 -> ominous ? VAULT_TIER_3_OMINOUS : VAULT_TIER_3;
            default -> ominous ? VAULT_TIER_4_OMINOUS : VAULT_TIER_4;
        };
    }

    /** The free, ungated supply-chest table for this tier (clamped to 1-4). */
    static String supplyTable(int tier) {
        return switch (clamp(tier)) {
            case 1 -> SUPPLY_TIER_1;
            case 2 -> SUPPLY_TIER_2;
            case 3 -> SUPPLY_TIER_3;
            default -> SUPPLY_TIER_4;
        };
    }

    private static int clamp(int tier) {
        return Math.max(1, Math.min(MAX_TIER, tier));
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
