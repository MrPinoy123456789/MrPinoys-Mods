package smalltalk.dialogue;

import net.minecraft.util.RandomSource;
import smalltalk.identity.Personality;

import java.util.List;

/**
 * Weighted toward specificity (SPEC.md section 4.3): walk the passing
 * contexts most-specific first, and speak from the first one that actually
 * has lines configured for this personality. Runs once, at dialog-open.
 */
public final class LineSelector {

    private LineSelector() {}

    public static String select(Personality personality, Situation situation, RandomSource random) {
        for (DialogueContext context : ContextEvaluator.passing(situation)) {
            List<String> pool = DialogueLinesConfig.linesFor(personality, context.id());
            if (!pool.isEmpty()) {
                return pool.get(random.nextInt(pool.size()));
            }
        }
        // Unreachable in practice: "generic" always passes and defaults always
        // populate it, but a broken lines.json edit shouldn't crash the dialog.
        return "...";
    }
}
