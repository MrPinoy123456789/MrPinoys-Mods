package chartercurrency.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Kingdom (organization) coin, held in Marks, under the same rules as player balances:
 * never negative, never partly executed, every movement in a ledger.
 *
 * <p>This class only holds and moves money. Who may spend it is a rights question that
 * Charter owns; {@link #mayWithdraw} is the one permission check, and widening it later
 * changes that method and nothing else.
 *
 * <p>Thread safe. State is plain data ({@link State}) so the fabric side can write it
 * as JSON under {@link #locked}.
 */
public final class Treasury {

    /** How many ledger entries each organization keeps in memory and on disk. */
    public static final int LEDGER_LIMIT = 500;

    /** One line of an organization's ledger, coin or otherwise (Renown shares it). */
    public static final class Entry {
        public long at;
        /** {@code coin} or {@code renown}. */
        public String currency;
        public String kind;
        /** Signed change in the currency's own unit. */
        public long delta;
        public long balanceAfter;
        public String actor;
        public String note;

        public Entry() {}
    }

    /** One organization's stored state. */
    public static final class Org {
        public long treasury;
        public long taxCollected;
        public long renown;
        public long renownLifetime;
        /** Catalogue key to times bought. */
        public Map<String, Integer> bought = new java.util.LinkedHashMap<>();
        public List<Entry> ledger = new ArrayList<>();
    }

    /** The file shape of {@code kingdoms_state.json}. */
    public static final class State {
        public Map<String, Org> orgs = new TreeMap<>();
    }

    private final State state;
    private final Runnable onChange;
    private final java.util.function.LongSupplier clock;
    private final Object lock = new Object();

    public Treasury(State initial, Runnable onChange, java.util.function.LongSupplier clock) {
        this.state = initial == null ? new State() : initial;
        if (this.state.orgs == null) this.state.orgs = new TreeMap<>();
        for (Org org : this.state.orgs.values()) {
            if (org.ledger == null) org.ledger = new ArrayList<>();
        }
        this.onChange = onChange;
        this.clock = clock;
    }

    public Treasury() {
        this(new State(), () -> { }, System::currentTimeMillis);
    }

    /** Run something against the state while holding the lock (serialisation). */
    public <T> T locked(java.util.function.Function<State, T> action) {
        synchronized (lock) {
            return action.apply(state);
        }
    }

    // ---- permission ---------------------------------------------------------

    /** Only a Lord of the organization (either one, when it has two), or an op, may take coin out. */
    public static boolean mayWithdraw(KingdomSource kingdoms, String orgId, UUID player, boolean op) {
        if (op) return true;
        if (kingdoms == null || orgId == null || player == null) return false;
        return kingdoms.lordsOf(orgId).contains(player);
    }

    /**
     * May this player take {@code marks} of coin out of the treasury? An op always may. When
     * Charter governs spending the answer is no for everyone else: the one door is Charter's
     * Withdraw. Otherwise the player needs a spend right whose limit covers the amount.
     */
    public static boolean mayWithdrawCoin(KingdomSource kingdoms, String orgId, UUID player, boolean op,
                                          long marks) {
        if (op) return true;
        if (kingdoms == null || orgId == null || player == null) return false;
        if (kingdoms.governsSpending()) return false;
        java.util.OptionalLong limit = kingdoms.spendLimit(orgId, player);
        return limit.isPresent() && marks <= limit.getAsLong();
    }

    // ---- reads --------------------------------------------------------------

    public long balance(String orgId) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            return org == null ? 0L : org.treasury;
        }
    }

    public long taxCollected(String orgId) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            return org == null ? 0L : org.taxCollected;
        }
    }

    /** Sum of every treasury, saturating. Counted in the coin supply. */
    public long totalSupply() {
        synchronized (lock) {
            long sum = 0;
            for (Org org : state.orgs.values()) {
                if (sum > Long.MAX_VALUE - org.treasury) return Long.MAX_VALUE;
                sum += org.treasury;
            }
            return sum;
        }
    }

    /** Organization ids that hold or have held anything. */
    public List<String> orgIds() {
        synchronized (lock) {
            return List.copyOf(state.orgs.keySet());
        }
    }

    /** Newest first, optionally only one currency ({@code coin} or {@code renown}). */
    public List<Entry> ledger(String orgId, String currencyFilter) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            List<Entry> out = new ArrayList<>();
            if (org == null) return out;
            for (int i = org.ledger.size() - 1; i >= 0; i--) {
                Entry e = org.ledger.get(i);
                if (currencyFilter == null || currencyFilter.equalsIgnoreCase(e.currency)) out.add(e);
            }
            return out;
        }
    }

    // ---- writes -------------------------------------------------------------

    /** Add Marks (a deposit, the Mint tax's destination, an admin top-up). */
    public boolean credit(String orgId, long marks, String kind, String actor, String note) {
        if (marks <= 0 || orgId == null || orgId.isBlank()) return false;
        synchronized (lock) {
            Org org = org(orgId);
            if (org.treasury > Long.MAX_VALUE - marks) return false;
            org.treasury += marks;
            append(org, "coin", kind, marks, org.treasury, actor, note);
        }
        onChange.run();
        return true;
    }

    /** Take exactly {@code marks} or nothing. False if the balance is short. */
    public boolean charge(String orgId, long marks, String kind, String actor, String note) {
        if (marks <= 0 || orgId == null || orgId.isBlank()) return false;
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            if (org == null || org.treasury < marks) return false;
            org.treasury -= marks;
            append(org, "coin", kind, -marks, org.treasury, actor, note);
        }
        onChange.run();
        return true;
    }

    /** Mint tax: a credit that also counts toward the organization's lifetime tax. */
    public boolean collectTax(String orgId, long marks, String actor) {
        if (marks <= 0 || orgId == null || orgId.isBlank()) return false;
        synchronized (lock) {
            Org org = org(orgId);
            if (org.treasury > Long.MAX_VALUE - marks) return false;
            org.treasury += marks;
            org.taxCollected += marks;
            append(org, "coin", "mint_tax", marks, org.treasury, actor, "Mint tax");
        }
        onChange.run();
        return true;
    }

    /** Append a non-coin ledger line (Renown shares the ledger). */
    public void record(String orgId, String currency, String kind, long delta, long balanceAfter,
                       String actor, String note) {
        synchronized (lock) {
            append(org(orgId), currency, kind, delta, balanceAfter, actor, note);
        }
        onChange.run();
    }

    // ---- Renown -------------------------------------------------------------

    /** The ledger id for Renown earned by players who are in no kingdom. */
    public static final String COMMONS = "commons";

    /** One thing a kingdom can buy with Renown (renown_catalog.json). */
    public record CatalogEntry(String key, String name, String description, long cost,
                               boolean repeatable, String requires, List<String> commands) {
        public CatalogEntry {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    public enum BuyStatus { OK, INSUFFICIENT, ALREADY_BOUGHT, REQUIRES_MISSING, UNKNOWN }

    public long renown(String orgId) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            return org == null ? 0L : org.renown;
        }
    }

    public long renownLifetime(String orgId) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            return org == null ? 0L : org.renownLifetime;
        }
    }

    /** How many times a catalogue key has been bought. */
    public int bought(String orgId, String key) {
        synchronized (lock) {
            Org org = state.orgs.get(key(orgId));
            return org == null ? 0 : org.bought.getOrDefault(key, 0);
        }
    }

    /** Renown earned by play: counts toward lifetime. */
    public boolean creditRenown(String orgId, long amount, String kind, String actor, String note) {
        if (amount <= 0 || orgId == null || orgId.isBlank()) return false;
        synchronized (lock) {
            Org org = org(orgId);
            if (org.renown > Long.MAX_VALUE - amount || org.renownLifetime > Long.MAX_VALUE - amount) return false;
            org.renown += amount;
            org.renownLifetime += amount;
            append(org, "renown", kind, amount, org.renown, actor, note);
        }
        onChange.run();
        return true;
    }

    /**
     * Admin adjustment. An add is not "earned" (a refund must not inflate the lifetime
     * ranking); a remove that would go negative is refused.
     */
    public boolean adjustRenown(String orgId, long delta, String actor) {
        if (delta == 0 || orgId == null || orgId.isBlank()) return false;
        synchronized (lock) {
            Org org = org(orgId);
            if (delta < 0 && org.renown < -delta) return false;
            if (delta > 0 && org.renown > Long.MAX_VALUE - delta) return false;
            org.renown += delta;
            append(org, "renown", delta > 0 ? "admin_add" : "admin_remove", delta, org.renown, actor, "");
        }
        onChange.run();
        return true;
    }

    /**
     * Spend Renown on a catalogue entry: balance, once-only and prerequisite checks and
     * the deduction happen under one lock, so concurrent buys can never overspend.
     */
    public BuyStatus buy(String orgId, CatalogEntry entry, String actor) {
        if (entry == null || orgId == null || orgId.isBlank()) return BuyStatus.UNKNOWN;
        BuyStatus result;
        synchronized (lock) {
            Org org = org(orgId);
            int have = org.bought.getOrDefault(entry.key(), 0);
            if (!entry.repeatable() && have > 0) {
                result = BuyStatus.ALREADY_BOUGHT;
            } else if (entry.requires() != null && !entry.requires().isBlank()
                    && org.bought.getOrDefault(entry.requires(), 0) <= 0) {
                result = BuyStatus.REQUIRES_MISSING;
            } else if (org.renown < entry.cost()) {
                result = BuyStatus.INSUFFICIENT;
            } else {
                org.renown -= entry.cost();
                org.bought.merge(entry.key(), 1, Integer::sum);
                append(org, "renown", "purchase", -entry.cost(), org.renown, actor, entry.key());
                result = BuyStatus.OK;
            }
        }
        if (result == BuyStatus.OK) onChange.run();
        return result;
    }

    // ---- internals ----------------------------------------------------------

    private Org org(String orgId) {
        return state.orgs.computeIfAbsent(key(orgId), k -> new Org());
    }

    private void append(Org org, String currency, String kind, long delta, long after,
                        String actor, String note) {
        Entry e = new Entry();
        e.at = clock.getAsLong();
        e.currency = currency;
        e.kind = kind;
        e.delta = delta;
        e.balanceAfter = after;
        e.actor = actor;
        e.note = note;
        org.ledger.add(e);
        while (org.ledger.size() > LEDGER_LIMIT) org.ledger.remove(0);
    }

    private static String key(String orgId) {
        return orgId == null ? "" : orgId.toLowerCase(Locale.ROOT);
    }
}
