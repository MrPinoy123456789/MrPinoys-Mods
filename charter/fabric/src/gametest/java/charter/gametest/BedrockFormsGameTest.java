package charter.gametest;

import charter.mc.CharterServer;
import charter.mc.book.BedrockForms;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookInput;
import charter.mc.book.BookScreen;
import charter.mc.cmd.CharterCommands;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The native Bedrock form path, with a fake sender in place of Floodgate (which a gametest never loads): which screen
 * becomes which form, that B runs the exit, that a press runs the page's action directly, and that a stale or repeated
 * answer does nothing. Every test finishes in one tick, so the fake sender never sees another test's players.
 */
public final class BedrockFormsGameTest {

    /** Records what would have been sent and lets the test answer it. */
    private static final class Fake implements BedrockForms.Sender {
        final UUID bedrock;
        final List<BedrockForms.Form> forms = new ArrayList<>();
        final List<BedrockForms.Handler> handlers = new ArrayList<>();
        boolean accept = true;

        Fake(UUID bedrock) {
            this.bedrock = bedrock;
        }

        @Override
        public boolean isBedrock(UUID id) {
            return id.equals(bedrock);
        }

        @Override
        public boolean send(UUID id, BedrockForms.Form form, BedrockForms.Handler handler) {
            if (!accept) return false;
            forms.add(form);
            handlers.add(handler);
            return true;
        }

        BedrockForms.Form last() {
            return forms.get(forms.size() - 1);
        }

        BedrockForms.Handler lastHandler() {
            return handlers.get(handlers.size() - 1);
        }
    }

    private interface Body {
        void run(Fake fake, ServerPlayer player) throws Exception;
    }

    private static void with(GameTestHelper helper, Body body) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Fake fake = new Fake(player.getUUID());
        boolean knob = CharterServer.runtime().knobs.bedrockNativeForms;
        BedrockForms.install(fake);
        BedrockForms.executor(Runnable::run);
        try {
            body.run(fake, player);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BedrockForms.install(null);
            BedrockForms.executor(null);
            CharterServer.runtime().knobs.bedrockNativeForms = knob;
            BedrockForms.forget(player.getUUID());
        }
        helper.succeed();
    }

    private static List<String> entryLabels(BedrockForms.Form.Simple form) {
        return form.entries().stream().map(BedrockForms.Entry::label).toList();
    }

    @GameTest(maxTicks = 20)
    public void aListBecomesASimpleFormWithIconsAndItsExitLast(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            boolean[] ran = {false};
            BookScreen screen = BookDialogs.list("Home", List.of(Component.literal("One line")),
                    List.of(BookDialogs.act(null, "Go home", null, p -> ran[0] = true).icon("textures/items/bed_red"),
                            BookDialogs.act(null, "Go to spawn", null, p -> { })),
                    BookDialogs.close());
            BookDialogs.show(player, screen);
            helper.assertTrue(BookDialogs.LAST.get(player.getUUID()) == screen, "LAST did not keep the screen");
            helper.assertTrue(fake.forms.size() == 1 && fake.last() instanceof BedrockForms.Form.Simple, "A list was not a simple form");
            var form = (BedrockForms.Form.Simple) fake.last();
            helper.assertTrue(form.title().equals("Home") && form.content().equals("One line"), "Title or content: " + form);
            helper.assertTrue(entryLabels(form).equals(List.of("Go home", "Go to spawn", "Close")), "Entries: " + entryLabels(form));
            helper.assertTrue("textures/items/bed_red".equals(form.entries().get(0).icon()) && form.entries().get(1).icon() == null, "Icons: " + form.entries());
            helper.assertTrue(CharterCommands.tokens().newest(player.getUUID(), "Go home").isEmpty(), "A token was issued for a native form");
            fake.lastHandler().pressed(0);
            helper.assertTrue(ran[0], "The press did not run the page's action");
        });
    }

    @GameTest(maxTicks = 20)
    public void closingTheFormRunsTheExitAndAnAnswerCountsOnce(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            int[] back = {0};
            int[] first = {0};
            BookDialogs.show(player, BookDialogs.list("Page", List.of(),
                    List.of(BookDialogs.act(null, "Do it", null, p -> first[0]++)),
                    BookDialogs.act(null, "Back", null, p -> back[0]++)));
            var handler = fake.lastHandler();
            handler.closed();
            helper.assertTrue(back[0] == 1 && first[0] == 0, "Closing did not run only the exit: back " + back[0] + ", first " + first[0]);
            handler.pressed(0);
            handler.closed();
            helper.assertTrue(back[0] == 1 && first[0] == 0, "An answer to a used form ran again");

            BookDialogs.show(player, BookDialogs.list("Page", List.of(),
                    List.of(BookDialogs.act(null, "Do it", null, p -> first[0]++)), BookDialogs.close()));
            var stale = fake.lastHandler();
            BookDialogs.show(player, BookDialogs.list("Newer", List.of(),
                    List.of(BookDialogs.act(null, "Other", null, p -> { })), BookDialogs.close()));
            stale.pressed(0);
            helper.assertTrue(first[0] == 0, "An answer to a replaced form ran");
        });
    }

    @GameTest(maxTicks = 20)
    public void aConfirmIsAModalFormWithTheVerbFirst(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            int[] done = {0};
            int[] back = {0};
            BookDialogs.show(player, BookDialogs.confirm("Leave Azrak", List.of(Component.literal("You will lose your keep.")),
                    BookDialogs.act(null, "Leave Azrak", null, p -> done[0]++), BookDialogs.act(null, "Stay", null, p -> back[0]++)));
            helper.assertTrue(fake.last() instanceof BedrockForms.Form.Modal m && m.yes().equals("Leave Azrak") && m.no().equals("Stay"),
                    "A confirm was not a modal form: " + fake.last());
            fake.lastHandler().pressed(1);
            helper.assertTrue(done[0] == 0 && back[0] == 1, "No did not run the way out");
            BookDialogs.show(player, BookDialogs.confirm("Leave Azrak", List.of(),
                    BookDialogs.act(null, "Leave Azrak", null, p -> done[0]++), BookDialogs.act(null, "Stay", null, p -> back[0]++)));
            fake.lastHandler().pressed(0);
            helper.assertTrue(done[0] == 1, "Yes did not run the verb");
            BookDialogs.show(player, BookDialogs.confirm("Leave", List.of(),
                    BookDialogs.act(null, "Leave Azrak", null, p -> done[0]++), BookDialogs.act(null, "Stay", null, p -> back[0]++)));
            fake.lastHandler().closed();
            helper.assertTrue(done[0] == 1 && back[0] == 2, "Closing a confirm did not run the way out");
        });
    }

    @GameTest(maxTicks = 20)
    public void aFormWithInputsIsACustomFormAndDropdownsReturnTheirIds(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            List<String> got = new ArrayList<>();
            List<BookInput> inputs = List.of(
                    BookDialogs.choice("who", "Who", List.of("id-a", "id-b"), List.of("Alice", "Bob")),
                    BookDialogs.textInput("note", "Note", "hi", 20),
                    new BookInput.Slider("n", "How many", 1, 64, 1, 10));
            BookButton submit = BookDialogs.submit(null, "Send", null, inputs, form -> got.add(form.raw()));
            BookDialogs.show(player, BookDialogs.form("Pick", List.of(Component.literal("Choose.")), inputs, submit, BookDialogs.close()));
            helper.assertTrue(fake.last() instanceof BedrockForms.Form.Custom, "A form was not a custom form");
            var form = (BedrockForms.Form.Custom) fake.last();
            helper.assertTrue(form.fields().size() == 4 && form.fields().get(0) instanceof BedrockForms.Field.Label
                    && form.fields().get(1) instanceof BedrockForms.Field.Dropdown && form.fields().get(2) instanceof BedrockForms.Field.Input
                    && form.fields().get(3) instanceof BedrockForms.Field.Slider, "Fields: " + form.fields());
            fake.lastHandler().submitted(List.of("1", "a note", "5"));
            helper.assertTrue(got.equals(List.of("=id-b | a note | 5")), "The raw text the page reads: " + got);
            BookDialogs.show(player, BookDialogs.form("Pick", List.of(), inputs, submit, BookDialogs.close()));
            fake.lastHandler().submitted(List.of("9", "x", "5"));
            helper.assertTrue(got.get(1).startsWith("= | "), "An index out of range was not refused as empty: " + got);
        });
    }

    @GameTest(maxTicks = 20)
    public void javaPlayersAndTheKnobAndAFailedSendKeepTheJavaDialog(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            ServerPlayer java = helper.makeMockServerPlayerInLevel();
            BookDialogs.show(java, BookDialogs.list("J", List.of(), List.of(BookDialogs.act(null, "A", null, p -> { })), BookDialogs.close()));
            helper.assertTrue(fake.forms.isEmpty() && CharterCommands.tokens().newest(java.getUUID(), "A").isPresent(),
                    "A Java player was sent a Bedrock form");

            CharterServer.runtime().knobs.bedrockNativeForms = false;
            BookDialogs.show(player, BookDialogs.list("K", List.of(), List.of(BookDialogs.act(null, "B", null, p -> { })), BookDialogs.close()));
            helper.assertTrue(fake.forms.isEmpty() && CharterCommands.tokens().newest(player.getUUID(), "B").isPresent(),
                    "The knob did not turn the native forms off");

            CharterServer.runtime().knobs.bedrockNativeForms = true;
            fake.accept = false;
            BookDialogs.show(player, BookDialogs.list("F", List.of(), List.of(BookDialogs.act(null, "C", null, p -> { })), BookDialogs.close()));
            helper.assertTrue(fake.forms.isEmpty() && CharterCommands.tokens().newest(player.getUUID(), "C").isPresent(),
                    "A refused send did not fall back to the Java dialog");
        });
    }

    @GameTest(maxTicks = 20)
    public void bodyColoursBecomeLegacyCodes(GameTestHelper helper) {
        with(helper, (fake, player) -> {
            BookDialogs.show(player, BookDialogs.list("C",
                    BookDialogs.withNotice("You may not.", List.of(Component.literal("Plain").withStyle(ChatFormatting.GRAY))),
                    List.of(BookDialogs.act(null, "A", null, p -> { })), BookDialogs.close()));
            String content = ((BedrockForms.Form.Simple) fake.last()).content();
            helper.assertTrue(content.contains("§cYou may not.§r"), "A red refusal: " + content);
            helper.assertTrue(content.contains("§7Plain§r"), "A grey line: " + content);
        });
    }
}
