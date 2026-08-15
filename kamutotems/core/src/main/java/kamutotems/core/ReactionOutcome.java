package kamutotems.core;

import java.util.List;

public record ReactionOutcome(
        List<Effect> effects,
        List<String> firedRuleIds,
        boolean truncated) {}
