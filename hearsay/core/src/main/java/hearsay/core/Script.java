package hearsay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scripted scene: ordered steps with beats between them.
 *
 * <p>Each step is one of {@code say}, {@code narrate}, {@code wait} or
 * {@code action}. Speaker indices are preserved for multi-speaker encounters.
 *
 * <p>The empty script and the "..." line are both legitimate content.
 */
public final class Script {

    private final List<Step> steps;

    public Script(List<Step> steps) {
        if (steps == null) {
            this.steps = List.of();
        } else {
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** One script step. */
    public static final class Step {
        public enum Kind { SAY, NARRATE, WAIT, ACTION }

        private final Kind kind;
        private final String text;
        private final int waitTicks;
        private final int speaker;
        private final String action;

        private Step(Kind kind, String text, int waitTicks, int speaker, String action) {
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.waitTicks = waitTicks;
            this.speaker = speaker;
            this.action = action == null ? "" : action;
        }

        public static Step say(String text, int speaker) {
            return new Step(Kind.SAY, text, 0, Math.max(0, speaker), "");
        }

        public static Step narrate(String text) {
            return new Step(Kind.NARRATE, text, 0, -1, "");
        }

        public static Step waitTicks(int ticks) {
            return new Step(Kind.WAIT, "", Math.max(0, ticks), -1, "");
        }

        public static Step action(String action) {
            return new Step(Kind.ACTION, "", 0, -1, action);
        }

        public Kind kind() { return kind; }
        public String text() { return text; }
        public int waitTicks() { return waitTicks; }
        public int speaker() { return speaker; }
        public String action() { return action; }

        @Override
        public String toString() {
            return switch (kind) {
                case SAY -> "say:" + text;
                case NARRATE -> "narrate:" + text;
                case WAIT -> "wait:" + waitTicks;
                case ACTION -> "action:" + action;
            };
        }
    }
}
