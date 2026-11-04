package charter.siege;

import charter.config.Knobs;
import charter.engine.ActionEngine;
import charter.food.EatPlan;
import charter.food.FoodTable;
import charter.food.Provisions;
import charter.food.SpoilagePlan;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The siege (PLAN.md §4.3): one {@link #step} advances the siege clock and every
 * drain, timer and transition, and records what happened. Nobody chooses these
 * changes, so they are recorded as the system's, outside any action.
 *
 * <p>Order within a step: camps finish raising; spoilage; timers. (Protected keeps:
 * a keep is never besieged, and its upkeep drain is the next step's work.) Drains
 * carry fractional debts, so the result does not depend on how the time is cut
 * into steps (the time scale and {@code advance} agree with real time).
 */
public final class SiegeSim {

    /** Longest stretch of siege time handled in one pass, so nothing is skipped. */
    public static final long MAX_PASS = Durations.MINUTE;

    private final ActionEngine engine;
    private final CharterState state;
    private final Knobs knobs;
    private final FoodTable food;
    private Runnable afterPass = () -> { };
    private WarInputs warInputs = WarInputs.NONE;

    public SiegeSim(ActionEngine engine, FoodTable food) {
        this.engine = engine;
        this.state = engine.state();
        this.knobs = engine.knobs();
        this.food = food;
        state.siege.food = food;
    }

    /** Who stands where, for captures. */
    public SiegeSim inputs(WarInputs newInputs) {
        warInputs = newInputs == null ? WarInputs.NONE : newInputs;
        return this;
    }

    /** Runs after every pass, step and admin advance alike (the debrief log's hourly snapshots). */
    public SiegeSim afterPass(Runnable listener) {
        afterPass = listener == null ? () -> { } : listener;
        return this;
    }

    public FoodTable food() {
        return food;
    }

    public SiegeRules rules() {
        return new SiegeRules(state, knobs, food, engine.now());
    }

    public Supply supply() {
        return new Supply(engine, food);
    }

    /** Advances by real elapsed time at the configured time scale. */
    public void step(long realElapsedMillis) {
        long delta = Math.round(realElapsedMillis * knobs.timeScale);
        advance(delta);
    }

    /** Advances siege time directly, in passes of at most a siege minute (admin "time advance"). */
    public void advance(long siegeMillis) {
        long left = siegeMillis;
        while (left > 0) {
            long pass = Math.min(MAX_PASS, left);
            state.siegeClock.advanceSiege(pass);
            run(pass);
            left -= pass;
        }
        engine.tick();
    }

    // ---- one pass ----

    private void run(long dt) {
        List<HistoryEvent> events = new ArrayList<>();
        long wall = engine.now();
        long now = state.siegeClock.now();
        SiegeRules rules = new SiegeRules(state, knobs, food, wall);

        forgetGone();
        events.addAll(charter.contract.Justice.freeOrphans(state, knobs, wall));
        events.addAll(charter.contract.Justice.tick(state, knobs, wall));
        raiseCamps(wall, now, events);
        upkeepPass(rules, dt / (double) Durations.HOUR, wall, now, events);
        warPass(rules, dt, wall, now, events);

        spoilPass(rules, dt / (double) Durations.HOUR);
        timers(rules, wall, now, events);

        engine.recordSystem(events);
        afterPass.run();
    }

    private List<Core> keeps() {
        return state.cores().stream().filter(Core::isKeep).toList();
    }

    private List<Core> camps() {
        return state.cores().stream().filter(c -> !c.isKeep()).toList();
    }

    private void forgetGone() {
        SiegeBook book = state.siege;
        book.stores.keySet().removeIf(id -> state.core(id).isEmpty());
        book.keeps.keySet().removeIf(id -> state.core(id).isEmpty());
    }

    // ---- camps raising ----

    private void raiseCamps(long wall, long now, List<HistoryEvent> events) {
        long raising = Durations.hours(knobs.campRaisingHours);
        for (Core camp : camps()) {
            if (camp.phase() != CampPhase.RAISING || now - camp.phaseSince() < raising) continue;
            camp.setPhase(CampPhase.ACTIVE, now);
            events.add(new SiegeEvents.CampActive(wall, now, camp.id, camp.owner));
        }
    }

    // ---- upkeep: the granary is the keep's upkeep (protected keeps, K2) ----

    /**
     * Each keep's granary drains an even share every hour, so a full one lasts {@code keepUpkeepDays}, and nothing else
     * changes the drain. Members are warned at {@code keepWarnDays} left (once per threshold; feeding the keep above it
     * re-arms it). At zero the claim lapses. The raiders' strongholds are not kept up this way: they fall to their waves.
     * A keep gets one upkeep period of grace from its first pass, so a new, unstocked keep does not lapse at once.
     */
    private void upkeepPass(SiegeRules rules, double hours, long wall, long now, List<HistoryEvent> events) {
        if (!knobs.keepGranary) return;
        for (Core keep : keeps()) {
            if (state.org(keep.owner).map(o -> o.npc || o.adminKingdom).orElse(false)) continue;
            SiegeBook.KeepSite site = state.siege.keep(keep.id);
            long period = Durations.days(knobs.keepNewGraceDays);
            if (site.graceUntilSiege == 0) site.graceUntilSiege = now + period;
            Store store = state.siege.store(keep.id);
            double perHour = rules.upkeepPerHour(keep);
            store.eatDebt += perHour * hours;
            EatPlan.Result eaten = EatPlan.plan(store.provisions, store.eatDebt, food);
            store.provisions.apply(eaten.eaten());
            store.eatDebt = eaten.debt();

            double have = store.provisions.nourishment(food);
            long foodMillis = perHour <= 0 ? Long.MAX_VALUE / 4 : Math.round(have / perHour * Durations.HOUR);
            long lapseAt = Math.max(site.graceUntilSiege, now + Math.min(foodMillis, Long.MAX_VALUE / 4));
            if (have <= 0 && now >= site.graceUntilSiege) {
                lapse(rules, keep, wall, now, events);
                continue;
            }
            double daysLeft = (lapseAt - now) / (double) Durations.DAY;
            int current = 0;
            for (int threshold : knobs.keepWarnDays) {
                if (daysLeft <= threshold && (current == 0 || threshold < current)) current = threshold;
            }
            if (current == 0) site.warned = 0;
            else if (site.warned == 0 || current < site.warned) {
                events.add(new SiegeEvents.KeepRunningLow(wall, now, keep.id, keep.owner, current));
                site.warned = current;
            } else if (current > site.warned) {
                site.warned = current;
            }
        }
    }

    /** The claim lapses: the land goes back to the wilderness, the blocks stay, the nation keeps its members and treasury. */
    private void lapse(SiegeRules rules, Core keep, long wall, long now, List<HistoryEvent> events) {
        events.add(new SiegeEvents.KeepLapsed(wall, now, keep.id, keep.owner, keep.center));
        events.addAll(charter.contract.Justice.freeKeep(state, knobs, keep.id, wall, "keep lapsed"));
        events.addAll(rules.land().loseCore(keep.id, wall));
        state.siege.stores.remove(keep.id);
    }

    // ---- war: capture an outpost (protected keeps, O1) ----

    private void warPass(SiegeRules rules, long dt, long wall, long now, List<HistoryEvent> events) {
        WarBook wars = state.wars;
        for (WarBook.Goal goal : new ArrayList<>(wars.goals)) {
            Core outpost = state.core(goal.outpost).orElse(null);
            if (outpost == null) {
                // The target was lost some other way (the owner's nation was dissolved, an operator removed it): not the
                // attackers' failure, so their stake comes back. Told, and the two nations rest for the truce.
                end(goal, false, wall, now, events);
                continue;
            }
            if (goal.from != null && state.core(goal.from).isEmpty()) {
                // The war is between two outposts: when the attackers' one is lost, there is nothing left to fight from.
                end(goal, true, wall, now, events);
                continue;
            }
            if (now >= goal.expiresAt) {
                end(goal, true, wall, now, events);
                continue;
            }
            if (!goal.opened && now >= goal.opensAt) {
                goal.opened = true;
                events.add(new SiegeEvents.WarOpened(wall, now, goal.id, goal.attacker, goal.defender, goal.outpost));
            }
            if (!goal.opened) continue;
            // A defender anywhere in the outpost resets the hold. With none, enough attackers in the banner chunk add to
            // it; fewer than warMinAttackers leave it unattended, and unattended progress halves every siege hour.
            if (warInputs.defenders(outpost) > 0) goal.progress = 0;
            else if (warInputs.attackers(goal, outpost) >= Math.max(1, knobs.warMinAttackers)) goal.progress += dt;
            else goal.progress = decayed(goal.progress, dt);
            if (goal.progress >= Durations.minutes(knobs.outpostCaptureMinutes)) capture(rules, goal, outpost, wall, now, events);
        }
    }

    /** Capture progress after {@code dt} siege milliseconds with nobody holding the outpost: it halves every half-life. */
    private long decayed(long progress, long dt) {
        if (progress <= 0) return 0;
        double halfLife = Math.max(0.01, knobs.warDecayHalfLifeHours) * Durations.HOUR;
        return (long) Math.floor(progress * Math.pow(0.5, dt / halfLife));
    }

    /**
     * A goal ends without a capture: told, a truce between the two nations, and the stake settled. {@code forfeit} is
     * true when the war lapsed or the attackers' outpost was lost (the stake goes to the defenders) and false when
     * the target vanished (it goes back to the attackers).
     */
    private void end(WarBook.Goal goal, boolean forfeit, long wall, long now, List<HistoryEvent> events) {
        events.add(new SiegeEvents.WarLapsed(wall, now, goal.id, goal.attacker, goal.defender, goal.outpost));
        state.wars.truces.add(new WarBook.Truce(goal.attacker, goal.defender, now + Durations.days(knobs.truceDays)));
        if (goal.stakeMarks > 0) {
            state.payment.credit((forfeit ? goal.defender : goal.attacker).toString(), goal.stakeMarks, forfeit ? "war_stake_forfeit" : "war_stake_refund");
            goal.stakeMarks = 0;
        }
        state.wars.goals.remove(goal);
    }

    /** The banner goes, the land is released, the deposit is the attackers', and they alone may plant there for a while. */
    private void capture(SiegeRules rules, WarBook.Goal goal, Core outpost, long wall, long now, List<HistoryEvent> events) {
        // The deposit left the defender's treasury when the outpost was planted, so the attackers' share is a plain credit.
        long deposit = outpost.depositMarks;
        outpost.depositMarks = 0;
        if (deposit > 0) state.payment.credit(goal.attacker.toString(), deposit, "plunder");
        if (goal.stakeMarks > 0) {
            state.payment.credit(goal.attacker.toString(), goal.stakeMarks, "war_stake_refund");
            goal.stakeMarks = 0;
        }
        events.add(new SiegeEvents.OutpostCaptured(wall, now, goal.id, goal.attacker, goal.defender, goal.outpost, outpost.center, 0, deposit));
        for (var claim : List.copyOf(state.claimsOf(outpost.owner))) {
            if (goal.outpost.equals(claim.core())) state.removeClaim(claim.id);
        }
        events.addAll(rules.land().loseCore(goal.outpost, wall));
        state.siege.stores.remove(goal.outpost);
        state.wars.goals.remove(goal);
        state.wars.truces.add(new WarBook.Truce(goal.attacker, goal.defender, now + Durations.days(knobs.truceDays)));
        state.wars.windows.add(new WarBook.Window(outpost.center, goal.attacker, now + Durations.minutes(knobs.outpostWinnerWindowMinutes)));
    }

    // ---- spoilage ----

    private void spoilPass(SiegeRules rules, double hours) {
        for (Map.Entry<CoreId, Store> entry : state.siege.stores.entrySet()) {
            Core core = state.core(entry.getKey()).orElse(null);
            if (core == null) continue;
            Store store = entry.getValue();
            if (store.provisions.isEmpty()) continue;
            double limit = core.isKeep() ? rules.storageLimit(core) : rules.campStorageLimit();
            SpoilagePlan.Result spoiled = SpoilagePlan.plan(store.provisions, store.spoilDebts, food, knobs, hours, limit);
            store.provisions.apply(spoiled.spoiled());
            store.spoilDebts.clear();
            store.spoilDebts.putAll(spoiled.debts());
        }
    }

    // ---- timers ----

    private void timers(SiegeRules rules, long wall, long now, List<HistoryEvent> events) {
        SiegeBook book = state.siege;
        state.wars.truces.removeIf(t -> now >= t.untilSiege());
        state.wars.windows.removeIf(w -> now >= w.untilSiege());
        events.addAll(rules.land().expireGrace(wall));
    }

    /** For tests and the debrief: the provisions a site holds now. */
    public Provisions provisions(CoreId site) {
        Store store = state.siege.stores.get(site);
        return store == null ? new Provisions() : store.provisions.copy();
    }
}
