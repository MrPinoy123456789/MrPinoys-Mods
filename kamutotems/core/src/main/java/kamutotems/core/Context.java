package kamutotems.core;

import java.util.List;
import java.util.Set;

public record Context(
        String sourceId,
        String targetId,
        Set<String> targetTags,
        Set<String> environmentTags,
        List<String> previousEffects,
        long seed) {}
