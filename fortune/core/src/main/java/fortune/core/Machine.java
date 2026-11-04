package fortune.core;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The settings checked and compiled into what a spin needs: the symbols, the three paytables and the roller. Built
 * once per load; a settings file that fails here is refused whole and the previous machine stays in use.
 */
public final class Machine {

    /** Everything a spin decides before anything is paid or drawn. */
    public record Spin(Claims.Plan plan, Roller.Outcome outcome, Settings.StakeTier stake, Stake.Reward reward) {
    }

    private final Settings settings;
    private final Roller roller;
    private final Paytable normal;
    private final Paytable golden;
    private final Paytable welcomeBack;

    public Machine(Settings settings) {
        this.settings = settings;
        List<Settings.SymbolDef> symbols = settings.symbols;
        if (symbols == null || symbols.size() < 3) throw new IllegalArgumentException("At least three symbols are needed");
        Set<String> ids = new HashSet<>();
        for (Settings.SymbolDef s : symbols) {
            if (s.id == null || !ids.add(s.id)) throw new IllegalArgumentException("Symbol ids must be present and unique");
            if (s.weight <= 0) throw new IllegalArgumentException("Symbol " + s.id + " needs a weight above 0");
            if (s.group < 0 || s.group > 3) throw new IllegalArgumentException("Symbol " + s.id + " has group " + s.group + "; use 0 to 3");
        }
        this.roller = new Roller(symbols);
        this.normal = Paytable.of("normalTable", settings.normalTable);
        this.golden = Paytable.of("goldenTable", settings.goldenTable);
        this.welcomeBack = Paytable.of("welcomeBackTable", settings.welcomeBackTable);
        for (Paytable table : List.of(normal, golden, welcomeBack)) {
            for (Paytable.Row row : table.rows()) {
                if (row.line().isTriple() && symbols.stream().noneMatch(s -> s.group == row.line().group())) {
                    throw new IllegalArgumentException(row.line() + " is in a paytable but no symbol has group " + row.line().group());
                }
            }
        }
        if (golden.row(Line.NO_MATCH) != null || golden.row(Line.PAIR) != null) {
            throw new IllegalArgumentException("goldenTable must be three of a kind only");
        }
        if (welcomeBack.row(Line.NO_MATCH) != null || welcomeBack.row(Line.PAIR) != null) {
            throw new IllegalArgumentException("welcomeBackTable must be three of a kind only");
        }
        for (Settings.StakeTier tier : settings.stakeTiers) {
            if (tier.id == null || "none".equals(tier.id) || tier.sovereigns <= 0 || tier.bonusPercent <= 0) {
                throw new IllegalArgumentException("Each stake tier needs an id (not \"none\"), a price in sovereigns and a bonus above 0");
            }
        }
        if (settings.rolloverHourUtc < 0 || settings.rolloverHourUtc > 23) {
            throw new IllegalArgumentException("rolloverHourUtc must be 0 to 23");
        }
    }

    /** The table a plan rolls on, floored. */
    public Paytable tableFor(Claims.Plan plan) {
        Paytable base = switch (plan.kind()) {
            case NORMAL -> normal;
            case GOLDEN -> golden;
            case WELCOME_BACK -> welcomeBack;
        };
        return base.atLeast(plan.floor());
    }

    /**
     * Decides a spin: the plan, the roll and the reward at the stake that was actually charged. Pure: the caller has
     * already tried the charge and saves the result before paying or animating.
     */
    public Spin decide(Claims.Plan plan, Settings.StakeTier chargedStake, RandomGenerator random) {
        Roller.Outcome outcome = roller.roll(tableFor(plan), random);
        int bonus = chargedStake == null ? 0 : chargedStake.bonusPercent;
        return new Spin(plan, outcome, chargedStake, Stake.reward(outcome.row(), bonus, settings));
    }

    /** Today's plan for a player, or empty when today's spin is used. */
    public Optional<Claims.Plan> plan(PlayerRecord record, long today) {
        return Claims.plan(record, today, settings);
    }

    public Settings settings() {
        return settings;
    }

    public Roller roller() {
        return roller;
    }

    public Paytable normal() {
        return normal;
    }

    public Paytable golden() {
        return golden;
    }

    public Paytable welcomeBack() {
        return welcomeBack;
    }

    public int symbolIndex(String id) {
        for (int i = 0; i < settings.symbols.size(); i++) if (settings.symbols.get(i).id.equals(id)) return i;
        return -1;
    }

    /** The plain name of a result, such as "Three Diamonds" or "Three Gold". */
    public String resultName(Roller.Outcome outcome) {
        Line line = outcome.line();
        if (line == Line.LOW_TRIPLE || line == Line.MID_TRIPLE) {
            return "Three " + settings.symbols.get(outcome.payline()[0]).name;
        }
        return line.label();
    }
}
