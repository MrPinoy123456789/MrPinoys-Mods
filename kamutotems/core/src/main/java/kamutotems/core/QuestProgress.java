package kamutotems.core;

import java.util.ArrayList;
import java.util.List;

public record QuestProgress(String dateKey, List<Integer> counts) {

    public QuestProgress advance(int segmentIndex, int by, QuestChain chain) {
        if (chain == null || chain.segments() == null) {
            return this;
        }
        if (segmentIndex < 0 || segmentIndex >= chain.segments().size()) {
            return this;
        }
        if (by <= 0) {
            return this;
        }

        int size = chain.segments().size();
        List<Integer> next = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int current = i < counts.size() ? counts.get(i) : 0;
            if (i == segmentIndex) {
                current += by;
            }
            int required = chain.segments().get(i).matcher().requiredCount();
            if (current > required) {
                current = required;
            }
            next.add(current);
        }
        return new QuestProgress(dateKey, List.copyOf(next));
    }

    public boolean segmentComplete(int i, QuestChain chain) {
        if (chain == null || chain.segments() == null) {
            return false;
        }
        if (i < 0 || i >= chain.segments().size()) {
            return false;
        }
        int c = i < counts.size() ? counts.get(i) : 0;
        return c >= chain.segments().get(i).matcher().requiredCount();
    }

    public boolean complete(QuestChain chain) {
        if (chain == null || chain.segments() == null) {
            return false;
        }
        for (int i = 0; i < chain.segments().size(); i++) {
            if (!segmentComplete(i, chain)) {
                return false;
            }
        }
        return true;
    }
}
