package smalltalk.dialogue;

import java.util.function.Predicate;

/**
 * One condition the context engine can notice, plus how specific it is.
 * Selection (SPEC.md section 4.3) always prefers the highest-specificity
 * context that currently passes -- a remark about your burning trousers
 * always beats one about the weather.
 */
public record DialogueContext(String id, int specificity, Predicate<Situation> condition) {
}
