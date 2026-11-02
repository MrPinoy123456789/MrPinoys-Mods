package charter.gametest;

import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookMod;
import charter.mc.book.BookResult;
import charter.mc.book.BookScreen;
import charter.mc.book.BoundItems;
import charter.mc.book.CharterBook;
import charter.mc.book.MorePages;
import charter.mc.book.NewcomerPages;
import charter.mc.land.HopperGate;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

import java.util.UUID;

/**
 * The Charter book's rules: one copy per player, their own; it outlives death without dropping; the portable button
 * gives one and never a second; the {@code bookGivenTo} knob; and the book stays out of containers, bundles, frames
 * and other players' hands. Knobs changed here are put back in the same tick.
 */
public final class BookRulesGameTest {

    private static ServerPlayer emptyHanded(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        return player;
    }

    private static int books(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (CharterBook.isBook(player.getInventory().getItem(i))) n++;
        }
        return n;
    }

    @GameTest(maxTicks = 20)
    public void theSweepRemovesSomeoneElsesBookAndAdoptsAnOldOne(GameTestHelper helper) {
        ServerPlayer me = emptyHanded(helper);
        ServerPlayer other = emptyHanded(helper);
        me.getInventory().setItem(4, CharterBook.create(other.getUUID()));
        helper.assertTrue(BookMod.removeExtras(me) == 1 && books(me) == 0, "Someone else's book stayed with me");
        me.getInventory().setItem(5, CharterBook.create());
        helper.assertTrue(BookMod.removeExtras(me) == 0 && books(me) == 1, "An old book with no owner was not kept");
        helper.assertTrue(me.getUUID().equals(CharterBook.ownerOf(me.getInventory().getItem(5))), "The old book was not adopted");
        me.getInventory().setItem(6, CharterBook.create(me.getUUID()));
        helper.assertTrue(BookMod.removeExtras(me) == 1 && books(me) == 1, "A second own copy was kept");
        helper.assertTrue(CharterBook.ownerOf(CharterBook.create(me.getUUID())).equals(me.getUUID()), "A new book does not name its owner");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void theBookOutlivesDeathWithoutDropping(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        ItemStack own = CharterBook.create(player.getUUID());
        player.getInventory().setItem(7, own);
        player.getInventory().setItem(8, CharterBook.create(UUID.randomUUID()));
        helper.assertTrue(BookMod.vanish(player) == 2 && !BookMod.carriesBook(player), "A book was left for the death drops");
        helper.assertTrue(BookMod.stashed(player.getUUID()), "The player's own book was not set aside");
        BookMod.respawned(player);
        helper.assertTrue(books(player) == 1 && player.getUUID().equals(CharterBook.ownerOf(firstBook(player))),
                "Respawn did not hand back exactly the player's own book");
        helper.assertTrue(!BookMod.stashed(player.getUUID()), "The book was handed back but is still set aside");
        helper.succeed();
    }

    private static ItemStack firstBook(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (CharterBook.isBook(player.getInventory().getItem(i))) return player.getInventory().getItem(i);
        }
        return ItemStack.EMPTY;
    }

    @GameTest(maxTicks = 20)
    public void thePortableButtonGivesOneAndNeverASecond(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        String given = BookMod.giveNew(player);
        helper.assertTrue(BookResult.isOk(given) && books(player) == 1, "The button did not give a book: " + given);
        String again = BookMod.giveNew(player);
        helper.assertTrue(!BookResult.isOk(again) && again.equals(Text.get("book.new.already")) && books(player) == 1,
                "The button gave a second book: " + again + ", " + books(player));

        // The button is on the newcomer Home (or its More when Home is full) and on a member's More.
        String label = Text.get("book.button.new_book");
        helper.assertTrue(label.length() <= BookScreen.MAX_LABEL, "The new book label is too long: " + label);
        NewcomerPages.home(player, null);
        boolean onHome = labels(player).contains(label);
        NewcomerPages.more(player, null);
        boolean onMore = labels(player).contains(label);
        helper.assertTrue(onHome != onMore, "The new book button should be on exactly one of Home and More: " + onHome + ", " + onMore);
        // Pressing it from the menu with a book already carried says so on the same page.
        if (onHome) NewcomerPages.home(player, null);
        var button = BookDialogs.buttons(BookDialogs.LAST.get(player.getUUID())).stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow();
        button.run().accept(player);
        helper.assertTrue(books(player) == 1, "The menu button gave a second book");
        helper.succeed();
    }

    private static java.util.List<String> labels(ServerPlayer player) {
        BookScreen screen = BookDialogs.LAST.get(player.getUUID());
        return BookDialogs.buttons(screen).stream().map(b -> b.label()).toList();
    }

    @GameTest(maxTicks = 100)
    public void theMemberMoreHasTheNewBookButton(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("BookMore");
            MorePages.page(lord, null);
            BookScreen screen = BookDialogs.LAST.get(lord.getUUID());
            boolean found = labels(lord).contains(Text.get("book.button.new_book"));
            // A paged More holds it on a later page; walk the Next buttons.
            for (int i = 0; i < 4 && !found; i++) {
                var next = BookDialogs.buttons(screen).stream().filter(b -> b.label().equals(Text.get("book.page.next"))).findFirst();
                if (next.isEmpty()) break;
                next.get().run().accept(lord);
                screen = BookDialogs.LAST.get(lord.getUUID());
                found = labels(lord).contains(Text.get("book.button.new_book"));
            }
            helper.assertTrue(found, "A member's More has no new book button");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void bookGivenToDecidesWhoIsHandedOne(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            var knobs = CharterServer.runtime().knobs;
            String was = knobs.bookGivenTo;
            try {
                ServerPlayer lord = kit.faction("BookGift");
                ServerPlayer merc = kit.outsider();
                kit.rt.engine.run(new charter.engine.Actor.Admin("gametest"),
                        new charter.org.actions.SetRace(new charter.id.PlayerId(merc.getUUID()), kit.orgOf(lord)));
                ServerPlayer newcomer = kit.outsider();
                for (ServerPlayer p : java.util.List.of(lord, merc, newcomer)) {
                    for (int i = 0; i < p.getInventory().getContainerSize(); i++) p.getInventory().setItem(i, ItemStack.EMPTY);
                }

                knobs.bookGivenTo = "newcomers";
                for (ServerPlayer p : java.util.List.of(lord, merc, newcomer)) BookMod.ensure(p, true);
                helper.assertTrue(books(newcomer) == 1, "newcomers: a newcomer was not handed a book");
                helper.assertTrue(books(lord) == 0 && books(merc) == 0, "newcomers: a member or a mercenary was handed a book");

                knobs.bookGivenTo = "none";
                ServerPlayer another = emptyHanded(helper);
                BookMod.ensure(another, true);
                BookMod.onJoin(another);
                helper.assertTrue(books(another) == 0, "none: a book was handed out");
                helper.assertTrue(BookResult.isOk(BookMod.giveNew(another)) && books(another) == 1, "none: the button did not work");

                knobs.bookGivenTo = "everyone";
                BookMod.ensure(lord, true);
                helper.assertTrue(books(lord) == 1, "everyone: the Lord was not handed a book");
            } finally {
                knobs.bookGivenTo = was;
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 20)
    public void containersBundlesHoppersAndFramesRefuseTheBook(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        ItemStack book = CharterBook.create(player.getUUID());
        SimpleContainer chest = new SimpleContainer(9);
        helper.assertTrue(!new Slot(chest, 0, 0, 0).mayPlace(book), "A chest slot took the book");
        helper.assertTrue(new Slot(chest, 0, 0, 0).mayPlace(new ItemStack(Items.PAPER)), "A chest slot refused plain paper");
        helper.assertTrue(!new net.minecraft.world.inventory.ShulkerBoxSlot(chest, 0, 0, 0).mayPlace(book), "A shulker box slot took the book");
        helper.assertTrue(new Slot(player.getInventory(), 3, 0, 0).mayPlace(book), "The player's own slot refused the book");
        helper.assertTrue(!BundleContents.canItemBeInBundle(book), "A bundle took the book");
        helper.assertTrue(HopperGate.offer(null, chest, book, Direction.DOWN) == book, "A hopper took the book");
        helper.assertTrue(BoundItems.isBound(book) && !BoundItems.isBound(new ItemStack(Items.PAPER)), "isBound is wrong");

        // With the affiliation rule on, a mock player (no kingdom, no race) is refused everything anyway; switch it off
        // for this one call so the refusal seen is the book's own.
        var knobs = CharterServer.runtime().knobs;
        boolean affiliation = knobs.requireAffiliation;
        knobs.requireAffiliation = false;
        try {
            ItemFrame frame = new ItemFrame(EntityType.ITEM_FRAME, helper.getLevel());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            InteractionResult paper = UseEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, frame, null);
            helper.assertTrue(paper != InteractionResult.FAIL, "An item frame refused plain paper: " + paper);
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            InteractionResult used = UseEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, frame, null);
            helper.assertTrue(used == InteractionResult.FAIL, "An item frame took the book: " + used);
        } finally {
            knobs.requireAffiliation = affiliation;
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aDroppedBookIsForItsOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = emptyHanded(helper);
        ServerPlayer other = emptyHanded(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        ItemEntity dropped = new ItemEntity(helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, CharterBook.create(owner.getUUID()));
        helper.getLevel().addFreshEntity(dropped);
        dropped.setNoPickUpDelay();
        dropped.playerTouch(other);
        helper.assertTrue(!dropped.isRemoved() && books(other) == 0, "Another player picked up someone's book");
        dropped.playerTouch(owner);
        helper.assertTrue(books(owner) == 1, "The owner could not pick up their book");
        helper.assertTrue(BoundItems.BOOK_DROP_TICKS <= 600, "A dropped book lies too long: " + BoundItems.BOOK_DROP_TICKS);
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void theDropsSetAsideAtDeathCountOnlyTheirOwnBook(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        player.getInventory().setItem(1, CharterBook.create(UUID.randomUUID()));
        BookMod.vanish(player);
        helper.assertTrue(!BookMod.stashed(player.getUUID()), "Someone else's book was set aside for the dead player");
        helper.succeed();
    }
}
