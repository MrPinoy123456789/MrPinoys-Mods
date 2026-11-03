package fortune.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The roll: a weighted pick of the result line first, then payline symbols that make exactly that line, then filler
 * for the rows above and below drawn from the symbol weights alone. Nothing here looks at the outcome when drawing
 * filler, so a jackpot symbol shows off the payline only as often as chance puts it there.
 */
public final class Roller {

    /** The rolled spin: the paytable row it pays and the three payline symbols (indices into the symbol list). */
    public record Outcome(Paytable.Row row, int[] payline) {
        public Outcome {
            payline = payline.clone();
        }

        public Line line() {
            return row.line();
        }

        @Override
        public int[] payline() {
            return payline.clone();
        }

        /** The symbol a week strip day shows: the matched symbol, or the highest of three on no match. */
        public int headline(List<Settings.SymbolDef> symbols) {
            if (payline[0] == payline[1] || payline[0] == payline[2]) return payline[0];
            if (payline[1] == payline[2]) return payline[1];
            int best = payline[0];
            for (int s : payline) if (symbols.get(s).group > symbols.get(best).group) best = s;
            return best;
        }
    }

    private final List<Settings.SymbolDef> symbols;

    public Roller(List<Settings.SymbolDef> symbols) {
        this.symbols = List.copyOf(symbols);
    }

    public Outcome roll(Paytable table, RandomGenerator random) {
        Paytable.Row row = table.pick(random);
        return new Outcome(row, paylineFor(row.line(), random));
    }

    /** Three payline symbols that classify as exactly {@code line}. */
    public int[] paylineFor(Line line, RandomGenerator random) {
        return switch (line) {
            case NO_MATCH -> {
                List<Integer> pool = all();
                int a = takeWeighted(pool, random);
                int b = takeWeighted(pool, random);
                int c = takeWeighted(pool, random);
                yield new int[]{a, b, c};
            }
            case PAIR -> {
                List<Integer> pool = all();
                int pair = takeWeighted(pool, random);
                int odd = takeWeighted(pool, random);
                int at = random.nextInt(3);
                int[] out = {pair, pair, pair};
                out[at] = odd;
                yield out;
            }
            default -> {
                List<Integer> pool = new ArrayList<>();
                for (int i = 0; i < symbols.size(); i++) if (symbols.get(i).group == line.group()) pool.add(i);
                if (pool.isEmpty()) throw new IllegalStateException("No symbol pays " + line);
                int s = takeWeighted(pool, random);
                yield new int[]{s, s, s};
            }
        };
    }

    /** One filler symbol, by weight alone. */
    public int filler(RandomGenerator random) {
        return takeWeighted(all(), random);
    }

    /** What a payline pays: three alike is that symbol's triple, two alike a pair, otherwise no match. */
    public Line classify(int[] payline) {
        int a = payline[0];
        int b = payline[1];
        int c = payline[2];
        if (a == b && b == c) return Line.tripleOf(symbols.get(a).group);
        if (a == b || a == c || b == c) return Line.PAIR;
        return Line.NO_MATCH;
    }

    public List<Settings.SymbolDef> symbols() {
        return symbols;
    }

    private List<Integer> all() {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) pool.add(i);
        return pool;
    }

    /** Removes and returns one index from {@code pool}, weighted by symbol weight. */
    private int takeWeighted(List<Integer> pool, RandomGenerator random) {
        int total = 0;
        for (int i : pool) total += symbols.get(i).weight;
        int roll = random.nextInt(total);
        for (int k = 0; k < pool.size(); k++) {
            roll -= symbols.get(pool.get(k)).weight;
            if (roll < 0) return pool.remove(k);
        }
        return pool.remove(pool.size() - 1);
    }
}
