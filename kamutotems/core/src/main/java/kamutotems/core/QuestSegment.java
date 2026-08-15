package kamutotems.core;

public record QuestSegment(
        String kind,
        EventMatcher matcher,
        String displayText) {}
