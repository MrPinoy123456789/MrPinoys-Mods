package charter.contract;

import charter.id.CoreId;
import charter.siege.SiegeInputs;
import charter.state.CharterState;

import java.util.function.LongSupplier;

/**
 * Feeds {@link SiegeInputs#prisoners} from the contracts. The fabric side counts
 * players from positions; prisoners come from the book, so this wraps whatever
 * inputs it has and answers the prisoner count itself.
 */
public final class JusticeInputs implements SiegeInputs {

    private final SiegeInputs base;
    private final CharterState state;
    private final charter.config.Knobs knobs;
    private final LongSupplier wallNow;

    private JusticeInputs(SiegeInputs base, CharterState state, charter.config.Knobs knobs, LongSupplier wallNow) {
        this.base = base == null ? SiegeInputs.NONE : base;
        this.state = state;
        this.knobs = knobs;
        this.wallNow = wallNow;
    }

    public static SiegeInputs wrap(SiegeInputs base, CharterState state, charter.config.Knobs knobs, LongSupplier wallNow) {
        return new JusticeInputs(base, state, knobs, wallNow);
    }

    @Override
    public int attackersInCamp(CoreId camp) {
        return base.attackersInCamp(camp);
    }

    @Override
    public int defendersInKeep(CoreId keep) {
        return base.defendersInKeep(keep);
    }

    @Override
    public int attackersInsideKeep(CoreId keep) {
        return base.attackersInsideKeep(keep);
    }

    @Override
    public int defendersBesideCamp(CoreId camp) {
        return base.defendersBesideCamp(camp);
    }

    @Override
    public int prisoners(CoreId keep) {
        return Justice.prisoners(state, knobs, keep, wallNow.getAsLong());
    }
}
