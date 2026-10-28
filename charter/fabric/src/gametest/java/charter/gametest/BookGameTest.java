package charter.gametest;

import charter.mc.book.BookDialogs;
import charter.mc.book.CharterBook;
import charter.mc.book.NexusItems;
import charter.mc.book.NexusRecipe;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;

import java.util.List;

/** The Charter book and the founding Nexus recipe (implementation plan, B0). */
public final class BookGameTest {

    @GameTest(maxTicks = 20)
    public void theBookLooksLikeABookAndQuill(GameTestHelper helper) {
        ItemStack book = CharterBook.create();
        helper.assertTrue(book.is(Items.PAPER), "The book is not paper");
        helper.assertTrue("minecraft:writable_book".equals(String.valueOf(book.get(DataComponents.ITEM_MODEL))),
                "The book does not look like a book and quill: " + book.get(DataComponents.ITEM_MODEL));
        helper.assertTrue(CharterBook.isBook(book), "isBook does not know its own book");
        helper.assertTrue(!CharterBook.isBook(new ItemStack(Items.PAPER)), "Plain paper counts as the book");
        helper.assertTrue(book.getMaxStackSize() == 1, "The book stacks");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void usingTheBookSendsADialog(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BookDialogs.LAST.remove(player.getUUID());
        player.setItemInHand(InteractionHand.OFF_HAND, CharterBook.create());
        helper.assertTrue(CharterBook.use(player, InteractionHand.OFF_HAND) == InteractionResult.PASS, "The off hand opened the book");
        helper.assertTrue(CharterBook.use(player, InteractionHand.MAIN_HAND) == InteractionResult.PASS, "An empty main hand opened the book");
        helper.assertTrue(!BookDialogs.LAST.containsKey(player.getUUID()), "A dialog was sent without the book in the main hand");
        player.setItemInHand(InteractionHand.MAIN_HAND, CharterBook.create());
        helper.assertTrue(CharterBook.use(player, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS_SERVER, "The main hand did not open the book");
        helper.assertTrue(BookDialogs.LAST.containsKey(player.getUUID()), "No dialog was sent");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void theButtonRunsForItsOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        player(owner);
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(owner.getUUID(), "Hello").orElse(null);
        helper.assertTrue(token != null, "The Hello button has no token");
        long now = System.currentTimeMillis();
        helper.assertTrue(tokens.take(token, other.getUUID(), now).isEmpty(), "Another player could press the button");
        var run = tokens.take(token, owner.getUUID(), now);
        helper.assertTrue(run.isPresent(), "The owner could not press the button after another player tried");
        run.get().accept(owner);
        helper.assertTrue(tokens.take(token, owner.getUUID(), now).isEmpty(), "The token worked twice");
        helper.succeed();
    }

    private static void player(ServerPlayer owner) {
        owner.setItemInHand(InteractionHand.MAIN_HAND, CharterBook.create());
        CharterBook.use(owner, InteractionHand.MAIN_HAND);
    }

    @GameTest(maxTicks = 20)
    public void theRecipeNamesTheNexusAfterTheBanner(GameTestHelper helper) {
        NexusRecipe recipe = new NexusRecipe();
        ItemStack banner = new ItemStack(Items.BANNER.white());
        banner.set(DataComponents.CUSTOM_NAME, Component.literal("Ashfall"));
        ItemStack lodestone = new ItemStack(Items.LODESTONE);
        CraftingInput good = CraftingInput.of(2, 1, List.of(lodestone, banner));
        helper.assertTrue(recipe.matches(good, helper.getLevel()), "A lodestone and a named banner do not match");
        ItemStack nexus = recipe.assemble(good);
        helper.assertTrue(NexusItems.foundingName(nexus).orElse("").equals("Ashfall"),
                "The Nexus is not named Ashfall: " + NexusItems.foundingName(nexus));

        CraftingInput unnamed = CraftingInput.of(2, 1, List.of(lodestone, new ItemStack(Items.BANNER.white())));
        helper.assertTrue(!recipe.matches(unnamed, helper.getLevel()), "An unnamed banner matched");
        CraftingInput extra = CraftingInput.of(3, 1, List.of(lodestone, banner, new ItemStack(Items.STICK)));
        helper.assertTrue(!recipe.matches(extra, helper.getLevel()), "A grid with a third item matched");
        CraftingInput noStone = CraftingInput.of(2, 1, List.of(banner, new ItemStack(Items.STONE)));
        helper.assertTrue(!recipe.matches(noStone, helper.getLevel()), "A grid without a lodestone matched");
        helper.succeed();
    }
}
