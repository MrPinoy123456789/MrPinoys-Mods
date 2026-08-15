package kamutotems.core;

import java.util.List;

public record StreakState(
        int streak,
        String lastCompletedDateKey,
        List<String> graceUsedDateKeys) {}
