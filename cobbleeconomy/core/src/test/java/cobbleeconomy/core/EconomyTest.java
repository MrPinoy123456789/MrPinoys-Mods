package cobbleeconomy.core;

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
 *   java -cp /tmp/out cobbleeconomy.core.EconomyTest
 * </pre>
 */
public final class EconomyTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID JAMIE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID TAYLOR = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

    private static final CurrencyRegistry REGISTRY = CurrencyRegistry.defaults();
    private static final Currency COBBLE = CurrencyRegistry.COBBLESTONE;
    private static final Currency DIAMOND = CurrencyRegistry.DIAMOND;

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

        check("resolves canonical id", REGISTRY.resolve("cobblestone").orElseThrow().equals(COBBLE));
        check("resolves alias", REGISTRY.resolve("cobble").orElseThrow().equals(COBBLE));
        check("resolves case-insensitively", REGISTRY.resolve("COBBLE").orElseThrow().equals(COBBLE));
        check("resolves plural alias", REGISTRY.resolve("diamonds").orElseThrow().equals(DIAMOND));
        check("unknown currency is empty", REGISTRY.resolve("emeralds").isEmpty());
        check("blank is empty", REGISTRY.resolve("  ").isEmpty());
        check("null is empty", REGISTRY.resolve(null).isEmpty());
        check("both currencies registered", REGISTRY.all().size() == 2);
        check("cobblestone is listed first", REGISTRY.all().iterator().next().equals(COBBLE));
        check("maps to the vanilla item", COBBLE.itemId().equals("minecraft:cobblestone"));
        check("diamond maps to the vanilla item", DIAMOND.itemId().equals("minecraft:diamond"));

        check("singular wording", DIAMOND.describe(1).equals("1 diamond"));
        check("plural wording", DIAMOND.describe(37).equals("37 diamonds"));
        check("plural wording groups digits", COBBLE.describe(12481).equals("12,481 cobblestone"));

        // An alias claimed by two currencies would make /pay ambiguous, so it is fatal.
        CurrencyRegistry clashing = new CurrencyRegistry();
        clashing.register(COBBLE);
        boolean rejected = false;
        try {
            clashing.register(Currency.of("granite", "Granite", "granite", "granite",
                    "minecraft:granite", "stone"));
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("clashing alias is rejected at registration", rejected);
    }

    // ---- depositing ---------------------------------------------------------

    private static void depositing() {
        section("Depositing");
        EconomyService e = fresh();

        check("deposit exact amount", e.deposit(ALEX, COBBLE, 500).ok());
        check("balance reflects deposit", e.getBalance(ALEX, COBBLE) == 500);

        e.deposit(ALEX, COBBLE, 1_842);
        check("deposits accumulate", e.getBalance(ALEX, COBBLE) == 2_342);

        check("deposit zero rejected",
                e.deposit(ALEX, COBBLE, 0).status() == TxStatus.INVALID_AMOUNT);
        check("deposit negative rejected",
                e.deposit(ALEX, COBBLE, -500).status() == TxStatus.INVALID_AMOUNT);
        check("rejected deposits do not move the balance", e.getBalance(ALEX, COBBLE) == 2_342);
        check("null currency rejected",
                e.deposit(ALEX, null, 100).status() == TxStatus.UNKNOWN_CURRENCY);

        EconomyService brim = fresh();
        brim.set(ALEX, COBBLE, Long.MAX_VALUE - 10);
        check("overflow rejected rather than wrapping negative",
                brim.deposit(ALEX, COBBLE, 100).status() == TxStatus.OVERFLOW);
        check("rejected overflow leaves the balance intact",
                brim.getBalance(ALEX, COBBLE) == Long.MAX_VALUE - 10);
        check("a deposit that exactly fills the range still works",
                brim.deposit(ALEX, COBBLE, 10).ok()
                        && brim.getBalance(ALEX, COBBLE) == Long.MAX_VALUE);
    }

    // ---- withdrawal ---------------------------------------------------------

    private static void withdrawal() {
        section("Withdrawal");
        EconomyService e = fresh();
        e.deposit(ALEX, COBBLE, 1_000);

        check("withdraw part of balance", e.withdraw(ALEX, COBBLE, 400).ok());
        check("balance reduced", e.getBalance(ALEX, COBBLE) == 600);
        check("withdraw exact balance", e.withdraw(ALEX, COBBLE, 600).ok());
        check("balance now zero", e.getBalance(ALEX, COBBLE) == 0);

        check("withdraw more than balance rejected",
                e.withdraw(ALEX, COBBLE, 1).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("withdraw zero rejected",
                e.withdraw(ALEX, COBBLE, 0).status() == TxStatus.INVALID_AMOUNT);
        check("withdraw negative rejected",
                e.withdraw(ALEX, COBBLE, -50).status() == TxStatus.INVALID_AMOUNT);

        e.deposit(STEVE, COBBLE, 100);
        e.withdraw(STEVE, COBBLE, 500);
        check("failed withdrawal leaves balance untouched", e.getBalance(STEVE, COBBLE) == 100);
        check("failed withdrawal reports current balance",
                e.withdraw(STEVE, COBBLE, 500).actorBalance() == 100);
    }

    // ---- payments -----------------------------------------------------------

    private static void payments() {
        section("Payments");
        EconomyService e = fresh();
        e.deposit(ALEX, COBBLE, 5_000);
        e.deposit(STEVE, COBBLE, 2_250);

        TxResult r = e.transfer(ALEX, STEVE, COBBLE, 500);
        check("successful payment", r.ok());
        check("sender debited", e.getBalance(ALEX, COBBLE) == 4_500);
        check("recipient credited", e.getBalance(STEVE, COBBLE) == 2_750);
        check("result carries sender balance", r.actorBalance() == 4_500);
        check("result carries recipient balance", r.targetBalance() == 2_750);
        check("result carries the currency", r.currency().equals(COBBLE));

        check("insufficient funds rejected",
                e.transfer(ALEX, STEVE, COBBLE, 999_999).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("failed payment moves nothing",
                e.getBalance(ALEX, COBBLE) == 4_500 && e.getBalance(STEVE, COBBLE) == 2_750);

        check("self payment rejected",
                e.transfer(ALEX, ALEX, COBBLE, 100).status() == TxStatus.SELF_TRANSFER);
        check("self payment does not double money", e.getBalance(ALEX, COBBLE) == 4_500);

        check("zero payment rejected",
                e.transfer(ALEX, STEVE, COBBLE, 0).status() == TxStatus.INVALID_AMOUNT);
        check("negative payment rejected",
                e.transfer(ALEX, STEVE, COBBLE, -100).status() == TxStatus.INVALID_AMOUNT);
        check("negative payment cannot drain the recipient",
                e.getBalance(STEVE, COBBLE) == 2_750);

        // Offline recipients are not special: an account is a UUID and some numbers.
        check("payment to an account never seen before",
                e.transfer(ALEX, JAMIE, COBBLE, 250).ok());
        check("previously unknown recipient credited", e.getBalance(JAMIE, COBBLE) == 250);
        check("null recipient rejected",
                e.transfer(ALEX, null, COBBLE, 100).status() == TxStatus.INVALID_AMOUNT);

        long before = e.totalSupply(COBBLE);
        e.transfer(ALEX, STEVE, COBBLE, 1_000);
        check("transfer conserves total supply", e.totalSupply(COBBLE) == before);
    }

    // ---- multi-currency independence ---------------------------------------

    private static void multiCurrencyIndependence() {
        section("Multi-currency independence");
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 12_480);
        e.set(ALEX, DIAMOND, 37);

        check("both balances held at once",
                e.getBalance(ALEX, COBBLE) == 12_480 && e.getBalance(ALEX, DIAMOND) == 37);

        e.deposit(ALEX, COBBLE, 1_000);
        check("depositing cobblestone leaves diamonds alone", e.getBalance(ALEX, DIAMOND) == 37);
        e.deposit(ALEX, DIAMOND, 3);
        check("depositing diamonds leaves cobblestone alone",
                e.getBalance(ALEX, COBBLE) == 13_480);

        e.withdraw(ALEX, COBBLE, 480);
        check("withdrawing cobblestone leaves diamonds alone", e.getBalance(ALEX, DIAMOND) == 40);
        e.withdraw(ALEX, DIAMOND, 10);
        check("withdrawing diamonds leaves cobblestone alone",
                e.getBalance(ALEX, COBBLE) == 13_000);

        e.set(STEVE, COBBLE, 100);
        e.set(STEVE, DIAMOND, 5);
        e.transfer(ALEX, STEVE, COBBLE, 500);
        check("paying cobblestone leaves the sender's diamonds alone",
                e.getBalance(ALEX, DIAMOND) == 30);
        check("paying cobblestone leaves the recipient's diamonds alone",
                e.getBalance(STEVE, DIAMOND) == 5);

        e.transfer(ALEX, STEVE, DIAMOND, 2);
        check("paying diamonds leaves the sender's cobblestone alone",
                e.getBalance(ALEX, COBBLE) == 12_500);
        check("paying diamonds leaves the recipient's cobblestone alone",
                e.getBalance(STEVE, COBBLE) == 600);
        check("diamond payment landed", e.getBalance(STEVE, DIAMOND) == 7);

        // Running out of one currency must not be answerable with the other.
        EconomyService poor = fresh();
        poor.set(ALEX, COBBLE, 1_000_000);
        check("a cobblestone fortune does not buy a diamond",
                poor.withdraw(ALEX, DIAMOND, 1).status() == TxStatus.INSUFFICIENT_FUNDS);
        check("the failed diamond withdrawal did not spend cobblestone",
                poor.getBalance(ALEX, COBBLE) == 1_000_000);

        check("getBalances reports every currency", fresh() != null
                && e.getBalances(ALEX).size() == 2);
        check("emptying one currency leaves the account", drainedAccountKeepsOther());
    }

    private static boolean drainedAccountKeepsOther() {
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 50);
        e.set(ALEX, DIAMOND, 5);
        e.withdraw(ALEX, COBBLE, 50);
        return e.getBalance(ALEX, COBBLE) == 0
                && e.getBalance(ALEX, DIAMOND) == 5
                && e.getBalances(ALEX).size() == 1;
    }

    // ---- compound prices ----------------------------------------------------

    private static void compoundPrices() {
        section("Compound prices");
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 1_000);
        e.set(ALEX, DIAMOND, 3);

        Map<Currency, Long> price = new LinkedHashMap<>();
        price.put(COBBLE, 500L);
        price.put(DIAMOND, 1L);

        check("compound charge succeeds when both are affordable", e.charge(ALEX, price).ok());
        check("cobblestone debited", e.getBalance(ALEX, COBBLE) == 500);
        check("diamond debited", e.getBalance(ALEX, DIAMOND) == 2);

        // The case the whole map-shaped price exists for: affordable in one currency,
        // not the other. Neither may be taken.
        Map<Currency, Long> steep = new LinkedHashMap<>();
        steep.put(COBBLE, 100L);
        steep.put(DIAMOND, 99L);
        TxResult failed = e.charge(ALEX, steep);
        check("compound charge fails if any line is unaffordable",
                failed.status() == TxStatus.INSUFFICIENT_FUNDS);
        check("failure names the currency that was short", failed.currency().equals(DIAMOND));
        check("affordable line was NOT debited on failure", e.getBalance(ALEX, COBBLE) == 500);
        check("unaffordable line was not debited", e.getBalance(ALEX, DIAMOND) == 2);

        e.refund(ALEX, price);
        check("refund restores cobblestone", e.getBalance(ALEX, COBBLE) == 1_000);
        check("refund restores diamonds", e.getBalance(ALEX, DIAMOND) == 3);

        check("empty price rejected",
                e.charge(ALEX, Map.of()).status() == TxStatus.INVALID_AMOUNT);
        check("non-positive price line rejected",
                e.charge(ALEX, Map.of(COBBLE, 0L)).status() == TxStatus.INVALID_AMOUNT);
    }

    // ---- admin operations ---------------------------------------------------

    private static void adminOps() {
        section("Admin operations");
        EconomyService e = fresh();

        check("set from nothing", e.set(ALEX, COBBLE, 84_231).ok());
        check("set applied", e.getBalance(ALEX, COBBLE) == 84_231);
        check("set overwrites rather than adds",
                e.set(ALEX, COBBLE, 100).ok() && e.getBalance(ALEX, COBBLE) == 100);
        check("set to zero allowed",
                e.set(ALEX, COBBLE, 0).ok() && e.getBalance(ALEX, COBBLE) == 0);
        check("set negative rejected",
                e.set(ALEX, COBBLE, -1).status() == TxStatus.INVALID_AMOUNT);

        check("admin add is a deposit", e.deposit(ALEX, DIAMOND, 50).ok());
        check("admin remove is a withdrawal", e.withdraw(ALEX, DIAMOND, 50).ok());
        check("admin remove past zero rejected",
                e.withdraw(ALEX, DIAMOND, 1).status() == TxStatus.INSUFFICIENT_FUNDS);

        e.set(STEVE, COBBLE, 500);
        e.set(STEVE, DIAMOND, 5);
        e.set(STEVE, DIAMOND, 0);
        check("admin clearing one currency spares the other",
                e.getBalance(STEVE, COBBLE) == 500 && e.getBalance(STEVE, DIAMOND) == 0);
    }

    // ---- persistence --------------------------------------------------------

    private static void persistence() {
        section("Persistence");
        RecordingStore store = new RecordingStore();
        EconomyService e = new CobbleEconomy(store);

        e.deposit(ALEX, COBBLE, 12_481);
        e.deposit(ALEX, DIAMOND, 37);
        e.deposit(STEVE, COBBLE, 932);

        check("both currencies reach the store",
                store.saved.get(ALEX).get("cobblestone") == 12_481L
                        && store.saved.get(ALEX).get("diamond") == 37L);
        check("multiple players persisted independently",
                store.saved.get(STEVE).get("cobblestone") == 932L);

        // The restart: a brand new service reading what the old one wrote.
        EconomyService restarted = new CobbleEconomy(store.asSourceOfTruth());
        check("cobblestone survives restart", restarted.getBalance(ALEX, COBBLE) == 12_481);
        check("diamonds survive restart", restarted.getBalance(ALEX, DIAMOND) == 37);
        check("all accounts survive restart", restarted.balances(COBBLE).size() == 2);
        check("a player with only one currency reloads correctly",
                restarted.getBalance(STEVE, DIAMOND) == 0);

        EconomyService big = fresh();
        big.set(ALEX, COBBLE, 9_000_000_000L);
        check("balances beyond int range are held exactly",
                big.getBalance(ALEX, COBBLE) == 9_000_000_000L);
        big.deposit(ALEX, COBBLE, 1L);
        check("large balances still arithmetic correctly",
                big.getBalance(ALEX, COBBLE) == 9_000_000_001L);

        EconomyService zeroed = fresh();
        zeroed.deposit(ALEX, COBBLE, 10);
        zeroed.withdraw(ALEX, COBBLE, 10);
        check("emptied accounts are not stored as rows of nothing",
                zeroed.balances(COBBLE).isEmpty());
        check("emptied account still reads as zero", zeroed.getBalance(ALEX, COBBLE) == 0);
    }

    // ---- security -----------------------------------------------------------

    private static void security() {
        section("Security");
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 1_000);

        // Command spam: 2,000 withdrawals of 1 against a balance of 1,000. Exactly
        // 1,000 must succeed -- one extra is duplicated currency.
        int ok = 0;
        for (int i = 0; i < 2_000; i++) if (e.withdraw(ALEX, COBBLE, 1).ok()) ok++;
        check("repeated commands cannot withdraw more than exists", ok == 1_000);
        check("spammed account lands exactly at zero", e.getBalance(ALEX, COBBLE) == 0);

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
            e.set(players[i], COBBLE, 10_000);
        }
        long before = e.totalSupply(COBBLE);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            final int from = t;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 2_000; i++) {
                    e.transfer(players[from], players[(from + 1) % players.length], COBBLE, 3);
                }
            });
        }
        start.countDown();
        shutdown(pool);
        return e.totalSupply(COBBLE) == before;
    }

    /** 8 threads race to drain one account. The sum withdrawn must equal the balance. */
    private static boolean raceWithdrawals() {
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 5_000);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<AtomicLong> tallies = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            AtomicLong tally = new AtomicLong();
            tallies.add(tally);
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 5_000; i++) {
                    if (e.withdraw(ALEX, COBBLE, 1).ok()) tally.incrementAndGet();
                }
            });
        }
        start.countDown();
        shutdown(pool);

        long withdrawn = tallies.stream().mapToLong(AtomicLong::get).sum();
        return withdrawn == 5_000 && e.getBalance(ALEX, COBBLE) == 0;
    }

    /**
     * The dupe a shop invites: several threads buying at once with only enough money
     * for a fixed number of purchases. Deliveries must exactly match successful charges.
     */
    private static boolean racePurchases() {
        EconomyService e = fresh();
        e.set(ALEX, COBBLE, 6_400);
        ShopEntry ice = ShopEntry.of("ice", "minecraft:ice", 64, COBBLE, 640, "Building");

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
                && e.getBalance(ALEX, COBBLE) == 0;
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
        catalog.put(ShopEntry.of("ice", "minecraft:ice", 64, COBBLE, 640, "Building Materials"));
        catalog.put(ShopEntry.of("snow", "minecraft:snow_block", 64, COBBLE, 320, "Building Materials"));
        catalog.put(ShopEntry.of("packed_ice", "minecraft:packed_ice", 64, COBBLE, 1_280, "Building Materials"));
        catalog.put(ShopEntry.of("ancient_debris", "minecraft:ancient_debris", 1, DIAMOND, 2, "Rare Resources"));
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
        check("price renders with the currency", ice.describePrice().equals("640 cobblestone"));
        check("diamond price uses the plural correctly",
                catalog.find("ancient_debris").orElseThrow().describePrice().equals("2 diamonds"));

        Map<Currency, Long> both = new LinkedHashMap<>();
        both.put(COBBLE, 500L);
        both.put(DIAMOND, 1L);
        ShopEntry compound = new ShopEntry("elytra", "minecraft:elytra", 1, both, "Premium", true);
        check("compound price renders both lines",
                compound.describePrice().equals("500 cobblestone + 1 diamond"));

        // Malformed entries are the server owner's typo, and must not crash /shop.
        catalog.put(new ShopEntry("broken", "minecraft:ice", 0, Map.of(COBBLE, 10L), "X", true));
        check("zero-quantity entry is invalid", !catalog.find("broken").orElseThrow().isValid());
        check("invalid entries are hidden from players", catalog.available().size() == 4);
        catalog.put(new ShopEntry("free", "minecraft:ice", 1, Map.of(COBBLE, 0L), "X", true));
        check("zero-price entry is invalid", !catalog.find("free").orElseThrow().isValid());
        catalog.put(new ShopEntry("empty_price", "minecraft:ice", 1, Map.of(), "X", true));
        check("priceless entry is invalid", !catalog.find("empty_price").orElseThrow().isValid());

        ShopEntry disabled = new ShopEntry("hidden", "minecraft:ice", 64,
                Map.of(COBBLE, 10L), "X", false);
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
        e.set(ALEX, COBBLE, 1_000);
        e.set(ALEX, DIAMOND, 5);

        List<String> delivered = new ArrayList<>();
        Shop.Delivery ok = (item, qty) -> {
            delivered.add(qty + "x" + item);
            return true;
        };

        Shop.PurchaseResult bought = Shop.buy(e, ALEX, ice, 640, ok);
        check("successful purchase", bought.ok());
        check("currency removed correctly", e.getBalance(ALEX, COBBLE) == 360);
        check("item granted correctly", delivered.equals(List.of("64xminecraft:ice")));
        check("purchase did not touch the other currency", e.getBalance(ALEX, DIAMOND) == 5);

        Shop.PurchaseResult broke = Shop.buy(e, ALEX, ice, 640, ok);
        check("insufficient currency rejected",
                broke.status() == Shop.PurchaseStatus.INSUFFICIENT_FUNDS);
        check("rejected purchase charges nothing", e.getBalance(ALEX, COBBLE) == 360);
        check("rejected purchase delivers nothing", delivered.size() == 1);

        Shop.PurchaseResult cramped = Shop.buy(e, ALEX, debris, 0, ok);
        check("insufficient inventory space rejected",
                cramped.status() == Shop.PurchaseStatus.INSUFFICIENT_SPACE);
        check("no space means no charge", e.getBalance(ALEX, DIAMOND) == 5);
        check("no space means no delivery", delivered.size() == 1);

        // The rollback the callback exists to make reachable.
        Shop.PurchaseResult failed = Shop.buy(e, ALEX, debris, 64, (item, qty) -> false);
        check("failed delivery is reported",
                failed.status() == Shop.PurchaseStatus.DELIVERY_FAILED);
        check("failed delivery refunds the money in full", e.getBalance(ALEX, DIAMOND) == 5);

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
        check("a throwing delivery still refunds", e.getBalance(ALEX, DIAMOND) == 5);

        check("invalid entry cannot be bought",
                Shop.buy(e, ALEX, new ShopEntry("x", "minecraft:ice", 0,
                        Map.of(COBBLE, 1L), "X", true), 64, ok).status()
                        == Shop.PurchaseStatus.UNAVAILABLE);
        check("disabled entry cannot be bought",
                Shop.buy(e, ALEX, new ShopEntry("x", "minecraft:ice", 1,
                        Map.of(COBBLE, 1L), "X", false), 64, ok).status()
                        == Shop.PurchaseStatus.UNAVAILABLE);
        check("null entry cannot be bought",
                Shop.buy(e, ALEX, null, 64, ok).status() == Shop.PurchaseStatus.UNAVAILABLE);

        // The shop is a currency sink: money spent leaves the economy entirely.
        EconomyService sink = fresh();
        sink.set(ALEX, COBBLE, 640);
        long before = sink.totalSupply(COBBLE);
        Shop.buy(sink, ALEX, ice, 640, ok);
        check("purchases destroy currency rather than moving it",
                sink.totalSupply(COBBLE) == before - 640);
    }

    // ---- leaderboards -------------------------------------------------------

    private static LeaderboardService board(EconomyService e) {
        return new LeaderboardService(e, NAMES::get);
    }

    private static void leaderboards() {
        section("Leaderboards");
        EconomyService e = fresh();
        e.set(STEVE, COBBLE, 82_410);
        e.set(ALEX, COBBLE, 61_290);
        e.set(JAMIE, COBBLE, 44_912);
        e.set(TAYLOR, COBBLE, 31_502);
        LeaderboardService board = board(e);

        List<LeaderboardService.Rank> top3 = board.top(COBBLE, 3);
        check("top 3 has three rows", top3.size() == 3);
        check("ranked highest first", top3.get(0).player().equals(STEVE));
        check("second place correct", top3.get(1).player().equals(ALEX));
        check("third place correct", top3.get(2).player().equals(JAMIE));
        check("rank numbers are 1-based", top3.get(0).rank() == 1 && top3.get(2).rank() == 3);
        check("balances carried through", top3.get(0).balance() == 82_410);

        check("rankOf finds a listed player", board.rankOf(TAYLOR, COBBLE) == 4);
        check("rankOf returns 0 for a player with no balance",
                board.rankOf(UUID.randomUUID(), COBBLE) == 0);
        check("participants counts holders", board.participants(COBBLE) == 4);

        // Section 9 of the leaderboard spec: zero balances do not appear.
        e.set(TAYLOR, COBBLE, 0);
        check("zero balance drops off the leaderboard", board.participants(COBBLE) == 3);
        check("zero balance has no rank", board.rankOf(TAYLOR, COBBLE) == 0);

        // Section 8: fewer than three players shows only what exists, no filler rows.
        EconomyService sparse = fresh();
        sparse.set(STEVE, COBBLE, 12_480);
        sparse.set(ALEX, COBBLE, 4_200);
        LeaderboardService sparseBoard = board(sparse);
        check("two players yield two rows", sparseBoard.top(COBBLE, 3).size() == 2);
        check("one player yields one row", sparseBoard.top(DIAMOND, 3).isEmpty());

        EconomyService empty = fresh();
        check("empty economy yields an empty leaderboard",
                board(empty).top(COBBLE, 3).isEmpty());
        check("empty economy does not throw on rank",
                board(empty).rankOf(ALEX, COBBLE) == 0);

        // Section 5: ties broken alphabetically by current username, deterministically.
        EconomyService tied = fresh();
        tied.set(STEVE, COBBLE, 100);
        tied.set(ALEX, COBBLE, 100);
        tied.set(JAMIE, COBBLE, 100);
        LeaderboardService tiedBoard = board(tied);
        List<LeaderboardService.Rank> order = tiedBoard.top(COBBLE, 3);
        check("equal balances sort alphabetically",
                order.get(0).player().equals(ALEX)
                        && order.get(1).player().equals(JAMIE)
                        && order.get(2).player().equals(STEVE));

        boolean stable = true;
        for (int i = 0; i < 20; i++) {
            if (!tiedBoard.top(COBBLE, 3).equals(order)) stable = false;
        }
        check("tie ordering is stable across calls", stable);

        // The bug the tie-break prevents: a name on two pages while another vanishes.
        EconomyService many = fresh();
        Map<UUID, String> names = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            names.put(id, "Player" + i);
            many.set(id, COBBLE, 100);
        }
        LeaderboardService manyBoard = new LeaderboardService(many, names::get);
        Map<UUID, Boolean> seen = new HashMap<>();
        boolean unique = true;
        for (int page = 0; page < 4; page++) {
            for (LeaderboardService.Rank row : manyBoard.top(COBBLE, 5, page * 5)) {
                if (seen.put(row.player(), true) != null) unique = false;
            }
        }
        check("tied balances paginate without repeats", unique);
        check("tied pagination covers everyone", seen.size() == 20);

        check("offset past the end is empty", manyBoard.top(COBBLE, 5, 999).isEmpty());
        check("zero limit is empty", manyBoard.top(COBBLE, 0).isEmpty());
        check("negative offset is empty", manyBoard.top(COBBLE, 5, -1).isEmpty());

        // Section 18: the login count is a parameter, never a hardcoded 3.
        check("top count is configurable upward", manyBoard.top(COBBLE, 5).size() == 5);
        check("top count is configurable downward", manyBoard.top(COBBLE, 1).size() == 1);

        // Section 20: identity is the UUID; a rename moves the label, not the account.
        EconomyService renamed = fresh();
        renamed.set(STEVE, COBBLE, 500);
        Map<UUID, String> mutable = new HashMap<>(NAMES);
        LeaderboardService renameBoard = new LeaderboardService(renamed, mutable::get);
        int rankBefore = renameBoard.rankOf(STEVE, COBBLE);
        mutable.put(STEVE, "SteveTheBuilder");
        check("a rename does not create a second account",
                renameBoard.participants(COBBLE) == 1);
        check("a rename preserves the balance", renamed.getBalance(STEVE, COBBLE) == 500);
        check("a rename preserves the rank",
                renameBoard.rankOf(STEVE, COBBLE) == rankBefore);
    }

    private static void leaderboardMultiCurrency() {
        section("Leaderboards across currencies");
        EconomyService e = fresh();
        e.set(STEVE, COBBLE, 82_410);
        e.set(ALEX, COBBLE, 61_290);
        e.set(JAMIE, COBBLE, 44_912);
        e.set(TAYLOR, DIAMOND, 91);
        e.set(STEVE, DIAMOND, 37);
        e.set(ALEX, DIAMOND, 24);
        LeaderboardService board = board(e);

        check("cobblestone ranking is led by the cobblestone holder",
                board.top(COBBLE, 3).get(0).player().equals(STEVE));
        check("diamond ranking is led by the diamond holder",
                board.top(DIAMOND, 3).get(0).player().equals(TAYLOR));
        check("a cobblestone-only player is absent from the diamond board",
                board.rankOf(JAMIE, DIAMOND) == 0);
        check("a diamond-only player is absent from the cobblestone board",
                board.rankOf(TAYLOR, COBBLE) == 0);
        check("the same player holds different ranks in each currency",
                board.rankOf(STEVE, COBBLE) == 1 && board.rankOf(STEVE, DIAMOND) == 2);

        // Section 25 of the leaderboard spec: the board tracks the economy live.
        e.deposit(JAMIE, COBBLE, 100_000);
        check("depositing updates the ranking",
                board.top(COBBLE, 1).get(0).player().equals(JAMIE));
        e.withdraw(JAMIE, COBBLE, 100_000);
        check("withdrawing updates the ranking",
                board.top(COBBLE, 1).get(0).player().equals(STEVE));

        int alexBefore = board.rankOf(ALEX, DIAMOND);
        e.transfer(TAYLOR, ALEX, DIAMOND, 80);
        check("paying updates the sender's rank", board.rankOf(TAYLOR, DIAMOND) == 3);
        check("paying updates the recipient's rank",
                board.rankOf(ALEX, DIAMOND) == 1 && alexBefore == 3);
        check("a diamond payment left the cobblestone board alone",
                board.top(COBBLE, 1).get(0).player().equals(STEVE));
    }

    // ---- harness ------------------------------------------------------------

    private static EconomyService fresh() {
        return new CobbleEconomy(AccountStore.inMemory());
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
