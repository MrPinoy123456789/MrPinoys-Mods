package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Coin in a real server: the recipe mixin (EC-7) and the bank command on a mixed inventory. Everything here
 * uses the live services the mod started with, and a fresh mock player so balances never overlap between tests.
 */
public final class CoinGameTest {

    private static Services services(GameTestHelper helper) {
        Services s = Services.current;
        helper.assertTrue(s != null, "Charter Currency did not start with the server");
        return s;
    }

    private static Denomination denomination(Services s, String id) {
        return s.coin().denominations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<ItemStack> nine(ItemStack one) {
        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) grid.add(one.copy());
        return grid;
    }

    @GameTest
    public void aCoinInACraftingGridYieldsNothing(GameTestHelper helper) {
        Services s = services(helper);
        ServerLevel level = helper.getLevel();
        RecipeManager recipes = level.recipeAccess();

        // The control: nine plain gold nuggets are a gold ingot in vanilla, so the lookup works in this world.
        CraftingInput plain = CraftingInput.of(3, 3, nine(new ItemStack(Items.GOLD_NUGGET)));
        helper.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, plain, level).isPresent(),
                "Nine plain gold nuggets matched no recipe, so this test would prove nothing");

        // Nine marked Gold Sovereigns are the same item with a marker, and must match nothing.
        ItemStack coin = CoinStacks.make(denomination(s, "gold"), s.coin(), 1);
        CraftingInput coins = CraftingInput.of(3, 3, nine(coin));
        helper.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, coins, level).isEmpty(),
                "Nine Gold Sovereigns still crafted something: the plain recipe lookup is not guarded");
        helper.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, coins, level, (RecipeHolder) null).isEmpty(),
                "Nine Gold Sovereigns still crafted something: the hinted recipe lookup is not guarded");

        // One coin among plain nuggets is enough to refuse.
        List<ItemStack> mixed = nine(new ItemStack(Items.GOLD_NUGGET));
        mixed.set(4, coin.copy());
        helper.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, mixed), level).isEmpty(),
                "A single coin among plain nuggets still crafted");
        helper.succeed();
    }

    @GameTest
    public void bankAllOnAMixedInventoryBanksOnlyMarkedCoins(GameTestHelper helper) {
        Services s = services(helper);
        Currency coin = s.coin();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID id = player.getUUID();
        Denomination gold = denomination(s, "gold");
        Denomination copper = denomination(s, "copper");

        player.getInventory().setItem(0, CoinStacks.make(gold, coin, 3));
        player.getInventory().setItem(1, CoinStacks.make(copper, coin, 5));
        player.getInventory().setItem(2, new ItemStack(Items.GOLD_NUGGET, 17));
        player.getInventory().setItem(3, new ItemStack(Items.COPPER_INGOT, 9));
        // A nugget renamed to look like a coin is still not one: identity is the marker only.
        ItemStack renamed = new ItemStack(Items.GOLD_NUGGET, 4);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(gold.singular()));
        player.getInventory().setItem(4, renamed);

        long before = s.economy.getBalance(id, coin);
        helper.getLevel().getServer().getCommands()
                .performPrefixedCommand(player.createCommandSourceStack(), "bank all");

        int[] faces = coin.faceValues();
        long expected = 3L * faces[coin.denominations().indexOf(gold)]
                + 5L * faces[coin.denominations().indexOf(copper)];
        long banked = s.economy.getBalance(id, coin) - before;
        helper.assertTrue(banked == expected, "/bank all banked " + banked + " Marks, expected " + expected);
        helper.assertTrue(ItemBank.count(player, stack -> CoinStacks.markerOf(stack).isPresent()) == 0,
                "A marked coin was left in the inventory after /bank all");
        helper.assertTrue(ItemBank.count(player, Items.GOLD_NUGGET) == 21,
                "Plain or renamed gold nuggets were taken: " + ItemBank.count(player, Items.GOLD_NUGGET));
        helper.assertTrue(ItemBank.count(player, Items.COPPER_INGOT) == 9, "Copper ingots were taken");
        helper.succeed();
    }
}
