package charter.gametest;

import charter.mc.book.BookDialogs;
import charter.mc.book.BookMod;
import charter.mc.book.CharterBook;
import charter.mc.book.MemberPages;
import charter.mc.book.NewcomerPages;
import charter.mc.book.NexusItems;
import charter.mc.book.NexusRecipe;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;

import java.util.ArrayList;
import java.util.List;

/** The Charter book, the menu command and the founding Nexus recipe (implementation plan, B0 and B4). */
public final class BookGameTest {

    private static void command(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static int books(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (CharterBook.isBook(player.getInventory().getItem(i))) n++;
        }
        return n;
    }

    /** The title and the button labels, which is all a test can see of a dialog. */
    private static List<String> shape(Dialog dialog) {
        List<String> out = new ArrayList<>();
        out.add(dialog.common().title().getString());
        for (var button : BookDialogs.buttons(dialog)) out.add(button.button().label().getString());
        return out;
    }

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
        // A mock player joins like a real one and is handed a book, so start from empty hands.
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
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
        boolean[] ran = {false};
        String token = CharterCommands.issue(owner, "Press", p -> ran[0] = true);
        var tokens = CharterCommands.tokens();
        long now = System.currentTimeMillis();
        helper.assertTrue(tokens.take(token, other.getUUID(), now).isEmpty(), "Another player could press the button");
        var run = tokens.take(token, owner.getUUID(), now);
        helper.assertTrue(run.isPresent(), "The owner could not press the button after another player tried");
        run.get().accept(owner);
        helper.assertTrue(ran[0], "The button did nothing for its owner");
        helper.assertTrue(tokens.take(token, owner.getUUID(), now).isEmpty(), "The token worked twice");
        helper.succeed();
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

    // ---- B4 ----

    @GameTest(maxTicks = 20)
    public void aJoinGivesOneBookAndNoMore(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        BookMod.onJoin(player);
        helper.assertTrue(books(player) == 1, "A join gave " + books(player) + " books");
        BookMod.onJoin(player);
        helper.assertTrue(books(player) == 1, "A second join while carrying one gave another");
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        BookMod.onJoin(player);
        helper.assertTrue(books(player) == 1, "A player who lost the book did not get a new one");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aFullInventoryGetsTheBookDropped(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Mock players start in creative, where an inventory is never full.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        BookMod.onJoin(player);
        helper.assertTrue(books(player) == 0, "The book found room in a full inventory");
        ItemEntity dropped = BookMod.giveOrDrop(player, CharterBook.create());
        helper.assertTrue(dropped != null && CharterBook.isBook(dropped.getItem()), "The book was not dropped at the player's feet");
        helper.assertTrue(BookMod.giveOrDrop(emptyHanded(helper), CharterBook.create()) == null, "A book that fits was dropped");
        helper.succeed();
    }

    private static ServerPlayer emptyHanded(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        return player;
    }

    @GameTest(maxTicks = 40)
    public void theCommandAndTheBookOpenTheSameMenu(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BookDialogs.LAST.remove(player.getUUID());
        command(player, "charter");
        Dialog byCommand = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(byCommand != null, "/charter sent no dialog");
        BookDialogs.LAST.remove(player.getUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, CharterBook.create());
        CharterBook.use(player, InteractionHand.MAIN_HAND);
        Dialog byBook = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(byBook != null, "The book sent no dialog");
        helper.assertTrue(shape(byCommand).equals(shape(byBook)), "The command and the book differ: " + shape(byCommand) + " and " + shape(byBook));

        BookDialogs.LAST.remove(player.getUUID());
        command(player, "charter page");
        helper.assertTrue(!BookDialogs.LAST.containsKey(player.getUUID()), "/charter page opened a dialog instead of the chat page");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void theSameBookShowsEachPlayerTheirOwnFaction(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer a = kit.faction("Alpha");
            ServerPlayer b = kit.faction("Beta");
            ItemStack book = CharterBook.create();
            a.setItemInHand(InteractionHand.MAIN_HAND, book);
            BookDialogs.LAST.remove(a.getUUID());
            CharterBook.use(a, InteractionHand.MAIN_HAND);
            a.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            b.setItemInHand(InteractionHand.MAIN_HAND, book);
            BookDialogs.LAST.remove(b.getUUID());
            CharterBook.use(b, InteractionHand.MAIN_HAND);
            String titleA = BookDialogs.LAST.get(a.getUUID()).common().title().getString();
            String titleB = BookDialogs.LAST.get(b.getUUID()).common().title().getString();
            helper.assertTrue(titleA.equals(kit.rt.describe.org(kit.orgOf(a))), "Home A is not about faction A: " + titleA);
            helper.assertTrue(titleB.equals(kit.rt.describe.org(kit.orgOf(b))), "Home B is not about faction B: " + titleB);
            helper.assertTrue(!titleA.equals(titleB), "Both players saw the same faction");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 20)
    public void everyPageHasAButtonEvenWithNoFactions(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BookDialogs.LAST.remove(player.getUUID());
        NewcomerPages.home(player, null);
        helper.assertTrue(BookDialogs.buttons(BookDialogs.LAST.get(player.getUUID())).size() >= 1, "The newcomer home has no button");
        BookDialogs.LAST.remove(player.getUUID());
        MemberPages.home(player, "A line from the last press");
        helper.assertTrue(BookDialogs.buttons(BookDialogs.LAST.get(player.getUUID())).size() >= 1, "The member home has no button");
        helper.succeed();
    }
}
