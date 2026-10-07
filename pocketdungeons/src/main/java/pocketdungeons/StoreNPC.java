package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M35 store anomaly: a shopkeeper villager that spawns in the store anomaly
 * room. J4 (D36): every trade is a plain vanilla {@link MerchantOffer} on the
 * villager itself, so the vanilla trading screen handles stock, delivery,
 * the result slot and shift-clicking; there is no SGUI screen and no click
 * handler (the PD-159 one-row click shop is superseded).
 *
 * <h2>Why a villager, not a custom entity</h2>
 *
 * <p>The mod is server-side only, so a tagged vanilla villager is the pattern
 * that works without a client component. The villager stands where it spawns
 * ({@code setNoAi}, PD-141: a free villager wandered into the next room) but
 * still turns to face a customer, is invincible, and does not attract
 * monsters.
 *
 * <h2>Stock</h2>
 *
 * <p>The inventory is rolled from the category pools (common, utility,
 * combat, rare, trimmed to {@link StorePricing#MAX_SLOTS}) when the room is
 * stamped, and written onto the villager with {@code setOffers}, which the
 * villager's own saved data persists across chunk reloads. A sell line
 * takes emeralds and carries {@code maxUses} equal to its stock; a buy line
 * takes a bundle of the merchant's themed drops and pays emeralds
 * ({@link StorePricing#buyBundle}). Every offer has {@code xp} 0 and
 * {@code priceMultiplier} 0 and the villager is pinned to max level, so no
 * trade ever unlocks, drifts with demand or earns a gossip discount.
 *
 * <h2>No mob attraction</h2>
 *
 * <p>The villager is tagged so hostile mobs do not target it. Vanilla
 * zombies do target villagers, but the dungeon's mobs are spawned via trial
 * spawners which target players, not villagers. The ALLOW_DAMAGE hook
 * ensures nothing can kill the shopkeeper regardless.
 */
final class StoreNPC {

    static final String STORE_TAG = "pocketdungeons_store";

    private StoreNPC() {}

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }
            if (!villager.entityTags().contains(STORE_TAG)) {
                return InteractionResult.PASS;
            }
            if (player.getItemInHand(hand).is(Items.NAME_TAG)) {
                return InteractionResult.PASS;
            }
            // Face the customer; the click itself falls through to vanilla's
            // own villager interact, which opens the trading screen.
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            villager.setYHeadRot(villager.getYRot());
            villager.setYBodyRot(villager.getYRot());
            return InteractionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof Villager villager)
                        || !villager.entityTags().contains(STORE_TAG));
    }

    /**
     * Spawns a shopkeeper villager in the store anomaly room. Its offers are
     * rolled from the category pools plus the merchant's buy list and written
     * with {@code setOffers}, which the villager's own saved data persists.
     */
    static void spawn(ServerLevel level, BlockPos cellOrigin, long seed, String theme) {
        BlockPos centre = cellOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        // Find a safe floor position near the centre
        BlockPos spawnPos = centre;
        while (!level.getBlockState(spawnPos.below()).isAir()
                && level.getBlockState(spawnPos).isAir()
                && spawnPos.getY() > cellOrigin.getY()) {
            spawnPos = spawnPos.below();
        }
        // If the centre is blocked, try a spot near the counter
        if (!level.getBlockState(spawnPos).isAir() || !level.getBlockState(spawnPos.above()).isAir()) {
            spawnPos = cellOrigin.offset(RoomGeometry.CELL / 2, 1, 4);
        }

        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        if (villager == null) {
            PocketDungeonsMod.LOG.warn("Failed to create store villager at {}", cellOrigin);
            return;
        }

        villager.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.TOOLSMITH)
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.addTag(STORE_TAG);
        villager.setPersistenceRequired();
        villager.setInvulnerable(true);
        // PD-141: a free villager wandered into the next room. The shopkeeper
        // stands where it spawns and still turns to face a customer.
        villager.setNoAi(true);
        MerchantThemes.Merchant merchant = MerchantThemes.forTheme(theme);
        villager.setCustomName(Component.literal(merchant.title()));
        villager.setCustomNameVisible(true);

        RandomSource rng = RandomSource.create(seed ^ cellOrigin.asLong());
        // setOffers writes the field directly; AbstractVillager.overrideOffers
        // is an empty extension stub in 26.2, and a null offers field lazily
        // regenerates vanilla profession trades on the first getOffers.
        villager.setOffers(buildOffers(rng, merchant));

        level.addFreshEntity(villager);
    }

    /**
     * The villager's offer list: one sell line per rolled stock entry
     * (emeralds in, the item out, {@code maxUses} = the stock), then one buy
     * line per drop the merchant takes ({@link StorePricing#buyBundle} at
     * full rate). Package private so a game test can inspect the offers a
     * merchant would carry without spawning one.
     */
    static MerchantOffers buildOffers(RandomSource rng, MerchantThemes.Merchant merchant) {
        MerchantOffers offers = new MerchantOffers();
        for (ShopEntry entry : rollInventory(rng, merchant)) {
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, entry.price()),
                    entry.stack(), entry.stock(), 0, 0f));
        }
        for (MerchantThemes.Currency currency : merchant.currencies()) {
            if (StorePricing.NEVER_BUY.contains(BuiltInRegistries.ITEM.getKey(currency.item()).toString())) {
                continue;
            }
            int[] bundle = StorePricing.buyBundle(currency.perEmerald(), 1.0);
            // Buying is a surplus sink with no stock: maxUses is effectively
            // unbounded and a NoAi villager never restocks anyway.
            offers.add(new MerchantOffer(new ItemCost(currency.item(), bundle[0]),
                    new ItemStack(Items.EMERALD, bundle[1]), Integer.MAX_VALUE, 0, 0f));
        }
        return offers;
    }

    // ---- inventory rolling ----

    /** One sell line: the item, its emerald price, how many the merchant has. */
    record ShopEntry(Item item, int price, int stock) {
        ItemStack stack() {
            return new ItemStack(item);
        }
    }

    /**
     * Rolls a randomized inventory from the pool groups: common supplies 2 to
     * 3 slots, utility 1 to 2, combat 1 to 2, rare 0 to 1, trimmed to the
     * lines the merchant shows ({@link StorePricing#composition}). Within a
     * group each slot draws a pool at random, then an item the store does not
     * already sell, module gated items aside
     * ({@link ContentModules#merchantItemEnabled}), and never the merchant's
     * own buy drop. Seeded, so a seed reproduces the same store.
     */
    private static List<ShopEntry> rollInventory(RandomSource rng, MerchantThemes.Merchant merchant) {
        boolean[] rolls = {rng.nextBoolean(), rng.nextInt(5) < 2, rng.nextInt(5) < 2, rng.nextBoolean()};
        int[] counts = StorePricing.composition(rolls);

        Set<String> taken = new HashSet<>();
        for (MerchantThemes.Currency currency : merchant.currencies()) {
            taken.add(BuiltInRegistries.ITEM.getKey(currency.item()).toString());
        }
        List<ShopEntry> entries = new ArrayList<>();
        fill(entries, COMMON, counts[0], rng, taken);
        fill(entries, UTILITY, counts[1], rng, taken);
        fill(entries, COMBAT, counts[2], rng, taken);
        fill(entries, RARE, counts[3], rng, taken);
        return entries;
    }

    private static void fill(List<ShopEntry> out, List<Pool> group, int count, RandomSource rng,
                             Set<String> taken) {
        for (int i = 0; i < count; i++) {
            // A few redraws: a small pool can be exhausted by earlier slots.
            for (int attempt = 0; attempt < 8; attempt++) {
                Pool pool = group.get(rng.nextInt(group.size()));
                PoolEntry chosen = pool.entries().get(rng.nextInt(pool.entries().size()));
                String id = BuiltInRegistries.ITEM.getKey(chosen.item()).toString();
                if (!taken.add(id) || !ContentModules.merchantItemEnabled(id)) {
                    continue;
                }
                out.add(new ShopEntry(chosen.item(), Math.max(1, pool.basePrice() + chosen.priceMod()),
                        rng.nextIntBetweenInclusive(pool.minStock(), pool.maxStock())));
                break;
            }
        }
    }

    /** An item for sale; {@code priceMod} shifts the pool's base emerald price. */
    private record PoolEntry(Item item, int priceMod) {}

    /** A pool of items sold at one base price (in emeralds) and a stock range. */
    private record Pool(List<PoolEntry> entries, int basePrice, int minStock, int maxStock) {}

    // ---- item pools ----

    // Stock follows the K and K2 loot lists: iron and diamond gear only, food
    // is the two staples, blocks are the building set a chest may pay.
    private static final List<PoolEntry> ARMOUR_POOL = List.of(
            new PoolEntry(Items.IRON_HELMET, 2),
            new PoolEntry(Items.IRON_CHESTPLATE, 5),
            new PoolEntry(Items.IRON_LEGGINGS, 4),
            new PoolEntry(Items.IRON_BOOTS, 2),
            new PoolEntry(Items.DIAMOND_HELMET, 8),
            new PoolEntry(Items.DIAMOND_BOOTS, 6));

    private static final List<PoolEntry> WEAPON_POOL = List.of(
            new PoolEntry(Items.IRON_SWORD, 2),
            new PoolEntry(Items.IRON_AXE, 1),
            new PoolEntry(Items.BOW, 2),
            new PoolEntry(Items.CROSSBOW, 3),
            new PoolEntry(Items.STONE_SWORD, 0),
            new PoolEntry(Items.SHIELD, 1));

    private static final List<PoolEntry> BLOCK_POOL = List.of(
            new PoolEntry(Items.OAK_PLANKS, 0),
            new PoolEntry(Items.COBBLESTONE, 0),
            new PoolEntry(Items.SAND, 0),
            new PoolEntry(Items.GRAVEL, 0),
            new PoolEntry(Items.OAK_LOG, 1),
            new PoolEntry(Items.TORCH, -1),
            new PoolEntry(Items.OBSIDIAN, 3));

    private static final List<PoolEntry> FOOD_POOL = List.of(
            new PoolEntry(Items.BREAD, 0),
            new PoolEntry(Items.COOKED_BEEF, 1),
            new PoolEntry(Items.CAKE, 4),
            new PoolEntry(Items.GOLDEN_APPLE, 5));

    private static final List<PoolEntry> UTILITY_POOL = List.of(
            new PoolEntry(Items.WATER_BUCKET, 3),
            new PoolEntry(Items.FLINT_AND_STEEL, 2),
            new PoolEntry(Items.SHEARS, 1),
            new PoolEntry(Items.FISHING_ROD, 1),
            new PoolEntry(Items.COMPASS, 1),
            new PoolEntry(Items.CLOCK, 1),
            new PoolEntry(Items.SPYGLASS, 2),
            new PoolEntry(Items.BUCKET, 1),
            new PoolEntry(Items.ENDER_PEARL, 4));

    private static final List<PoolEntry> VALUABLE_POOL = List.of(
            new PoolEntry(Items.IRON_INGOT, 0),
            new PoolEntry(Items.GOLD_INGOT, 1),
            new PoolEntry(Items.DIAMOND, 5),
            new PoolEntry(Items.LAPIS_LAZULI, 0),
            new PoolEntry(Items.GUNPOWDER, 1),
            new PoolEntry(Items.NETHERITE_INGOT, 8),
            new PoolEntry(Items.EXPERIENCE_BOTTLE, 2),
            // Alchemy module only: merchantStock claims it, so the line is
            // skipped while the module is off.
            new PoolEntry(Items.NETHER_WART, 3));

    // ---- pool groups (declared after the pools they hold; static init runs in order) ----

    private static final List<Pool> COMMON = List.of(
            new Pool(FOOD_POOL, 2, 4, 8),
            new Pool(BLOCK_POOL, 4, 16, 32),
            new Pool(List.of(new PoolEntry(Items.ARROW, 0)), 2, 8, 16));
    private static final List<Pool> UTILITY = List.of(new Pool(UTILITY_POOL, 3, 1, 2));
    private static final List<Pool> COMBAT = List.of(
            new Pool(ARMOUR_POOL, 1, 1, 1),
            new Pool(WEAPON_POOL, 1, 1, 1));
    private static final List<Pool> RARE = List.of(new Pool(VALUABLE_POOL, 5, 1, 4));
}
