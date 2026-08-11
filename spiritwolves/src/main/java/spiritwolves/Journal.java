package spiritwolves;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * The living journal (SPEC.md section 15.1): the wolf accumulates its history
 * as journal entries in its {@link WolfRecord}, rendered as extra stone lore.
 * Pure text -- zero balance impact, the whole feature is the tooltip.
 *
 * <p>Every method here only <em>writes</em> journal entries. Callers are
 * responsible for the following {@code SpiritStone.refreshLore(...)} that
 * renders them, so a single interaction that touches several entries still
 * rebuilds the lore exactly once.
 */
public final class Journal {

    // Entry keys. Rewriting an existing key replaces that entry in place, which
    // is what keeps the death-save line a running count instead of a pile of
    // duplicates.
    private static final String KEY_BOUND = "bound";
    private static final String KEY_SAVES = "saves";
    private static final String KEY_FALL = "fall";
    private static final String KEY_VOID = "void";
    private static final String KEY_UNTESTED = "untested";
    private static final String KEY_STREAK = "streak";

    /** How long a wolf must go unharmed before it earns the "never tested" line. */
    private static final long UNTESTED_AFTER_MILLIS = TimeUnit.DAYS.toMillis(7);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private Journal() {}

    /** First bind. */
    public static void bound(WolfRecord record) {
        record.addJournalEntry(KEY_BOUND, "Bound " + LocalDate.now().format(DATE) + ".");
    }

    /**
     * A death was cancelled. Rewrites the running count, and adds a flavour line
     * for the two damage sources worth calling out.
     *
     * @param saves the new death-save total, from {@code record.saveCount}
     */
    public static void deathSaved(WolfRecord record, DamageSource damageSource, int saves) {
        record.addJournalEntry(KEY_SAVES,
                "Cheated death, " + saves + (saves == 1 ? " time." : " times."));

        if (damageSource == null) {
            return;
        }
        if (damageSource.is(DamageTypes.FALL)) {
            record.addJournalEntry(KEY_FALL, "Fell and lived.");
        } else if (damageSource.is(DamageTypes.FELL_OUT_OF_WORLD)
                || damageSource.is(DamageTypes.GENERIC_KILL)) {
            record.addJournalEntry(KEY_VOID, "Walked back from the void.");
        }
    }

    /** A new best killstreak. Only recorded once the streak actually ends. */
    public static void bestStreak(WolfRecord record, int streak) {
        record.addJournalEntry(KEY_STREAK, "Felled " + streak + " in a single outing.");
    }

    /**
     * Cosmetic milestone, checked periodically from the {@code Tracker} poll: a
     * wolf bound long ago that has never dropped below half charges (on its
     * current stone) and has never had to be saved from death.
     *
     * @return true if an entry was written (so the caller knows to refresh lore)
     */
    public static boolean checkUntested(WolfRecord record, int chargesRemaining) {
        if (chargesRemaining * 2 < SpiritStone.maxCharges()) {
            return false;
        }
        if (record.boundAt <= 0L || System.currentTimeMillis() - record.boundAt < UNTESTED_AFTER_MILLIS) {
            return false;
        }
        if (record.saveCount > 0) {
            return false;
        }
        if (record.hasJournalEntry(KEY_UNTESTED)) {
            return false;
        }
        record.addJournalEntry(KEY_UNTESTED, "Never truly tested.");
        return true;
    }
}
