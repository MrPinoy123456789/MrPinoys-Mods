package kamutotems.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/** A quest definition assigned to one player, including its grant date and current progress. */
public record AssignedQuest(
        String definitionId,
        String grantedDateKey,
        QuestProgress progress,
        QuestState state) {

    /** Returns whether the definition's expiry window has elapsed since this quest was granted. */
    public boolean isExpired(String todayKey, QuestDefinition def) {
        if (def == null || def.expiryDays() <= 0) {
            return false;
        }
        if (todayKey == null || grantedDateKey == null) {
            return false;
        }
        try {
            LocalDate granted = LocalDate.parse(grantedDateKey);
            LocalDate today = LocalDate.parse(todayKey);
            long days = ChronoUnit.DAYS.between(granted, today);
            return days >= def.expiryDays();
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Returns a copy with one segment advanced, capped at that segment's required count. */
    public AssignedQuest advance(int segmentIndex, int by, QuestDefinition def) {
        if (def == null || def.segments() == null) {
            return this;
        }
        // QuestProgress.advance is shaped around QuestChain; an assigned
        // quest's definition supplies the same segment shape, so it is
        // wrapped rather than duplicating QuestProgress's logic.
        QuestChain shim = new QuestChain(grantedDateKey, def.segments());
        QuestProgress next = progress.advance(segmentIndex, by, shim);
        return new AssignedQuest(definitionId, grantedDateKey, next, state);
    }

    /** Returns whether every segment in the supplied definition is complete. */
    public boolean complete(QuestDefinition def) {
        if (def == null || def.segments() == null) {
            return false;
        }
        QuestChain shim = new QuestChain(grantedDateKey, def.segments());
        return progress.complete(shim);
    }
}
