package wayfarers.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The §9.1 per-player record, kept free of NBT so it lives in core.
 */
public record TraderRecord(
        String traderId,
        int meetings,
        long lastMetAt,
        int spentWith,
        List<String> marvelsSeen,
        int encountersMet) {

    public TraderRecord {
        if (marvelsSeen == null) marvelsSeen = new ArrayList<>();
    }

    public static TraderRecord empty() {
        return new TraderRecord(null, 0, 0L, 0, List.of(), 0);
    }

    public TraderRecord sawMarvel(String id) {
        List<String> next = new ArrayList<>(marvelsSeen);
        if (!next.contains(id)) next.add(id);
        return new TraderRecord(traderId, meetings, lastMetAt, spentWith, next, encountersMet);
    }

    public TraderRecord withMeeting(String id, long at) {
        return new TraderRecord(id == null ? traderId : id, meetings + 1, at, spentWith, marvelsSeen, encountersMet);
    }
}
