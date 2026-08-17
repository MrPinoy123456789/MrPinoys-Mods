package quizengine.mc.dialog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.input.TextInput;
import quizengine.Engine;
import quizengine.Option;
import quizengine.Round;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The three screens a player ever sees: write an answer, pick a trivia option, vote.
 *
 * <p>Every one carries its round id, because a dialog is a window a player can leave
 * open. Rounds advance on a timer, so a submission arriving from a screen opened two
 * phases ago is completely ordinary and must be rejected rather than applied to
 * whatever is running now.
 */
public final class QuizDialogs {

    private QuizDialogs() {}

    static final String KEY_ROUND = "round";
    static final String KEY_OPTION = "option";
    static final String KEY_ANSWER = "answer";

    public static final String SUBMIT_TEXT = "quiplash_submit";
    public static final String ANSWER = "trivia_answer";
    public static final String VOTE = "quiplash_vote";

    /**
     * Quiplash's write-in box, and the reason this whole layer exists: the alternative
     * is typing a secret answer into the chat box, where forgetting {@code /quiz submit}
     * broadcasts it to everyone.
     */
    public static Dialog write(Round round) {
        Input answer = new Input(KEY_ANSWER, new TextInput(
                DialogKit.WIDE,
                Component.literal("Your answer"),
                false,
                "",
                Engine.MAX_SUBMISSION_LENGTH,
                Optional.empty()));

        return new NoticeDialog(
                DialogKit.common("Hot take!",
                        List.of(DialogKit.text(round.prompt())),
                        List.of(answer)),
                DialogKit.button("Submit",
                        "Nobody sees it until voting opens",
                        DialogKit.submit(SUBMIT_TEXT, context(round))));
    }

    /** Trivia's fixed options, one button each. */
    public static Dialog answer(Round round) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Option option : round.options()) {
            buttons.add(DialogKit.button(option.text(), null,
                    DialogKit.submit(ANSWER, context(round, option.index()))));
        }
        return options("Trivia!", round.prompt(), buttons);
    }

    /**
     * The Quiplash ballot — the options are whatever people wrote, so they are read off
     * the round rather than out of the content files.
     */
    public static Dialog ballot(Round round) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int index : round.submittedOptions()) {
            buttons.add(DialogKit.button(round.optionText(index), null,
                    DialogKit.submit(VOTE, context(round, index))));
        }
        return options("Vote", round.prompt(), buttons);
    }

    /**
     * One column, always. Answers are written by players and run to a sentence, and a
     * two-column grid truncates them into nonsense.
     */
    private static Dialog options(String title, String prompt, List<ActionButton> buttons) {
        return new MultiActionDialog(
                DialogKit.common(title, List.of(DialogKit.text(prompt)), List.of()),
                buttons,
                Optional.empty(),
                1);
    }

    private static CompoundTag context(Round round) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ROUND, round.roundId());
        return tag;
    }

    private static CompoundTag context(Round round, int option) {
        CompoundTag tag = context(round);
        tag.putInt(KEY_OPTION, option);
        return tag;
    }
}
