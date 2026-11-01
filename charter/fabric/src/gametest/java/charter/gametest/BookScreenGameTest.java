package charter.gametest;

import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookResult;
import charter.mc.book.BookScreen;
import charter.mc.book.MemberPages;
import charter.mc.book.NewcomerPages;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** The rules every book screen obeys, whatever draws it: an exit, at most 8 buttons, paging, green and red results. */
public final class BookScreenGameTest {

    private static void press(GameTestHelper helper, ServerPlayer player, String label) {
        var tokens = CharterCommands.tokens();
        String token = tokens.newest(player.getUUID(), label).orElse(null);
        helper.assertTrue(token != null, "No [" + label + "] button was shown to the player");
        tokens.take(token, player.getUUID(), System.currentTimeMillis()).orElseThrow().accept(player);
    }

    private static List<BookButton> items(int n) {
        List<BookButton> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(BookDialogs.act(null, "Item " + i, null, p -> { }));
        return out;
    }

    private static List<String> labels(BookScreen screen) {
        return screen.buttons().stream().map(BookButton::label).toList();
    }

    @GameTest(maxTicks = 20)
    public void aScreenWithoutAnExitIsRefused(GameTestHelper helper) {
        boolean refused = false;
        try {
            BookDialogs.list("T", List.of(), items(2), null);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        helper.assertTrue(refused, "A screen with no exit was built");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aLongListIsPagedSixToAPage(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BookScreen first = BookDialogs.list("People", List.of(), items(14), BookDialogs.close());
        helper.assertTrue(first.buttons().size() <= BookScreen.MAX_BUTTONS, "The first page holds " + first.buttons().size());
        helper.assertTrue(labels(first).contains("Item 6") && !labels(first).contains("Item 7"), "Page 1 is not items 1 to 6: " + labels(first));
        helper.assertTrue(first.bodyText().get(0).contains("1 of 3"), "The body does not say the page: " + first.bodyText());
        helper.assertTrue(labels(first).contains("Next") && !labels(first).contains("Previous"), "Page 1 buttons: " + labels(first));
        BookDialogs.show(player, first);
        press(helper, player, "Next");
        BookScreen second = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(second.bodyText().get(0).contains("2 of 3"), "Next did not open page 2: " + second.bodyText());
        helper.assertTrue(labels(second).contains("Item 7") && labels(second).contains("Previous") && labels(second).contains("Next"),
                "Page 2 buttons: " + labels(second));
        helper.assertTrue(second.buttons().size() == 8, "A middle page should hold 8 buttons, held " + second.buttons().size());
        press(helper, player, "Next");
        BookScreen third = BookDialogs.LAST.get(player.getUUID());
        helper.assertTrue(labels(third).contains("Item 14") && !labels(third).contains("Next"), "The last page: " + labels(third));
        press(helper, player, "Previous");
        helper.assertTrue(BookDialogs.LAST.get(player.getUUID()).bodyText().get(0).contains("2 of 3"), "Previous did not go back");
        helper.assertTrue(BookDialogs.LAST.get(player.getUUID()).exit() != null, "A page lost its exit");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void eightButtonsFitWithoutPaging(GameTestHelper helper) {
        BookScreen screen = BookDialogs.list("Eight", List.of(), items(8), BookDialogs.close());
        helper.assertTrue(screen.buttons().size() == 8 && screen.title().equals("Eight"), "Eight buttons were paged");
        helper.assertTrue(BookDialogs.list("Nine", List.of(), items(9), BookDialogs.close()).buttons().size() <= 8, "Nine buttons were not paged");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aListWithNoButtonsBecomesANoticeWithItsExit(GameTestHelper helper) {
        BookScreen screen = BookDialogs.list("Empty", List.of(), List.of(), BookDialogs.close());
        helper.assertTrue(screen.kind() == BookScreen.Kind.NOTICE, "An empty list stayed a list");
        helper.assertTrue(!screen.body().isEmpty() && screen.exit() != null, "An empty notice has no line or no exit");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aSuccessIsGreenAndARefusalIsRed(GameTestHelper helper) {
        Component ok = BookDialogs.withNotice(BookResult.ok("Admitted Bob."), List.of()).get(0);
        Component bad = BookDialogs.withNotice("You may not.", List.of()).get(0);
        helper.assertTrue(ok.getString().equals("Admitted Bob."), "The success mark reached the player: " + ok.getString());
        helper.assertTrue(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(ok.getStyle().getColor()), "A success was not green");
        helper.assertTrue(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.RED).equals(bad.getStyle().getColor()), "A refusal was not red");
        helper.assertTrue(BookDialogs.withNotice(null, List.of()).isEmpty(), "No notice still added a line");
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void bothHomesObeyTheStructuralRules(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Rules");
            ServerPlayer alone = kit.outsider();
            BookDialogs.LAST.remove(lord.getUUID());
            MemberPages.home(lord, null);
            NewcomerPages.home(alone, null);
            for (ServerPlayer p : List.of(lord, alone)) {
                BookScreen screen = BookDialogs.LAST.get(p.getUUID());
                helper.assertTrue(screen.exit() != null, "A home has no exit");
                helper.assertTrue(screen.buttons().size() <= BookScreen.MAX_BUTTONS, "A home holds " + screen.buttons().size() + " buttons");
            }
            helper.succeed();
        }
    }
}
