package charter.gametest;

import charter.id.OrgId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.Text;
import charter.mc.book.BookDialogs;
import charter.mc.book.WarPages;
import charter.mc.cmd.CharterCommands;
import charter.mc.land.LandItems;
import charter.mc.war.SiegeWorld;
import charter.siege.SiegeEvents;
import charter.siege.WarBook;
import charter.time.Durations;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * War to capture an outpost, through the book and the world (protected keeps, O1): the War page, declaring with its
 * confirm, the notice, the hold of the chunk by a real player, and the capture taking the banner down. The clock is
 * shared with every other test, so nothing here moves it by more than a minute: the goal's times are set by hand.
 */
public final class WarGameTest {

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    private static List<String> body(ServerPlayer player) {
        Dialog dialog = BookDialogs.LAST.get(player.getUUID());
        List<String> out = new ArrayList<>();
        for (var b : dialog.common().body()) {
            if (b instanceof PlainMessage m) out.add(m.contents().getString());
        }
        return out;
    }

    private static Core outpost(CharterRuntime rt, OrgId org) {
        return rt.state.coresOf(org).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
    }

    @GameTest(maxTicks = 200)
    public void aWarIsDeclaredThroughTheBookAndAnOutpostIsCaptured(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            int savedSeconds = rt.knobs.presenceMinMemberSeconds;
            rt.knobs.presenceMinMemberSeconds = 0;
            try {
                ServerPlayer attackerLord = kit.faction("Hawk");
                ServerPlayer defenderLord = kit.faction("Dove");
                OrgId hawk = kit.orgOf(attackerLord);
                OrgId dove = kit.orgOf(defenderLord);
                kit.placeOn(attackerLord, kit.hold(attackerLord, LandItems.WAR_BANNER), kit.pos(-8, 0));
                kit.placeOn(defenderLord, kit.hold(defenderLord, LandItems.WAR_BANNER), kit.pos(-20, 0));
                Core mine = outpost(rt, hawk);
                Core theirs = outpost(rt, dove);
                mine.setPhase(CampPhase.ACTIVE, 0);
                theirs.setPhase(CampPhase.ACTIVE, 0);
                helper.assertTrue(theirs.deposit > 0, "The defender's outpost holds no deposit");

                // The War page names the other nation's outpost; the confirm says what it takes; declaring works.
                WarPages.page(attackerLord, null);
                String name = rt.describe.org(dove);
                String button = Text.get("book.war.declare_button", "org", name, "x", theirs.center.x() * 16 + 8, "z", theirs.center.z() * 16 + 8);
                press(helper, attackerLord, button);
                String confirm = String.join("\n", body(attackerLord));
                helper.assertTrue(confirm.contains("raised outpost of its own"), "The confirm page does not say the declarer needs an outpost: " + confirm);
                helper.assertTrue(confirm.contains("12 hours"), "The confirm page does not give the notice: " + confirm);
                press(helper, attackerLord, Text.get("book.war.declare_yes"));
                WarBook.Goal goal = rt.state.wars.against(theirs.id).orElse(null);
                helper.assertTrue(goal != null, "Declaring through the book made no goal");
                helper.assertTrue(rt.state.history.has(SiegeEvents.WarDeclared.class), "The declaration was not recorded");

                // The defenders are told when capture opens.
                WarPages.page(defenderLord, null);
                String against = String.join("\n", body(defenderLord));
                helper.assertTrue(against.contains("Nothing can be done to it for"), "The defenders' War page does not give the time: " + against);

                // The notice runs out; an attacker holds the chunk.
                goal.opensAt = rt.state.siegeClock.now();
                BlockPos banner = kit.pos(-20, 0);
                attackerLord.teleportTo(banner.getX() + 0.5, kit.y, banner.getZ() + 3.5);
                SiegeWorld.beforeStep(kit.server, rt);
                rt.sim.advance(Durations.MINUTE);
                helper.assertTrue(goal.opened, "Capture did not open when the notice ran out");
                helper.assertTrue(rt.state.core(theirs.id).isPresent(), "The outpost fell the moment capture opened");
                goal.progress = Durations.minutes(rt.knobs.outpostCaptureMinutes) - Durations.MINUTE / 2;
                long before = rt.state.org(hawk).orElseThrow().ledger.balance(Currency.DIAMOND);
                long deposit = theirs.deposit;
                SiegeWorld.beforeStep(kit.server, rt);
                rt.sim.advance(Durations.MINUTE);
                helper.assertTrue(rt.state.core(theirs.id).isEmpty(), "Holding the chunk did not capture the outpost");
                helper.assertTrue(rt.state.org(hawk).orElseThrow().ledger.balance(Currency.DIAMOND) == before + deposit,
                        "The deposit did not go to the attackers");
                helper.assertTrue(rt.state.history.has(SiegeEvents.OutpostCaptured.class), "The capture was not recorded");
                helper.assertTrue(kit.level.getBlockState(banner).isAir(),
                        "The captured outpost's banner is still standing");
                helper.assertTrue(rt.state.wars.windowHolder(theirs.center, rt.state.siegeClock.now()).equals(java.util.Optional.of(hawk)),
                        "The captors have no window on the chunk");
                helper.succeed();
            } finally {
                rt.knobs.presenceMinMemberSeconds = savedSeconds;
            }
        }
    }
}
