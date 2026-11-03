package chartercurrency.core;

import java.util.UUID;

/**
 * Applies {@link Mint} plans to stored state and routes the tax. The fabric side's
 * order is: {@link #preview}, remove the ingots, {@link #commit}, give the coins; so a
 * strike that cannot take its ingots changes nothing.
 */
public final class MintService {

    public static final String STAT_MINTED = "minted";
    public static final String STAT_TAX_PAID = "taxpaid";
    public static final String STAT_TAX_DESTROYED = "taxdestroyed";

    private final PlayerBook book;
    private final Treasury treasury;
    private volatile Mint.Rules rules;

    public MintService(PlayerBook book, Treasury treasury, Mint.Rules rules) {
        this.book = book;
        this.treasury = treasury;
        this.rules = rules;
    }

    public Mint.Rules rules() {
        return rules;
    }

    /** Settings can be reloaded; the new numbers apply from the next strike. */
    public void setRules(Mint.Rules rules) {
        this.rules = rules;
    }

    /** The player's state rolled to {@code today}. Nothing is stored. */
    public Mint.State state(UUID player, long today) {
        return book.locked(file -> {
            PlayerBook.Player p = file.players.get(player.toString());
            if (p == null || p.mint == null) return Mint.State.fresh(today);
            return Mint.rollTo(p.mint, today);
        });
    }

    /** Plan a strike without storing anything. */
    public Mint.Plan preview(UUID player, String denominationId, int faceValue,
                             int ingotsOffered, long today) {
        return Mint.plan(state(player, today), rules, denominationId, faceValue, ingotsOffered);
    }

    /**
     * Store the plan's resulting state and pay the tax: into the kingdom's treasury, or
     * destroyed when {@code kingdomId} is null. If the treasury refuses the credit
     * (overflow) the share is destroyed rather than handed to the player.
     *
     * @return the Marks that actually reached a treasury
     */
    public long commit(UUID player, Mint.Plan plan, String kingdomId, String actorName, long today) {
        if (!plan.struck()) return 0L;
        long taxMarks = plan.marksToKingdom();
        long paid = 0;
        if (taxMarks > 0 && kingdomId != null && treasury.collectTax(kingdomId, taxMarks, actorName)) {
            paid = taxMarks;
        }
        long destroyed = taxMarks - paid;
        long fPaid = paid;
        book.locked(file -> {
            PlayerBook.Player p = book.player(player);
            Mint.State after = plan.after();
            // The plan's state is already rolled to today; keep the day it was rolled to.
            p.mint = after.copy();
            book.addStat(STAT_MINTED, today, plan.marksStruck());
            if (fPaid > 0) book.addStat(STAT_TAX_PAID, today, fPaid);
            if (destroyed > 0) book.addStat(STAT_TAX_DESTROYED, today, destroyed);
            return null;
        });
        book.changed();
        return paid;
    }
}
