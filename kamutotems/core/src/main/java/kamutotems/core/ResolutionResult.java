package kamutotems.core;

import java.util.List;

public record ResolutionResult(
        boolean valid,
        String fault,
        List<Effect> effects,
        String deliveryId) {}
