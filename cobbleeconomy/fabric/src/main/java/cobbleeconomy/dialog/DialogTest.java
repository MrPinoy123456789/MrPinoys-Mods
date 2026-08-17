package cobbleeconomy.dialog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * A throwaway diagnostic dialog: one of every input control this mod might eventually
 * use, wired to {@link DialogRouter#report} instead of a real feature.
 *
 * <p>{@code DIALOGS_SPEC.md} Part 3 keeps every planned price/quantity field on
 * {@code TextInput} because the {@link BooleanInput} and {@link NumberRangeInput}
 * submitted-tag shapes are unconfirmed anywhere in this workspace. This is how that gets
 * confirmed cheaply, in play, before betting a real shop price on a guessed encoding.
 *
 * <p>Delete this class (and the {@code dialog_test} case in {@link DialogRouter}) once
 * Part 2 of the spec is actually implemented and the real dialogs exist to look at
 * instead.
 */
public final class DialogTest {

    public static final String SUBMIT = "dialog_test";

    private static final String KEY_TEXT = "text_field";
    private static final String KEY_BOOL = "bool_field";
    private static final String KEY_NUMBER = "number_field";
    private static final String KEY_OPTION = "option_field";

    private DialogTest() {}

    public static void open(ServerPlayer player) {
        List<Input> inputs = List.of(
                new Input(KEY_TEXT, new TextInput(
                        DialogKit.WIDE, Component.literal("Text"), true,
                        "", 32, Optional.empty())),
                new Input(KEY_BOOL, new BooleanInput(
                        Component.literal("Boolean"), true, "true", "false")),
                new Input(KEY_NUMBER, new NumberRangeInput(
                        DialogKit.WIDE, Component.literal("Number"), "%s",
                        new NumberRangeInput.RangeInfo(0f, 100f, Optional.of(50f), Optional.of(1f)))),
                new Input(KEY_OPTION, new SingleOptionInput(
                        DialogKit.WIDE,
                        List.of(
                                new SingleOptionInput.Entry("alpha", Optional.of(Component.literal("Alpha")), true),
                                new SingleOptionInput.Entry("beta", Optional.of(Component.literal("Beta")), false)),
                        Component.literal("Option"), true)));

        Dialog dialog = new NoticeDialog(
                DialogKit.common("Dialog test",
                        List.of(DialogKit.text("Set each field, then submit. The result "
                                + "prints in chat and in the server log.")),
                        inputs),
                DialogKit.button("Submit", null, DialogKit.submit(SUBMIT, new CompoundTag())));

        DialogKit.show(player, dialog);
    }
}
