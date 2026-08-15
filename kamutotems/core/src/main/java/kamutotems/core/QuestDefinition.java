package kamutotems.core;

import java.util.List;

/** A quest as authored in quests.json. Data, not code. */
public record QuestDefinition(
        String id,                       // "wayfarer_lost_cargo"
        String title,
        String sourceLabel,              // "A wayfarer asked this of you."
        List<QuestSegment> segments,     // reuses the v1 record
        List<QuestReward> rewards,
        int expiryDays,                  // 0 = never expires
        boolean repeatable) {}
