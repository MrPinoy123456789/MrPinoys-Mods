package chartercurrency.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The testing requirements from all three specs, as far as they can be checked
 * without Minecraft running -- which is nearly all of them, because the inventory
 * rules are arithmetic in {@link Wallet} and the purchase transaction takes delivery
 * as a callback.
 *
 * <p>A plain {@code main()} rather than JUnit: no test framework on the classpath, no
 * network needed to resolve one, runs anywhere a JDK exists.
 *
 * <pre>
 *   javac -d /tmp/out $(find core/src -name '*.java')
 *   java -cp /tmp/out chartercurrency.core.EconomyTest
 * </pre>
 */
public final class EconomyTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID JAMIE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID TAYLOR = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

    /**
     * The fork's generic two-currency tests keep running against an ordinary payable,
     * withdrawable second currency (GEM), because Valor is deliberately not payable.
     * Coin is the real coin; the shipped registry (coin + valor) is tested separately.
     */
    private static final Currency COIN = CurrencyRegistry.COIN;
    private static final Currency VALOR = CurrencyRegistry.VALOR;
    private static final Currency GEM = Currency.of("gem", "Gem", "gem", "gems",
            "minecraft:diamond", "gems", "dia");
    private static final CurrencyRegistry REGISTRY = testRegistry();

    private static CurrencyRegistry testRegistry() {
        CurrencyRegistry r = new CurrencyRegistry();
        r.register(COIN);
        r.register(GEM);
        return r;
    }

    /** Names used for the leaderboard tie-break. */
    private static final Map<UUID, String> NAMES = new HashMap<>();
    static {
        NAMES.put(ALEX, "Alex");
        NAMES.put(STEVE, "Steve");
        NAMES.put(JAMIE, "Jamie");
        NAMES.put(TAYLOR, "Taylor");
    }

    public static void main(String[] args) {
        currencyRegistry();
        depositing();
        withdrawal();
        payments();
        multiCurrencyIndependence();
        compoundPrices();
        adminOps();
        persistence();
        security();
        denominations();
        kingdomSource();
        mintPlanner();
        mintTax();
        treasury();
        earnRules();
        renownRules();
        renownCatalogue();
        earnConcurrency();
        valor();
        inventoryMath();
        formatting();
        shopCatalog();
        purchasing();
        leaderboards();
        leaderboardMultiCurrency();

        System.out.println();
        for (String f : failures) System.out.println("FAIL  " + f);
        System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
        if (!failures.isEmpty()) System.exit(1);
    }

    // ---- currency registry --------------------------------------------------

    private static void currencyRegistry() {
        section("Currency registry");

        check("resolves canonical id", REGISTRY.resolve("coin").orElseThrow().equals(COIN));
        check("resolves alias", REGISTRY.resolve("coins").orElseThrow().equals(COIN));
        check("resolves case-insensitively", REGISTRY.resolve("COIN").orElseThrow().equals(COIN));
        check("resolves plural alias", REGISTRY.resolve("gems").orElseThrow().equals(GEM));
        check("unknown currency is empty", REGISTRY.resolve("emeralds").isEmpty());
        check("blank is empty", REGISTRY.resolve("  ").isEmpty());
        check("null is empty", REGISTRY.resolve(null).isEmpty());
        check("both currencies registered", REGISTRY.all().size() == 2);
        check("coin is listed first", REGISTRY.all().iterator().next().equals(COIN));
        check("a plain currency maps to its item", GEM.itemId().equals("minecraft:diamond"));
        check("coin has no single item, it has denominations",
                COIN.itemId() == null && COIN.denominations().size() == 3);

        check("singular wording", GEM.describe(1).equals("1 gem"));
        check("plural wording", GEM.describe(37).equals("37 gems"));
        check("plural wording groups digits", GEM.describe(12481).equals("12,481 gems"));

        // An alias claimed by two currencies would make /pay ambiguous, so it is fatal.
        CurrencyRegistry clashing = new CurrencyRegistry();
        clashing.register(COIN);
        boolean rejected = false;
        try {
            clashing.register(Currency.of("granite", "Granite", "granite", "granite",
                    "minecraft:granite", "coin"));
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("clashing alias is rejected at registration", rejected);

        // Denomination words are aliases too: a currency may not steal "crowns".
        boolean denomRejected = false;
        try {
            clashing.register(Currency.of("crownland", "Crownland", "crown", "crowns",
                    "minecraft:granite", "crowns"));
        } catch (IllegalArgumentException e) {
            denomRejected = true;
        }
        check("clashing denomination alias is rejected at registration", denomRejected);
    }

    // ---- depositing ---------------------------------------------------------

    private static void depositing() {
        section("Depositing");
        EconomyService e = fresh();

        check("deposit exact amount", e.deposit(ALEX, COIN, 500).ok());
        check("balance reflects deposit", e.getBalance(ALEX, COIN) == 500);

        e.deposit(ALEX, COIN, 1_842);
        check("deposits accumulate", e.getBalance(ALEX, COIN) == 2_342);

        check("deposit zero rejected",
                e.deposit(ALEX, COIN, 0).status() == TxStatus.INVALID_AMOUNT);
        check("deposit negative rejected",
                e.deposit(ALEX, COIN, -500).status() == TxStatus.INVALID_AMOUNT);
        check("rejected deposits do not move the balance", e.getBalance(ALEX, COIN) == 2_342);
        check("null currency rejected",
                e.deposit(ALEX, null, 100).status() == TxStatus.UNKNOWN_CURRENCY);

        EconomyService brim = fresh();
        brim.set(ALEX, COIN, Long.MAX_VALUE - 10);
        check("overflow rejected rather than wrapping negative",
                brim.deposit(ALEX, COIN, 100).status() == TxStatus.OVERFLOW);
        check("rejected overflow leaves the balance intact",
                brim.getBalance(ALEX, COIN) == Long.MAX_VALUE - 10);
        check("a deposit that exactly fills the range still works",
                brim.deposit(ALEX, COIN, 10).ok()
                        && brim.getBalance(ALEX, COIN) == Long.MAX_VALUE);
    }

    // ---- withdrawal ---------------------------------------------------------

    private static void withdrawal() {
        section("Withdrawal");
        EconomyService e = fresh();
        e.deposit(ALEX, COIN, 1_000);

        check("withdraw part of balance", e.withdraw(ALEX, COIN, 400).ok());
        check("balance reduced", e.getBalance(ALEX, COIN) == 600);
        check("withdraw exact balance", e.withdraw(ALEX, COIN, 600).ok());
        check("balance now zero", e.getBalance(ALEX, COIN) == 0);

        check("withdraw more than balance rejected",
                e.withdraw(ALEX, COIN, 1).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("withdraw zero rejected",
                e.withdraw(ALEX, COIN, 0).status() == TxStatus.INVALID_AMOUNT);
        check("withdraw negative rejected",
                e.withdraw(ALEX, COIN, -50).status() == TxStatus.INVALID_AMOUNT);

        e.deposit(STEVE, COIN, 100);
        e.withdraw(STEVE, COIN, 500);
        check("failed withdrawal leaves balance untouched", e.getBalance(STEVE, COIN) == 100);
        check("failed withdrawal reports current balance",
                e.withdraw(STEVE, COIN, 500).actorBalance() == 100);
    }

    // ---- payments -----------------------------------------------------------

    private static void payments() {
        section("Payments");
        EconomyService e = fresh();
        e.deposit(ALEX, COIN, 5_000);
        e.deposit(STEVE, COIN, 2_250);

        TxResult r = e.transfer(ALEX, STEVE, COIN, 500);
        check("successful payment", r.ok());
        check("sender debited", e.getBalance(ALEX, COIN) == 4_500);
        check("recipient credited", e.getBalance(STEVE, COIN) == 2_750);
        check("result carries sender balance", r.actorBalance() == 4_500);
        check("result carries recipient balance", r.targetBalance() == 2_750);
        check("result carries the currency", r.currency().equals(COIN));

        check("insufficient funds rejected",
                e.transfer(ALEX, STEVE, COIN, 999_999).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("failed payment moves nothing",
                e.getBalance(ALEX, COIN) == 4_500 && e.getBalance(STEVE, COIN) == 2_750);

        check("self payment rejected",
                e.transfer(ALEX, ALEX, COIN, 100).status() == TxStatus.SELF_TRANSFER);
        check("self payment does not double money", e.getBalance(ALEX, COIN) == 4_500);

        check("zero payment rejected",
                e.transfer(ALEX, STEVE, COIN, 0).status() == TxStatus.INVALID_AMOUNT);
        check("negative payment rejected",
                e.transfer(ALEX, STEVE, COIN, -100).status() == TxStatus.INVALID_AMOUNT);
        check("negative payment cannot drain the recipient",
                e.getBalance(STEVE, COIN) == 2_750);

        // Offline recipients are not special: an account is a UUID and some numbers.
        check("payment to an account never seen before",
                e.transfer(ALEX, JAMIE, COIN, 250).ok());
        check("previously unknown recipient credited", e.getBalance(JAMIE, COIN) == 250);
        check("null recipient rejected",
                e.transfer(ALEX, null, COIN, 100).status() == TxStatus.INVALID_AMOUNT);

        long before = e.totalSupply(COIN);
        e.transfer(ALEX, STEVE, COIN, 1_000);
        check("transfer conserves total supply", e.totalSupply(COIN) == before);
    }

    // ---- multi-currency independence ---------------------------------------

    private static void multiCurrencyIndependence() {
        section("Multi-currency independence");
        EconomyService e = fresh();
        e.set(ALEX, COIN, 12_480);
        e.set(ALEX, GEM, 37);

        check("both balances held at once",
                e.getBalance(ALEX, COIN) == 12_480 && e.getBalance(ALEX, GEM) == 37);

        e.deposit(ALEX, COIN, 1_000);
        check("depositing cobblestone leaves diamonds alone", e.getBalance(ALEX, GEM) == 37);
        e.deposit(ALEX, GEM, 3);
        check("depositing diamonds leaves cobblestone alone",
                e.getBalance(ALEX, COIN) == 13_480);

        e.withdraw(ALEX, COIN, 480);
        check("withdrawing cobblestone leaves diamonds alone", e.getBalance(ALEX, GEM) == 40);
        e.withdraw(ALEX, GEM, 10);
        check("withdrawing diamonds leaves cobblestone alone",
                e.getBalance(ALEX, COIN) == 13_000);

        e.set(STEVE, COIN, 100);
        e.set(STEVE, GEM, 5);
        e.transfer(ALEX, STEVE, COIN, 500);
        check("paying cobblestone leaves the sender's diamonds alone",
                e.getBalance(ALEX, GEM) == 30);
        check("paying cobblestone leaves the recipient's diamonds alone",
                e.getBalance(STEVE, GEM) == 5);

        e.transfer(ALEX, STEVE, GEM, 2);
        check("paying diamonds leaves the sender's cobblestone alone",
                e.getBalance(ALEX, COIN) == 12_500);
        check("paying diamonds leaves the recipient's cobblestone alone",
                e.getBalance(STEVE, COIN) == 600);
        check("diamond payment landed", e.getBalance(STEVE, GEM) == 7);

        // Running out of one currency must not be answerable with the other.
        EconomyService poor = fresh();
        poor.set(ALEX, COIN, 1_000_000);
        check("a cobblestone fortune does not buy a diamond",
                poor.withdraw(ALEX, GEM, 1).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("the failed diamond withdrawal did not spend cobblestone",
                poor.getBalance(ALEX, COIN) == 1_000_000);

        check("getBalances reports every currency", fresh() != null
                && e.getBalances(ALEX).size() == 2);
        check("emptying one currency leaves the account", drainedAccountKeepsOther());
    }

    private static boolean drainedAccountKeepsOther() {
        EconomyService e = fresh();
        e.set(ALEX, COIN, 50);
        e.set(ALEX, GEM, 5);
        e.withdraw(ALEX, COIN, 50);
        return e.getBalance(ALEX, COIN) == 0
                && e.getBalance(ALEX, GEM) == 5
                && e.getBalances(ALEX).size() == 1;
    }

    // ---- compound prices ----------------------------------------------------

    private static void compoundPrices() {
        section("Compound prices");
        EconomyService e = fresh();
        e.set(ALEX, COIN, 1_000);
        e.set(ALEX, GEM, 3);

        Map<Currency, Long> price = new LinkedHashMap<>();
        price.put(COIN, 500L);
        price.put(GEM, 1L);

        check("compound charge succeeds when both are affordable", e.charge(ALEX, price).ok());
        check("cobblestone debited", e.getBalance(ALEX, COIN) == 500);
        check("diamond debited", e.getBalance(ALEX, GEM) == 2);

        // The case the whole map-shaped price exists for: affordable in one currency,
        // not the other. Neither may be taken.
        Map<Currency, Long> steep = new LinkedHashMap<>();
        steep.put(COIN, 100L);
        steep.put(GEM, 99L);
        TxResult failed = e.charge(ALEX, steep);
        check("compound charge fails if any line is unaffordable",
                failed.status() == TxStatus.INSUFFICIENT_FUNDS);
        check("failure names the currency that was short", failed.currency().equals(GEM));
        check("affordable line was NOT debited on failure", e.getBalance(ALEX, COIN) == 500);
        check("unaffordable line was not debited", e.getBalance(ALEX, GEM) == 2);

        e.refund(ALEX, price);
        check("refund restores cobblestone", e.getBalance(ALEX, COIN) == 1_000);
        check("refund restores diamonds", e.getBalance(ALEX, GEM) == 3);

        check("empty price rejected",
                e.charge(ALEX, Map.of()).status() == TxStatus.INVALID_AMOUNT);
        check("non-positive price line rejected",
                e.charge(ALEX, Map.of(COIN, 0L)).status() == TxStatus.INVALID_AMOUNT);
    }

    // ---- admin operations ---------------------------------------------------

    private static void adminOps() {
        section("Admin operations");
        EconomyService e = fresh();

        check("set from nothing", e.set(ALEX, COIN, 84_231).ok());
        check("set applied", e.getBalance(ALEX, COIN) == 84_231);
        check("set overwrites rather than adds",
                e.set(ALEX, COIN, 100).ok() && e.getBalance(ALEX, COIN) == 100);
        check("set to zero allowed",
                e.set(ALEX, COIN, 0).ok() && e.getBalance(ALEX, COIN) == 0);
        check("set negative rejected",
                e.set(ALEX, COIN, -1).status() == TxStatus.INVALID_AMOUNT);

        check("admin add is a deposit", e.deposit(ALEX, GEM, 50).ok());
        check("admin remove is a withdrawal", e.withdraw(ALEX, GEM, 50).ok());
        check("admin remove past zero rejected",
                e.withdraw(ALEX, GEM, 1).status() == TxStatus.INSUFFICIENT_FUNDS);

        e.set(STEVE, COIN, 500);
        e.set(STEVE, GEM, 5);
        e.set(STEVE, GEM, 0);
        check("admin clearing one currency spares the other",
                e.getBalance(STEVE, COIN) == 500 && e.getBalance(STEVE, GEM) == 0);
    }

    // ---- persistence --------------------------------------------------------

    private static void persistence() {
        section("Persistence");
        RecordingStore store = new RecordingStore();
        EconomyService e = new CharterCurrency(store);

        e.deposit(ALEX, COIN, 12_481);
        e.deposit(ALEX, GEM, 37);
        e.deposit(STEVE, COIN, 932);

        check("both currencies reach the store",
                store.saved.get(ALEX).get("coin") == 12_481L
                        && store.saved.get(ALEX).get("gem") == 37L);
        check("multiple players persisted independently",
                store.saved.get(STEVE).get("coin") == 932L);

        // The restart: a brand new service reading what the old one wrote.
        EconomyService restarted = new CharterCurrency(store.asSourceOfTruth());
        check("cobblestone survives restart", restarted.getBalance(ALEX, COIN) == 12_481);
        check("diamonds survive restart", restarted.getBalance(ALEX, GEM) == 37);
        check("all accounts survive restart", restarted.balances(COIN).size() == 2);
        check("a player with only one currency reloads correctly",
                restarted.getBalance(STEVE, GEM) == 0);

        EconomyService big = fresh();
        big.set(ALEX, COIN, 9_000_000_000L);
        check("balances beyond int range are held exactly",
                big.getBalance(ALEX, COIN) == 9_000_000_000L);
        big.deposit(ALEX, COIN, 1L);
        check("large balances still arithmetic correctly",
                big.getBalance(ALEX, COIN) == 9_000_000_001L);

        EconomyService zeroed = fresh();
        zeroed.deposit(ALEX, COIN, 10);
        zeroed.withdraw(ALEX, COIN, 10);
        check("emptied accounts are not stored as rows of nothing",
                zeroed.balances(COIN).isEmpty());
        check("emptied account still reads as zero", zeroed.getBalance(ALEX, COIN) == 0);
    }

    // ---- security -----------------------------------------------------------

    private static void security() {
        section("Security");
        EconomyService e = fresh();
        e.set(ALEX, COIN, 1_000);

        // Command spam: 2,000 withdrawals of 1 against a balance of 1,000. Exactly
        // 1,000 must succeed -- one extra is duplicated currency.
        int ok = 0;
        for (int i = 0; i < 2_000; i++) if (e.withdraw(ALEX, COIN, 1).ok()) ok++;
        check("repeated commands cannot withdraw more than exists", ok == 1_000);
        check("spammed account lands exactly at zero", e.getBalance(ALEX, COIN) == 0);

        check("concurrent transfers neither duplicate nor destroy", raceTransfers());
        check("concurrent withdrawals never overdraw", raceWithdrawals());
        check("concurrent purchases cannot be double-spent", racePurchases());
    }

    /** 8 threads move money in a ring. Total supply must be unchanged at the end. */
    private static boolean raceTransfers() {
        EconomyService e = fresh();
        UUID[] players = new UUID[8];
        for (int i = 0; i < players.length; i++) {
            players[i] = UUID.randomUUID();
            e.set(players[i], COIN, 10_000);
        }
        long before = e.totalSupply(COIN);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            final int from = t;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 2_000; i++) {
                    e.transfer(players[from], players[(from + 1) % players.length], COIN, 3);
                }
            });
        }
        start.countDown();
        shutdown(pool);
        return e.totalSupply(COIN) == before;
    }

    /** 8 threads race to drain one account. The sum withdrawn must equal the balance. */
    private static boolean raceWithdrawals() {
        EconomyService e = fresh();
        e.set(ALEX, COIN, 5_000);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<AtomicLong> tallies = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            AtomicLong tally = new AtomicLong();
            tallies.add(tally);
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 5_000; i++) {
                    if (e.withdraw(ALEX, COIN, 1).ok()) tally.incrementAndGet();
                }
            });
        }
        start.countDown();
        shutdown(pool);

        long withdrawn = tallies.stream().mapToLong(AtomicLong::get).sum();
        return withdrawn == 5_000 && e.getBalance(ALEX, COIN) == 0;
    }

    /**
     * The dupe a shop invites: several threads buying at once with only enough money
     * for a fixed number of purchases. Deliveries must exactly match successful charges.
     */
    private static boolean racePurchases() {
        EconomyService e = fresh();
        e.set(ALEX, COIN, 6_400);
        ShopEntry ice = ShopEntry.of("ice", "minecraft:ice", 64, COIN, 640, "Building");

        AtomicLong delivered = new AtomicLong();
        AtomicLong succeeded = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 200; i++) {
                    Shop.PurchaseResult result = Shop.buy(e, ALEX, ice, Long.MAX_VALUE,
                            (item, qty) -> {
                                delivered.incrementAndGet();
                                return true;
                            });
                    if (result.ok()) succeeded.incrementAndGet();
                }
            });
        }
        start.countDown();
        shutdown(pool);

        return succeeded.get() == 10
                && delivered.get() == 10
                && e.getBalance(ALEX, COIN) == 0;
    }

    // ---- denominations ------------------------------------------------------

    private static void denominations() {
        section("Denominations");
        int[] faces = Wallet.DEFAULT_FACE_VALUES;

        check("shipped face values are 1, 8, 64",
                java.util.Arrays.equals(COIN.faceValues(), new int[] {1, 8, 64}));
        check("display spells out parts and the Marks total",
                COIN.describe(213).equals("3 Sovereigns, 2 Crowns, 5 Marks (213 Marks)"));
        check("zero parts are omitted", COIN.describe(16).equals("2 Crowns (16 Marks)"));
        check("a Marks-only amount is not repeated", COIN.describe(5).equals("5 Marks"));
        check("one Mark is singular", COIN.describe(1).equals("1 Mark"));
        check("zero is shown", COIN.describe(0).equals("0 Marks"));
        check("denomination words resolve",
                REGISTRY.resolveWord("Crowns").orElseThrow().denomination().id().equals("iron")
                        && REGISTRY.resolveWord("sov").orElseThrow().denomination().id().equals("gold")
                        && REGISTRY.resolveWord("MARK").orElseThrow().denomination().id().equals("copper"));
        check("the word coin names the currency, not a denomination",
                REGISTRY.resolveWord("coin").orElseThrow().denomination() == null);

        // deposit: mixed coins at face value
        int[] carried = {5, 3, 2};
        Wallet.DepositPlan all = Wallet.planDeposit(carried, 5 + 24 + 128);
        check("deposit of everything counts mixed coins at face value",
                all != null && all.credited() == 157 && all.change() == 0
                        && java.util.Arrays.equals(all.take(), carried));

        // deposit: exact
        Wallet.DepositPlan exact = Wallet.planDeposit(new int[] {10, 10, 10}, 75);
        check("exact deposit takes exact coins", exact != null && exact.taken() == 75
                && exact.change() == 0 && exact.credited() == 75);

        // deposit: overpay with change
        Wallet.DepositPlan over = Wallet.planDeposit(new int[] {0, 0, 2}, 100);
        check("overpay takes the smallest sufficient coins", over != null && over.taken() == 128
                && java.util.Arrays.equals(over.take(), new int[] {0, 0, 2}));
        check("overpay returns the difference as change",
                over != null && over.change() == 28
                        && java.util.Arrays.equals(over.changeCoins(), new int[] {4, 3, 0})
                        && over.credited() == 100);
        Wallet.DepositPlan prefer = Wallet.planDeposit(new int[] {0, 5, 1}, 60);
        check("smallest overpayment wins over fewer coins",
                prefer != null && prefer.taken() == 64);

        // deposit: not enough
        check("not enough in total returns null", Wallet.planDeposit(new int[] {3, 1, 0}, 12) == null);
        check("zero deposit returns null", Wallet.planDeposit(new int[] {3, 1, 0}, 0) == null);
        check("a plan never takes more than carried", exactNeverExceeds());

        // change
        check("makeChange gives the fewest coins: 200 is 3 Sovereigns and 1 Crown",
                java.util.Arrays.equals(Wallet.makeChange(200, faces), new int[] {0, 1, 3}));
        check("makeChange of 7 is seven Marks",
                java.util.Arrays.equals(Wallet.makeChange(7, faces), new int[] {7, 0, 0}));
        check("makeChange honours face values in any order",
                java.util.Arrays.equals(Wallet.makeChange(73, new int[] {64, 1, 8}), new int[] {1, 1, 1}));
        check("makeChange of zero is nothing",
                java.util.Arrays.equals(Wallet.makeChange(0, faces), new int[] {0, 0, 0}));

        // withdraw: joint fit, and rollback when there is no space
        long[] none = {0, 0, 0};
        check("withdrawal fits when empty slots cover every kind",
                Wallet.fitsAll(2, none, new long[] {0, 1, 3}, 64));
        check("withdrawal does not fit when kinds compete for one slot",
                !Wallet.fitsAll(1, none, new long[] {0, 1, 3}, 64));
        check("partial stacks absorb the need",
                Wallet.fitsAll(0, new long[] {10, 10, 10}, new long[] {5, 5, 5}, 64));
        check("a full inventory fits nothing",
                !Wallet.fitsAll(0, none, new long[] {1, 0, 0}, 64));

        EconomyService e = fresh();
        e.set(ALEX, COIN, 200);
        boolean fits = Wallet.fitsAll(1, none, new long[] {0, 1, 3}, 64);
        if (fits) e.withdraw(ALEX, COIN, 200);
        check("a withdrawal that does not fit leaves the balance untouched",
                !fits && e.getBalance(ALEX, COIN) == 200);
    }

    private static boolean exactNeverExceeds() {
        java.util.Random random = new java.util.Random(42);
        for (int trial = 0; trial < 400; trial++) {
            int[] c = {random.nextInt(40), random.nextInt(12), random.nextInt(5)};
            long total = c[0] + 8L * c[1] + 64L * c[2];
            if (total == 0) continue;
            long amount = 1 + (long) (random.nextDouble() * total);
            Wallet.DepositPlan p = Wallet.planDeposit(c, amount);
            if (p == null) return false;
            for (int i = 0; i < 3; i++) if (p.take()[i] > c[i] || p.take()[i] < 0) return false;
            if (p.credited() != amount || p.taken() < amount) return false;
        }
        return true;
    }

    // ---- valor --------------------------------------------------------------

    private static void valor() {
        section("Valor");
        EconomyService e = fresh();

        check("valor has no item and no coins", VALOR.itemId() == null && !VALOR.hasDenominations());
        check("valor is neither withdrawable nor payable",
                !VALOR.withdrawable() && !VALOR.payable());
        check("valor wording", VALOR.describe(24).equals("24 Valor"));
        e.set(ALEX, VALOR, 100);
        TxResult pay = e.transfer(ALEX, STEVE, VALOR, 10);
        check("paying valor is refused", pay.status() == TxStatus.NOT_PAYABLE);
        check("a refused payment moves nothing",
                e.getBalance(ALEX, VALOR) == 100 && e.getBalance(STEVE, VALOR) == 0);
        check("admin add still works", e.deposit(ALEX, VALOR, 5).ok()
                && e.getBalance(ALEX, VALOR) == 105);
        check("valor is charged by the shop path",
                e.charge(ALEX, Map.of(VALOR, 50L)).ok() && e.getBalance(ALEX, VALOR) == 55);

        // A compound price in coin and valor is both or neither.
        e.set(ALEX, COIN, 500);
        Map<Currency, Long> both = new LinkedHashMap<>();
        both.put(COIN, 100L);
        both.put(VALOR, 1_000L);
        check("coin plus valor price is refused when valor is short",
                e.charge(ALEX, both).status() == TxStatus.INSUFFICIENT_FUNDS
                        && e.getBalance(ALEX, COIN) == 500);

        CurrencyRegistry shipped = CurrencyRegistry.defaults();
        check("shipped registry is coin then valor",
                shipped.all().size() == 2 && shipped.all().iterator().next().equals(COIN));
        check("valor resolves with no denomination",
                shipped.resolveWord("valor").orElseThrow().denomination() == null);
    }

    // ---- kingdom source -----------------------------------------------------

    private static void kingdomSource() {
        section("Kingdom source (manual stand-in)");
        ManualKingdomSource k = new ManualKingdomSource();
        check("create works", k.create("northreach", "Northreach"));
        check("duplicate create refused", !k.create("NorthReach", "Again"));
        check("unknown player is in no kingdom", k.kingdomOf(ALEX).isEmpty());
        check("setMember unknown kingdom refused", !k.setMember(ALEX, "nowhere"));
        check("setMember works", k.setMember(ALEX, "northreach")
                && k.kingdomOf(ALEX).orElse("").equals("northreach"));
        check("no lord until named", k.lordOf("northreach").isEmpty());
        check("naming a lord moves them in",
                k.setLord("northreach", STEVE) && k.kingdomOf(STEVE).isPresent()
                        && k.lordOf("northreach").orElseThrow().equals(STEVE));
        k.create("southmark", "Southmark");
        k.setMember(STEVE, "southmark");
        check("a lord who moves away vacates the office", k.lordOf("northreach").isEmpty());
        check("a player is in one kingdom only", k.memberCount("northreach") == 1
                && k.memberCount("southmark") == 1);
        check("member can leave", k.setMember(ALEX, null) && k.kingdomOf(ALEX).isEmpty());
        check("display name", k.displayName("southmark").equals("Southmark")
                && k.displayName("ghost").equals("ghost"));
        check("ids listed", k.kingdomIds().size() == 2);
        check("remove releases members", k.remove("southmark") && k.kingdomOf(STEVE).isEmpty());
        ManualKingdomSource copy = new ManualKingdomSource(k.snapshot(), () -> { });
        check("state round trips through a snapshot", copy.kingdomIds().size() == 1);
    }

    // ---- mint planner -------------------------------------------------------

    private static final Mint.Rules RULES = Mint.Rules.defaults();

    private static Mint.State freshMint() {
        return Mint.State.fresh(100, RULES);
    }

    private static void mintPlanner() {
        section("Mint planner");

        check("the Mint strikes gold only, never the farmed copper or iron",
                Mint.strikes("gold") && !Mint.strikes("iron") && !Mint.strikes("copper"));

        Mint.Plan full = Mint.plan(freshMint(), "iron", 8, 10);
        check("full band first at one ingot per coin",
                full.coinsFull() == 10 && full.coinsHalf() == 0 && full.ingotsUsed() == 10);
        check("allowance falls by face value", full.after().fullRemaining == 192 - 80);

        Mint.State noFull = freshMint();
        noFull.fullRemaining = 0;
        Mint.Plan half = Mint.plan(noFull, "iron", 8, 10);
        check("half band costs two ingots per coin",
                half.coinsHalf() == 5 && half.ingotsUsed() == 10 && half.coinsFull() == 0);

        Mint.State partial = freshMint();
        partial.fullRemaining = 100;
        Mint.Plan split = Mint.plan(partial, "gold", 64, 5);
        check("a coin is never split across bands: 1 full, 2 half",
                split.coinsFull() == 1 && split.coinsHalf() == 2 && split.ingotsUsed() == 5);
        check("the unspent full band remainder stays",
                split.after().fullRemaining == 36 && split.after().halfRemaining == 192 - 128);

        Mint.State spent = freshMint();
        spent.fullRemaining = 0;
        spent.halfRemaining = 0;
        Mint.Plan none = Mint.plan(spent, "copper", 1, 50);
        check("nothing past the half band",
                !none.struck() && none.shortfall() == Mint.Shortfall.EXHAUSTED && none.ingotsUsed() == 0);

        Mint.State oddHalf = freshMint();
        oddHalf.fullRemaining = 0;
        Mint.Plan odd = Mint.plan(oddHalf, "copper", 1, 5);
        check("an odd ingot in the half band is not consumed",
                odd.coinsStruck() == 2 && odd.ingotsUsed() == 4);
        Mint.Plan one = Mint.plan(oddHalf, "copper", 1, 1);
        check("one ingot cannot buy a half band coin",
                !one.struck() && one.shortfall() == Mint.Shortfall.NEED_MORE_INGOTS);
        check("nothing offered", Mint.plan(freshMint(), "copper", 1, 0).shortfall()
                == Mint.Shortfall.NOTHING_OFFERED);

        // carry-over
        Mint.State day0 = Mint.State.fresh(0, RULES);
        check("a 1 day gap adds one day", Mint.rollTo(day0, 1, RULES).fullRemaining == 384);
        check("2 days later it is 576", Mint.rollTo(day0, 2, RULES).fullRemaining == 576);
        check("a 5 day gap caps at 576", Mint.rollTo(day0, 5, RULES).fullRemaining == 576);
        Mint.State used = Mint.State.fresh(0, RULES);
        used.fullRemaining = 10;
        used.halfRemaining = 3;
        Mint.State next = Mint.rollTo(used, 1, RULES);
        check("unused full band carries over", next.fullRemaining == 202);
        check("the half band does not carry over", next.halfRemaining == 192);
        check("the same day changes nothing", Mint.rollTo(day0, 0, RULES).fullRemaining == 192);
        check("a past day changes nothing", Mint.rollTo(day0, -3, RULES).day == 0);
    }

    private static void mintTax() {
        section("Mint tax");

        Mint.State s = freshMint();
        Mint.Plan first = Mint.plan(s, "iron", 8, 3);
        check("3 coins: none taxed yet", first.coinsToKingdom() == 0 && first.coinsToPlayer() == 3);
        check("counter holds 3", first.after().taxCounters.get("iron") == 3);
        Mint.Plan second = Mint.plan(first.after(), "iron", 8, 2);
        check("strikes of 3 then 2 tax the fifth",
                second.coinsToKingdom() == 1 && second.coinsToPlayer() == 1
                        && second.after().taxCounters.get("iron") == 0);

        Mint.Plan ten = Mint.plan(freshMint(), "iron", 8, 10);
        check("10 coins: 8 to the player, 2 to the kingdom",
                ten.coinsToPlayer() == 8 && ten.coinsToKingdom() == 2 && ten.marksToKingdom() == 16);
        check("the allowance counts taxed coins", ten.after().fullRemaining == 192 - 80
                && ten.marksStruck() == 80);
        Mint.Plan single = Mint.plan(freshMint(), "gold", 64, 1);
        check("a single Sovereign works with the counter", single.coinsToKingdom() == 0
                && single.after().taxCounters.get("gold") == 1);
        Mint.State four = freshMint();
        four.taxCounters.put("gold", 4);
        check("the fifth Sovereign is the kingdom's",
                Mint.plan(four, "gold", 64, 1).marksToKingdom() == 64);
        Mint.State mixed = freshMint();
        mixed.taxCounters.put("iron", 4);
        check("counters are per denomination",
                Mint.plan(mixed, "copper", 1, 1).coinsToKingdom() == 0);

        // exactly 20 percent over 1,000 random strikes
        java.util.Random random = new java.util.Random(7);
        Mint.State big = freshMint();
        big.fullRemaining = Long.MAX_VALUE / 4;
        long struck = 0;
        long taxed = 0;
        for (int i = 0; i < 1000; i++) {
            Mint.Plan p = Mint.plan(big, "copper", 1, 1 + random.nextInt(20));
            struck += p.coinsStruck();
            taxed += p.coinsToKingdom();
            big = p.after();
        }
        check("exactly 20 percent over 1,000 random strikes",
                taxed == struck / 5 && big.taxCounters.get("copper") == struck % 5);

        // service: kingdom, no kingdom, and a change of kingdom
        Treasury treasury = new Treasury();
        PlayerBook book = new PlayerBook();
        MintService service = new MintService(book, treasury, RULES);
        long today = 100;
        Mint.Plan a = service.preview(ALEX, "iron", 8, 3, today);
        service.commit(ALEX, a, "northreach", "Alex", today);
        Mint.Plan b = service.preview(ALEX, "iron", 8, 2, today);
        long paid = service.commit(ALEX, b, "southmark", "Alex", today);
        check("the counter survives a change of kingdom: the new kingdom gets the fifth coin",
                paid == 8 && treasury.balance("southmark") == 8 && treasury.balance("northreach") == 0);
        check("the tax lands in the treasury and its lifetime total",
                treasury.taxCollected("southmark") == 8);

        Mint.Plan c = service.preview(STEVE, "iron", 8, 10, today);
        long destroyedPaid = service.commit(STEVE, c, null, "Steve", today);
        check("no kingdom: the fifth coin is destroyed, nothing is credited",
                destroyedPaid == 0 && treasury.totalSupply() == 8);
        check("stats record minted and destroyed",
                book.stat(MintService.STAT_MINTED, today, 1) == 120);
        check("destroyed tax is tracked", book.stat(MintService.STAT_TAX_DESTROYED, today, 1) == 16);
        check("allowance persists per player",
                service.state(ALEX, today).fullRemaining == 192 - 40
                        && service.state(STEVE, today).fullRemaining == 192 - 80);
        check("a new day carries the unused band over",
                service.state(ALEX, today + 1).fullRemaining == 192 - 40 + 192);
    }

    // ---- treasury -----------------------------------------------------------

    private static void treasury() {
        section("Treasury");
        Treasury t = new Treasury();
        check("deposit credits", t.credit("northreach", 500, "deposit", "Alex", "") && t.balance("northreach") == 500);
        check("zero deposit refused", !t.credit("northreach", 0, "deposit", "Alex", ""));
        check("withdraw takes exactly", t.charge("northreach", 200, "withdraw", "Steve", "") && t.balance("northreach") == 300);
        check("a short withdrawal changes nothing",
                !t.charge("northreach", 301, "withdraw", "Steve", "") && t.balance("northreach") == 300);
        check("an unknown treasury has nothing", !t.charge("ghost", 1, "withdraw", "x", "") && t.balance("ghost") == 0);
        check("ledger newest first", t.ledger("northreach", null).get(0).kind.equals("withdraw")
                && t.ledger("northreach", null).size() == 2);
        t.record("northreach", "renown", "earn", 24, 24, "Alex", "dungeon");
        check("ledger filters by currency", t.ledger("northreach", "renown").size() == 1
                && t.ledger("northreach", "coin").size() == 2);
        for (int i = 0; i < 600; i++) t.credit("northreach", 1, "deposit", "Alex", "");
        check("ledger keeps the last 500", t.ledger("northreach", null).size() == Treasury.LEDGER_LIMIT);

        ManualKingdomSource k = new ManualKingdomSource();
        k.create("northreach", "Northreach");
        k.setLord("northreach", STEVE);
        k.setMember(ALEX, "northreach");
        check("the Lord may withdraw", Treasury.mayWithdraw(k, "northreach", STEVE, false));
        check("an ordinary member may not", !Treasury.mayWithdraw(k, "northreach", ALEX, false));
        check("an op may", Treasury.mayWithdraw(k, "northreach", JAMIE, true));
        check("a stranger may not", !Treasury.mayWithdraw(k, "northreach", JAMIE, false));
        check("a vacant lordship allows only ops", !Treasury.mayWithdraw(k, "ghost", STEVE, false));
        // A kingdom with two Lords: either may take coin out, and a member still may not.
        KingdomSource twoLords = new KingdomSource() {
            public java.util.Optional<String> kingdomOf(UUID player) { return java.util.Optional.of("pair"); }
            public java.util.Optional<UUID> lordOf(String id) { return java.util.Optional.of(STEVE); }
            public java.util.List<UUID> lordsOf(String id) { return java.util.List.of(STEVE, JAMIE); }
            public String displayName(String id) { return id; }
            public java.util.Collection<String> kingdomIds() { return java.util.List.of("pair"); }
        };
        check("the first of two Lords may withdraw", Treasury.mayWithdraw(twoLords, "pair", STEVE, false));
        check("the second of two Lords may withdraw", Treasury.mayWithdraw(twoLords, "pair", JAMIE, false));
        check("a member of a two Lord kingdom may not", !Treasury.mayWithdraw(twoLords, "pair", ALEX, false));

        // never negative under concurrent withdrawals
        Treasury race = new Treasury();
        race.credit("a", 1_000, "deposit", "x", "");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicLong ok = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                await(start);
                if (race.charge("a", 100, "withdraw", "t", "")) ok.incrementAndGet();
            });
        }
        start.countDown();
        shutdown(pool);
        check("40 racing withdrawals of 100 from 1,000: exactly 10 succeed, never negative",
                ok.get() == 10 && race.balance("a") == 0);

        Treasury taxes = new Treasury();
        ExecutorService pool2 = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 800; i++) {
            pool2.submit(() -> {
                await(go);
                taxes.collectTax("a", 8, "t");
            });
        }
        go.countDown();
        shutdown(pool2);
        check("800 racing tax credits are never lost", taxes.balance("a") == 6_400
                && taxes.taxCollected("a") == 6_400);
    }

    // ---- earn and renown ----------------------------------------------------

    private static final long DAY_MS = 86_400_000L;

    private static EarnService.Config earnConfig(int probationDays, long playerCap) {
        Map<String, EarnService.Source> sources = new LinkedHashMap<>();
        sources.put("dungeon", new EarnService.Source("dungeon", 10, 2, 150, 0, 1.0));
        sources.put("event", new EarnService.Source("event", 20, 0, 0, 0, 1.0));
        sources.put("soft", new EarnService.Source("soft", 100, 0, 150, 0, 1.0));
        sources.put("tiny", new EarnService.Source("tiny", 1, 0, 1, 0, 1.0));
        sources.put("hard", new EarnService.Source("hard", 60, 0, 0, 100, 1.0));
        sources.put("half", new EarnService.Source("half", 10, 0, 0, 0, 0.5));
        return new EarnService.Config(sources, playerCap, 30, probationDays);
    }

    private static final class EarnRig {
        final EconomyService economy = new CharterCurrency(AccountStore.inMemory());
        final PlayerBook book = new PlayerBook();
        final Treasury treasury = new Treasury();
        final AtomicLong now = new AtomicLong(1_000_000L * DAY_MS / 1_000_000L);
        final EarnService earn;

        EarnRig(int probationDays, long playerCap) {
            earn = new EarnService(book, economy, treasury, VALOR, earnConfig(probationDays, playerCap),
                    now::get);
        }

        long day() {
            return now.get() / DAY_MS;
        }

        EarnService.Result award(UUID who, String kingdom, String source, int level, String run) {
            return earn.award(who, "p", kingdom, source, level, run, day());
        }
    }

    private static void earnRules() {
        section("Earn rules");

        EarnRig r = new EarnRig(0, 0);
        check("formula without a level", r.award(ALEX, "k", "event", 0, null).valor() == 20);
        check("formula with a level", r.award(STEVE, "k", "dungeon", 7, null).valor() == 24);
        check("formula treats a missing level as 0", r.award(JAMIE, "k", "dungeon", 0, null).valor() == 10);
        check("valor reaches the balance", r.economy.getBalance(STEVE, VALOR) == 24);
        check("an unknown source is refused",
                r.award(ALEX, "k", "nope", 0, null).status() == EarnService.Status.UNKNOWN_SOURCE
                        && r.economy.getBalance(ALEX, VALOR) == 20);

        // soft cap: 150 pool, awards of 100
        EarnRig soft = new EarnRig(0, 0);
        long a1 = soft.award(ALEX, null, "soft", 0, null).valor();
        long a2 = soft.award(ALEX, null, "soft", 0, null).valor();
        EarnService.Result a3 = soft.award(ALEX, null, "soft", 0, null);
        check("awards inside the soft pool pay in full (the crossing award too)", a1 == 100 && a2 == 100);
        check("once the pool is spent awards are halved", a3.valor() == 50 && a3.halved());
        EarnRig tiny = new EarnRig(0, 0);
        tiny.award(ALEX, null, "tiny", 0, null);
        check("halving never goes below 1", tiny.award(ALEX, null, "tiny", 0, null).valor() == 1);

        // carry-over: skipped days refill, to at most 3x
        EarnRig carry = new EarnRig(0, 0);
        carry.award(ALEX, null, "soft", 0, null);
        carry.award(ALEX, null, "soft", 0, null);
        check("pool empty after two awards", carry.award(ALEX, null, "soft", 0, null).halved());
        carry.now.addAndGet(5 * DAY_MS);
        EarnService.Result back = carry.award(ALEX, null, "soft", 0, null);
        EarnService.Result back2 = carry.award(ALEX, null, "soft", 0, null);
        EarnService.Result back3 = carry.award(ALEX, null, "soft", 0, null);
        EarnService.Result back4 = carry.award(ALEX, null, "soft", 0, null);
        EarnService.Result back5 = carry.award(ALEX, null, "soft", 0, null);
        check("a skipped stretch refills the pool up to three days' worth (450, capped)",
                !back.halved() && !back2.halved() && !back3.halved() && !back4.halved()
                        && !back5.halved() && carry.award(ALEX, null, "soft", 0, null).halved());

        // hard cap: 100 a day, awards of 60
        EarnRig hard = new EarnRig(0, 0);
        long h1 = hard.award(ALEX, "k", "hard", 0, null).valor();
        EarnService.Result h2 = hard.award(ALEX, "k", "hard", 1, null);
        EarnService.Result h3 = hard.award(ALEX, "k", "hard", 2, null);
        check("an award that crosses the hard cap is clipped to the room", h1 == 60 && h2.valor() == 40
                && h2.hardCapped());
        check("past the hard cap an award pays 0 Valor and 0 Renown",
                h3.valor() == 0 && h3.renown() == 0 && h3.hardCapped());
        hard.now.addAndGet(DAY_MS);
        check("the hard cap resets at the day change", hard.award(ALEX, "k", "hard", 3, null).valor() == 60);

        // renown multiplier rounding
        EarnRig mult = new EarnRig(0, 0);
        check("renown follows the multiplier", mult.award(ALEX, "k", "half", 0, null).renown() == 5);
    }

    private static void renownRules() {
        section("Renown rules");

        // per player daily cap
        EarnRig cap = new EarnRig(0, 120);
        check("first big award credited in full", cap.award(ALEX, "k", "dungeon", 50, null).renown() == 110);
        EarnService.Result second = cap.award(ALEX, "k", "dungeon", 51, null);
        check("the award that crosses the player cap is clipped", second.renown() == 10);
        EarnService.Result third = cap.award(ALEX, "k", "dungeon", 52, null);
        check("past the cap Valor still pays but Renown does not",
                third.renown() == 0 && third.valor() > 0
                        && third.outcome() == EarnService.RenownOutcome.DAILY_CAP);
        check("the kingdom got exactly 120", cap.treasury.renown("k") == 120);
        cap.now.addAndGet(DAY_MS);
        check("the player cap resets daily", cap.award(ALEX, "k", "dungeon", 10, null).renown() == 30);

        // run de-duplication without a run id: window
        EarnRig win = new EarnRig(0, 0);
        win.award(ALEX, "k", "dungeon", 5, null);
        win.award(STEVE, "k", "dungeon", 5, null);
        win.award(JAMIE, "k", "dungeon", 5, null);
        EarnService.Result tay = win.award(TAYLOR, "k", "dungeon", 5, null);
        check("a party of four earns the kingdom one credit", win.treasury.renown("k") == 20
                && tay.outcome() == EarnService.RenownOutcome.DUPLICATE_RUN);
        check("each player still gets their own Valor", win.economy.getBalance(TAYLOR, VALOR) == 20
                && win.economy.getBalance(ALEX, VALOR) == 20);
        win.now.addAndGet(31_000);
        win.award(ALEX, "k", "dungeon", 5, null);
        check("outside the window it is a new run", win.treasury.renown("k") == 40);
        win.award(STEVE, "k", "dungeon", 9, null);
        check("a different level is a different run", win.treasury.renown("k") == 68);

        // run de-duplication with a run id: top-up to the largest award
        EarnRig run = new EarnRig(0, 0);
        run.award(ALEX, "k", "dungeon", 0, "run1");
        run.award(STEVE, "k", "dungeon", 10, "run1");
        check("a larger award in the same run tops the credit up", run.treasury.renown("k") == 30
                && run.treasury.renownLifetime("k") == 30);
        run.award(JAMIE, "k", "dungeon", 5, "run1");
        check("a smaller one adds nothing", run.treasury.renown("k") == 30);
        run.award(ALEX, "k", "dungeon", 0, "run2");
        check("another run id is another credit", run.treasury.renown("k") == 40);
        run.now.addAndGet(5 * 60_000L);
        run.award(TAYLOR, "k", "dungeon", 0, "run1");
        check("with a run id there is no time window", run.treasury.renown("k") == 40);
        run.award(ALEX, "other", "dungeon", 0, "run1");
        check("the key is per kingdom", run.treasury.renown("other") == 10);

        // probation
        EarnRig pro = new EarnRig(7, 0);
        EarnService.Result first = pro.award(ALEX, "k", "dungeon", 0, null);
        check("a new member earns Valor but no Renown", first.valor() == 10 && first.renown() == 0
                && first.outcome() == EarnService.RenownOutcome.PROBATION && first.probationDaysLeft() == 7);
        pro.now.addAndGet(4 * DAY_MS);
        check("days left count down", pro.award(ALEX, "k", "dungeon", 1, null).probationDaysLeft() == 3);
        pro.now.addAndGet(3 * DAY_MS);
        check("after the probation Renown counts", pro.award(ALEX, "k", "dungeon", 2, null).renown() == 14);
        pro.now.addAndGet(DAY_MS);
        EarnService.Result moved = pro.award(ALEX, "other", "dungeon", 3, null);
        check("a change of kingdom restarts the clock",
                moved.outcome() == EarnService.RenownOutcome.PROBATION && moved.probationDaysLeft() == 7);

        // an ineligible first settler does not claim the run
        EarnRig order = new EarnRig(7, 0);
        order.award(STEVE, "k", "dungeon", 0, "x");
        order.now.addAndGet(8 * DAY_MS);
        order.award(JAMIE, "k", "dungeon", 0, "warm");
        EarnService.Result fresh = order.award(ALEX, "k", "dungeon", 0, "y");
        check("a newcomer is on probation", fresh.renown() == 0);
        order.award(ALEX, "k", "dungeon", 0, "run9");
        EarnService.Result vet = order.award(STEVE, "k", "dungeon", 0, "run9");
        check("an eligible player still credits after an ineligible one", vet.renown() == 10);

        // Commons
        EarnRig commons = new EarnRig(7, 0);
        EarnService.Result free = commons.award(ALEX, null, "dungeon", 7, null);
        check("no kingdom: the Renown goes to the Commons, with no probation",
                free.renown() == 24 && Treasury.COMMONS.equals(free.renownTarget())
                        && commons.treasury.renown(Treasury.COMMONS) == 24);
        commons.award(STEVE, "k", "dungeon", 0, null);
        check("a later kingdom never receives the held Renown", commons.treasury.renown("k") == 0);
    }

    private static void renownCatalogue() {
        section("Renown catalogue");
        Treasury t = new Treasury();
        Treasury.CatalogEntry hamlet = new Treasury.CatalogEntry("tier_hamlet", "Hamlet", "", 300, false, null, List.of());
        Treasury.CatalogEntry village = new Treasury.CatalogEntry("tier_village", "Village", "", 900, false,
                "tier_hamlet", List.of());
        Treasury.CatalogEntry repeat = new Treasury.CatalogEntry("banner", "Banner", "", 100, true, null, List.of());

        t.creditRenown("k", 1_000, "earn", "x", "");
        check("insufficient balance is refused",
                t.buy("k", new Treasury.CatalogEntry("big", "Big", "", 5_000, false, null, List.of()), "x")
                        == Treasury.BuyStatus.INSUFFICIENT && t.renown("k") == 1_000);
        check("requires is enforced", t.buy("k", village, "x") == Treasury.BuyStatus.REQUIRES_MISSING
                && t.renown("k") == 1_000);
        check("a purchase deducts", t.buy("k", hamlet, "x") == Treasury.BuyStatus.OK && t.renown("k") == 700);
        check("a non-repeatable entry is bought once",
                t.buy("k", hamlet, "x") == Treasury.BuyStatus.ALREADY_BOUGHT && t.renown("k") == 700);
        check("the prerequisite now unlocks the next tier", t.buy("k", village, "x") == Treasury.BuyStatus.INSUFFICIENT);
        check("lifetime earned is untouched by spending", t.renownLifetime("k") == 1_000);
        check("admin add does not inflate lifetime", t.adjustRenown("k", 50, "op")
                && t.renownLifetime("k") == 1_000 && t.renown("k") == 750);
        check("admin remove cannot go negative", !t.adjustRenown("k", -5_000, "op") && t.renown("k") == 750);

        Treasury race = new Treasury();
        race.creditRenown("k", 1_000, "earn", "x", "");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicLong ok = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                await(start);
                if (race.buy("k", repeat, "t") == Treasury.BuyStatus.OK) ok.incrementAndGet();
            });
        }
        start.countDown();
        shutdown(pool);
        check("40 racing buys against 1,000 Renown: exactly 10 succeed, never negative",
                ok.get() == 10 && race.renown("k") == 0 && race.bought("k", "banner") == 10);
    }

    private static void earnConcurrency() {
        section("Earn concurrency");

        // eight threads, one run: the kingdom is credited once, never doubled
        EarnRig same = new EarnRig(0, 0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            UUID who = UUID.fromString("00000000-0000-0000-0000-00000000b0" + (10 + i));
            pool.submit(() -> {
                await(start);
                same.award(who, "k", "dungeon", 5, "shared");
            });
        }
        start.countDown();
        shutdown(pool);
        check("eight threads in one run credit the kingdom exactly once", same.treasury.renown("k") == 20);

        // eight threads, distinct runs: every credit lands
        EarnRig many = new EarnRig(0, 0);
        ExecutorService pool2 = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            int n = i;
            UUID who = UUID.fromString("00000000-0000-0000-0000-00000000c" + String.format("%03d", n));
            pool2.submit(() -> {
                await(go);
                many.award(who, "k", "dungeon", 0, "run" + n);
            });
        }
        go.countDown();
        shutdown(pool2);
        check("400 racing awards to one kingdom never lose or duplicate Renown",
                many.treasury.renown("k") == 4_000 && many.treasury.renownLifetime("k") == 4_000);
        check("and every player was paid their Valor", many.economy.totalSupply(VALOR) == 4_000);
    }

    // ---- inventory arithmetic ----------------------------------------------

    private static void inventoryMath() {
        section("Inventory arithmetic");

        check("counts across stacks", Wallet.count(new int[] {64, 64, 12}) == 140);
        check("counts an empty inventory", Wallet.count(new int[] {}) == 0);

        check("capacity of empty slots", Wallet.freeCapacity(5, new int[] {}, 64) == 320);
        check("capacity includes partial stacks",
                Wallet.freeCapacity(4, new int[] {32, 60}, 64) == 256 + 32 + 4);
        check("full inventory has no capacity",
                Wallet.freeCapacity(0, new int[] {64, 64}, 64) == 0);

        // Diamonds stack to 64 like everything else, but the arithmetic is driven by
        // the item's own stack size rather than a hardcoded 64, so a future
        // currency backed by a stack-of-16 item works with no changes here.
        check("capacity honours a smaller stack size",
                Wallet.freeCapacity(2, new int[] {8}, 16) == 32 + 8);
        check("stack split honours a smaller stack size",
                java.util.Arrays.equals(Wallet.splitIntoStacks(40, 16), new long[] {2, 8}));

        // The spec's worked example: 500 requested, 320 free, cancel entirely.
        check("insufficient capacity is visible before anything moves",
                Wallet.freeCapacity(5, new int[] {}, 64) < 500);

        int[] stacks = {64, 10, 64, 30};
        int[] plan = Wallet.planRemoval(stacks, 100);
        check("removal plan exists when affordable", plan != null);
        check("removal plan takes exactly the amount asked", sum(plan) == 100);
        check("removal plan never takes more than a slot holds", withinSlots(plan, stacks));
        check("removal of the exact total succeeds",
                sum(Wallet.planRemoval(stacks, 168)) == 168);
        check("deposit more than inventory contains yields no plan",
                Wallet.planRemoval(stacks, 169) == null);
        check("deposit from an empty inventory yields no plan",
                Wallet.planRemoval(new int[] {}, 1) == null);
        check("zero removal yields no plan", Wallet.planRemoval(stacks, 0) == null);
        check("negative removal yields no plan", Wallet.planRemoval(stacks, -5) == null);
        check("failed plan cannot be partially applied",
                Wallet.planRemoval(new int[] {5}, 10) == null);

        check("stack split is exact",
                java.util.Arrays.equals(Wallet.splitIntoStacks(5_000, 64), new long[] {78, 8}));
        check("stack split of a whole multiple has no remainder",
                java.util.Arrays.equals(Wallet.splitIntoStacks(128, 64), new long[] {2, 0}));
        check("stack split of zero is empty",
                java.util.Arrays.equals(Wallet.splitIntoStacks(0, 64), new long[] {0, 0}));
    }

    private static void formatting() {
        section("Formatting");
        check("thousands separated", Wallet.format(12_481).equals("12,481"));
        check("small numbers unchanged", Wallet.format(347).equals("347"));
        check("exact thousand", Wallet.format(1_000).equals("1,000"));
        check("millions", Wallet.format(9_000_000).equals("9,000,000"));
        check("zero", Wallet.format(0).equals("0"));
        check("negative", Wallet.format(-1_500).equals("-1,500"));
        check("long max does not throw", Wallet.format(Long.MAX_VALUE).length() > 0);
    }

    // ---- shop ---------------------------------------------------------------

    private static ShopCatalog sampleCatalog() {
        ShopCatalog catalog = new ShopCatalog();
        catalog.put(ShopEntry.of("ice", "minecraft:ice", 64, COIN, 640, "Building Materials"));
        catalog.put(ShopEntry.of("snow", "minecraft:snow_block", 64, COIN, 320, "Building Materials"));
        catalog.put(ShopEntry.of("packed_ice", "minecraft:packed_ice", 64, COIN, 1_280, "Building Materials"));
        catalog.put(ShopEntry.of("ancient_debris", "minecraft:ancient_debris", 1, GEM, 2, "Rare Resources"));
        return catalog;
    }

    private static void shopCatalog() {
        section("Shop catalog");
        ShopCatalog catalog = sampleCatalog();

        check("entry found by key", catalog.find("ice").isPresent());
        check("lookup is case-insensitive", catalog.find("ICE").isPresent());
        check("unknown key is empty", catalog.find("elytra").isEmpty());
        check("blank key is empty", catalog.find("").isEmpty());

        check("categories preserve file order",
                catalog.categories().equals(List.of("Building Materials", "Rare Resources")));
        check("category listing filters correctly",
                catalog.inCategory("Rare Resources").size() == 1);
        check("all entries available by default", catalog.available().size() == 4);

        ShopEntry ice = catalog.find("ice").orElseThrow();
        check("price renders with the currency",
                ice.describePrice().equals("10 Sovereigns (640 Marks)"));
        check("plain price uses the plural correctly",
                catalog.find("ancient_debris").orElseThrow().describePrice().equals("2 gems"));

        Map<Currency, Long> both = new LinkedHashMap<>();
        both.put(COIN, 500L);
        both.put(GEM, 1L);
        ShopEntry compound = new ShopEntry("elytra", "minecraft:elytra", 1, both, "Premium", true);
        check("compound price renders both lines",
                compound.describePrice().equals(
                        "7 Sovereigns, 6 Crowns, 4 Marks (500 Marks) + 1 gem"));

        // Malformed entries are the server owner's typo, and must not crash /shop.
        catalog.put(new ShopEntry("broken", "minecraft:ice", 0, Map.of(COIN, 10L), "X", true));
        check("zero-quantity entry is invalid", !catalog.find("broken").orElseThrow().isValid());
        check("invalid entries are hidden from players", catalog.available().size() == 4);
        catalog.put(new ShopEntry("free", "minecraft:ice", 1, Map.of(COIN, 0L), "X", true));
        check("zero-price entry is invalid", !catalog.find("free").orElseThrow().isValid());
        catalog.put(new ShopEntry("empty_price", "minecraft:ice", 1, Map.of(), "X", true));
        check("priceless entry is invalid", !catalog.find("empty_price").orElseThrow().isValid());

        ShopEntry disabled = new ShopEntry("hidden", "minecraft:ice", 64,
                Map.of(COIN, 10L), "X", false);
        catalog.put(disabled);
        check("disabled entry is still findable by admins", catalog.find("hidden").isPresent());
        check("disabled entry is hidden from players", !catalog.keys().contains("hidden"));

        check("removal works", catalog.remove("ice") && catalog.find("ice").isEmpty());
        check("removing a missing key reports false", !catalog.remove("ice"));
    }

    private static void purchasing() {
        section("Purchasing");
        ShopCatalog catalog = sampleCatalog();
        ShopEntry ice = catalog.find("ice").orElseThrow();
        ShopEntry debris = catalog.find("ancient_debris").orElseThrow();

        EconomyService e = fresh();
        e.set(ALEX, COIN, 1_000);
        e.set(ALEX, GEM, 5);

        List<String> delivered = new ArrayList<>();
        Shop.Delivery ok = (item, qty) -> {
            delivered.add(qty + "x" + item);
            return true;
        };

        Shop.PurchaseResult bought = Shop.buy(e, ALEX, ice, 640, ok);
        check("successful purchase", bought.ok());
        check("currency removed correctly", e.getBalance(ALEX, COIN) == 360);
        check("item granted correctly", delivered.equals(List.of("64xminecraft:ice")));
        check("purchase did not touch the other currency", e.getBalance(ALEX, GEM) == 5);

        Shop.PurchaseResult broke = Shop.buy(e, ALEX, ice, 640, ok);
        check("insufficient currency rejected",
                broke.status() == Shop.PurchaseStatus.INSUFFICIENT_FUNDS);
        check("rejected purchase charges nothing", e.getBalance(ALEX, COIN) == 360);
        check("rejected purchase delivers nothing", delivered.size() == 1);

        Shop.PurchaseResult cramped = Shop.buy(e, ALEX, debris, 0, ok);
        check("insufficient inventory space rejected",
                cramped.status() == Shop.PurchaseStatus.INSUFFICIENT_SPACE);
        check("no space means no charge", e.getBalance(ALEX, GEM) == 5);
        check("no space means no delivery", delivered.size() == 1);

        // The rollback the callback exists to make reachable.
        Shop.PurchaseResult failed = Shop.buy(e, ALEX, debris, 64, (item, qty) -> false);
        check("failed delivery is reported",
                failed.status() == Shop.PurchaseStatus.DELIVERY_FAILED);
        check("failed delivery refunds the money in full", e.getBalance(ALEX, GEM) == 5);

        // A delivery that throws must not leave a player charged either.
        boolean threw = false;
        try {
            Shop.buy(e, ALEX, debris, 64, (item, qty) -> {
                throw new IllegalStateException("inventory exploded");
            });
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check("a throwing delivery propagates", threw);
        check("a throwing delivery still refunds", e.getBalance(ALEX, GEM) == 5);

        check("invalid entry cannot be bought",
                Shop.buy(e, ALEX, new ShopEntry("x", "minecraft:ice", 0,
                        Map.of(COIN, 1L), "X", true), 64, ok).status()
                        == Shop.PurchaseStatus.UNAVAILABLE);
        check("disabled entry cannot be bought",
                Shop.buy(e, ALEX, new ShopEntry("x", "minecraft:ice", 1,
                        Map.of(COIN, 1L), "X", false), 64, ok).status()
                        == Shop.PurchaseStatus.UNAVAILABLE);
        check("null entry cannot be bought",
                Shop.buy(e, ALEX, null, 64, ok).status() == Shop.PurchaseStatus.UNAVAILABLE);

        // The shop is a currency sink: money spent leaves the economy entirely.
        EconomyService sink = fresh();
        sink.set(ALEX, COIN, 640);
        long before = sink.totalSupply(COIN);
        Shop.buy(sink, ALEX, ice, 640, ok);
        check("purchases destroy currency rather than moving it",
                sink.totalSupply(COIN) == before - 640);
    }

    // ---- leaderboards -------------------------------------------------------

    private static LeaderboardService board(EconomyService e) {
        return new LeaderboardService(e, NAMES::get);
    }

    private static void leaderboards() {
        section("Leaderboards");
        EconomyService e = fresh();
        e.set(STEVE, COIN, 82_410);
        e.set(ALEX, COIN, 61_290);
        e.set(JAMIE, COIN, 44_912);
        e.set(TAYLOR, COIN, 31_502);
        LeaderboardService board = board(e);

        List<LeaderboardService.Rank> top3 = board.top(COIN, 3);
        check("top 3 has three rows", top3.size() == 3);
        check("ranked highest first", top3.get(0).player().equals(STEVE));
        check("second place correct", top3.get(1).player().equals(ALEX));
        check("third place correct", top3.get(2).player().equals(JAMIE));
        check("rank numbers are 1-based", top3.get(0).rank() == 1 && top3.get(2).rank() == 3);
        check("balances carried through", top3.get(0).balance() == 82_410);

        check("rankOf finds a listed player", board.rankOf(TAYLOR, COIN) == 4);
        check("rankOf returns 0 for a player with no balance",
                board.rankOf(UUID.randomUUID(), COIN) == 0);
        check("participants counts holders", board.participants(COIN) == 4);

        // Section 9 of the leaderboard spec: zero balances do not appear.
        e.set(TAYLOR, COIN, 0);
        check("zero balance drops off the leaderboard", board.participants(COIN) == 3);
        check("zero balance has no rank", board.rankOf(TAYLOR, COIN) == 0);

        // Section 8: fewer than three players shows only what exists, no filler rows.
        EconomyService sparse = fresh();
        sparse.set(STEVE, COIN, 12_480);
        sparse.set(ALEX, COIN, 4_200);
        LeaderboardService sparseBoard = board(sparse);
        check("two players yield two rows", sparseBoard.top(COIN, 3).size() == 2);
        check("one player yields one row", sparseBoard.top(GEM, 3).isEmpty());

        EconomyService empty = fresh();
        check("empty economy yields an empty leaderboard",
                board(empty).top(COIN, 3).isEmpty());
        check("empty economy does not throw on rank",
                board(empty).rankOf(ALEX, COIN) == 0);

        // Section 5: ties broken alphabetically by current username, deterministically.
        EconomyService tied = fresh();
        tied.set(STEVE, COIN, 100);
        tied.set(ALEX, COIN, 100);
        tied.set(JAMIE, COIN, 100);
        LeaderboardService tiedBoard = board(tied);
        List<LeaderboardService.Rank> order = tiedBoard.top(COIN, 3);
        check("equal balances sort alphabetically",
                order.get(0).player().equals(ALEX)
                        && order.get(1).player().equals(JAMIE)
                        && order.get(2).player().equals(STEVE));

        boolean stable = true;
        for (int i = 0; i < 20; i++) {
            if (!tiedBoard.top(COIN, 3).equals(order)) stable = false;
        }
        check("tie ordering is stable across calls", stable);

        // The bug the tie-break prevents: a name on two pages while another vanishes.
        EconomyService many = fresh();
        Map<UUID, String> names = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            names.put(id, "Player" + i);
            many.set(id, COIN, 100);
        }
        LeaderboardService manyBoard = new LeaderboardService(many, names::get);
        Map<UUID, Boolean> seen = new HashMap<>();
        boolean unique = true;
        for (int page = 0; page < 4; page++) {
            for (LeaderboardService.Rank row : manyBoard.top(COIN, 5, page * 5)) {
                if (seen.put(row.player(), true) != null) unique = false;
            }
        }
        check("tied balances paginate without repeats", unique);
        check("tied pagination covers everyone", seen.size() == 20);

        check("offset past the end is empty", manyBoard.top(COIN, 5, 999).isEmpty());
        check("zero limit is empty", manyBoard.top(COIN, 0).isEmpty());
        check("negative offset is empty", manyBoard.top(COIN, 5, -1).isEmpty());

        // Section 18: the login count is a parameter, never a hardcoded 3.
        check("top count is configurable upward", manyBoard.top(COIN, 5).size() == 5);
        check("top count is configurable downward", manyBoard.top(COIN, 1).size() == 1);

        // Section 20: identity is the UUID; a rename moves the label, not the account.
        EconomyService renamed = fresh();
        renamed.set(STEVE, COIN, 500);
        Map<UUID, String> mutable = new HashMap<>(NAMES);
        LeaderboardService renameBoard = new LeaderboardService(renamed, mutable::get);
        int rankBefore = renameBoard.rankOf(STEVE, COIN);
        mutable.put(STEVE, "SteveTheBuilder");
        check("a rename does not create a second account",
                renameBoard.participants(COIN) == 1);
        check("a rename preserves the balance", renamed.getBalance(STEVE, COIN) == 500);
        check("a rename preserves the rank",
                renameBoard.rankOf(STEVE, COIN) == rankBefore);
    }

    private static void leaderboardMultiCurrency() {
        section("Leaderboards across currencies");
        EconomyService e = fresh();
        e.set(STEVE, COIN, 82_410);
        e.set(ALEX, COIN, 61_290);
        e.set(JAMIE, COIN, 44_912);
        e.set(TAYLOR, GEM, 91);
        e.set(STEVE, GEM, 37);
        e.set(ALEX, GEM, 24);
        LeaderboardService board = board(e);

        check("cobblestone ranking is led by the cobblestone holder",
                board.top(COIN, 3).get(0).player().equals(STEVE));
        check("diamond ranking is led by the diamond holder",
                board.top(GEM, 3).get(0).player().equals(TAYLOR));
        check("a cobblestone-only player is absent from the diamond board",
                board.rankOf(JAMIE, GEM) == 0);
        check("a diamond-only player is absent from the cobblestone board",
                board.rankOf(TAYLOR, COIN) == 0);
        check("the same player holds different ranks in each currency",
                board.rankOf(STEVE, COIN) == 1 && board.rankOf(STEVE, GEM) == 2);

        // Section 25 of the leaderboard spec: the board tracks the economy live.
        e.deposit(JAMIE, COIN, 100_000);
        check("depositing updates the ranking",
                board.top(COIN, 1).get(0).player().equals(JAMIE));
        e.withdraw(JAMIE, COIN, 100_000);
        check("withdrawing updates the ranking",
                board.top(COIN, 1).get(0).player().equals(STEVE));

        int alexBefore = board.rankOf(ALEX, GEM);
        e.transfer(TAYLOR, ALEX, GEM, 80);
        check("paying updates the sender's rank", board.rankOf(TAYLOR, GEM) == 3);
        check("paying updates the recipient's rank",
                board.rankOf(ALEX, GEM) == 1 && alexBefore == 3);
        check("a diamond payment left the cobblestone board alone",
                board.top(COIN, 1).get(0).player().equals(STEVE));
    }

    // ---- harness ------------------------------------------------------------

    private static EconomyService fresh() {
        return new CharterCurrency(AccountStore.inMemory());
    }

    /** A store that actually keeps what it is told, so a restart can be simulated. */
    private static final class RecordingStore implements AccountStore {
        final Map<UUID, Map<String, Long>> saved = new HashMap<>();
        @Override public Map<UUID, Map<String, Long>> load() { return Map.of(); }
        @Override public void put(UUID player, Map<String, Long> balances) {
            saved.put(player, new HashMap<>(balances));
        }
        @Override public void flushNow() { }
        AccountStore asSourceOfTruth() {
            return new AccountStore() {
                @Override public Map<UUID, Map<String, Long>> load() { return new HashMap<>(saved); }
                @Override public void put(UUID p, Map<String, Long> b) {
                    saved.put(p, new HashMap<>(b));
                }
                @Override public void flushNow() { }
            };
        }
    }

    private static long sum(int[] plan) {
        if (plan == null) return -1;
        long t = 0;
        for (int p : plan) t += p;
        return t;
    }

    private static boolean withinSlots(int[] plan, int[] stacks) {
        if (plan == null) return false;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] < 0 || plan[i] > stacks[i]) return false;
        }
        return true;
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static void shutdown(ExecutorService pool) {
        pool.shutdown();
        try { pool.awaitTermination(60, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failures.add(what);
            System.out.println("  FAIL " + what);
        }
    }
}
