package kamutotems.core;

import java.util.Map;
import java.util.Set;

public record Kamu(
        String id,
        String displayName,
        Category category,
        Rarity rarity,
        int complexity,
        String effectId,
        Map<String, Double> parameters,
        Set<String> tags,
        Set<HostType> allowedHosts,
        Polarity polarity) {

    /**
     * Compatibility constructor for call sites that do not yet know about
     * aura polarity. Such kamu are treated as having no aura reading.
     */
    public Kamu(
            String id,
            String displayName,
            Category category,
            Rarity rarity,
            int complexity,
            String effectId,
            Map<String, Double> parameters,
            Set<String> tags,
            Set<HostType> allowedHosts) {
        this(id, displayName, category, rarity, complexity, effectId,
                parameters, tags, allowedHosts, Polarity.NONE);
    }
}
