package charter.siege;

import charter.config.Knobs;
import charter.engine.ActionEngine;
import charter.food.EatPlan;
import charter.food.FoodTable;
import charter.food.Provisions;
import charter.food.SpoilagePlan;
import charter.history.HistoryEvent;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.Perimeter;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The siege (PLAN.md §4.3): one {@link #step} advances the siege clock and every
 * drain, timer and transition, and records what happened. Nobody chooses these
 * changes, so they are recorded as the system's, outside any action.
 *
 * <p>Order within a step: camps finish raising; which camps count and against
 * whom; each keep's supply; keep drains with presence, notoriety and assault;
 * camp burn; raids and hold refills; sallies; spoilage; timers; falls. Drains
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
    private SiegeInputs inputs = SiegeInputs.NONE;

    public SiegeSim(ActionEngine engine, FoodTable food) {
        this.engine = engine;
        this.state = engine.state();
        this.knobs = engine.knobs();
        this.food = food;
        state.siege.food = food;
    }

    public SiegeSim inputs(SiegeInputs newInputs) {
        inputs = newInputs == null ? SiegeInputs.NONE : newInputs;
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
        double hours = dt / (double) Durations.HOUR;

        forgetGone();
        events.addAll(charter.contract.Justice.freeOrphans(state, knobs, wall));
        events.addAll(charter.contract.Justice.tick(state, knobs, wall));
        raiseCamps(rules, wall, now, events);
        settleFalls(rules, wall, now, events);

        Map<CoreId, List<Core>> counting = new LinkedHashMap<>();
        for (Core keep : keeps()) counting.put(keep.id, rules.countingCamps(keep));

        for (Core keep : keeps()) keepPass(rules, keep, counting.get(keep.id), hours, wall, now, events);
        for (Core camp : camps()) campPass(rules, camp, hours, dt, wall, now, events);
        holdPass(rules, dt, wall, now, events);
        spoilPass(rules, hours);
        timers(rules, dt, wall, now, events);

        engine.recordSystem(events);
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
        book.camps.keySet().removeIf(id -> state.core(id).isEmpty());
        book.falls.keySet().removeIf(id -> state.core(id).isEmpty());
        book.occupations.keySet().removeIf(id -> state.core(id).isEmpty());
        book.holds.keySet().removeIf(id -> state.claim(id).isEmpty());
        book.allowances.removeIf(a -> state.core(a.site).isEmpty());
    }

    // ---- camps raising ----

    private void raiseCamps(SiegeRules rules, long wall, long now, List<HistoryEvent> events) {
        long raising = Durations.hours(knobs.campRaisingHours);
        for (Core camp : camps()) {
            if (camp.phase() != CampPhase.RAISING || now - camp.phaseSince() < raising) continue;
            camp.setPhase(CampPhase.ACTIVE, now);
            SiegeBook.CampSite site = state.siege.camp(camp.id);
            site.sallyLeft = Durations.hours(knobs.campSallyProtectionHours);
            Set<OrgId> targets = rules.targets(camp);
            site.targets = new LinkedHashSet<>(targets);
            events.add(new SiegeEvents.CampActive(wall, now, camp.id, camp.owner, targets));
            if (!targets.isEmpty()) {
                OrgId home = rules.faction(camp.owner);
                addNotoriety(home, knobs.notorietyPerCamp);
                if (rules.protectedFaction(home) && state.siege.protectionWaived.add(home)) {
                    events.add(new SiegeEvents.ProtectionWaived(wall, home));
                }
                state.siege.protectionWaived.add(home);
            }
        }
    }

    // ---- keeps ----

    private void keepPass(SiegeRules rules, Core keep, List<Core> counting, double hours, long wall, long now,
                          List<HistoryEvent> events) {
        if (state.siege.falls.containsKey(keep.id)) return;
        SiegeBook.KeepSite site = state.siege.keep(keep.id);
        Set<OrgId> besiegers = new LinkedHashSet<>();
        for (Core camp : counting) besiegers.add(rules.faction(camp.owner));

        KeepSupply supply;
        if (counting.isEmpty()) {
            supply = KeepSupply.SUPPLIED;
        } else {
            Perimeter perimeter = rules.keepPerimeter(keep, counting);
            supply = perimeter.fullyClosed() ? KeepSupply.ENCIRCLED : KeepSupply.BESIEGED;
        }
        transition(rules, keep, site, supply, besiegers, wall, now, events);
        site.supply = supply;
        if (supply == KeepSupply.SUPPLIED) {
            site.besiegers = new LinkedHashSet<>();
            site.starving = false;
            return;
        }
        site.besiegers = besiegers;

        Store store = state.siege.store(keep.id);
        double limit = rules.storageLimit(keep);
        double perHour = charter.food.Mouths.drainPerHour(knobs, rules.mouths(keep, inputs.prisoners(keep.id)));
        double presence = 1 + knobs.presencePerPlayer * (attackersAt(counting) - inputs.defendersInKeep(keep.id));
        double multiplier = clamp(presence * rules.notorietyMultiplier(rules.holder(keep)), knobs.multiplierMin, knobs.multiplierMax);
        boolean starving = store.provisions.nourishment(food) < knobs.starvationThreshold * limit;
        if (starving && inputs.attackersInsideKeep(keep.id) > 0) multiplier *= knobs.assaultMultiplier;
        if (starving && !site.starving) events.add(new SiegeEvents.Starving(wall, now, keep.id));
        site.starving = starving;

        store.eatDebt += perHour * multiplier * hours;
        EatPlan.Result eaten = EatPlan.plan(store.provisions, store.eatDebt, food);
        store.provisions.apply(eaten.eaten());
        store.eatDebt = eaten.debt();
        if (eaten.unmet() > 0 || store.provisions.nourishment(food) <= 0) fall(rules, keep, counting, wall, now, events);
    }

    private int attackersAt(List<Core> camps) {
        int n = 0;
        for (Core camp : camps) n += inputs.attackersInCamp(camp.id);
        return n;
    }

    private void transition(SiegeRules rules, Core keep, SiegeBook.KeepSite site, KeepSupply supply, Set<OrgId> besiegers,
                            long wall, long now, List<HistoryEvent> events) {
        KeepSupply was = site.supply;
        if (was == KeepSupply.SUPPLIED && supply != KeepSupply.SUPPLIED) {
            events.add(new SiegeEvents.SiegeBegan(wall, now, keep.id, rules.holder(keep), besiegers));
        }
        if (supply == KeepSupply.ENCIRCLED && was != KeepSupply.ENCIRCLED) events.add(new SiegeEvents.Encircled(wall, now, keep.id));
        if (was == KeepSupply.ENCIRCLED && supply == KeepSupply.BESIEGED) events.add(new SiegeEvents.RingBroken(wall, now, keep.id));
        if (was != KeepSupply.SUPPLIED && supply == KeepSupply.SUPPLIED) {
            events.add(new SiegeEvents.SiegeLifted(wall, now, keep.id, site.besiegers));
            long until = now + Durations.days(knobs.truceDays);
            for (OrgId attacker : site.besiegers) {
                state.siege.truces.add(new SiegeBook.Truce(keep.id, attacker, until));
                events.add(new SiegeEvents.TruceBegan(wall, now, keep.id, attacker, until));
            }
        }
    }

    // ---- the fall ----

    private void fall(SiegeRules rules, Core keep, List<Core> counting, long wall, long now, List<HistoryEvent> events) {
        SiegeBook.KeepSite site = state.siege.keep(keep.id);
        SiegeBook.Occupation occupation = state.siege.occupations.remove(keep.id);
        if (occupation != null) {
            site.supply = KeepSupply.SUPPLIED;
            site.besiegers = new LinkedHashSet<>();
            site.starving = false;
            events.add(new SiegeEvents.OccupationEnded(wall, now, keep.id, occupation.occupier(), "starved"));
            events.addAll(charter.contract.Occupations.end(state, keep.id, "starved", wall));
            return;
        }
        OrgId besieger = mainBesieger(rules, keep, counting);
        state.siege.falls.put(keep.id, new SiegeBook.PendingFall(keep.id, besieger, now + Durations.minutes(knobs.fallChoiceMinutes)));
        addNotoriety(rules.faction(besieger), knobs.notorietyPerClaimTaken);
        events.add(new SiegeEvents.KeepFell(wall, now, keep.id, keep.owner, besieger, keep.center));
        events.addAll(charter.contract.Justice.freeKeep(state, knobs, keep.id, wall, "keep fell"));
    }

    /** The besieging organization holding the most closed sides; the older camp wins a tie. */
    private OrgId mainBesieger(SiegeRules rules, Core keep, List<Core> counting) {
        Perimeter perimeter = rules.keepPerimeter(keep, counting);
        Map<OrgId, Integer> sides = new LinkedHashMap<>();
        for (Core camp : counting) sides.putIfAbsent(camp.owner, 0);
        for (var chunk : perimeter.closed()) {
            state.claimAt(chunk).ifPresent(c -> sides.merge(c.owner, 1, Integer::sum));
        }
        OrgId best = counting.getFirst().owner;
        int most = -1;
        for (Core camp : counting) {
            int n = sides.getOrDefault(camp.owner, 0);
            if (n > most) {
                most = n;
                best = camp.owner;
            }
        }
        return best;
    }

    /** Resolves falls whose choice time has run out: they are razed. */
    private void settleFalls(SiegeRules rules, long wall, long now, List<HistoryEvent> events) {
        for (SiegeBook.PendingFall pending : new ArrayList<>(state.siege.falls.values())) {
            if (now >= pending.decideBySiege()) events.addAll(Falls.resolve(state, knobs, pending.keep(), FallChoice.RAZE, wall));
        }
    }

    // ---- camps ----

    private void campPass(SiegeRules rules, Core camp, double hours, long dt, long wall,
                          long now, List<HistoryEvent> events) {
        SiegeBook.CampSite site = state.siege.camp(camp.id);
        if (!camp.activeCamp()) {
            site.supply = CampSupply.IDLE;
            return;
        }
        Set<OrgId> targets = rules.targets(camp);
        site.targets = new LinkedHashSet<>(targets);
        sally(camp, site, dt, wall, now, events);
        if (targets.isEmpty()) {
            site.supply = CampSupply.IDLE;
            return;
        }
        Store store = state.siege.store(camp.id);
        if (store.provisions.nourishment(food) <= 0) {
            if (site.supply != CampSupply.EMPTY) events.add(new SiegeEvents.CampStoreEmpty(wall, now, camp.id));
            site.supply = CampSupply.EMPTY;
            return;
        }
        boolean cutOff = rules.campPerimeter(camp).fullyClosed();
        if (cutOff && site.supply != CampSupply.CUT_OFF) events.add(new SiegeEvents.CampCutOff(wall, now, camp.id));
        site.supply = cutOff ? CampSupply.CUT_OFF : CampSupply.OPEN;
        double burn = cutOff ? knobs.campBurnCutOffPerHour : knobs.campBurnPerHour;
        store.eatDebt += burn * hours;
        EatPlan.Result eaten = EatPlan.plan(store.provisions, store.eatDebt, food);
        store.provisions.apply(eaten.eaten());
        store.eatDebt = eaten.debt();
        if (store.provisions.nourishment(food) <= 0) {
            site.supply = CampSupply.EMPTY;
            events.add(new SiegeEvents.CampStoreEmpty(wall, now, camp.id));
        }
    }

    /**
     * The sally hold: defenders standing beside the camp drain it, one unit each,
     * attackers in the camp cancel one each; it refills when nobody contests it.
     * Empty, the camp is exposed until it has refilled completely.
     */
    private void sally(Core camp, SiegeBook.CampSite site, long dt, long wall, long now, List<HistoryEvent> events) {
        long full = Durations.hours(knobs.campSallyProtectionHours);
        if (site.sallyLeft < 0) site.sallyLeft = full;
        int defenders = inputs.defendersBesideCamp(camp.id);
        int net = defenders - inputs.attackersInCamp(camp.id);
        if (net > 0) {
            site.sallyLeft = Math.max(0, site.sallyLeft - net * dt);
            if (site.sallyLeft == 0 && !site.exposed) {
                site.exposed = true;
                events.add(new SiegeEvents.CampExposed(wall, now, camp.id));
            }
        } else if (defenders == 0) {
            site.sallyLeft = Math.min(full, site.sallyLeft + dt);
            if (site.sallyLeft >= full) site.exposed = false;
        }
    }

    // ---- raids and holds ----

    private void holdPass(SiegeRules rules, long dt, long wall, long now, List<HistoryEvent> events) {
        long full = Durations.hours(knobs.outlyingHoldHours);
        double refillPerMilli = full / (double) Durations.hours(knobs.outlyingRefillHours);
        Map<ClaimId, OrgId> raided = new LinkedHashMap<>();
        for (Core camp : camps()) {
            if (!camp.activeCamp() || !rules.hasFood(camp.id)) continue;
            for (Claim claim : rules.raidable(camp, rules.campChunks(camp))) raided.putIfAbsent(claim.id, camp.owner);
        }
        for (Claim claim : new ArrayList<>(state.claims())) {
            if (claim.kind() != ClaimKind.OUTLYING) continue;
            long left = state.siege.holds.getOrDefault(claim.id, full);
            if (raided.containsKey(claim.id)) {
                left -= dt;
                if (left <= 0) {
                    state.siege.holds.remove(claim.id);
                    OrgId raider = raided.get(claim.id);
                    addNotoriety(rules.faction(raider), knobs.notorietyPerClaimTaken);
                    events.add(new SiegeEvents.HoldEmptied(wall, now, claim.chunk, claim.owner, raider));
                    events.addAll(rules.land().unclaim(claim.owner, claim.chunk, wall));
                    continue;
                }
            } else if (claim.core() != null && left < full) {
                left = Math.min(full, left + Math.round(dt * refillPerMilli));
            }
            if (left >= full) state.siege.holds.remove(claim.id);
            else state.siege.holds.put(claim.id, left);
        }
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

    private void timers(SiegeRules rules, long dt, long wall, long now, List<HistoryEvent> events) {
        SiegeBook book = state.siege;
        book.allowances.removeIf(a -> now >= a.expiresSiege);
        book.truces.removeIf(t -> now >= t.untilSiege());
        for (SiegeBook.Occupation occupation : new ArrayList<>(book.occupations.values())) {
            if (now >= occupation.untilSiege()) {
                book.occupations.remove(occupation.keep());
                events.add(new SiegeEvents.OccupationEnded(wall, now, occupation.keep(), occupation.occupier(), "cap"));
                events.addAll(charter.contract.Occupations.end(state, occupation.keep(), "cap", wall));
                // The occupier leaves under the same truce as a lifted siege, or a keep handed back
                // with an empty granary would fall to the camp still beside it at once, again and again.
                long until = now + Durations.days(knobs.truceDays);
                book.truces.add(new SiegeBook.Truce(occupation.keep(), occupation.occupier(), until));
                events.add(new SiegeEvents.TruceBegan(wall, now, occupation.keep(), occupation.occupier(), until));
            }
        }
        double decay = knobs.notorietyDecayPerDay * dt / (double) Durations.DAY;
        book.notoriety.replaceAll((faction, n) -> Math.max(0, n - decay));
        book.notoriety.values().removeIf(n -> n <= 0);
        events.addAll(rules.land().expireGrace(wall));
    }

    private void addNotoriety(OrgId faction, double amount) {
        if (amount > 0) state.siege.notoriety.merge(faction, amount, Double::sum);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** For tests and the debrief: the provisions a site holds now. */
    public Provisions provisions(CoreId site) {
        Store store = state.siege.stores.get(site);
        return store == null ? new Provisions() : store.provisions.copy();
    }
}
