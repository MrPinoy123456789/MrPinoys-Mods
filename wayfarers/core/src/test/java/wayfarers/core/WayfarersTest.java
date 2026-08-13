package wayfarers.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The dependency-free rules suite for Wayfarers. Plain {@code main}, no JUnit.
 *
 * <pre>
 *   javac --release 25 -d build core/src/main/java/wayfarers/core/*.java \
 *                               core/src/test/java/wayfarers/core/*.java
 *   java -cp build wayfarers.core.WayfarersTest
 * </pre>
 */
public final class WayfarersTest {

    private static int run = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        currencies();
        listing();
        wallet();
        purchase();
        linePools();
        encounterPool();
        wager();
        triggerRules();
        traderRecord();
        readOrCreate();

        int passed = run - failed;
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- currencies

    private static void currencies() {
        section("currencies");

        check("diamond resolves", Currencies.from("diamond"), Currencies.DIAMOND);
        check("cobblestone resolves", Currencies.from("cobblestone"), Currencies.COBBLESTONE);
        check("cobble alias resolves", Currencies.from("cobble"), Currencies.COBBLE);
        check("mixed case resolves", Currencies.from("DIAMOND"), Currencies.DIAMOND);
        check("whitespace is tolerated", Currencies.from("  cobblestone  "), Currencies.COBBLESTONE);
        check("emerald is invalid", Currencies.from("emerald"), null);
        check("null is invalid", Currencies.from(null), null);
        check("empty string is invalid", Currencies.from(""), null);
        check("diamond item id", Currencies.DIAMOND.itemId(), "minecraft:diamond");
        check("cobble item id", Currencies.COBBLESTONE.itemId(), "minecraft:cobblestone");
        check("isValid for diamond", Currencies.isValid("diamond"), true);
        check("isValid for emerald", Currencies.isValid("emerald"), false);
    }

    // ------------------------------------------------------------------ listing

    private static void listing() {
        section("listing");

        Listing diamond = new Listing("debris", "minecraft:ancient_debris", null, 1, 2, "diamond", 3, null);
        check("diamond listing enabled", diamond.enabled(), true);
        check("diamond currency type", diamond.currencyType(), Currencies.DIAMOND);
        check("diamond does not warn", diamond.priceWarns(), false);

        Listing cobble = new Listing("flesh", "minecraft:rotten_flesh", null, 32, 1, "cobblestone", 10, null);
        check("cobblestone listing enabled", cobble.enabled(), true);
        check("cobblestone does not warn under 640", cobble.priceWarns(), false);

        Listing expensiveCobble = new Listing("gravel", "minecraft:gravel", null, 64, 641, "cobble", 5, null);
        check("cobble over 640 warns", expensiveCobble.priceWarns(), true);

        Listing emerald = new Listing("stick", "minecraft:stick", null, 1, 1, "emerald", 1, null);
        check("emerald listing disabled", emerald.enabled(), false);
        check("emerald currency type", emerald.currencyType(), null);

        Listing updated = diamond.withStock(2);
        check("withStock lowers stock", updated.stock(), 2);
        check("original stock untouched", diamond.stock(), 3);

        Listing clamped = new Listing("x", "minecraft:stick", null, -1, -5, "diamond", -2, null);
        check("negative quantity clamped", clamped.quantity(), 0);
        check("negative price clamped", clamped.price(), 0);
        check("negative stock clamped", clamped.stock(), 0);
    }

    // -------------------------------------------------------------------- wallet

    private static void wallet() {
        section("wallet");

        Wallet w = new Wallet(10, 64);
        check("diamond getter", w.diamond(), 10);
        check("cobble getter", w.cobble(), 64);
        check("can afford diamond", w.canAfford(Currencies.DIAMOND, 10), true);
        check("cannot afford diamond over", w.canAfford(Currencies.DIAMOND, 11), false);
        check("can afford cobble", w.canAfford(Currencies.COBBLE, 64), true);

        w.add(Currencies.DIAMOND, 5);
        check("add diamonds", w.diamond(), 15);
        w.remove(Currencies.COBBLE, 4);
        check("remove cobble", w.cobble(), 60);
        w.add(Currencies.COBBLE, 4);
        check("add cobble via COBBLE alias", w.cobble(), 64);
    }

    // ----------------------------------------------------------------- purchase

    private static void purchase() {
        section("purchase");

        Listing sellShard = new Listing("shard", "minecraft:echo_shard", null, 1, 20, "diamond", 2, null);

        // Exact payment.
        Wallet player = new Wallet(20, 0);
        Purchase.Result exact = Purchase.buy(sellShard, player);
        check("exact payment succeeds", exact.outcome(), Purchase.Outcome.OK);
        check("exact plan cost", exact.plan().cost(), 20);
        check("exact plan currency", exact.plan().currency(), Currencies.DIAMOND);
        check("exact plan decrements stock", exact.plan().listing().stock(), 1);
        check("wallet untouched by planning", player.diamond(), 20);
        player.apply(exact.plan());
        check("wallet debited on apply", player.diamond(), 0);

        // Overpayment (player has more than enough).
        Wallet rich = new Wallet(50, 0);
        Purchase.Result over = Purchase.buy(sellShard, rich);
        check("overpayment succeeds", over.outcome(), Purchase.Outcome.OK);
        check("overpayment takes exactly the price", over.plan().cost(), 20);

        // Underpayment.
        Listing stillInStock = sellShard.withStock(2);
        Wallet poor = new Wallet(19, 0);
        Purchase.Result under = Purchase.buy(stillInStock, poor);
        check("underpayment refused", under.outcome(), Purchase.Outcome.INSUFFICIENT_PAYMENT);
        check("refusal leaves wallet alone", poor.diamond(), 19);
        check("refusal leaves listing stock", stillInStock.stock(), 2);

        // Zero stock.
        Listing empty = sellShard.withStock(0);
        Purchase.Result none = Purchase.buy(empty, new Wallet(100, 0));
        check("zero stock refused", none.outcome(), Purchase.Outcome.NO_STOCK);

        // Invalid currency.
        Listing bad = new Listing("bad", "minecraft:stick", null, 1, 1, "emerald", 1, null);
        Purchase.Result invalid = Purchase.buy(bad, new Wallet(10, 0));
        check("invalid currency refused", invalid.outcome(), Purchase.Outcome.INVALID_CURRENCY);

        // Sell to trader: trader pays player.
        Listing buyFlesh = new Listing("flesh", "minecraft:rotten_flesh", null, 32, 1, "diamond", 5, null);
        Wallet traderCoin = new Wallet(3, 0);
        Wallet seller = new Wallet(0, 0);
        Purchase.Result sale = Purchase.sell(buyFlesh, traderCoin, seller);
        check("sell succeeds", sale.outcome(), Purchase.Outcome.OK);
        check("sell plan is a credit to player", sale.plan().cost(), -1);
        check("sell is marked as buy", sale.plan().isBuy(), true);

        // Exhausted buyer coin.
        Purchase.Result broke = Purchase.sell(buyFlesh, new Wallet(0, 0), seller);
        check("exhausted coin refused", broke.outcome(), Purchase.Outcome.COIN_EXHAUSTED);
        check("exhausted coin leaves seller wallet", seller.diamond(), 0);

        // Apply a sell plan: trader loses, player gains.
        Wallet t = new Wallet(3, 0);
        Wallet p = new Wallet(0, 0);
        Purchase.Result ok = Purchase.sell(buyFlesh, t, p);
        t.remove(ok.plan().currency(), -ok.plan().cost());  // cost is -1, so remove 1
        p.apply(ok.plan());
        check("trader coin reduced", t.diamond(), 2);
        check("player coin increased", p.diamond(), 1);
    }

    // ---------------------------------------------------------------- line pools

    private static void linePools() {
        section("line pools");

        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("lost_trader.open", List.of("Hello, traveller."));
        raw.put("lost_trader.during", List.of("The road is long.", "Have you seen my pack?"));
        raw.put("open", List.of("A voice cuts the air."));

        LinePools pools = new LinePools(raw);
        check("pick from populated pool", pools.pick("lost_trader.open", new Random(1)), "Hello, traveller.");
        check("missing pool is silent", pools.pick("missing", new Random(1)), "");
        check("missing pool reports no has", pools.has("missing"), false);

        List<String> messy = new ArrayList<>();
        messy.add("real");
        messy.add("");
        messy.add("   ");
        messy.add(null);
        LinePools cleaned = new LinePools(Map.of("hit", messy));
        check("blank and null lines dropped", cleaned.pool("hit").size(), 1);

        check("pool key format", LinePools.key("lost_trader", "open"), "lost_trader.open");

        LinePools operator = new LinePools(Map.of("lost_trader.open", List.of("mine")));
        LinePools defaults = new LinePools(Map.of(
                "lost_trader.open", List.of("stock"),
                "lost_trader.during", List.of("stock during")));
        LinePools merged = operator.withDefaults(defaults);
        check("operator lines win", merged.pick("lost_trader.open", new Random(1)), "mine");
        check("missing pools fall back", merged.pick("lost_trader.during", new Random(1)), "stock during");

        LinePools scoped = new LinePools(Map.of(
                "lost_trader.exit_bribed", List.of("SPECIFIC"),
                "exit_grudge", List.of("SHARED")));
        check("pickFor behaviour wins", scoped.pickFor("lost_trader", "exit_bribed", new Random(1)), "SPECIFIC");
        check("pickFor falls back", scoped.pickFor("lost_trader", "exit_grudge", new Random(1)), "SHARED");
    }

    // --------------------------------------------------------------- encounter pool

    private static void encounterPool() {
        section("encounter pool");

        EncounterDefinition wayfarer = new EncounterDefinition("lost_trader", 20, "patient");
        EncounterDefinition witch = new EncounterDefinition("swamp_witch", 10, "patient");
        EncounterDefinition patrol = new EncounterDefinition("pillager_patrol", 10, "hostile");
        EncounterDefinition zero = new EncounterDefinition("invisible", 0, "patient");
        EncounterDefinition unknown = new EncounterDefinition("fake", 10, "not_a_template");

        check("drops defaults to none", patrol.drops(), "");
        check("drops is kept", new EncounterDefinition("patrol", 10, "hostile", List.of(), false,
                List.of(), false, false, new Body("minecraft:pillager", ""), 5, "", 0.0,
                List.of(), List.of(), 0, "", "minecraft:chests/pillager_outpost", Map.of()).drops(),
                "minecraft:chests/pillager_outpost");
        check("null drops normalises", new EncounterDefinition("patrol", 10, "hostile", List.of(), false,
                List.of(), false, false, new Body("minecraft:pillager", ""), 5, "", 0.0,
                List.of(), List.of(), 0, "", null, Map.of()).drops(), "");

        EncounterPool pool = new EncounterPool(
                List.of(wayfarer, witch, patrol, zero, unknown),
                java.util.Set.of("patient", "hostile"));

        Random r = new Random(1);
        Map<String, Integer> counts = new HashMap<>();
        int rolls = 1_000;
        for (int i = 0; i < rolls; i++) {
            EncounterDefinition picked = pool.select(TraderRecord.empty(), "minecraft:plains", false, "clear", r);
            if (picked != null) {
                counts.merge(picked.id(), 1, Integer::sum);
            }
        }
        check("wayfarer selected proportional", counts.getOrDefault("lost_trader", 0) > 400, true);
        check("witch selected proportional", counts.getOrDefault("swamp_witch", 0) > 150, true);
        check("patrol selected proportional", counts.getOrDefault("pillager_patrol", 0) > 150, true);
        check("zero weight never selected", counts.getOrDefault("invisible", 0), 0);
        check("unknown template never selected", counts.getOrDefault("fake", 0), 0);

        // Once filter.
        EncounterDefinition marvel = new EncounterDefinition("enderman_egg", 100, "patient");
        EncounterDefinition emptyRecord = new EncounterDefinition("enderman_egg", 100, "patient", List.of(), false, List.of(), true, false);
        EncounterPool oncePool = new EncounterPool(
                List.of(emptyRecord, wayfarer),
                java.util.Set.of("patient"));
        TraderRecord seen = TraderRecord.empty().sawMarvel("enderman_egg");
        for (int i = 0; i < 50; i++) {
            EncounterDefinition picked = oncePool.select(seen, "minecraft:plains", false, "clear", new Random(i));
            check("once encounter not re-seen", picked == null || !"enderman_egg".equals(picked.id()), true);
        }

        // Biome filter.
        EncounterDefinition swampOnly = new EncounterDefinition("swamp_witch", 10, "patient",
                List.of("minecraft:swamp"), false, List.of(), false, false);
        EncounterPool biomePool = new EncounterPool(List.of(swampOnly), java.util.Set.of("patient"));
        check("wrong biome not selected", biomePool.select(TraderRecord.empty(), "minecraft:plains", false, "clear", new Random(1)), null);
        check("right biome selected", biomePool.select(TraderRecord.empty(), "minecraft:swamp", false, "clear", new Random(1)).id(), "swamp_witch");

        // Night and weather.
        EncounterDefinition nightOnly = new EncounterDefinition("night_walker", 10, "patient",
                List.of(), true, List.of(), false, false);
        EncounterPool nightPool = new EncounterPool(List.of(nightOnly), java.util.Set.of("patient"));
        check("night only in day not selected", nightPool.select(TraderRecord.empty(), "minecraft:plains", false, "clear", new Random(1)), null);
        check("night only at night selected", nightPool.select(TraderRecord.empty(), "minecraft:plains", true, "clear", new Random(1)).id(), "night_walker");

        EncounterDefinition rainOnly = new EncounterDefinition("drowned_sailor", 10, "patient",
                List.of(), false, List.of("rain"), false, false);
        EncounterPool weatherPool = new EncounterPool(List.of(rainOnly), java.util.Set.of("patient"));
        check("wrong weather not selected", weatherPool.select(TraderRecord.empty(), "minecraft:plains", false, "clear", new Random(1)), null);
        check("right weather selected", weatherPool.select(TraderRecord.empty(), "minecraft:plains", false, "rain", new Random(1)).id(), "drowned_sailor");
    }

    // -------------------------------------------------------------------- wager

    private static void wager() {
        section("wager");

        Wager w = new Wager(10, 2.0, 20, "raid");
        check("payout at 2.0 odds", w.payout(), 20);

        Wager zero = new Wager(0, 2.0, 20, "nothing");
        check("zero stake pays nothing", zero.payout(), 0);

        Wager badOdds = new Wager(10, -1.0, 5, "nothing");
        check("negative odds pay nothing", badOdds.payout(), 0);
    }

    // ---------------------------------------------------------------- trigger rules

    private static void triggerRules() {
        section("trigger rules");

        long t0 = 1_000_000L;
        long now = t0 + mins(60);
        TriggerRules.Settings s = new TriggerRules.Settings(
                true, 120, 1, 25, 60, 2, 128, 0.5);

        TriggerRules.PlayerState ready = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(5), 0, false, 0, false);
        check("a ready player triggers with a winning roll",
                TriggerRules.decide(s, ready, now, 0, 0.0), TriggerRules.Decision.TRIGGER);
        check("a losing roll fails",
                TriggerRules.decide(s, ready, now, 0, 0.5), TriggerRules.Decision.ROLL_FAILED);

        TriggerRules.Settings off = new TriggerRules.Settings(
                false, 120, 1, 25, 60, 2, 128, 1.0);
        check("disabled short-circuits everything",
                TriggerRules.decide(off, ready, now, 0, 0.0), TriggerRules.Decision.DISABLED);

        TriggerRules.PlayerState early = new TriggerRules.PlayerState(
                t0, now - secs(30), now - secs(5), 0, false, 0, false);
        check("not time yet",
                TriggerRules.decide(s, early, now, 0, 0.0), TriggerRules.Decision.NOT_TIME_YET);

        TriggerRules.PlayerState busy = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(5), 0, true, 0, false);
        check("already in event",
                TriggerRules.decide(s, busy, now, 0, 0.0), TriggerRules.Decision.ALREADY_IN_EVENT);

        TriggerRules.PlayerState graced = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(5), 0, false, now + secs(10), false);
        check("in grace",
                TriggerRules.decide(s, graced, now, 0, 0.0), TriggerRules.Decision.IN_GRACE);

        TriggerRules.PlayerState fresh = new TriggerRules.PlayerState(
                now - secs(30), now - secs(200), now - secs(5), 0, false, 0, false);
        check("session too young",
                TriggerRules.decide(s, fresh, now, 0, 0.0), TriggerRules.Decision.SESSION_TOO_YOUNG);

        TriggerRules.PlayerState afk = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(120), 0, false, 0, false);
        check("inactive",
                TriggerRules.decide(s, afk, now, 0, 0.0), TriggerRules.Decision.INACTIVE);

        TriggerRules.PlayerState cooling = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(5), now - mins(10), false, 0, false);
        check("on cooldown",
                TriggerRules.decide(s, cooling, now, 0, 0.0), TriggerRules.Decision.ON_COOLDOWN);
        check("cooldown has lapsed",
                TriggerRules.decide(s, new TriggerRules.PlayerState(
                        t0, now - secs(200), now - secs(5), now - mins(26), false, 0, false), now, 0, 0.0),
                TriggerRules.Decision.TRIGGER);

        TriggerRules.PlayerState near = new TriggerRules.PlayerState(
                t0, now - secs(200), now - secs(5), 0, false, 0, true);
        check("proximity gate",
                TriggerRules.decide(s, near, now, 0, 0.0), TriggerRules.Decision.PROXIMITY);

        check("server cap blocks",
                TriggerRules.decide(s, ready, now, 2, 0.0), TriggerRules.Decision.SERVER_BUSY);
    }

    // --------------------------------------------------------------- trader record

    private static void traderRecord() {
        section("trader record");

        TraderRecord base = TraderRecord.empty();
        check("empty record has no meetings", base.meetings(), 0);
        check("empty record has no marvels", base.marvelsSeen().isEmpty(), true);

        TraderRecord afterMarvel = base.sawMarvel("enderman_egg");
        check("sawMarvel adds the id", afterMarvel.marvelsSeen().contains("enderman_egg"), true);
        check("sawMarvel does not mutate original", base.marvelsSeen().contains("enderman_egg"), false);

        TraderRecord afterMeeting = afterMarvel.withMeeting("ostvold", 1_000L);
        check("meeting increments", afterMeeting.meetings(), 1);
        check("lastMetAt set", afterMeeting.lastMetAt(), 1_000L);
        check("traderId set", afterMeeting.traderId(), "ostvold");
    }

    // ---------------------------------------------------------------- read or create

    private static void readOrCreate() throws Exception {
        section("read or create");

        Path tmp = Files.createTempDirectory("wayfarers-test");
        Path missing = tmp.resolve("missing.json");

        List<String> logs = new ArrayList<>();
        ReadOrCreate.Parser<String> p = s -> {
            if (s.startsWith("not a string")) throw new IllegalStateException("bad");
            return s;
        };
        ReadOrCreate.Renderer<String> r = s -> s;

        ReadOrCreate.Result<String> created = ReadOrCreate.load(missing, "default", p, r, logs::add);
        check("missing file creates", created.outcome(), ReadOrCreate.Outcome.CREATED);
        check("missing file uses defaults", created.value(), "default");
        check("missing file wrote defaults", Files.readString(missing), "default");

        Path broken = tmp.resolve("broken.json");
        Files.writeString(broken, "not a string parser result");
        ReadOrCreate.Result<String> kept = ReadOrCreate.load(broken, "default", p, r, logs::add);
        check("broken file kept", kept.outcome(), ReadOrCreate.Outcome.KEPT_BROKEN);
        check("broken file uses defaults", kept.value(), "default");
        check("broken file on disk left", Files.readString(broken), "not a string parser result");

        Path good = tmp.resolve("good.json");
        Files.writeString(good, "operator");
        ReadOrCreate.Result<String> loaded = ReadOrCreate.load(good, "default", p, r, logs::add);
        check("good file loaded", loaded.outcome(), ReadOrCreate.Outcome.LOADED);
        check("good file value", loaded.value(), "operator");
    }

    // --------------------------------------------------------------------- helpers

    private static long secs(int s) { return s * 1000L; }
    private static long mins(int m) { return m * 60_000L; }

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name);
    }

    private static void check(String what, Object actual, Object expected) {
        run++;
        if (actual == null ? expected == null : actual.equals(expected)) {
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + "  (expected " + expected + ", got " + actual + ")");
        }
    }
}
