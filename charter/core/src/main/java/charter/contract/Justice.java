package charter.contract;

import charter.config.Knobs;
import charter.engine.ActionContext;
import charter.engine.Reason;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.state.CharterState;
import charter.treasury.Currency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Justice in the world (PLAN.md §7.4, DESIGN.md §15.5): the checks and effects
 * behind arrest and sentencing, and the time-aware questions the fabric side asks
 * each tick (who is held where, who is banished from what, how long a respawn
 * waits). Every question takes {@code now} and answers from the contract's own
 * times, so it is right even before {@link #tick} has recorded that a sentence ended.
 */
public final class Justice {

    private Justice() {
    }

    // ---- time ----

    /** A sentence's length in wall milliseconds; shortened by the time scale only if that knob is on (D13). */
    public static long wall(Knobs knobs, long sentenceMillis) {
        if (!knobs.scaleSentencesWithTimeScale || knobs.timeScale <= 0) return sentenceMillis;
        return Math.round(sentenceMillis / knobs.timeScale);
    }

    /** When one term of a wall-timed contract ends. */
    public static long until(Contract contract, Term term, Knobs knobs) {
        return contract.createdAt + wall(knobs, term.durationMillis());
    }

    // ---- questions ----

    public static boolean custodyLive(Custody custody, Knobs knobs, long now) {
        return now - custody.since() < charter.time.Durations.minutes(knobs.custodyLapseMinutes);
    }

    /** The contract holding this player in a cell or on a tether now, if any. */
    public static Optional<Contract> holding(CharterState state, Knobs knobs, PlayerId player, long now) {
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || !c.subject.equals(Party.of(player))) continue;
            Term held = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
            if (held != null && now < until(c, held, knobs)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Prisoners held in this keep's cells or on its tether: each one is a mouth on its granary. */
    public static int prisoners(CharterState state, Knobs knobs, CoreId keep, long now) {
        int n = 0;
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || !keep.equals(c.keep) || !c.subject.isPlayer()) continue;
            Term held = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
            if (held != null && now < until(c, held, knobs)) n++;
        }
        return n;
    }

    /** True if the player is banished from the land of this organization's faction now. */
    public static boolean banished(CharterState state, Knobs knobs, PlayerId player, OrgId land, long now) {
        OrgId faction = state.root(land);
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || !c.subject.equals(Party.of(player))) continue;
            for (Term t : c.terms) {
                if (t instanceof Term.Banishment b && state.root(b.from()).equals(faction) && now < until(c, b, knobs)) return true;
            }
        }
        return false;
    }

    /** Milliseconds the player still waits before respawning, or 0. */
    public static long respawnDelayLeft(CharterState state, Knobs knobs, PlayerId player, long now) {
        long left = 0;
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || !c.subject.equals(Party.of(player))) continue;
            Term.RespawnDelay delay = c.term(Term.RespawnDelay.class);
            if (delay != null) left = Math.max(left, until(c, delay, knobs) - now);
        }
        return left;
    }

    // ---- the tick ----

    /** Lapses custody nobody sentenced, and finishes wall-timed contracts whose time is up. */
    public static List<HistoryEvent> tick(CharterState state, Knobs knobs, long now) {
        List<HistoryEvent> events = new ArrayList<>();
        for (Custody custody : new ArrayList<>(state.contracts.custodies())) {
            if (!custodyLive(custody, knobs, now)) {
                state.contracts.releaseCustody(custody.prisoner());
                events.add(new ContractEvents.CustodyLapsed(now, custody.faction(), custody.prisoner()));
            }
        }
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || now < c.endsAt) continue;
            c.fulfil(now);
            events.add(new ContractEvents.Fulfilled(now, c.id, c.basis));
        }
        return events;
    }

    /** Prisoners walk free when the keep that held them falls (DESIGN.md §15.5). */
    public static List<HistoryEvent> freeKeep(CharterState state, Knobs knobs, CoreId keep, long now, String why) {
        List<PlayerId> freed = new ArrayList<>();
        List<HistoryEvent> events = new ArrayList<>();
        for (Contract c : state.contracts.live()) {
            if (c.siegeTimed || !keep.equals(c.keep) || !c.holdsPlayer()) continue;
            c.end(now, why);
            state.contracts.punishments.served(c.id, now - c.createdAt);
            freed.add(c.subject.player());
            events.add(new ContractEvents.Ended(now, c.id, c.basis, why));
        }
        if (!freed.isEmpty()) events.add(new ContractEvents.PrisonersFreed(now, keep, freed));
        return events;
    }

    /** Frees prisoners whose keep no longer exists at all (razed, lost). */
    public static List<HistoryEvent> freeOrphans(CharterState state, Knobs knobs, long now) {
        List<HistoryEvent> events = new ArrayList<>();
        List<CoreId> gone = new ArrayList<>();
        for (Contract c : state.contracts.live()) {
            if (!c.siegeTimed && c.keep != null && c.holdsPlayer() && state.core(c.keep).isEmpty() && !gone.contains(c.keep)) {
                gone.add(c.keep);
            }
        }
        for (CoreId keep : gone) events.addAll(freeKeep(state, knobs, keep, now, "keep lost"));
        return events;
    }

    // ---- sentencing ----

    /** The terms after every cap, or the reason to refuse. */
    public record Plan(List<Term> terms, boolean cut, Reason refusal) {
        public boolean ok() {
            return refusal == null;
        }

        static Plan refuse(Reason reason) {
            return new Plan(List.of(), false, reason);
        }
    }

    /**
     * Checks a sentence against the custody, the cooldown, the basis caps and the
     * weekly total, cutting each counting term to what is left of the week. Changes
     * nothing, so an action's check and apply agree.
     */
    public static Plan plan(ActionContext ctx, OrgId faction, PlayerId prisoner, List<Term> asked) {
        CharterState state = ctx.state;
        Knobs knobs = ctx.knobs;
        OrgId root = state.root(faction);
        if (asked.isEmpty()) return Plan.refuse(ContractReasons.NO_TERMS);
        Optional<Custody> custody = state.contracts.custodyOf(prisoner);
        if (custody.isEmpty() || !state.root(custody.get().faction()).equals(root) || !custodyLive(custody.get(), knobs, ctx.now)) {
            return Plan.refuse(ContractReasons.NO_CUSTODY);
        }
        if (state.contracts.punishments.coolingDown(root, prisoner, ctx.now, knobs)) return Plan.refuse(ContractReasons.COOLDOWN);

        int holds = 0;
        long remaining = state.contracts.punishments.remaining(prisoner, ctx.now, knobs);
        boolean cut = false;
        boolean refusedByWeek = false;
        List<Term> out = new ArrayList<>();
        for (Term term : asked) {
            BasisCaps.Checked checked = BasisCaps.check(Basis.CUSTODY, term, knobs);
            if (!checked.ok()) return Plan.refuse(checked.refusal());
            Term use = checked.term();
            cut |= checked.cut();
            if (use instanceof Term.Confinement || use instanceof Term.Tether) {
                holds++;
                if (holds > 1) return Plan.refuse(ContractReasons.ONE_HOLD);
                CoreId cell = use instanceof Term.Confinement c ? c.keep() : ((Term.Tether) use).keep();
                if (!usableCell(state, cell, root)) return Plan.refuse(ContractReasons.NO_CELL);
            }
            if (use.countsTowardWeeklyCap()) {
                if (remaining <= 0) {
                    refusedByWeek = true;
                    continue;
                }
                if (use.durationMillis() > remaining) {
                    use = use.withDuration(remaining);
                    cut = true;
                }
                remaining -= use.durationMillis();
            }
            out.add(use);
        }
        if (out.isEmpty() && refusedByWeek) return Plan.refuse(ContractReasons.WEEKLY_CAP);
        if (refusedByWeek) cut = true;
        return new Plan(List.copyOf(out), cut, null);
    }

    private static boolean usableCell(CharterState state, CoreId cell, OrgId root) {
        if (cell == null) return false;
        Core core = state.core(cell).orElse(null);
        return core != null && core.isKeep() && state.root(core.owner).equals(root);
    }

    /** The contract made by {@link #impose}, and the events to record. */
    public record Imposition(Contract contract, List<HistoryEvent> events) {
    }

    /** Ends a held or banished prisoner's contract early; the weekly total then counts only what was served. */
    public static void endEarly(CharterState state, Contract contract, long now, String why) {
        contract.end(now, why);
        state.contracts.punishments.served(contract.id, now - contract.createdAt);
    }

    /** Creates the imposed contract from a plan that passed. Charges notoriety, takes the fine, ends custody. */
    public static Imposition impose(ActionContext ctx, OrgId faction, PlayerId prisoner, Plan plan) {
        CharterState state = ctx.state;
        Knobs knobs = ctx.knobs;
        OrgId root = state.root(faction);
        List<HistoryEvent> events = new ArrayList<>();

        long longest = 0;
        long counted = 0;
        double severity = 0;
        CoreId keep = null;
        for (Term t : plan.terms()) {
            longest = Math.max(longest, wall(knobs, t.durationMillis()));
            if (t.countsTowardWeeklyCap()) counted += t.durationMillis();
            severity += t.severity(knobs);
            if (t instanceof Term.Confinement c) keep = c.keep();
            if (t instanceof Term.Tether tether) keep = tether.keep();
        }
        Contract contract = new Contract(state.ids.contract(), Consent.IMPOSED, Basis.CUSTODY, Party.of(faction),
                Party.of(prisoner), plan.terms(), ctx.now, keep, false, ctx.now + longest);
        state.contracts.add(contract);
        state.contracts.punishments.record(contract.id, prisoner, root, ctx.now, counted);
        state.contracts.releaseCustody(prisoner);

        double notoriety = severity * knobs.imposedNotorietyPerSeverity;
        if (notoriety > 0) state.siege.notoriety.merge(root, notoriety, Double::sum);
        boolean outsider = state.membership(prisoner).map(m -> !state.root(m.org()).equals(root)).orElse(true);
        events.add(new ContractEvents.Imposed(ctx.now, contract.id, Basis.CUSTODY, contract.imposer, contract.subject,
                contract.terms, plan.cut(), outsider, notoriety));

        for (Term t : plan.terms()) {
            if (t instanceof Term.Fine fine) {
                long paid = Math.min(fine.diamonds(), state.contracts.vault(prisoner).balance(Currency.DIAMOND));
                if (paid > 0) {
                    state.contracts.vault(prisoner).take(Currency.DIAMOND, paid);
                    state.org(faction).ifPresent(o -> o.ledger.add(Currency.DIAMOND, paid));
                }
                events.add(new ContractEvents.Fined(ctx.now, contract.id, prisoner, faction, fine.diamonds(), paid));
            }
        }
        return new Imposition(contract, events);
    }
}
