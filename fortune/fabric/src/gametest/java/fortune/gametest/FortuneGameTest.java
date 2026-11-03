package fortune.gametest;

import fortune.core.PlayerRecord;
import fortune.core.Settings;
import fortune.mc.FortuneService;
import fortune.mc.MachineGui;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The machine in a running server: the reward is fixed and saved before the first frame and paid at the reveal,
 * closing mid spin pays it and blocks a second spin, leaving mid spin keeps it for the next join, the chest refuses every take, and an unaffordable stake is greyed out and never charged.
 * Every test finishes in the tick it starts, driving the animation by hand.
 */
public final class FortuneGameTest {

    @GameTest
    public void theRewardIsSavedBeforeTheAnimationAndPaidAtTheReveal(GameTestHelper helper) throws Exception {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        MachineGui gui = service.open(player, null);

        gui.pullLever();
        helper.assertTrue(gui.spinning(), "The reels did not start");
        helper.assertTrue(!gui.revealed(), "The result was revealed before the reels moved");
        PlayerRecord record = service.record(player.getUUID());
        helper.assertTrue(record.claimedOn(service.today()), "The claim was not recorded before the first frame");
        helper.assertTrue(record.payPending && record.lastLevel >= 1, "The reward was not fixed and pending before the first frame");

        Path file = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("fortune/players.json");
        helper.assertTrue(Files.exists(file), "players.json was not written before the first frame");
        String saved = Files.readString(file);
        helper.assertTrue(saved.contains(player.getUUID().toString()), "players.json does not hold the spin");
        helper.assertTrue(saved.contains("\"payPending\": true"), "players.json does not hold the pending reward");
        helper.assertTrue(!Files.exists(file.resolveSibling("players.json.tmp")), "The temporary file was left behind");

        // Spec 9: nothing about the outcome reaches the player while the reels turn, so the payout waits.
        gui.advance(20);
        helper.assertTrue(gui.spinning(), "The reels stopped too early for this test");
        helper.assertTrue(!player.entityTags().contains(FortuneKit.PAID_TAG), "The payout ran while the reels were turning");

        gui.advance(200);
        helper.assertTrue(!gui.spinning() && gui.revealed(), "The reels did not stop and reveal");
        helper.assertTrue(player.entityTags().contains(FortuneKit.PAID_TAG), "The payout command did not run at the reveal");
        helper.assertTrue(!record.payPending, "The reward was still pending after the reveal");
        int[] payline = gui.lastSpin().spin().outcome().payline();
        for (int reel = 0; reel < 3; reel++) {
            helper.assertTrue(gui.paylineShown(reel) == payline[reel], "Reel " + (reel + 1) + " stopped off the rolled symbol");
        }
        gui.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void theReelsRunOnRealServerTicks(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        MachineGui gui = service.open(player, null);
        gui.pullLever();
        helper.assertTrue(gui.spinning(), "The reels did not start");
        // Reel 3 stops 60 ticks after the pull at Java pacing; nothing here calls advance().
        helper.runAfterDelay(30, () -> helper.assertTrue(gui.spinning(), "The reels stopped before reel 3's stop time"));
        helper.succeedWhen(() -> {
            helper.assertTrue(gui.revealed() && !gui.spinning(), "The reels have not stopped on their own yet");
            helper.assertTrue(player.entityTags().contains(FortuneKit.PAID_TAG), "The reveal on real ticks did not pay");
            int[] payline = gui.lastSpin().spin().outcome().payline();
            for (int reel = 0; reel < 3; reel++) {
                helper.assertTrue(gui.paylineShown(reel) == payline[reel], "Reel " + (reel + 1) + " stopped off the rolled symbol");
            }
            gui.close();
        });
    }

    @GameTest
    public void closingMidSpinKeepsTheRewardAndBlocksASecondSpin(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        MachineGui gui = service.open(player, null);
        gui.pullLever();
        gui.advance(5);
        helper.assertTrue(gui.spinning(), "The reels stopped too early for this test");
        gui.close();
        helper.assertTrue(gui.revealed(), "Closing mid spin did not reveal the result");
        helper.assertTrue(player.entityTags().contains(FortuneKit.PAID_TAG), "Closing mid spin lost the payout");

        PlayerRecord record = service.record(player.getUUID());
        int claims = record.totalClaims;
        helper.assertTrue(claims == 1, "Expected one claim, found " + claims);

        MachineGui again = service.open(player, null);
        again.pullLever();
        helper.assertTrue(!again.spinning(), "A second spin started the same day");
        helper.assertTrue(service.pull(player, null) instanceof FortuneService.Pull.Refused, "A second pull was not refused");
        helper.assertTrue(record.totalClaims == claims, "A second claim was recorded the same day");
        again.close();

        helper.assertTrue(service.reset(player.getUUID()), "An operator reset did not give the spin back");
        helper.assertTrue(service.pull(player, null) instanceof FortuneService.Pull.Spun, "The spin given back could not be used");
        helper.assertTrue(service.pull(player, null) instanceof FortuneService.Pull.Refused, "The reset gave more than one spin");
        helper.succeed();
    }

    @GameTest
    public void aSpinLeftBeforeItsRevealIsPaidOnTheNextJoin(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        // A pull whose window is never revealed: the player left, or the server stopped, mid spin.
        helper.assertTrue(service.pull(player, null) instanceof FortuneService.Pull.Spun, "The pull did not spin");
        PlayerRecord record = service.record(player.getUUID());
        helper.assertTrue(record.payPending, "The unrevealed reward was not kept pending");
        helper.assertTrue(!player.entityTags().contains(FortuneKit.PAID_TAG), "The unrevealed spin was paid");
        helper.assertTrue(service.pull(player, null) instanceof FortuneService.Pull.Refused, "Leaving mid spin allowed a second roll");
        service.finishPending(player);
        helper.assertTrue(!record.payPending && player.entityTags().contains(FortuneKit.PAID_TAG), "The next join did not pay it");
        helper.assertTrue(record.totalClaims == 1, "Paying on join counted another claim");
        helper.succeed();
    }

    @GameTest
    public void aPayoutLeftPendingByACrashIsFinishedOnJoin(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        PlayerRecord record = service.record(player.getUUID());
        record.payPending = true;
        record.pendingExtras = new ArrayList<>(List.of(new Settings.Extra("minecraft:diamond", 3)));
        record.lastLevel = 2;
        record.lastClaimDay = service.today();
        service.finishPending(player);
        helper.assertTrue(!record.payPending, "The pending payout was not finished");
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 3, "The pending items were not given");
        helper.assertTrue(player.entityTags().contains(FortuneKit.PAID_TAG), "The pending payout command did not run");
        service.finishPending(player);
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 3, "A finished payout was paid twice");
        helper.succeed();
    }

    @GameTest
    public void theChestRefusesEveryTakePlaceShiftClickAndNumberKey(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        FortuneKit.BANK.balances.put(player.getUUID(), 100L);
        player.getInventory().setItem(9, new ItemStack(Items.DIRT, 5));
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 3));
        MachineGui gui = service.open(player, null);
        helper.assertTrue(player.containerMenu.containerId != 0, "The machine did not open as a container");

        List<ItemStack> guiBefore = guiStacks(gui);
        List<ItemStack> invBefore = inventory(player);
        int menuSize = player.containerMenu.slots.size();
        for (int slot = 0; slot < menuSize; slot++) {
            boolean button = slot >= 45 && slot <= 53;
            if (!button) {
                FortuneKit.click(player, slot, 0, ContainerInput.PICKUP);
                FortuneKit.click(player, slot, 1, ContainerInput.PICKUP);
            }
            FortuneKit.click(player, slot, 0, ContainerInput.QUICK_MOVE);
            FortuneKit.click(player, slot, 1, ContainerInput.QUICK_MOVE);
            for (int key = 0; key < 9; key++) FortuneKit.click(player, slot, key, ContainerInput.SWAP);
            FortuneKit.click(player, slot, 40, ContainerInput.SWAP);
            FortuneKit.click(player, slot, 0, ContainerInput.THROW);
            FortuneKit.click(player, slot, 1, ContainerInput.THROW);
            FortuneKit.click(player, slot, 2, ContainerInput.CLONE);
            FortuneKit.click(player, slot, 0, ContainerInput.PICKUP_ALL);
        }
        // A carried stack put down on a reel and on the payline arrow.
        player.containerMenu.setCarried(new ItemStack(Items.EMERALD, 2));
        FortuneKit.click(player, 22, 0, ContainerInput.PICKUP);
        FortuneKit.click(player, 27, 1, ContainerInput.PICKUP);
        ItemStack carried = player.containerMenu.getCarried();
        helper.assertTrue(carried.is(Items.EMERALD) && carried.getCount() == 2, "The carried stack changed: " + carried);
        // Emerald is also a reel symbol, so placement is checked by the slot by slot comparison below.
        player.containerMenu.setCarried(ItemStack.EMPTY);

        List<ItemStack> guiAfter = guiStacks(gui);
        for (int i = 0; i < guiBefore.size(); i++) {
            helper.assertTrue(same(guiBefore.get(i), guiAfter.get(i)), "Machine slot " + i + " changed: " + guiAfter.get(i));
        }
        List<ItemStack> invAfter = inventory(player);
        for (int i = 0; i < invBefore.size(); i++) {
            helper.assertTrue(same(invBefore.get(i), invAfter.get(i)), "Inventory slot " + i + " changed: " + invAfter.get(i));
        }
        helper.assertTrue(service.record(player.getUUID()).totalClaims == 0, "A refused click pulled the lever");
        helper.assertTrue(FortuneKit.BANK.chargesFor(player.getUUID()) == 0, "A refused click charged a stake");

        // A plain click on the lever is the one thing that does something.
        FortuneKit.click(player, MachineGui.LEVER, 0, ContainerInput.PICKUP);
        helper.assertTrue(gui.spinning(), "A plain click on the lever did not pull it");
        gui.close();
        helper.succeed();
    }

    @GameTest
    public void anUnaffordableStakeIsGreyedOutAndNeverCharged(GameTestHelper helper) {
        FortuneService service = FortuneKit.service(helper);
        ServerPlayer player = FortuneKit.player(helper);
        FortuneKit.BANK.balances.put(player.getUUID(), 10L);
        // Gold was chosen once, back when they were richer; it is remembered but only 10 Marks are banked now.
        service.chooseStake(player.getUUID(), "gold");
        MachineGui gui = service.open(player, null);
        int goldSlot = MachineGui.STAKE_SLOTS[2];
        helper.assertTrue(gui.getGuiElement(goldSlot).getItemStack().is(Items.STAINED_GLASS_PANE.gray()),
                "The gold stake was not greyed out at 10 Marks");
        helper.assertTrue(gui.getGuiElement(MachineGui.STAKE_SLOTS[0]).getItemStack().is(Items.COPPER_NUGGET),
                "The affordable copper stake was not offered");
        FortuneKit.click(player, goldSlot, 0, ContainerInput.PICKUP);
        helper.assertTrue(FortuneKit.BANK.chargesFor(player.getUUID()) == 0, "Clicking a greyed out stake charged it");

        gui.pullLever();
        FortuneService.Pull.Spun spun = gui.lastSpin();
        helper.assertTrue(spun != null, "The spin did not go ahead");
        helper.assertTrue(spun.spin().stake() == null, "An unaffordable stake was applied");
        helper.assertTrue(spun.stakeFailed(), "The player was not told the stake did not go through");
        helper.assertTrue(FortuneKit.BANK.chargesFor(player.getUUID()) == 0, "An unaffordable stake was charged");
        helper.assertTrue(FortuneKit.BANK.balance(player.getUUID()) == 10L, "The balance changed");
        gui.close();

        // An affordable stake, chosen by clicking its button, is charged once before the roll and only then.
        ServerPlayer rich = FortuneKit.player(helper);
        FortuneKit.BANK.balances.put(rich.getUUID(), 30L);
        MachineGui richGui = service.open(rich, null);
        FortuneKit.click(rich, MachineGui.STAKE_SLOTS[0], 0, ContainerInput.PICKUP);
        helper.assertTrue("copper".equals(service.record(rich.getUUID()).stakeTier), "Clicking the copper stake did not choose it");
        helper.assertTrue(FortuneKit.BANK.chargesFor(rich.getUUID()) == 0, "Choosing a stake charged it before the pull");
        richGui.pullLever();
        FortuneService.Pull.Spun staked = richGui.lastSpin();
        helper.assertTrue(staked.spin().stake() != null && "copper".equals(staked.spin().stake().id), "The copper stake was not applied");
        helper.assertTrue(FortuneKit.BANK.chargesFor(rich.getUUID()) == 1 && FortuneKit.BANK.balance(rich.getUUID()) == 22L,
                "The copper stake was not charged exactly 8 Marks once");
        helper.assertTrue(staked.spin().reward().level() >= staked.spin().outcome().row().level(), "A stake lowered the reward");
        richGui.close();
        helper.succeed();
    }

    // ---- helpers ----

    private static List<ItemStack> guiStacks(MachineGui gui) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 54; i++) out.add(gui.getGuiElement(i).getItemStack().copy());
        return out;
    }

    private static List<ItemStack> inventory(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) out.add(player.getInventory().getItem(i).copy());
        return out;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }
}
