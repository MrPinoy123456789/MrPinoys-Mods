package kamutotems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Deterministically applies the highest-priority matching reaction until a terminal rule or depth cap. */
public final class ReactionEngine {

    private final List<ReactionRule> rules;
    private final int depthCap;

    public ReactionEngine(List<ReactionRule> rules, int depthCap) {
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        this.depthCap = depthCap;
    }

    /** Creates the built-in reaction registry and its default recursion cap. */
    public static ReactionEngine defaults() {
        return new ReactionEngine(List.of(
                new ReactionRule("thermal_shock", "fire", "ice", Set.of(), "thermal_shock", 10, true),
                new ReactionRule("melt", "ice", "fire", Set.of(), null, 10, true),
                new ReactionRule("conduct", "lightning", "tag:wet", Set.of(), "conduct", 9, false),
                new ReactionRule("shatter", "ice", "lightning", Set.of(), "shatter", 9, true),
                new ReactionRule("toxic_flame", "poison", "fire", Set.of(), "toxic_flame", 8, false),
                new ReactionRule("tainted", "heal", "poison", Set.of(), "tainted", 8, false)), 4);
    }

    /**
     * The rules this engine holds, in declaration order.
     *
     * <p>Added during integration so the fabric layer can serialise the default
     * table into {@code reactions.json} rather than duplicating it. The default
     * table is defined once, here, and written out from what actually loaded.
     */
    public List<ReactionRule> rules() {
        return List.copyOf(rules);
    }

    public int depthCap() {
        return depthCap;
    }

    /** Resolves reactions in deterministic priority order without mutating the input list. */
    public ReactionOutcome react(List<Effect> ordered, Context context) {
        if (ordered == null) {
            return new ReactionOutcome(List.of(), List.of(), false);
        }

        List<Effect> current = new ArrayList<>(ordered);
        List<String> fired = new ArrayList<>();
        boolean truncated = false;

        Set<String> tags = mergedTags(context);

        int applied = 0;
        while (true) {
            Match best = findBest(current, tags);
            if (best == null) {
                break;
            }
            if (applied >= depthCap) {
                truncated = true;
                break;
            }

            apply(current, best, context);
            fired.add(best.rule.id());
            applied++;

            if (best.rule.terminal()) {
                break;
            }
        }

        return new ReactionOutcome(List.copyOf(current), List.copyOf(fired), truncated);
    }

    private Set<String> mergedTags(Context context) {
        Set<String> out = new java.util.HashSet<>();
        if (context != null) {
            if (context.targetTags() != null) {
                out.addAll(context.targetTags());
            }
            if (context.environmentTags() != null) {
                out.addAll(context.environmentTags());
            }
        }
        return out;
    }

    private Match findBest(List<Effect> effects, Set<String> tags) {
        Match best = null;
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            ReactionRule rule = rules.get(ruleIndex);
            if (!conditionsMet(rule, tags)) {
                continue;
            }
            for (int i = 0; i < effects.size(); i++) {
                Effect a = effects.get(i);
                if (!matchesA(rule, a)) {
                    continue;
                }

                if (rule.b().startsWith("tag:")) {
                    String tag = rule.b().substring(4);
                    if (tags.contains(tag)) {
                        Match m = new Match(rule, i, -1, ruleIndex);
                        best = better(best, m);
                    }
                    continue;
                }

                for (int j = i + 1; j < effects.size(); j++) {
                    Effect b = effects.get(j);
                    if (matchesB(rule, b)) {
                        Match m = new Match(rule, i, j, ruleIndex);
                        best = better(best, m);
                    }
                }
            }
        }
        return best;
    }

    private boolean conditionsMet(ReactionRule rule, Set<String> tags) {
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return true;
        }
        for (String c : rule.conditions()) {
            if (!tags.contains(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesA(ReactionRule rule, Effect a) {
        return a != null && rule.a().equals(a.effectId());
    }

    private boolean matchesB(ReactionRule rule, Effect b) {
        return b != null && rule.b().equals(b.effectId());
    }

    private Match better(Match a, Match b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (b.rule.priority() != a.rule.priority()) {
            return b.rule.priority() > a.rule.priority() ? b : a;
        }
        if (b.i != a.i) {
            return b.i < a.i ? b : a;
        }
        if (b.j != a.j) {
            return b.j < a.j ? b : a;
        }
        return b.ruleIndex < a.ruleIndex ? b : a;
    }

    private void apply(List<Effect> effects, Match match, Context context) {
        int i = match.i;
        effects.remove(i);

        if (match.j >= 0) {
            effects.remove(match.j - 1);
        }

        String outcome = match.rule.outcomeEffectId();
        if (outcome != null) {
            String target = context == null ? null : context.targetId();
            effects.add(i, new Effect(outcome, target, java.util.Map.of()));
        }
    }

    private static final class Match {
        final ReactionRule rule;
        final int i;
        final int j;
        final int ruleIndex;

        Match(ReactionRule rule, int i, int j, int ruleIndex) {
            this.rule = rule;
            this.i = i;
            this.j = j;
            this.ruleIndex = ruleIndex;
        }
    }
}
