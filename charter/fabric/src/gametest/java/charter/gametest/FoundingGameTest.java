package charter.gametest;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.ClaimKind;
import charter.mc.Text;
import charter.mc.book.BookDialogs;
import charter.mc.book.MemberPages;
import charter.mc.book.NewcomerPages;
import charter.mc.book.NexusItems;
import charter.mc.cmd.CharterCommands;
import charter.mc.land.LandItems;
import charter.org.actions.Found;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * Founding a faction by placing a named Nexus, and asking to join one (B6). Founding
 * makes a faction a scenario clear may take down, so this runs in its own batch.
 */
public final class FoundingGameTest {

    private static final String BATCH = "charter-gametest:founding_alone";

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static int diamonds(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(Items.DIAMOND)) n += p.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static void clear(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) p.getInventory().setItem(i, ItemStack.EMPTY);
    }

    private static String unique(String label) {
        return label + Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    private static int firstOpen(boolean[] steps) {
        for (int i = 0; i < steps.length; i++) if (!steps[i]) return i;
        return steps.length;
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void placingANamedNexusFoundsAFaction(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer player = kit.outsider();
            clear(player);
            String name = unique("Fnd");
            player.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 64));
            ItemStack nexus = NexusItems.founding(name, "");
            kit.placeOn(player, nexus, kit.nexusPos());

            var org = kit.rt.state.orgNamed(name).orElse(null);
            helper.assertTrue(org != null, "No faction named " + name + " exists");
            helper.assertTrue(org.governance.deciders != null && kit.rt.state.office(org.governance.deciders)
                    .map(o -> o.holds(id(player))).orElse(false), "The placer is not the Lord");
            long claimed = kit.rt.state.claimsOf(org.id).stream()
                    .filter(c -> c.kind() == ClaimKind.KEEP_CORE || c.kind() == ClaimKind.KEEP_RING).count();
            helper.assertTrue(claimed == 9, "The keep claims " + claimed + " chunks, not nine");
            helper.assertTrue(diamonds(player) == 0, "The player still holds " + diamonds(player) + " diamonds");
            helper.assertTrue(org.ledger.balance(charter.treasury.Currency.DIAMOND) == 0, "The treasury is not empty");
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).is(Blocks.LODESTONE), "The Nexus block is not standing");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void tooFewDiamondsRefusesAndKeepsTheNexus(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer player = kit.outsider();
            clear(player);
            String name = unique("Poor");
            // The Nexus goes in the selected hotbar slot, so the diamonds wait elsewhere.
            player.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 63));
            kit.placeOn(player, NexusItems.founding(name, ""), kit.nexusPos());

            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).isAir(), "The refused Nexus left its block");
            ItemStack back = player.getItemInHand(InteractionHand.MAIN_HAND);
            helper.assertTrue(NexusItems.foundingName(back).isPresent(), "The Nexus did not come back to the hand");
            helper.assertTrue(diamonds(player) == 63, "The refusal took diamonds: " + diamonds(player));
            helper.assertTrue(kit.rt.state.orgNamed(name).isEmpty(), "A faction was founded anyway");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void anUnnamedNexusIsRefusedForAPlayerWithNoFaction(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer player = kit.outsider();
            clear(player);
            player.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 64));
            kit.placeOn(player, LandItems.nexus(), kit.nexusPos());
            helper.assertTrue(kit.level.getBlockState(kit.nexusPos()).isAir(), "An unnamed Nexus stood for a player with no faction");
            helper.assertTrue(kit.rt.state.membership(id(player)).isEmpty(), "The player became a member");
            helper.assertTrue(diamonds(player) == 64, "Diamonds were taken: " + diamonds(player));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void aMemberPlacingAnAdminNexusStillPaysFromTheTreasury(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Member");
            OrgId org = kit.orgOf(lord);
            long before = kit.rt.state.org(org).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND);
            kit.keep(lord);
            long after = kit.rt.state.org(org).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND);
            helper.assertTrue(before - after == kit.rt.knobs.keepPriceDiamonds, "The treasury paid " + (before - after));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void theFoundPageFollowsTheInventory(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        clear(player);
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 0, "With nothing the first step is not open");
        player.getInventory().add(new ItemStack(Items.BANNER.white()));
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 1, "A banner did not finish step one");
        clear(player);
        ItemStack banner = new ItemStack(Items.BANNER.white());
        banner.set(DataComponents.CUSTOM_NAME, Component.literal("Ashfall"));
        player.getInventory().add(banner);
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 2, "A named banner did not finish step two");
        player.getInventory().add(new ItemStack(Items.LODESTONE));
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 3, "A lodestone did not finish step three");
        clear(player);
        player.getInventory().add(NexusItems.founding("Ashfall", ""));
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 4, "A crafted Nexus did not finish step four");
        player.getInventory().add(new ItemStack(Items.DIAMOND, 64));
        helper.assertTrue(firstOpen(NewcomerPages.foundSteps(player)) == 5, "Sixty four diamonds did not finish step five");
        BookDialogs.LAST.remove(player.getUUID());
        NewcomerPages.found(player, null);
        helper.assertTrue(BookDialogs.LAST.containsKey(player.getUUID()), "The Found page sent no dialog");
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = BATCH)
    public void askingToJoinAndBeingAdmitted(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Hall");
            OrgId org = kit.orgOf(lord);
            ServerPlayer newcomer = kit.outsider();
            kit.rt.state.touch(id(newcomer), newcomer.getPlainTextName(), kit.rt.clock.now());
            NewcomerPages.faction(newcomer, org, null);
            press(helper, newcomer, Text.get("book.button.ask"));
            helper.assertTrue(kit.rt.state.joinRequest(org, id(newcomer)).isPresent(), "Asking to join left no request");
            var page = BookDialogs.LAST.get(newcomer.getUUID());
            var text = new java.util.ArrayList<String>();
            for (var b : page.common().body()) if (b instanceof net.minecraft.server.dialog.body.PlainMessage m) text.add(m.contents().getString());
            String all = String.join(" ", text);
            helper.assertTrue(all.contains(Text.get("book.join.who_decides")), "The faction page does not say who decides: " + all);
            helper.assertTrue(!all.toLowerCase().contains("gives away") && !all.toLowerCase().contains("garrison"),
                    "The faction page still lists what does not touch a member: " + all);
            helper.assertTrue(all.contains(Text.get("book.holder.vote_newcomer")), "The charter vote is not worded for a newcomer: " + all);
            NewcomerPages.home(newcomer, null);
            helper.assertTrue(BookDialogs.LAST.containsKey(newcomer.getUUID()), "The newcomer home sent no dialog");

            MemberPages.faction(lord, null);
            press(helper, lord, Text.get("book.button.admit", "player", kit.rt.describe.player(id(newcomer))));
            helper.assertTrue(kit.rt.state.isMember(id(newcomer), org), "The newcomer was not admitted");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100, environment = BATCH)
    public void anOfflineLordIsToldByMail(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            PlayerId lord = new PlayerId(UUID.randomUUID());
            kit.rt.state.touch(lord, "Absent", kit.rt.clock.now());
            String name = unique("Mail");
            Outcome found = kit.rt.engine.run(new Actor.Admin("gametest"), new Found(lord, name, Preset.LORDSHIP));
            helper.assertTrue(found.done(), "Could not found for the offline lord: " + found);
            OrgId org = kit.rt.state.orgNamed(name).orElseThrow().id;
            ServerPlayer newcomer = kit.outsider();
            kit.rt.state.touch(id(newcomer), newcomer.getPlainTextName(), kit.rt.clock.now());
            kit.rt.engine.run(new Actor.Player(id(newcomer)), new charter.org.actions.Apply(id(newcomer), org));
            String expected = Text.get("notice.asked", "player", kit.rt.describe.player(id(newcomer)), "org", name);
            helper.assertTrue(kit.rt.mail.peek(lord).stream().anyMatch(m -> m.text().equals(expected)),
                    "The offline Lord has no mail saying " + expected + ": " + kit.rt.mail.peek(lord));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 40)
    public void theJoinPageAlwaysHasAButton(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BookDialogs.LAST.remove(player.getUUID());
        NewcomerPages.join(player, null);
        helper.assertTrue(!BookDialogs.buttons(BookDialogs.LAST.get(player.getUUID())).isEmpty(), "The Join page has no button");
        helper.succeed();
    }
}
