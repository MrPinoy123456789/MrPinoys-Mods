package fortune.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** This week's biggest wins on the server, for the Annals button: the top few of the last seven days by level. */
public final class Annals {

    public static final int KEEP = 5;
    public static final int DAYS = 7;

    public static final class Win {
        public String name;
        public String result;
        public int level;
        public long day;

        public Win() {
        }

        public Win(String name, String result, int level, long day) {
            this.name = name;
            this.result = result;
            this.level = level;
            this.day = day;
        }
    }

    private Annals() {
    }

    /** The list with {@code win} added, wins older than a week dropped, best first, at most {@link #KEEP}. */
    public static List<Win> add(List<Win> wins, Win win, long today) {
        List<Win> out = new ArrayList<>();
        for (Win w : wins) if (w.day > today - DAYS) out.add(w);
        if (win != null) out.add(win);
        out.sort(Comparator.comparingInt((Win w) -> w.level).reversed().thenComparingLong(w -> -w.day));
        return out.size() > KEEP ? new ArrayList<>(out.subList(0, KEEP)) : out;
    }
}
