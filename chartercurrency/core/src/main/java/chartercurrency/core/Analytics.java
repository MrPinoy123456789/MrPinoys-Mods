package chartercurrency.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Machine readable analytics for the economy (SPEC section 10): one JSON object per line
 * for every movement of money, and one snapshot line a day. This class is the pure part:
 * the line format, the snapshot maths and the day's flows, with no files and no clock.
 * The fabric side captures the numbers, writes the files and never lets a failure reach
 * a player.
 *
 * <p>Coin amounts are always canonical Marks (a Gold Sovereign is 64 Marks). Valor and
 * Renown are in their own units.
 */
public final class Analytics {

    private Analytics() {}

    // ---- event types ----

    public static final String STRIKE = "strike";
    public static final String DEPOSIT = "deposit";
    public static final String WITHDRAW = "withdraw";
    public static final String PAY = "pay";
    public static final String SHOP_PURCHASE = "shop_purchase";
    public static final String TREASURY_DEPOSIT = "treasury_deposit";
    public static final String TREASURY_WITHDRAW = "treasury_withdraw";
    public static final String TREASURY_PAY = "treasury_pay";
    public static final String TREASURY_GIVE = "treasury_give";
    public static final String TREASURY_ADMIN = "treasury_admin";
    public static final String CHARTER_CHARGE = "charter_charge";
    public static final String CHARTER_CREDIT = "charter_credit";
    public static final String ADMIN_BALANCE = "admin_balance";
    public static final String VALOR_EARN = "valor_earn";
    public static final String RENOWN_BUY = "renown_buy";
    public static final String RENOWN_ADMIN = "renown_admin";
    /** Coin another mod took from a player (Fortune's stake). */
    public static final String EXTERNAL_CHARGE = "external_charge";

    /** The coin currency id, as events and snapshots name it. */
    public static final String COIN = "coin";

    /** Marks in one Gold Sovereign. */
    public static final long MARKS_PER_SOVEREIGN = 64;

    /** How many of the largest balances a snapshot lists and measures. */
    public static final int TOP = 10;

    /** The file a day's event lines go to. */
    public static String eventFile(LocalDate day) {
        return "events-" + day + ".jsonl";
    }

    public static final String SNAPSHOT_FILE = "snapshots.jsonl";

    // ---- events ----

    /**
     * One event line, built field by field in the order the line shows them. The
     * recorder adds {@code ts} and, when the event does not name one, the player's
     * kingdom.
     */
    public static final class Event {
        private final String type;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private UUID player;
        private String kingdom;
        private boolean kingdomSet;

        private Event(String type) {
            this.type = type;
        }

        public static Event of(String type) {
            return new Event(type);
        }

        public String type() {
            return type;
        }

        /** The player the movement belongs to; the recorder looks up their kingdom. */
        public Event player(UUID id, String name) {
            this.player = id;
            fields.put("player", id == null ? null : id.toString());
            fields.put("name", name);
            return this;
        }

        public UUID playerId() {
            return player;
        }

        /** The kingdom, when the event already knows it (a treasury, a tax). Null is "none". */
        public Event kingdom(String kingdom) {
            this.kingdom = kingdom;
            this.kingdomSet = true;
            return this;
        }

        public boolean hasKingdom() {
            return kingdomSet;
        }

        public Event currency(String currency) {
            fields.put("currency", currency);
            return this;
        }

        /** Marks for coin; the currency's own unit otherwise. */
        public Event amount(long amount) {
            fields.put("amount", amount);
            return this;
        }

        public Event balanceAfter(long balance) {
            fields.put("balanceAfter", balance);
            return this;
        }

        public Event put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        /** The line, with the time and the kingdom (as given here, or as looked up). */
        public String toJson(long ts, String lookedUpKingdom) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ts", ts);
            out.put("type", type);
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (e.getKey().equals("player") || e.getKey().equals("name")) out.put(e.getKey(), e.getValue());
            }
            out.put("kingdom", kingdomSet ? kingdom : lookedUpKingdom);
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (!e.getKey().equals("player") && !e.getKey().equals("name")) out.put(e.getKey(), e.getValue());
            }
            return json(out);
        }
    }

    // ---- the day's flows, read back from its event lines ----

    /**
     * What a day's event lines add up to: coin made or destroyed other than at the Mint,
     * and shop sales per listing. The Mint and its tax come from the players' own daily
     * totals instead, which are kept whether or not analytics is on.
     */
    public static final class DayFlows {
        public final Map<String, Long> created = new TreeMap<>();
        public final Map<String, Long> destroyed = new TreeMap<>();
        public final Map<String, Sales> shop = new TreeMap<>();
        public long events;

        /** One listing's sales in a day. */
        public static final class Sales {
            public long purchases;
            public long items;
            public final Map<String, Long> revenue = new TreeMap<>();
        }

        /** Adds one event line, already parsed into plain maps, lists, strings and numbers. */
        public void add(Map<String, Object> event) {
            events++;
            String type = String.valueOf(event.get("type"));
            String currency = event.get("currency") == null ? null : String.valueOf(event.get("currency"));
            long amount = number(event.get("amount"));
            boolean coin = COIN.equals(currency);
            switch (type) {
                case SHOP_PURCHASE -> {
                    String key = String.valueOf(event.get("item"));
                    Sales sales = shop.computeIfAbsent(key, k -> new Sales());
                    sales.purchases++;
                    sales.items += number(event.get("quantity"));
                    if (event.get("price") instanceof Map<?, ?> price) {
                        for (Map.Entry<?, ?> p : price.entrySet()) {
                            long paid = number(p.getValue());
                            sales.revenue.merge(String.valueOf(p.getKey()), paid, Long::sum);
                            if (COIN.equals(String.valueOf(p.getKey()))) bump(destroyed, "shop", paid);
                        }
                    }
                }
                case CHARTER_CHARGE -> {
                    if (coin) bump(destroyed, "charter", amount);
                }
                case CHARTER_CREDIT -> {
                    if (coin) bump(created, "charter", amount);
                }
                case ADMIN_BALANCE, TREASURY_ADMIN -> {
                    if (!coin) return;
                    long delta = number(event.get("delta"));
                    if (delta > 0) bump(created, "admin", delta);
                    else if (delta < 0) bump(destroyed, "admin", -delta);
                }
                default -> { }
            }
        }

        private static void bump(Map<String, Long> map, String key, long amount) {
            if (amount > 0) map.merge(key, amount, Long::sum);
        }
    }

    /** A number from a parsed line: integral values as they are, anything else as 0. */
    static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        return 0;
    }

    // ---- concentration ----

    /** One banked balance, for the top ten. */
    public record Holding(UUID player, String name, long marks) {}

    /**
     * How concentrated the banked coin is.
     *
     * @param accounts   accounts holding any banked coin
     * @param total      their banked coin
     * @param topMarks   held by the largest {@link #TOP}
     * @param topShare   that share of the total, 0 to 1
     * @param gini       0 when every account holds the same, towards 1 when one holds it all
     */
    public record Concentration(int accounts, long total, long topMarks, double topShare, double gini) {}

    public static Concentration concentration(Collection<Long> balances) {
        List<Long> held = new ArrayList<>();
        for (Long b : balances) if (b != null && b > 0) held.add(b);
        held.sort(Comparator.naturalOrder());
        int n = held.size();
        if (n == 0) return new Concentration(0, 0, 0, 0, 0);
        double total = 0;
        double weighted = 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            long x = held.get(i);
            total += x;
            weighted += (double) (i + 1) * x;
            sum = sum > Long.MAX_VALUE - x ? Long.MAX_VALUE : sum + x;
        }
        long top = 0;
        for (int i = n - 1; i >= Math.max(0, n - TOP); i--) {
            long x = held.get(i);
            top = top > Long.MAX_VALUE - x ? Long.MAX_VALUE : top + x;
        }
        double gini = n == 1 ? 0 : (2 * weighted) / (n * total) - (n + 1.0) / n;
        return new Concentration(n, sum, top, round(top / total), round(Math.max(0, gini)));
    }

    /** The largest {@link #TOP} holdings, largest first; ties by name. */
    public static List<Holding> top(Collection<Holding> holdings) {
        List<Holding> sorted = new ArrayList<>();
        for (Holding h : holdings) if (h.marks() > 0) sorted.add(h);
        sorted.sort(Comparator.comparingLong(Holding::marks).reversed()
                .thenComparing(h -> h.name() == null ? "" : h.name()));
        return sorted.size() > TOP ? new ArrayList<>(sorted.subList(0, TOP)) : sorted;
    }

    // ---- the snapshot ----

    /** One kingdom treasury in a snapshot. */
    public record KingdomRow(String id, String name, long treasury, long taxCollected, long renown) {}

    /**
     * Everything a snapshot reads from the running server, captured at one moment.
     *
     * @param day            the day the numbers belong to (the one just ended, at rollover)
     * @param reason         {@code rollover} or {@code startup}
     * @param banked         every banked coin balance, in Marks
     * @param treasuries     every kingdom treasury, in Marks
     * @param carriedOnline  coin carried by online players only, in Marks
     * @param onlinePlayers  how many players that counted
     * @param holdings       every banked coin balance, by player
     * @param minted         coin struck at the Mint that day, in Marks
     * @param taxCollected   Mint tax paid to kingdoms that day
     * @param taxDestroyed   Mint tax struck by players in no kingdom, so kept by nobody
     * @param valorInAccounts every banked Valor balance
     * @param valorBySource  Valor earned that day, by earn source
     * @param kingdoms       each treasury
     */
    public record SnapshotInput(long ts, LocalDate day, String reason, long banked, long treasuries,
                                long carriedOnline, int onlinePlayers, List<Holding> holdings,
                                long minted, long taxCollected, long taxDestroyed,
                                long valorInAccounts, Map<String, Long> valorBySource,
                                List<KingdomRow> kingdoms) {}

    /** The snapshot line. {@code flows} is the day's event lines added up, or empty when there were none. */
    public static String snapshot(SnapshotInput in, DayFlows flows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", in.ts());
        out.put("day", in.day().toString());
        out.put("reason", in.reason());

        Map<String, Object> coin = new LinkedHashMap<>();
        coin.put("banked", in.banked());
        coin.put("treasuries", in.treasuries());
        coin.put("carriedOnline", in.carriedOnline());
        coin.put("onlinePlayers", in.onlinePlayers());
        coin.put("carriedNote", "carriedOnline counts the main inventory of players online at the time;"
                + " coin carried by offline players, and coin in chests, is not counted");
        coin.put("counted", saturatingSum(in.banked(), in.treasuries(), in.carriedOnline()));
        out.put("coin", coin);

        Map<String, Long> created = new TreeMap<>(flows.created);
        if (in.minted() > 0) created.put("mint", in.minted());
        Map<String, Long> destroyed = new TreeMap<>(flows.destroyed);
        if (in.taxDestroyed() > 0) destroyed.put("mintTax", in.taxDestroyed());
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("minted", in.minted());
        day.put("taxCollected", in.taxCollected());
        day.put("taxDestroyed", in.taxDestroyed());
        day.put("created", saturatingSum(created.values()));
        day.put("createdBy", created);
        day.put("destroyed", saturatingSum(destroyed.values()));
        day.put("destroyedBy", destroyed);
        day.put("eventLines", flows.events);
        out.put("today", day);

        Concentration c = concentration(in.holdings().stream().map(Holding::marks).toList());
        out.put("accounts", c.accounts());
        List<Object> top = new ArrayList<>();
        for (Holding h : top(in.holdings())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", h.player().toString());
            row.put("name", h.name());
            row.put("marks", h.marks());
            top.add(row);
        }
        out.put("top", top);
        Map<String, Object> conc = new LinkedHashMap<>();
        conc.put("over", "banked coin balances");
        conc.put("topMarks", c.topMarks());
        conc.put("topShare", c.topShare());
        conc.put("gini", c.gini());
        out.put("concentration", conc);

        Map<String, Object> valor = new LinkedHashMap<>();
        valor.put("inAccounts", in.valorInAccounts());
        valor.put("earnedBySource", new TreeMap<>(in.valorBySource()));
        valor.put("earned", saturatingSum(in.valorBySource().values()));
        out.put("valor", valor);

        List<Object> kingdoms = new ArrayList<>();
        for (KingdomRow k : in.kingdoms()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", k.id());
            row.put("name", k.name());
            row.put("treasury", k.treasury());
            row.put("taxCollected", k.taxCollected());
            row.put("renown", k.renown());
            kingdoms.add(row);
        }
        out.put("kingdoms", kingdoms);

        Map<String, Object> shop = new LinkedHashMap<>();
        for (Map.Entry<String, DayFlows.Sales> e : flows.shop.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("purchases", e.getValue().purchases);
            row.put("items", e.getValue().items);
            row.put("revenue", e.getValue().revenue);
            shop.put(e.getKey(), row);
        }
        out.put("shop", shop);
        return json(out);
    }

    private static long saturatingSum(long... values) {
        long sum = 0;
        for (long v : values) sum = sum > Long.MAX_VALUE - v ? Long.MAX_VALUE : sum + v;
        return sum;
    }

    private static long saturatingSum(Collection<Long> values) {
        long sum = 0;
        for (long v : values) sum = sum > Long.MAX_VALUE - v ? Long.MAX_VALUE : sum + v;
        return sum;
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    // ---- showing coin ----

    /**
     * Marks as Gold Sovereigns with up to two decimals and group separators:
     * {@code 79008} is {@code 1,234.5}. The owner's rule is to quote coin in Sovereigns.
     */
    public static String sovereigns(long marks) {
        long whole = marks / MARKS_PER_SOVEREIGN;
        long hundredths = Math.round(Math.abs(marks % MARKS_PER_SOVEREIGN) * 100.0 / MARKS_PER_SOVEREIGN);
        if (hundredths == 100) {
            whole += marks < 0 ? -1 : 1;
            hundredths = 0;
        }
        String out = Wallet.format(whole);
        if (marks < 0 && whole == 0) out = "-0";
        if (hundredths == 0) return out;
        String frac = hundredths < 10 ? "0" + hundredths : Long.toString(hundredths);
        if (frac.endsWith("0")) frac = frac.substring(0, 1);
        return out + "." + frac;
    }

    // ---- json ----

    /** A small JSON writer: maps (in their order), lists, strings, numbers, booleans and null. */
    public static String json(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) (double) d);
            else out.append(d);
        } else if (value instanceof Float f) {
            write(out, (double) f);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) out.append(',');
                first = false;
                write(out, o);
            }
            out.append(']');
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
