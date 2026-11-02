package charter.gametest;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.Text;
import charter.mc.book.AdminBook;
import charter.mc.book.AdminPages;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookScreen;
import charter.mc.cmd.CharterCommands;
import charter.mc.land.Fixtures;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** The Admin Book, the operators' menu it opens, and the operator commands under it, driven by UUID. */
public final class AdminBookGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static ServerPlayer operator(LandKit kit) {
        ServerPlayer p = kit.outsider();
        kit.server.getPlayerList().op(p.nameAndId(), java.util.Optional.of(
                net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
        return p;
    }

    private static void as(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    private static void submit(GameTestHelper helper, ServerPlayer player, String label, String raw) {
        var forms = CharterCommands.forms();
        String token = forms.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] submit button was shown");
        forms.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(new CharterCommands.Form(player, raw));
    }

    private static String title(ServerPlayer player) {
        BookScreen screen = BookDialogs.LAST.get(player.getUUID());
        return screen == null ? "" : screen.title();
    }

    @GameTest(maxTicks = 200)
    public void anOperatorOpensTheBookAndAnyoneElseLosesIt(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = operator(kit);
            op.setItemInHand(InteractionHand.MAIN_HAND, AdminBook.create());
            BookDialogs.LAST.remove(op.getUUID());
            helper.assertTrue(AdminBook.use(op, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS_SERVER, "The operator's book did nothing");
            helper.assertTrue(title(op).equals(Text.get("admin.menu.title")), "The book did not open the operators' menu: " + title(op));

            ServerPlayer plain = kit.outsider();
            plain.setItemInHand(InteractionHand.MAIN_HAND, AdminBook.create());
            BookDialogs.LAST.remove(plain.getUUID());
            AdminBook.use(plain, InteractionHand.MAIN_HAND);
            helper.assertTrue(BookDialogs.LAST.get(plain.getUUID()) == null, "A player who is not an operator opened the menu");
            helper.assertTrue(AdminBook.count(plain) == 0, "A player who is not an operator kept the book");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void onlyOperatorsKeepOneAndItVanishesOnDeath(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer plain = kit.outsider();
            plain.getInventory().setItem(20, AdminBook.create());
            AdminBook.police(plain);
            helper.assertTrue(AdminBook.count(plain) == 0, "The pass left a book with someone who is not an operator");

            ServerPlayer op = operator(kit);
            for (int i = 0; i < op.getInventory().getContainerSize(); i++) op.getInventory().setItem(i, ItemStack.EMPTY);
            AdminBook.ensure(op);
            helper.assertTrue(AdminBook.count(op) == 1, "An operator was not given a book");
            helper.assertTrue(!AdminBook.isBook(op.getInventory().getItem(8)), "The Admin Book took hotbar slot 9, the Charter book's");
            op.getInventory().setItem(21, AdminBook.create());
            AdminBook.police(op);
            helper.assertTrue(AdminBook.count(op) == 1, "An operator kept more than one book");
            AdminBook.vanish(op);
            helper.assertTrue(AdminBook.count(op) == 0, "The book did not vanish on death");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aDeopBetweenOpeningAndPressingIsRefused(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = operator(kit);
            AdminPages.open(op);
            kit.server.getPlayerList().deop(op.nameAndId());
            press(helper, op, Text.get("admin.menu.button.players"));
            helper.assertTrue(title(op).equals(Text.get("admin.menu.title")), "A deopped player went on into the menu: " + title(op));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void theNewCommandsWorkByUuidAndId(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Cmds");
            OrgId org = kit.orgOf(lord);
            kit.keep(lord);
            ServerPlayer op = operator(kit);
            ServerPlayer gil = kit.outsider();
            kit.rt.state.touch(id(gil), gil.getPlainTextName(), kit.rt.clock.now());

            as(op, "charter admin faction add \"" + org + "\" " + gil.getUUID());
            helper.assertTrue(kit.rt.state.isMember(id(gil), org), "faction add by org id and UUID did nothing");
            as(op, "charter admin faction remove \"" + org + "\" " + gil.getUUID());
            helper.assertTrue(!kit.rt.state.isMember(id(gil), org), "faction remove did not take the member out");

            String fresh = "Ren" + Long.toString(System.nanoTime() & 0xFFFFFL, 36);
            as(op, "charter admin faction rename \"" + org + "\" " + fresh);
            helper.assertTrue(kit.rt.state.org(org).orElseThrow().name().equals(fresh), "faction rename did not rename");

            as(op, "charter admin goto \"" + fresh + "\"");
            var core = kit.rt.state.coresOf(org).stream().filter(c -> c.isKeep()).findFirst().orElseThrow();
            Fixtures.Fixture nexus = Fixtures.of(kit.rt.state).first(core.id, Fixtures.Kind.NEXUS);
            helper.assertTrue(nexus != null && op.blockPosition().distManhattan(new net.minecraft.core.BlockPos(nexus.x(), nexus.y() + 1, nexus.z())) <= 2,
                    "goto did not take the operator to the Nexus: " + op.blockPosition());

            int listed = kit.server.getCommands().getDispatcher().execute("charter admin keeps", op.createCommandSourceStack());
            helper.assertTrue(listed >= 1, "keeps listed no keep");
            int kingdoms = kit.server.getCommands().getDispatcher().execute("charter admin kingdoms", op.createCommandSourceStack());
            helper.assertTrue(kingdoms >= 1, "kingdoms listed no kingdom");
            int info = kit.server.getCommands().getDispatcher().execute("charter admin player " + lord.getUUID(), op.createCommandSourceStack());
            helper.assertTrue(info == 1, "player found nobody by UUID");
            int help = kit.server.getCommands().getDispatcher().execute("charter admin help", op.createCommandSourceStack());
            helper.assertTrue(help > 20, "help listed too little: " + help);
            helper.succeed();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @GameTest(maxTicks = 200)
    public void theMenuPressesThroughToRealChanges(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Menu2");
            OrgId org = kit.orgOf(lord);
            String name = kit.rt.describe.org(org);
            ServerPlayer op = operator(kit);

            AdminPages.open(op);
            press(helper, op, Text.get("admin.menu.button.kingdoms"));
            press(helper, op, name);
            BookScreen page = BookDialogs.LAST.get(op.getUUID());
            helper.assertTrue(page.buttons().stream().map(BookButton::label).toList().contains(Text.get("admin.kingdom.button.goto")),
                    "The kingdom page has no Go to the Nexus");
            press(helper, op, Text.get("admin.kingdom.button.rename"));
            String fresh = "Men" + Long.toString(System.nanoTime() & 0xFFFFFL, 36);
            submit(helper, op, Text.get("admin.rename.button"), "=" + fresh);
            helper.assertTrue(kit.rt.state.org(org).orElseThrow().name().equals(fresh), "Rename from the menu did nothing");

            ServerPlayer gil = kit.outsider();
            kit.rt.engine.run(new charter.engine.Actor.Admin("gametest"), new charter.org.actions.AddMember(org, id(gil)));
            AdminPages.playerPage(op, gil.getUUID());
            press(helper, op, Text.get("admin.player.button.remove"));
            helper.assertTrue(BookDialogs.LAST.get(op.getUUID()).kind() == BookScreen.Kind.CONFIRM, "Taking a member out did not ask first");
            helper.assertTrue(kit.rt.state.isMember(id(gil), org), "Asking took the member out already");
            press(helper, op, Text.get("admin.remove.yes", "player", kit.rt.describe.player(id(gil))));
            helper.assertTrue(!kit.rt.state.isMember(id(gil), org), "Confirming did not take the member out");

            AdminPages.playerPage(op, gil.getUUID());
            press(helper, op, Text.get("admin.player.button.race"));
            submit(helper, op, Text.get("admin.race.button"), "=" + org);
            helper.assertTrue(kit.rt.state.raceOf(id(gil)).filter(org::equals).isPresent(), "Set race from the menu did nothing");
            helper.succeed();
        }
    }
}
