package kamutotems.core;

import java.util.Map;

public record Effect(
        String effectId,
        String targetId,
        Map<String, Double> parameters) {}
