package charter.gametest;

import charter.id.OrgId;
import charter.mc.Text;
import charter.mc.book.AdminPages;
import charter.mc.book.BookDialogs;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** The operators' menu: {@code /charter admin} on its own, and every page and form behind it. */
public final class AdminMenuGameTest {

    private static ServerPlayer operator(LandKit kit) {
        ServerPlayer p = kit.outsider();
        // The test server's default operator level is not an operator's, so the level is named.
        kit.server.getPlayerList().op(p.nameAndId(), java.util.Optional.of(
                net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
        return p;
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    /** Sends a form the way the client does: the raw text after the token. */
    private static void submit(GameTestHelper helper, ServerPlayer player, String label, String raw) {
        var forms = CharterCommands.forms();
        String token = forms.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] submit button was shown to the player");
        forms.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(new CharterCommands.Form(player, raw));
    }

    private static Dialog shown(GameTestHelper helper, ServerPlayer player, String what) {
        Dialog dialog = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(dialog != null, "No dialog was sent for " + what);
        helper.assertTrue(!BookDialogs.buttons(dialog).isEmpty(), "The " + what + " dialog has no button");
        return dialog;
    }

    private static String title(GameTestHelper helper, ServerPlayer player) {
        return shown(helper, player, "the page").common().title().getString();
    }

    private static String body(ServerPlayer player) {
        Dialog dialog = BookDialogs.LAST.get(player.getUUID());
        List<String> out = new ArrayList<>();
        for (var b : dialog.common().body()) {
            if (b instanceof PlainMessage m) out.add(m.contents().getString());
        }
        return String.join("\n", out);
    }

    private static boolean hasButton(ServerPlayer player, String label) {
        return BookDialogs.buttons(BookDialogs.LAST.get(player.getUUID())).stream()
                .anyMatch(b -> b.button().label().getString().equals(label));
    }

    @GameTest(maxTicks = 200)
    public void theCommandOpensTheMenuForAnOperator(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = operator(kit);
            BookDialogs.LAST.remove(op.getUUID());
            kit.server.getCommands().performPrefixedCommand(op.createCommandSourceStack(), "charter admin");
            helper.assertTrue(title(helper, op).equals(Text.get("admin.menu.title")), "/charter admin did not open the admin menu");
            for (String key : List.of("admin.menu.button.kingdoms", "admin.menu.button.create", "admin.menu.button.sites",
                    "admin.menu.button.tools")) {
                helper.assertTrue(hasButton(op, Text.get(key)), "The home page has no [" + Text.get(key) + "] button");
            }
            kit.server.getPlayerList().deop(op.nameAndId());
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aPlayerWhoIsNotAnOperatorGetsNoMenu(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer plain = kit.outsider();
            BookDialogs.LAST.remove(plain.getUUID());
            kit.server.getCommands().performPrefixedCommand(plain.createCommandSourceStack(), "charter admin");
            helper.assertTrue(BookDialogs.LAST.get(plain.getUUID()) == null, "A player who is not an operator was shown the admin menu");
            AdminPages.open(plain);
            helper.assertTrue(BookDialogs.LAST.get(plain.getUUID()) == null, "AdminPages.open showed the menu to a player who is not an operator");

            // A link outlives a demotion: pressing it after the operator lost the permission does nothing.
            ServerPlayer op = operator(kit);
            AdminPages.open(op);
            String home = title(helper, op);
            kit.server.getPlayerList().deop(op.nameAndId());
            press(helper, op, Text.get("admin.menu.button.kingdoms"));
            helper.assertTrue(title(helper, op).equals(home), "A demoted operator still moved through the menu");

            // A form token that is unknown does nothing but say so.
            kit.server.getCommands().performPrefixedCommand(op.createCommandSourceStack(), "charter form nosuchtoken =1");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 300)
    public void aKingdomPageChangesTheKingdomThroughTheRealCommands(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Menu");
            OrgId id = kit.orgOf(lord);
            var org = kit.rt.state.org(id).orElseThrow();
            String name = org.name();
            ServerPlayer op = operator(kit);

            AdminPages.open(op);
            press(helper, op, Text.get("admin.menu.button.kingdoms"));
            helper.assertTrue(title(helper, op).equals(Text.get("admin.kingdoms.title")), "The Kingdoms page did not open");
            helper.assertTrue(hasButton(op, name), "The Kingdoms page does not list [" + name + "]");
            press(helper, op, name);
            helper.assertTrue(title(helper, op).equals(name), "The kingdom page did not open");

            // The claim bonus form: a good value is applied through the real command.
            press(helper, op, Text.get("admin.kingdom.button.bonus"));
            helper.assertTrue(shown(helper, op, "the bonus form").common().inputs().size() == 1, "The bonus form should have one input");
            submit(helper, op, Text.get("admin.bonus.button"), "=7");
            helper.assertTrue(org.claimBonus == 7, "The bonus form did not set the bonus: " + org.claimBonus);
            helper.assertTrue(title(helper, op).equals(name), "The bonus form did not return to the kingdom page");
            helper.assertTrue(body(op).contains("7"), "The page does not show what the command said: " + body(op));

            // Nonsense and the wrong number of values are refused without a change.
            press(helper, op, Text.get("admin.kingdom.button.bonus"));
            submit(helper, op, Text.get("admin.bonus.button"), "=abc");
            helper.assertTrue(org.claimBonus == 7, "A word set the bonus");
            helper.assertTrue(body(op).contains(Text.get("admin.bonus.bad")), "A bad bonus was not explained: " + body(op));
            press(helper, op, Text.get("admin.kingdom.button.bonus"));
            submit(helper, op, Text.get("admin.bonus.button"), "=1 | 2");
            helper.assertTrue(org.claimBonus == 7, "A form with the wrong number of values changed the bonus");

            // The admin flag flips, and the button says so.
            helper.assertTrue(!org.adminKingdom, "The setup kingdom is already an admin kingdom");
            press(helper, op, Text.get("admin.kingdom.button.make_admin"));
            helper.assertTrue(org.adminKingdom, "The button did not make the kingdom an admin kingdom");
            helper.assertTrue(hasButton(op, Text.get("admin.kingdom.button.make_ordinary")), "The page did not offer to undo it");
            press(helper, op, Text.get("admin.kingdom.button.make_ordinary"));
            helper.assertTrue(!org.adminKingdom, "The button did not undo the admin flag");

            // Dissolving asks first, and Keep it changes nothing.
            press(helper, op, Text.get("admin.kingdom.button.dissolve"));
            helper.assertTrue(BookDialogs.LAST.get(op.getUUID()) instanceof ConfirmationDialog, "Dissolve did not ask for confirmation");
            press(helper, op, Text.get("admin.dissolve.no"));
            helper.assertTrue(kit.rt.state.org(id).orElseThrow().exists(), "Keep it dissolved the kingdom");
            helper.assertTrue(title(helper, op).equals(name), "Keep it did not return to the kingdom page");

            kit.server.getPlayerList().deop(op.nameAndId());
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 300)
    public void theToolPagesRunTheirCommandsAndShowTheAnswer(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = operator(kit);
            AdminPages.open(op);

            press(helper, op, Text.get("admin.menu.button.tools"));
            helper.assertTrue(title(helper, op).equals(Text.get("admin.tools.title")), "The tools page did not open");
            press(helper, op, Text.get("admin.tools.button.save"));
            helper.assertTrue(title(helper, op).equals(Text.get("admin.tools.title")), "Saving did not return to the tools page");
            helper.assertTrue(!body(op).isBlank(), "Saving showed no answer");

            press(helper, op, Text.get("admin.tools.button.vote"));
            helper.assertTrue(shown(helper, op, "the vote form").common().inputs().size() == 1, "The vote form should have one input");
            submit(helper, op, Text.get("admin.vote.button"), "=999999");
            helper.assertTrue(body(op).contains("999999"), "A missing vote was not reported: " + body(op));

            press(helper, op, Text.get("admin.tools.button.vote"));
            submit(helper, op, Text.get("admin.vote.button"), "=0");
            helper.assertTrue(body(op).contains(Text.get("admin.vote.bad")), "A vote number of 0 was not refused: " + body(op));

            // The sites page opens and offers its buttons.
            AdminPages.open(op);
            press(helper, op, Text.get("admin.menu.button.sites"));
            helper.assertTrue(title(helper, op).equals(Text.get("admin.sites.title")), "The sites page did not open");
            helper.assertTrue(hasButton(op, Text.get("admin.sites.button.respawn")), "The sites page has no respawn button");

            // The new kingdom form starts at the operator's position and refuses a blank name.
            AdminPages.open(op);
            press(helper, op, Text.get("admin.menu.button.create"));
            helper.assertTrue(shown(helper, op, "the create form").common().inputs().size() == 4, "The create form should have four inputs");
            int before = kit.rt.state.orgs().size();
            submit(helper, op, Text.get("admin.create.button"), "= | 0 | 64 | 0");
            helper.assertTrue(kit.rt.state.orgs().size() == before, "A blank name made a kingdom");
            helper.assertTrue(body(op).contains(Text.get("admin.create.bad_name")), "A blank name was not explained: " + body(op));
            submit(helper, op, Text.get("admin.create.button"), "=Nowhere | north | 64 | 0");
            helper.assertTrue(kit.rt.state.orgs().size() == before, "A word for a coordinate made a kingdom");

            kit.server.getPlayerList().deop(op.nameAndId());
            helper.succeed();
        }
    }
}
