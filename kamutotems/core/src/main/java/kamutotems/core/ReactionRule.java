package kamutotems.core;

import java.util.Set;

public record ReactionRule(
        String id,
        String a,
        String b,
        Set<String> conditions,
        String outcomeEffectId,
        int priority,
        boolean terminal) {}
