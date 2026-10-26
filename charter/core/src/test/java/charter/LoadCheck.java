package charter;

import charter.food.FoodTable;
import charter.food.ShelfClass;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.siege.KeepSupply;
import charter.siege.SiegeInputs;
import charter.siege.SiegeSim;
import charter.siege.Supply;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The load check (PLAN.md §11 M14, risk R1): the rules model at the plan's size,
 * timed. Ten keeps under siege by ten camps, 100 claims in all, 40 players walking
 * about at random, every one of them checked for a crossing on every tick, and the
 * siege stepped once a tick at time scale 24. It prints the cost of a tick and
 * fails if the slowest one in a hundred takes more than a fifth of a tick's 50 ms.
 *
 * <p>Run with {@code gradlew :core:loadCheck}; it is not part of {@code check},
 * because timing on a busy machine is not a rule. The numbers go in README.md.
 */
public final class LoadCheck {

    static final String BREAD = "minecraft:bread";
    static final int SIEGES = 10;
    static final int PLAYERS = 40;
    static final int TICKS = 6_000;
    static final double BUDGET_MILLIS = 10;

    private LoadCheck() {
    }

    static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    public static void main(String[] args) throws IOException {
        Fixture f = new Fixture();
        f.knobs.timeScale = 24;
        FoodTable food = new FoodTable().put(BREAD, 5, ShelfClass.PRESERVED);
        Map<CoreId, Integer> inCamp = new HashMap<>();
        SiegeInputs inputs = new SiegeInputs() {
            public int attackersInCamp(CoreId camp) { return inCamp.getOrDefault(camp, 0); }
            public int defendersInKeep(CoreId keep) { return 1; }
            public int attackersInsideKeep(CoreId keep) { return 0; }
            public int defendersBesideCamp(CoreId camp) { return 0; }
            public int prisoners(CoreId keep) { return 0; }
        };
        SiegeSim sim = new SiegeSim(f.engine, food).inputs(inputs);

        List<PlayerId> players = new ArrayList<>();
        List<Core> keeps = new ArrayList<>();
        for (int i = 0; i < SIEGES; i++) {
            PlayerId[] defenders = {Fixture.player("Def" + i + "a"), Fixture.player("Def" + i + "b")};
            PlayerId[] attackers = {Fixture.player("Att" + i + "a"), Fixture.player("Att" + i + "b")};
            players.addAll(Arrays.asList(defenders));
            players.addAll(Arrays.asList(attackers));
            OrgId keepOrg = f.found("Hold" + i, Preset.LORDSHIP, defenders[0], defenders[1]);
            OrgId campOrg = f.found("Raid" + i, Preset.LORDSHIP, attackers[0], attackers[1]);
            for (OrgId org : List.of(keepOrg, campOrg)) {
                f.admin(new Deposit(org, Currency.DIAMOND, 400));
                f.admin(new Deposit(org, Currency.COBBLESTONE, 5000));
            }
            int x = i * 20;
            check(f.as(defenders[0], new PlaceNexus(keepOrg, ch(x, 0))).done(), "keep " + i);
            // Nine claims for the keep, one farm beside it: ten claims per siege, a hundred in all with the camps.
            check(f.as(defenders[0], new ClaimLand(keepOrg, ch(x, -2))).done(), "farm " + i);
        }
        f.passDays(3.1, players.toArray(PlayerId[]::new));
        for (int i = 0; i < SIEGES; i++) {
            OrgId campOrg = f.state.orgNamed("Raid" + i).orElseThrow().id;
            PlayerId lord = players.get(i * 4 + 2);
            check(f.as(lord, new PlantCamp(campOrg, ch(i * 20 + 2, 0))).done(), "camp " + i);
        }
        for (Core core : f.state.cores()) {
            if (core.isKeep()) {
                keeps.add(core);
                sim.supply().deposit("setup", core.id, Map.of(BREAD, 400), new ArrayList<>());
            } else {
                sim.supply().deposit("setup", core.id, Map.of(BREAD, 2000), new ArrayList<>());
                inCamp.put(core.id, 2);
            }
        }
        sim.advance(Durations.hours(f.knobs.campRaisingHours));
        int besieged = 0;
        for (Core keep : keeps) if (f.state.siege.keep(keep.id).supply != KeepSupply.SUPPLIED) besieged++;
        check(besieged == SIEGES, "every keep is under siege, " + besieged + " are");
        System.out.println("load check: " + f.state.claims().size() + " claims, " + besieged + " sieges, " + players.size() + " players");

        // Each player walks a random path around its own siege; every tick checks every player's crossing.
        Random random = new Random(14);
        int[][] at = new int[players.size()][2];
        for (int p = 0; p < players.size(); p++) {
            at[p][0] = (p / 4) * 20 + random.nextInt(7) - 3;
            at[p][1] = random.nextInt(7) - 3;
        }
        Supply supply = sim.supply();
        Map<String, Integer> carried = Map.of(BREAD, 4);
        long[] nanos = new long[TICKS];
        long crossings = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            long start = System.nanoTime();
            for (int p = 0; p < players.size(); p++) {
                if (random.nextInt(20) != 0) continue; // about one chunk step a second each
                ChunkKey from = ch(at[p][0], at[p][1]);
                int axis = random.nextInt(2);
                int step = random.nextBoolean() ? 1 : -1;
                at[p][axis] += step;
                int home = (p / 4) * 20;
                at[p][0] = Math.max(home - 4, Math.min(home + 4, at[p][0]));
                at[p][1] = Math.max(-4, Math.min(4, at[p][1]));
                ChunkKey to = ch(at[p][0], at[p][1]);
                if (!from.equals(to)) {
                    supply.enter("player:" + players.get(p).value(), from, to, carried);
                    crossings++;
                }
            }
            f.clock.advance(50);
            sim.step(50);
            nanos[tick] = System.nanoTime() - start;
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double median = sorted[TICKS / 2] / 1e6;
        double p99 = sorted[(int) (TICKS * 0.99)] / 1e6;
        double worst = sorted[TICKS - 1] / 1e6;
        double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
        String result = String.format(java.util.Locale.ROOT,
                "load check: %d ticks (%.1f siege hours), %d crossings checked; per tick mean %.3f ms, median %.3f ms, p99 %.3f ms, worst %.3f ms",
                TICKS, f.state.siegeClock.now() / (double) Durations.HOUR, crossings, mean, median, p99, worst);
        System.out.println(result);
        Path out = Path.of("build", "load-check.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, result + System.lineSeparator());
        if (p99 > BUDGET_MILLIS) {
            System.out.println("load check: FAIL, p99 above " + BUDGET_MILLIS + " ms");
            System.exit(1);
        }
        System.out.println("load check: ok, p99 within " + BUDGET_MILLIS + " ms of a 50 ms tick");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("load check setup failed: " + what);
    }
}
