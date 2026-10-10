package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * J5a: the home vendor's real offer list and its re-roll on coming home.
 * The pure plan is {@code VendorStockTest}; this covers the half that needs
 * the item registries and loot tables: rolled gear carries the durability
 * cap, the Mending book is always 64 emeralds, surplus buys pay half rate,
 * and {@link LibrarianNPC#restock} (the call {@code FirstVisitTutorial.homeArrival} makes; the gametest server has no dungeon dimension) replaces the villager's offers.
 */
public final class VendorGameTest {

    private static final int SLOT = 9982;

    @GameTest(maxTicks = 40)
    public void vendorOffersCarryTheCapMendingAndHalfRateBuys(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MerchantOffers offers = LibrarianNPC.buildOffers(level, UUID.randomUUID());

        int gear = 0;
        boolean mending = false;
        int buys = 0;
        for (MerchantOffer offer : offers) {
            ItemStack result = offer.getResult();
            if (result.is(Items.ENCHANTED_BOOK)) {
                mending = true;
                helper.assertValueEqual(offer.getItemCostA().count(), 64, "Mending costs 64 emeralds");
            } else if (result.is(Items.EMERALD)) {
                buys++;
            } else {
                gear++;
                helper.assertValueEqual(offer.getMaxUses(), 1, "a gear offer is one use");
                // The cap table is lifted by the durability knobs (110 percent by default).
                int cap = DungeonTools.scaledCap(DungeonTools.durabilityCap(result.getItem()),
                        Math.max(PocketDungeonsConfig.lootDurabilityPercent(),
                                PocketDungeonsConfig.craftedDurabilityPercent()));
                if (cap > 0 && result.isDamageableItem()) {
                    helper.assertTrue(result.getMaxDamage() <= cap,
                            result.getItem() + " carries the dungeon durability cap");
                }
            }
        }
        helper.assertTrue(mending, "the Mending book is always in stock");
        helper.assertValueEqual(gear, 6, "a fresh compass sees tiers I and II: six gear offers");
        helper.assertValueEqual(buys, MerchantThemes.ALL_BUYS.size(), "one buy offer per surplus drop");
        MerchantThemes.Currency first = MerchantThemes.ALL_BUYS.get(0);
        int[] half = StorePricing.buyBundle(first.perEmerald(), VendorMath.BUY_RATE);
        boolean found = false;
        for (MerchantOffer offer : offers) {
            if (offer.getItemCostA().itemStack().is(first.item())) {
                found = true;
                helper.assertValueEqual(offer.getItemCostA().count(), half[0], "items in at half rate");
                helper.assertValueEqual(offer.getResult().getCount(), half[1], "emeralds out at half rate");
            }
        }
        helper.assertTrue(found, "the first surplus drop has a buy offer");
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void comingHomeReRollsTheVendorsOffers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        UUID owner = UUID.randomUUID();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(SLOT, origin, server.getTickCount(), layout,
                Set.of(), owner, false);
        record.roomCellOrigin = origin;

        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        villager.setPos(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 4.5);
        villager.addTag(LibrarianNPC.LIBRARIAN_TAG);
        MerchantOffers before = LibrarianNPC.buildOffers(level, owner);
        villager.setOffers(before);
        level.addFreshEntity(villager);
        try {
            LibrarianNPC.restock(level, record);
            helper.assertTrue(villager.getOffers() != before,
                    "coming home replaced the vendor's offer list");
            helper.assertTrue(!villager.getOffers().isEmpty(), "and the new list is stocked");
        } finally {
            villager.discard();
        }
        helper.succeed();
    }
}
