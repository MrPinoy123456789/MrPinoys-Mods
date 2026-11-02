package charter.gametest;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.BookMod;
import charter.mc.book.CharterBook;
import charter.mc.cmd.Teleports;
import charter.org.actions.Admit;
import charter.org.actions.Apply;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Feedback outside the book (plan, Phase 5): where the book is given, its glint and count, and the travel boss bar. */
public final class BookOutsideGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static ServerPlayer emptyHanded(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        return player;
    }

    private static ItemStack book(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (CharterBook.isBook(player.getInventory().getItem(i))) return player.getInventory().getItem(i);
        }
        return null;
    }

    @GameTest(maxTicks = 20)
    public void theBookGoesIntoTheLastHotbarSlotWhenFree(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        BookMod.ensure(player, true);
        helper.assertTrue(CharterBook.isBook(player.getInventory().getItem(8)), "The book is not in hotbar slot 9");

        ServerPlayer full = emptyHanded(helper);
        full.getInventory().setItem(8, new ItemStack(Items.STONE));
        full.getInventory().setItem(0, new ItemStack(Items.STONE));
        BookMod.ensure(full, true);
        helper.assertTrue(CharterBook.isBook(full.getInventory().getItem(1)), "With slot 9 taken the book is not in the first free hotbar slot");

        ServerPlayer packed = emptyHanded(helper);
        for (int i = 0; i < 9; i++) packed.getInventory().setItem(i, new ItemStack(Items.STONE));
        BookMod.ensure(packed, true);
        helper.assertTrue(BookMod.carriesBook(packed), "With the hotbar full the book was not put in the inventory");
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void theBookGlintsAndCountsWhileSomethingNeedsYou(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Glint");
            OrgId org = kit.orgOf(lord);
            BookMod.ensure(lord, true);
            BookMod.refresh(lord);
            ItemStack stack = book(lord);
            helper.assertTrue(stack != null, "The lord has no book");
            helper.assertTrue(!stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "The book glints with nothing waiting");

            ServerPlayer asker = kit.outsider();
            kit.rt.state.touch(id(asker), asker.getPlainTextName(), kit.rt.clock.now());
            Outcome asked = kit.rt.engine.run(new Actor.Player(id(asker)), new Apply(id(asker), org));
            helper.assertTrue(asked.done(), "The applicant could not ask: " + asked);
            BookMod.refresh(lord);
            stack = book(lord);
            helper.assertTrue(Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "A waiting request did not set the glint");
            helper.assertTrue(stack.getHoverName().getString().equals(Text.get("book.item.name_needs", "n", 1)),
                    "The name does not count the need: " + stack.getHoverName().getString());
            ItemLore lore = stack.get(DataComponents.LORE);
            helper.assertTrue(lore != null && lore.lines().get(0).getString().equals(Text.get("book.item.need.request", "n", 1)),
                    "The lore does not say what waits: " + lore);

            // Unchanged needs leave the stack alone: a mark put on it survives the next refresh.
            stack.set(DataComponents.LORE, new ItemLore(List.of(net.minecraft.network.chat.Component.literal("mark"))));
            BookMod.refresh(lord);
            helper.assertTrue(book(lord).get(DataComponents.LORE).lines().get(0).getString().equals("mark"), "An unchanged count rewrote the book");

            kit.rt.engine.run(new Actor.Admin("gametest"), new Admit(org, id(asker)));
            BookMod.refresh(lord);
            stack = book(lord);
            helper.assertTrue(!stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "The glint stayed after the request was answered");
            helper.assertTrue(stack.getHoverName().getString().equals(Text.get("book.item.name")), "The name kept a count: " + stack.getHoverName().getString());
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 20)
    public void aKeptBookNeverShowsSomeoneElsesCount(GameTestHelper helper) {
        ServerPlayer player = emptyHanded(helper);
        BookMod.ensure(player, true);
        BookMod.refresh(player);
        // A book picked up from someone else, still glinting with their count, lands first in the inventory.
        ItemStack theirs = CharterBook.create();
        theirs.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        theirs.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(Text.get("book.item.name_needs", "n", 2)));
        player.getInventory().setItem(0, theirs);
        helper.assertTrue(BookMod.removeExtras(player) == 1, "The second book was not removed");
        BookMod.refresh(player);
        ItemStack kept = book(player);
        helper.assertTrue(!kept.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "The kept book still glints with someone else's count");
        helper.assertTrue(kept.getHoverName().getString().equals(Text.get("book.item.name")), "The kept book keeps their name: " + kept.getHoverName().getString());
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void travelShowsABossBarThatGoesWhenTheWaitEnds(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        var knobs = CharterServer.runtime().knobs;
        int warmup = knobs.teleportWarmupSeconds;
        knobs.teleportWarmupSeconds = 5;
        ServerPlayer player = kit.outsider();
        String refusal = Teleports.start(player, Teleports.Kind.SPAWN);
        knobs.teleportWarmupSeconds = warmup;
        helper.assertTrue(refusal == null, "Going to spawn was refused: " + refusal);
        var bar = Teleports.bar(player);
        helper.assertTrue(bar != null && bar.getPlayers().contains(player), "No boss bar was shown for the wait");
        helper.assertTrue(bar.getName().getString().equals(Text.get("teleport.bar.spawn")), "The bar does not say where: " + bar.getName().getString());
        // Moving cancels the wait on the next tick: the bar must go with it.
        player.teleportTo(player.getX() + 3, player.getY(), player.getZ());
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(!Teleports.waiting(player), "Moving did not cancel the wait");
                helper.assertTrue(bar.getPlayers().isEmpty(), "The boss bar stayed after the wait was cancelled");
                helper.succeed();
            } finally {
                kit.close();
            }
        });
    }
}
