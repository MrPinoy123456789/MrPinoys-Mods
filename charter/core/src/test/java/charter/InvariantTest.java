package charter;

import charter.engine.Action;
import charter.engine.Outcome;
import charter.food.FoodTable;
import charter.food.ShelfClass;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.gov.actions.Vacate;
import charter.grant.AuthorityResolver;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.grant.actions.RevokeGrant;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.LandGraph;
import charter.land.actions.ClaimLand;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.land.actions.Unclaim;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.Expel;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.org.actions.Leave;
import charter.org.actions.Rename;
import charter.siege.Allowance;
import charter.siege.FallChoice;
import charter.siege.KeepSupply;
import charter.siege.SiegeBook;
import charter.siege.SiegeInputs;
import charter.siege.SiegeSim;
import charter.siege.Store;
import charter.siege.actions.ChooseFall;
import charter.siege.actions.DepositFood;
import charter.state.CharterState;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Transfer;
import charter.treasury.actions.Withdraw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static charter.Check.check;

/**
 * The adversarial test (INVARIANTS.md). Seeded worlds take thousands of random
 * steps: actions by random players, most of them invalid; votes proposed and cast;
 * crossings into keeps; wall and siege time passing. After every step every
 * invariant is checked, and a refused action must have left the state exactly as
 * it was. A failure prints the seed and step so it can be replayed.
 */
public final class InvariantTest {

    static final int SEEDS = 12;
    static final int STEPS = 1_500;
    static final Map<String, Integer> JUSTICE_DONE = new java.util.TreeMap<>();
    /** The knobs of the world being checked, for the cap invariants. */
    static final ThreadLocal<charter.config.Knobs> KNOBS = ThreadLocal.withInitial(charter.config.Knobs::new);

    private InvariantTest() {
    }

    /** Runs this test alone: {@code java -cp <core classes>:<test classes> charter.InvariantTest}. */
    public static void main(String[] args) {
        run();
        System.out.println("invariant test: " + Check.passed() + " passed, " + Check.failures().size() + " failed");
        for (String failure : Check.failures()) System.out.println("  FAIL " + failure);
        if (!Check.failures().isEmpty()) System.exit(1);
    }

    public static void run() {
        int refused = 0;
        int done = 0;
        for (int seed = 1; seed <= SEEDS; seed++) {
            int[] tally = runSeed(seed);
            if (tally == null) return;
            done += tally[0];
            refused += tally[1];
        }
        check("invariants: the random worlds exercised both successes and refusals (" + done + " done, " + refused + " refused)",
                done > SEEDS * 50 && refused > SEEDS * 200);
        System.out.println("invariants: " + SEEDS + " seeds x " + STEPS + " steps, " + done + " done, " + refused + " refused, every invariant held");
        System.out.println("invariants: justice actions that succeeded: " + JUSTICE_DONE);
        check("invariants: arrests and sentences really happen in the random worlds", JUSTICE_DONE.getOrDefault("Arrest", 0) > 0
                && JUSTICE_DONE.getOrDefault("Confine", 0) + JUSTICE_DONE.getOrDefault("Sentence", 0) + JUSTICE_DONE.getOrDefault("Execute", 0) > 0);
    }

    // ---- one world ----

    static final class World {
        final Random rng;
        final Fixture f = new Fixture();
        final RandomInputs inputs;
        final SiegeSim sim;
        final List<PlayerId> players = new ArrayList<>();

        World(long seed) {
            rng = new Random(seed);
            inputs = new RandomInputs(rng);
            sim = new SiegeSim(f.engine, new FoodTable()
                    .put("minecraft:bread", 5, ShelfClass.PRESERVED)
                    .put("minecraft:cooked_beef", 8, ShelfClass.COOKED)
                    .put("minecraft:carrot", 3, ShelfClass.FRESH))
                    .inputs(charter.contract.JusticeInputs.wrap(inputs, f.state, f.knobs, f.engine::now));
            for (int i = 0; i < 8; i++) players.add(Fixture.player("chaos" + i));
            f.knobs.campRaisingHours = 0.25;
            f.knobs.newFactionProtectionDays = 0;
            // A weekly cap above the per-sentence caps, so each cap can be broken (and caught) on its own.
            f.knobs.weeklyPunishmentCapHours = 48;
        }

        <T> T pick(List<T> list) {
            return list.isEmpty() ? null : list.get(rng.nextInt(list.size()));
        }

        PlayerId player() {
            return pick(players);
        }

        OrgId org() {
            Organization o = pick(new ArrayList<>(f.state.orgs()));
            return o == null ? null : o.id;
        }

        ChunkKey chunk() {
            String dim = rng.nextInt(40) == 0 ? "minecraft:the_nether" : "minecraft:overworld";
            return new ChunkKey(dim, rng.nextInt(17) - 8, rng.nextInt(17) - 8);
        }

        Power power() {
            Power[] all = Power.values();
            return all[rng.nextInt(all.length)];
        }

        Currency currency() {
            return rng.nextBoolean() ? Currency.COBBLESTONE : Currency.DIAMOND;
        }

        Holder holder() {
            return switch (rng.nextInt(3)) {
                case 0 -> new Holder.OfPlayer(player());
                case 1 -> {
                    Office o = pick(new ArrayList<>(f.state.allOffices()));
                    yield o == null ? new Holder.OfPlayer(player()) : new Holder.OfOffice(o.id);
                }
                default -> {
                    OrgId o = org();
                    yield o == null ? new Holder.OfPlayer(player()) : new Holder.OfOrg(o);
                }
            };
        }

        /** Who should usually perform the last steered draw (a Lord with the power), or null. */
        PlayerId preferred;

        PlayerId lordOf(OrgId org) {
            return f.state.officeNamed(org, Preset.LORD).map(Office::holder).orElse(null);
        }

        /** A claimed chunk where one exists (so arrests can happen on someone's land), else any chunk. */
        ChunkKey claimChunk() {
            Claim c = pick(new ArrayList<>(f.state.claims()));
            return c == null || rng.nextInt(5) == 0 ? chunk() : c.chunk;
        }

        /** A random handful of terms, often over their caps or not allowed under custody. */
        List<charter.contract.Term> terms() {
            Core c = pick(new ArrayList<>(f.state.cores()));
            return terms(c == null ? null : c.id);
        }

        List<charter.contract.Term> terms(charter.id.CoreId keep) {
            List<charter.contract.Term> out = new ArrayList<>();
            Core c = keep == null ? null : f.state.core(keep).orElse(null);
            for (int i = rng.nextInt(3); i >= 0; i--) {
                out.add(switch (rng.nextInt(6)) {
                    case 0 -> new charter.contract.Term.Confinement(Durations.minutes(rng.nextInt(4 * 24 * 60)), c == null ? null : c.id, "cell");
                    case 1 -> new charter.contract.Term.Banishment(org(), Durations.minutes(rng.nextInt(4 * 24 * 60)));
                    case 2 -> new charter.contract.Term.RespawnDelay(Durations.minutes(rng.nextInt(240)));
                    case 3 -> new charter.contract.Term.Fine(rng.nextInt(60));
                    case 4 -> new charter.contract.Term.Tether(Durations.minutes(rng.nextInt(4 * 24 * 60)), c == null ? null : c.id);
                    default -> new charter.contract.Term.Payment(currency(), rng.nextInt(50));
                });
            }
            return out;
        }

        Map<String, Integer> food() {
            String[] items = {"minecraft:bread", "minecraft:cooked_beef", "minecraft:carrot", "minecraft:stone"};
            return Map.of(items[rng.nextInt(items.length)], 1 + rng.nextInt(40));
        }

        /** A random action, valid or not. Null when the world has nothing of the kind yet. */
        Action action() {
            OrgId org = org();
            PlayerId p = player();
            int roll = rng.nextInt(34);
            if (org == null && roll > 1) roll = rng.nextInt(2);
            preferred = null;
            // Half the arrests are made on a faction's own land by its Lord, so custody really happens.
            if (roll == 24 && rng.nextBoolean()) {
                Core keep = pick(f.state.cores().stream().filter(Core::isKeep).toList());
                if (keep != null) {
                    OrgId faction = f.state.root(keep.owner);
                    preferred = lordOf(faction);
                    return new charter.contract.actions.Arrest(faction, player(), keep.center);
                }
            }
            // Half the sentencing draws aim at a prisoner really in custody, usually by the Lord, so the caps are exercised.
            charter.contract.Custody held = pick(new ArrayList<>(f.state.contracts.custodies()));
            if (held != null && roll >= 25 && roll <= 30 && rng.nextBoolean()) {
                preferred = lordOf(held.faction());
                return switch (roll) {
                    case 25 -> new charter.contract.actions.Confine(held.faction(), held.prisoner(),
                            Durations.minutes(rng.nextInt(4 * 24 * 60)), held.keep(), "cell");
                    case 26 -> new charter.contract.actions.Sentence(held.faction(), held.prisoner(), terms(held.keep()));
                    case 27 -> new charter.contract.actions.Execute(held.faction(), held.prisoner(),
                            Durations.minutes(rng.nextInt(240)), Durations.minutes(rng.nextInt(4 * 24 * 60)));
                    case 28 -> new charter.contract.actions.FineCustody(held.faction(), held.prisoner(), rng.nextInt(60));
                    case 29 -> new charter.contract.actions.Pardon(held.faction(), held.prisoner());
                    default -> new charter.contract.actions.Release(held.faction(), held.prisoner());
                };
            }
            return switch (roll) {
                case 0 -> new Found(p, "Realm" + rng.nextInt(6), rng.nextBoolean() ? Preset.LORDSHIP : Preset.COMMUNE);
                case 1 -> {
                    OrgId any = org();
                    yield any == null ? new Found(p, "Realm" + rng.nextInt(6), Preset.COMMUNE) : new AcceptInvite(p, any);
                }
                case 2 -> new InviteMember(org, player());
                case 3 -> new Leave(p);
                case 4 -> new Expel(org, player());
                case 5 -> new Rename(org, "Realm" + rng.nextInt(6));
                case 6 -> new CreateSubOrg(org, "Sub" + rng.nextInt(6), rng.nextBoolean() ? Preset.LORDSHIP : Preset.COMMUNE, player());
                case 7 -> new CreateOffice(org, "Office" + rng.nextInt(3));
                case 8 -> {
                    Office o = pick(new ArrayList<>(f.state.allOffices()));
                    yield o == null ? new CreateOffice(org, "Reeve") : new Appoint(o.org, o.id, player());
                }
                case 9 -> {
                    Office o = pick(new ArrayList<>(f.state.allOffices()));
                    yield o == null ? new CreateOffice(org, "Reeve") : new Vacate(o.org, o.id);
                }
                case 10, 11 -> new GrantPower(org, power(), holder(),
                        rng.nextBoolean() ? GrantScope.ANY : GrantScope.spendUpTo(rng.nextInt(3000), rng.nextInt(60)),
                        rng.nextBoolean(), rng.nextInt(4) == 0 ? f.clock.now() + Durations.hours(rng.nextInt(48)) : 0);
                case 12 -> {
                    Grant g = pick(new ArrayList<>(f.state.grants()));
                    yield g == null ? new Deposit(org, currency(), 10) : new RevokeGrant(g.subject, g.power, g.id);
                }
                case 13, 14 -> new Deposit(org, currency(), rng.nextInt(600) - 20);
                case 15 -> new Withdraw(org, currency(), rng.nextInt(80), p);
                case 16 -> new Transfer(org, org(), currency(), rng.nextInt(80));
                case 17 -> new PlaceNexus(org, chunk());
                case 18 -> new PlantCamp(org, chunk());
                case 19, 20 -> new ClaimLand(org, chunk());
                case 21 -> new Unclaim(org, chunk());
                case 22 -> {
                    Core c = pick(new ArrayList<>(f.state.cores()));
                    yield c == null ? new PlaceNexus(org, chunk()) : new DepositFood(c.owner, c.id, "player:" + p, food());
                }
                case 24 -> new charter.contract.actions.Arrest(org, player(), claimChunk());
                case 25 -> {
                    Core c = pick(new ArrayList<>(f.state.cores()));
                    yield new charter.contract.actions.Confine(org, player(), Durations.minutes(rng.nextInt(4 * 24 * 60)),
                            c == null ? null : c.id, "cell");
                }
                case 26 -> new charter.contract.actions.Sentence(org, player(), terms());
                case 27 -> new charter.contract.actions.Execute(org, player(), Durations.minutes(rng.nextInt(240)),
                        Durations.minutes(rng.nextInt(4 * 24 * 60)));
                case 28 -> new charter.contract.actions.FineCustody(org, player(), rng.nextInt(60));
                case 29 -> new charter.contract.actions.Pardon(org, player());
                case 30 -> new charter.contract.actions.Release(org, player());
                case 31 -> new charter.contract.actions.Escape(player());
                case 32 -> new charter.contract.actions.DepositPersonal(player(), currency(), rng.nextInt(50) - 5);
                case 33 -> {
                    charter.contract.Contract c = pick(new ArrayList<>(f.state.contracts.all()));
                    yield c == null ? new charter.contract.actions.DepositPersonal(p, Currency.DIAMOND, 3)
                            : new charter.contract.actions.Breach(c.id, rng.nextBoolean() ? c.imposer : c.subject);
                }
                default -> {
                    SiegeBook.PendingFall fall = pick(new ArrayList<>(f.state.siege.falls.values()));
                    yield fall == null ? new ClaimLand(org, chunk())
                            : new ChooseFall(rng.nextBoolean() ? fall.besieger() : org, fall.keep(), rng.nextBoolean() ? FallChoice.RAZE : FallChoice.OCCUPY);
                }
            };
        }
    }

    /** Presence and prisoners drawn at random each time the sim asks. */
    static final class RandomInputs implements SiegeInputs {
        final Random rng;

        RandomInputs(Random rng) {
            this.rng = rng;
        }

        int small() {
            return rng.nextInt(4) == 0 ? rng.nextInt(4) : 0;
        }

        public int attackersInCamp(charter.id.CoreId camp) { return small(); }
        public int defendersInKeep(charter.id.CoreId keep) { return small(); }
        public int attackersInsideKeep(charter.id.CoreId keep) { return small(); }
        public int defendersBesideCamp(charter.id.CoreId camp) { return small(); }
        public int prisoners(charter.id.CoreId keep) { return 0; }
    }

    // ---- the run ----

    /** Returns {done, refused}, or null after reporting a broken invariant. */
    static int[] runSeed(long seed) {
        World w = new World(seed);
        KNOBS.set(w.f.knobs);
        int done = 0;
        int refused = 0;
        int historyBefore = 0;
        for (int step = 0; step < STEPS; step++) {
            String what;
            String before = fingerprint(w.f.state);
            int roll = w.rng.nextInt(20);
            Outcome outcome = null;
            Action acted = null;
            if (roll < 13) {
                Action action = w.action();
                boolean asAdmin = w.rng.nextInt(15) == 0;
                what = (asAdmin ? "admin " : "") + action;
                acted = action;
                PlayerId actor = w.preferred != null && w.rng.nextInt(4) != 0 ? w.preferred : w.player();
                outcome = asAdmin ? w.f.admin(action) : w.f.as(actor, action);
            } else if (roll < 15) {
                Action action = w.action();
                what = "propose " + action;
                outcome = w.f.engine.propose(w.player(), action);
            } else if (roll < 17) {
                Vote vote = w.pick(w.f.state.votes().stream().filter(Vote::open).toList());
                if (vote == null) continue;
                Vote.Ballot ballot = vote.kind == Vote.Kind.ELECTION ? Vote.Ballot.candidate(w.player())
                        : w.rng.nextBoolean() ? Vote.Ballot.YES : Vote.Ballot.NO;
                what = "cast " + ballot + " on " + vote.id;
                outcome = w.f.engine.cast(w.player(), vote.id, ballot);
            } else if (roll < 18) {
                Core site = w.pick(new ArrayList<>(w.f.state.cores()));
                if (site == null) continue;
                ChunkKey from = site.center.offset(w.rng.nextInt(5) - 2, w.rng.nextInt(5) - 2);
                ChunkKey to = site.center.offset(w.rng.nextInt(3) - 1, w.rng.nextInt(3) - 1);
                what = "enter " + from + " to " + to;
                w.sim.supply().enter("player:" + w.player(), from, to, w.food());
            } else {
                long wall = Durations.minutes(w.rng.nextInt(240));
                long siege = Durations.minutes(w.rng.nextInt(180));
                what = "time +" + wall / Durations.MINUTE + "m wall, +" + siege / Durations.MINUTE + "m siege";
                for (PlayerId p : w.players) {
                    if (w.rng.nextInt(3) != 0) w.f.state.touch(p, "", w.f.clock.now());
                }
                w.f.clock.advance(wall);
                w.sim.advance(siege);
                String broken = afterStep(w.f.state, w.sim, w.f.clock.now());
                if (broken != null) return fail(seed, step, what, broken);
            }

            if (outcome != null) {
                if (outcome.refused() || outcome instanceof Outcome.Ok) {
                    if (outcome.refused()) refused++;
                    String after = fingerprint(w.f.state);
                    if (outcome.refused() && !after.equals(before)) {
                        return fail(seed, step, what, "a refused action (" + outcome.reasonKey() + ") changed the state:\n"
                                + firstDifference(before, after));
                    }
                } else {
                    done++;
                    if (acted != null && acted.getClass().getPackageName().equals("charter.contract.actions")) {
                        JUSTICE_DONE.merge(acted.getClass().getSimpleName(), 1, Integer::sum);
                    }
                }
            }
            String broken = invariants(w.f.state, w.f.clock.now());
            if (broken == null && w.f.state.history.size() < historyBefore) broken = "history shrank";
            if (broken != null) return fail(seed, step, what, broken);
            historyBefore = w.f.state.history.size();
        }
        check("invariants: seed " + seed + " ran " + STEPS + " steps", true);
        return new int[]{done, refused};
    }

    static int[] fail(long seed, int step, String what, String broken) {
        check("invariants: seed " + seed + ", step " + step + " (" + what + "): " + broken, false);
        return null;
    }

    static String firstDifference(String a, String b) {
        String[] x = a.split("\n");
        String[] y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String l = i < x.length ? x[i] : "<none>";
            String r = i < y.length ? y[i] : "<none>";
            if (!l.equals(r)) return "    before: " + l + "\n    after:  " + r;
        }
        return "    (same lines, different length)";
    }

    // ---- the invariants (INVARIANTS.md) ----

    /** Null if every invariant holds, otherwise which one broke and how. */
    static String invariants(CharterState s, long now) {
        for (Organization org : s.orgs()) {
            if (org.parent() != null && s.org(org.parent()).isEmpty()) return "3: " + org.id + " has a missing parent";
            Set<OrgId> seen = new HashSet<>();
            OrgId at = org.id;
            while (at != null) {
                if (!seen.add(at)) return "3: cycle above " + org.id;
                at = s.orgIncludingDissolved(at).map(Organization::parent).orElse(null);
            }
            for (Currency c : Currency.values()) {
                if (org.ledger.balance(c) < 0) return "9: " + org.id + " holds negative " + c;
            }
            for (MemberRecord m : s.members(org.id)) {
                if (!s.membership(m.player()).map(r -> r.org().equals(org.id)).orElse(false)) return "4: membership index disagrees for " + m.player();
            }
        }
        for (Office office : s.allOffices()) {
            if (s.org(office.org).isEmpty()) continue;
            if (!office.vacant() && !s.isMember(office.holder(), office.org)) return "5: " + office.name + " is held by a non-member";
        }
        AuthorityResolver resolver = new AuthorityResolver(s, now);
        for (Grant grant : s.grants()) {
            if (!resolver.valid(grant)) continue;
            String chain = chainProblem(s, grant, now);
            if (chain != null) return "6: grant " + grant.id + " works but " + chain;
        }
        Set<ChunkKey> chunks = new HashSet<>();
        LandGraph land = new LandGraph(s, new charter.config.Knobs());
        for (Claim claim : s.claims()) {
            if (!chunks.add(claim.chunk)) return "10: two claims at " + claim.chunk;
            if (s.claimAt(claim.chunk).map(c -> c != claim).orElse(true)) return "10: chunk index disagrees at " + claim.chunk;
            if ((claim.core() == null) != claim.contested()) return "11: claim at " + claim.chunk + " is neither assigned nor contested, or both";
            if (claim.core() != null) {
                Core core = s.core(claim.core()).orElse(null);
                if (core == null) return "11: claim at " + claim.chunk + " names a missing core";
                if (!core.owner.equals(claim.owner)) return "11: claim at " + claim.chunk + " is assigned to another owner's core";
            }
        }
        for (Core core : s.cores()) {
            List<Claim> own = s.claims().stream().filter(c -> core.id.equals(c.core())).toList();
            long fixed = own.stream().filter(Claim::fixed).count();
            if (core.isKeep() && fixed != 9) return "12: keep " + core.id + " has " + fixed + " of its nine chunks";
            if (!core.isKeep() && fixed != 1) return "12: camp " + core.id + " has " + fixed + " banner chunks";
            long counted = own.stream().filter(c -> !c.fixed()).count();
            if (counted > land.capacity(core)) return "13: core " + core.id + " holds " + counted + " claims over capacity " + land.capacity(core);
            for (Claim c : own) {
                if (c.hops() > land.range(core)) return "13: claim at " + c.chunk + " is " + c.hops() + " hops out, past range";
            }
        }
        SiegeBook book = s.siege;
        for (var entry : book.stores.entrySet()) {
            if (s.core(entry.getKey()).isEmpty()) continue;
            for (var count : entry.getValue().provisions.counts().entrySet()) {
                if (count.getValue() < 0) return "14: negative " + count.getKey() + " in a store";
            }
        }
        for (Allowance a : book.allowances) {
            for (var item : a.items().entrySet()) {
                if (item.getValue() < 0) return "14: an allowance allows negative " + item.getKey();
            }
        }
        return justice(s, now, KNOBS.get());
    }

    /** Invariants 18 and 19 (M6): punishment within its caps, and resistance never breach. */
    static String justice(CharterState s, long now, charter.config.Knobs k) {

        long weekly = Durations.hours(k.weeklyPunishmentCapHours);
        for (int i = 0; i < 8; i++) {
            PlayerId p = Fixture.player("chaos" + i);
            long counted = s.contracts.punishments.counted(p, now);
            if (counted > weekly) return "18: " + p + " has " + counted / Durations.MINUTE + " minutes of punishment this week, over the cap";
        }
        for (charter.contract.Contract c : s.contracts.all()) {
            if (c.consent == charter.contract.Consent.IMPOSED && c.state() == charter.contract.ContractState.BREACHED) {
                return "19: imposed contract " + c.id + " was marked as breached";
            }
            if (c.consent != charter.contract.Consent.IMPOSED) continue;
            for (charter.contract.Term t : c.terms) {
                String over = switch (t) {
                    case charter.contract.Term.Confinement x -> x.durationMillis() > Durations.hours(k.confinementMaxHours) ? "confinement" : null;
                    case charter.contract.Term.Tether x -> x.durationMillis() > Durations.hours(k.confinementMaxHours) ? "bond" : null;
                    case charter.contract.Term.Banishment x -> x.durationMillis() > Durations.hours(k.banishmentMaxHours) ? "banishment" : null;
                    case charter.contract.Term.RespawnDelay x -> x.durationMillis() > Durations.minutes(k.executionRespawnDelayMaxMinutes) ? "respawn delay" : null;
                    case charter.contract.Term.Fine x -> x.diamonds() > k.custodyFineCapDiamonds ? "fine" : null;
                    default -> null;
                };
                if (over != null) return "18: imposed contract " + c.id + " has a " + over + " over its cap: " + t;
            }
        }
        return null;
    }

    /** Checks a working grant's chain independently of the resolver. */
    static String chainProblem(CharterState s, Grant grant, long now) {
        Grant g = grant;
        for (int depth = 0; depth < 64; depth++) {
            if (g.revoked()) return "a link is revoked";
            if (g.expiresAt != 0 && now >= g.expiresAt) return "a link has expired";
            if (g.parent == null) {
                return g.grantor.equals(new Holder.OfOrg(g.subject)) ? null : "its root was not given by the organization itself";
            }
            Grant parent = s.grant(g.parent).orElse(null);
            if (parent == null) return "a parent is missing";
            if (!parent.redelegable) return "a parent was not passable on";
            if (!g.grantor.equals(parent.recipient)) return "a link was not given by its parent's recipient";
            // Compared limit by limit, not with GrantScope.within, so this check does not share the code it checks.
            for (Currency c : Currency.values()) {
                if (g.scope.limit(c) > parent.scope.limit(c)) return "a link allows more " + c + " than its parent";
            }
            if (parent.power != g.power || !parent.subject.equals(g.subject)) return "a link changes power or subject";
            g = parent;
        }
        return "the chain is too long";
    }

    /** Invariants that hold after a siege step (15, 16). */
    static String afterStep(CharterState s, SiegeSim sim, long now) {
        String basic = invariants(s, now);
        for (Core keep : s.cores()) {
            if (!keep.isKeep()) continue;
            SiegeBook.KeepSite site = s.siege.keeps.get(keep.id);
            if (site == null || site.supply == KeepSupply.SUPPLIED) continue;
            Store store = s.siege.stores.get(keep.id);
            boolean empty = store == null || store.provisions.nourishment(sim.food()) <= 0;
            if (empty && !s.siege.falls.containsKey(keep.id) && !s.siege.occupations.containsKey(keep.id)) {
                return "15: keep " + keep.id + " is " + site.supply + " with an empty granary but has not fallen";
            }
        }
        for (var id : s.siege.falls.keySet()) if (s.core(id).isEmpty()) return "16: a fall names a missing keep";
        for (var id : s.siege.occupations.keySet()) if (s.core(id).isEmpty()) return "16: an occupation names a missing keep";
        for (var id : s.siege.holds.keySet()) if (s.claim(id).isEmpty()) return "16: a hold names a missing claim";
        for (var claimId : s.siege.holds.keySet()) {
            Claim c = s.claim(claimId).orElse(null);
            if (c != null && c.kind() != ClaimKind.OUTLYING) return "16: a hold on a claim that is not outlying";
        }
        return basic;
    }

    // ---- the fingerprint ----

    /** Every piece of state a refused action must leave alone, one fact per line. */
    static String fingerprint(CharterState s) {
        StringBuilder b = new StringBuilder();
        b.append("ids ").append(s.ids.peek()).append('\n');
        b.append("history ").append(s.history.size()).append('\n');
        b.append("siege clock ").append(s.siegeClock.now()).append('\n');
        for (Organization o : s.orgs()) {
            b.append("org ").append(o.id).append(' ').append(o.name()).append(' ').append(o.parent()).append(' ')
                    .append(o.status()).append(' ').append(o.ledger.snapshot()).append('\n');
            for (MemberRecord m : s.members(o.id)) b.append("  member ").append(m).append('\n');
        }
        for (var i : s.invites()) b.append("invite ").append(i).append('\n');
        for (Office o : s.allOffices()) b.append("office ").append(o.id).append(' ').append(o.holder()).append('\n');
        for (Grant g : s.grants()) b.append("grant ").append(g.id).append(' ').append(g.revoked()).append('\n');
        for (Vote v : s.votes()) b.append("vote ").append(v.id).append(' ').append(v.state()).append(' ').append(v.ballots()).append('\n');
        for (Core c : s.cores()) b.append("core ").append(c.id).append(' ').append(c.phase()).append('\n');
        for (Claim c : s.claims()) b.append("claim ").append(c.chunk).append(' ').append(c.core()).append(' ').append(c.kind())
                .append(' ').append(c.hops()).append(' ').append(c.contestedUntil()).append('\n');
        SiegeBook book = s.siege;
        for (var e : book.stores.entrySet()) b.append("store ").append(e.getKey()).append(' ').append(e.getValue().provisions.counts())
                .append(' ').append(e.getValue().eatDebt).append('\n');
        for (var e : book.keeps.entrySet()) b.append("keepsite ").append(e.getKey()).append(' ').append(e.getValue().supply).append('\n');
        for (var e : book.camps.entrySet()) b.append("campsite ").append(e.getKey()).append(' ').append(e.getValue().supply)
                .append(' ').append(e.getValue().sallyLeft).append('\n');
        for (Allowance a : book.allowances) b.append("allowance ").append(a.carrier).append(' ').append(a.items()).append('\n');
        b.append("holds ").append(book.holds).append('\n');
        b.append("falls ").append(book.falls).append('\n');
        b.append("occupations ").append(book.occupations).append('\n');
        b.append("truces ").append(book.truces).append('\n');
        b.append("notoriety ").append(book.notoriety).append('\n');
        b.append("waived ").append(new java.util.TreeSet<>(book.protectionWaived.stream().map(Object::toString).toList())).append('\n');
        for (int i = 0; i < 8; i++) {
            PlayerId p = Fixture.player("chaos" + i);
            s.knownPlayer(p).ifPresent(r -> b.append("player ").append(p).append(' ').append(r.lastSeen).append(' ').append(r.lastFoundedAt).append('\n'));
        }
        for (charter.contract.Contract c : s.contracts.all()) {
            b.append("contract ").append(c.id).append(' ').append(c.state()).append(' ').append(c.breachedBy()).append('\n');
        }
        for (var held : s.contracts.custodies()) b.append("custody ").append(held).append('\n');
        for (var e : s.contracts.punishments.entries()) b.append("punished ").append(e.player).append(' ').append(e.counted()).append('\n');
        b.append("vaults ").append(vaults(s)).append('\n');
        return b.toString();
    }

    /** Players' own deposits, read without {@code ContractBook.vault}, which creates a ledger on read. */
    static String vaults(CharterState s) {
        try {
            java.lang.reflect.Field field = charter.contract.ContractBook.class.getDeclaredField("vault");
            field.setAccessible(true);
            StringBuilder out = new StringBuilder();
            Map<?, ?> vault = (Map<?, ?>) field.get(s.contracts);
            for (var e : vault.entrySet()) {
                out.append(e.getKey()).append('=').append(((charter.treasury.Ledger) e.getValue()).snapshot()).append(';');
            }
            return out.toString();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the contract book's vault field moved", e);
        }
    }
}
