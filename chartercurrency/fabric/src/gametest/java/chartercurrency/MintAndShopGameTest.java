package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.Mint;
import chartercurrency.core.Shop;
import chartercurrency.core.ShopEntry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The Mint and the shop in a real server (EC-7): overflow banking and a stored enchantment delivered. */
public final class MintAndShopGameTest {

    private static Services services(GameTestHelper helper) {
        Services s = Services.current;
        helper.assertTrue(s != null, "Charter Currency did not start with the server");
        return s;
    }

    @GameTest
    public void aMintStrikeWithANearlyFullInventoryBanksTheOverflow(GameTestHelper helper) {
        Services s = services(helper);
        Currency coin = s.coin();
        Denomination gold = coin.denominations().stream().filter(d -> d.id().equals("gold")).findFirst().orElseThrow();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        // Nine ingots in the hand and every other main slot packed, so only the ingots' own slot frees up.
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 9));
        for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().setSelectedSlot(0);

        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(at, Blocks.SMITHING_TABLE.defaultBlockState());
        s.sites.bind(helper.getLevel(), at);

        Mint.Plan plan = s.mint.preview(player.getUUID(), gold.id(), gold.value(), 9, s.settings.today());
        helper.assertTrue(plan.struck() && plan.coinsToPlayer() > 64,
                "The plan did not strike enough coin to overflow one slot: " + plan.coinsToPlayer());

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false);
        InteractionResult result = UseBlockCallback.EVENT.invoker()
                .interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, hit);
        s.sites.unbind(helper.getLevel(), at);
        helper.assertTrue(result == InteractionResult.CONSUME, "The bound smithing table did not answer the click: " + result);

        long carried = ItemBank.count(player, stack -> CoinStacks.isCoin(stack, gold));
        long banked = s.economy.getBalance(player.getUUID(), coin);
        helper.assertTrue(ItemBank.count(player, Items.GOLD_INGOT) == 0, "The ingots were not taken");
        helper.assertTrue(carried == 64, "The free slot did not fill with coin: " + carried);
        helper.assertTrue(banked > 0 && carried * gold.value() + banked == plan.coinsToPlayer() * gold.value(),
                "The overflow was not banked: carried " + carried + ", banked " + banked
                        + " Marks, struck for the player " + plan.coinsToPlayer());
        helper.succeed();
    }

    @GameTest
    public void aShopPurchaseOfAnEnchantedBookDeliversTheStoredEnchantment(GameTestHelper helper) {
        Services s = services(helper);
        Currency coin = s.coin();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        long price = 192;
        s.economy.deposit(player.getUUID(), coin, price + 8);

        ShopEntry entry = ShopEntry.of("gametest_book", "minecraft:enchanted_book", 1, coin, price, "Books",
                "{\"minecraft:stored_enchantments\":{\"minecraft:unbreaking\":3}}");
        Shop.PurchaseResult result = ShopPurchase.attempt(s.economy, s.log, player, entry, false);
        helper.assertTrue(result.ok(), "The purchase failed: " + result.status());

        helper.assertTrue(s.economy.getBalance(player.getUUID(), coin) == 8, "The price was not charged exactly");
        ItemStack book = ItemStack.EMPTY;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.ENCHANTED_BOOK)) book = stack;
        }
        helper.assertTrue(!book.isEmpty(), "No enchanted book was delivered");
        ItemEnchantments stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        helper.assertTrue(stored != null && !stored.isEmpty(), "The delivered book carries no stored enchantment");
        boolean found = false;
        for (var e : stored.entrySet()) {
            if (e.getKey().is(Enchantments.UNBREAKING) && e.getIntValue() == 3) found = true;
        }
        helper.assertTrue(found, "The stored enchantment is not Unbreaking III: " + stored);
        helper.succeed();
    }
}
