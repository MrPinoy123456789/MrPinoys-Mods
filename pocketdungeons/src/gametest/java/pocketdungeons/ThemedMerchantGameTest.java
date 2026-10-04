package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Themed store merchants: the merchant follows the floor's theme, prices its
 * stock in that floor's mob drops, and a theme without a merchant keeps the
 * original emerald-only shopkeeper. Reads the villager's saved inventory tag,
 * the same string the shop GUI loads, rather than opening the GUI (nothing
 * headless clicks an SGUI chest).
 */
@SuppressWarnings("removal")
public final class ThemedMerchantGameTest {

    private static final String INVENTORY_PREFIX = "pocketdungeons_store_inv:";

    @GameTest
    public void anOssuaryFloorHostsABoneCollectorPricedInBones(GameTestHelper helper) {
        int bonePriced = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "pocketdungeons:ossuary", seed);
            helper.assertValueEqual(villager.getName().getString(), "Bone Collector", "merchant for ossuary");
            for (String currency : currencies(villager)) {
                helper.assertTrue(currency.equals("minecraft:bone") || currency.equals("minecraft:emerald"),
                        "an ossuary merchant prices in bones or emeralds, saw " + currency);
                if (currency.equals("minecraft:bone")) {
                    bonePriced++;
                }
            }
            villager.discard();
        }
        helper.assertTrue(bonePriced > 0, "twelve ossuary stores priced at least one item in bones");
        helper.succeed();
    }

    @GameTest
    public void aNetherFloorHostsANetherMerchantThatTakesBlazeRodsAndMagmaCream(GameTestHelper helper) {
        for (long seed = 1; seed <= 12; seed++) {
            Villager villager = spawnStore(helper, "basalt_foundry", seed);
            helper.assertValueEqual(villager.getName().getString(), "Nether Merchant", "merchant for basalt foundry");
            for (String currency : currencies(villager)) {
                helper.assertTrue(currency.equals("minecraft:blaze_rod")
                                || currency.equals("minecraft:magma_cream")
                                || currency.equals("minecraft:emerald"),
                        "a nether merchant prices in its floor's drops or emeralds, saw " + currency);
            }
            villager.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void aThemeWithoutAMerchantKeepsTheEmeraldShopkeeper(GameTestHelper helper) {
        for (String theme : new String[] {null, "pocketdungeons:prismarine", "somepack:unheard_of"}) {
            Villager villager = spawnStore(helper, theme, 5);
            helper.assertValueEqual(villager.getName().getString(), "Wandering Merchant", "default merchant");
            for (String currency : currencies(villager)) {
                helper.assertValueEqual(currency, "minecraft:emerald", "default merchant prices in emeralds");
            }
            villager.discard();
        }
        helper.succeed();
    }

    /** A merchant never sells the drop it buys with, and never lists an item twice. */
    @GameTest
    public void aStoreNeverSellsItsOwnCurrencyOrRepeatsAnItem(GameTestHelper helper) {
        for (long seed = 1; seed <= 20; seed++) {
            Villager villager = spawnStore(helper, "ender_archive", seed);
            List<String> sold = new ArrayList<>();
            for (String entry : entries(villager)) {
                String item = entry.split(",", 5)[0];
                helper.assertTrue(!item.equals("minecraft:ender_pearl"),
                        "the archivist buys pearls, so it does not sell them");
                helper.assertTrue(!sold.contains(item), "no item listed twice, saw " + item + " again");
                sold.add(item);
            }
            helper.assertTrue(sold.size() >= 4 && sold.size() <= StorePricing.MAX_SLOTS,
                    "a store lists four to seven items, saw " + sold.size());
            villager.discard();
        }
        helper.succeed();
    }

    private static Villager spawnStore(GameTestHelper helper, String theme, long seed) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        StoreNPC.spawn(level, origin, seed, theme);
        List<Villager> found = level.getEntitiesOfClass(Villager.class,
                new AABB(origin).inflate(RoomGeometry.CELL),
                v -> v.entityTags().contains(StoreNPC.STORE_TAG) && entries(v).size() > 0);
        if (found.isEmpty()) {
            helper.fail("StoreNPC.spawn left no store villager for theme " + theme);
        }
        return found.get(0);
    }

    private static List<String> entries(Villager villager) {
        for (String tag : villager.entityTags()) {
            if (tag.startsWith(INVENTORY_PREFIX)) {
                String data = tag.substring(INVENTORY_PREFIX.length());
                return data.isEmpty() ? List.of() : List.of(data.split(";"));
            }
        }
        return List.of();
    }

    /** The currency item id of each saved entry ({@code item,currency,price,stock,name}). */
    private static List<String> currencies(Villager villager) {
        List<String> out = new ArrayList<>();
        for (String entry : entries(villager)) {
            out.add(entry.split(",", 5)[1]);
        }
        return out;
    }
}
