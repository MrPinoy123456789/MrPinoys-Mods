package chartercurrency.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The earn endpoint's rules: Valor per award, its caps, and the Renown that follows it,
 * credited in one step under one lock so neither can be lost or doubled.
 *
 * <p>Order of an award, and why:
 * <ol>
 *   <li>Valor = base + perLevel * level, then the soft cap (halved once the pool is
 *       spent), then the hard cap (clipped to the room left).
 *   <li>Renown = round(valor paid * multiplier), then the checks that make a player
 *       ineligible (probation, the player's own daily Renown cap) run BEFORE a run key is
 *       claimed, so an ineligible first settler cannot use up a run and block an eligible
 *       teammate.
 *   <li>Run de-duplication: one credit per (kingdom, source, run), raised to the largest
 *       single award by topping up. A top-up counts against the topping player's cap.
 * </ol>
 */
public final class EarnService {

    /** One earn source from earn.json. 0 means no cap. */
    public record Source(String key, long base, long perLevel, long softCap, long hardCap,
                         double renownMultiplier) {}

    /** earn.json as a whole. */
    public record Config(Map<String, Source> sources, long perPlayerDailyCap,
                         long runWindowSeconds, int probationDays) {
        public Config {
            sources = Map.copyOf(sources);
        }
    }

    public enum Status { OK, UNKNOWN_SOURCE }

    /** What happened to the Renown part of an award. */
    public enum RenownOutcome {
        /** Renown was credited. */
        CREDITED,
        /** The player is still on probation for their kingdom. */
        PROBATION,
        /** The kingdom already got this run's Renown (or a larger one). */
        DUPLICATE_RUN,
        /** The player's daily Renown cap is spent. */
        DAILY_CAP,
        /** No Valor was paid, so there was nothing to turn into Renown. */
        NO_VALOR
    }

    /**
     * @param valorRaw     the formula's result before caps
     * @param valor        Valor actually paid
     * @param halved       the soft cap halved this award
     * @param hardCapped   the hard cap stopped or clipped this award
     * @param renown       Renown credited by this award
     * @param renownTarget the kingdom id, or {@link Treasury#COMMONS}
     * @param probationDaysLeft whole days until Renown starts counting, when on probation
     */
    public record Result(Status status, long valorRaw, long valor, boolean halved,
                         boolean hardCapped, long renown, String renownTarget,
                         RenownOutcome outcome, int probationDaysLeft) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private static final long DAY_MS = 86_400_000L;
    private static final long RUN_KEY_KEEP_MS = 12L * 3_600_000L;

    private record Claim(long firstMs, long amount) {}

    private final PlayerBook book;
    private final EconomyService economy;
    private final Treasury treasury;
    private final Currency valor;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Map<String, Claim> claims = new HashMap<>();
    private volatile Config config;

    public EarnService(PlayerBook book, EconomyService economy, Treasury treasury, Currency valor,
                       Config config, LongSupplier clock) {
        this.book = book;
        this.economy = economy;
        this.treasury = treasury;
        this.valor = valor;
        this.config = config;
        this.clock = clock;
    }

    public Config config() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    /**
     * Apply one award.
     *
     * @param kingdomId the player's kingdom as just resolved, or null for none
     * @param level     the level for the formula (0 when absent)
     * @param run       identifies one shared activity, or null
     * @param today     the day number in the configured time zone
     */
    public Result award(UUID player, String playerName, String kingdomId, String sourceKey,
                        int level, String run, long today) {
        Config cfg = config;
        Source src = sourceKey == null ? null : cfg.sources().get(sourceKey.toLowerCase());
        if (src == null) {
            return new Result(Status.UNKNOWN_SOURCE, 0, 0, false, false, 0, null,
                    RenownOutcome.NO_VALOR, 0);
        }
        // Lock order is always this service, then the book. The book lock is held for the
        // whole award because the records are mutated in place and the file writer reads
        // them under the same lock.
        synchronized (lock) {
          synchronized (book.monitor()) {
            long now = clock.getAsLong();
            long raw = Math.max(0, src.base() + src.perLevel() * Math.max(0, level));

            PlayerBook.Player p = book.player(player);
            PlayerBook.SourceDay sd = rollSource(p, src, today);

            // ---- Valor ----
            long v = raw;
            boolean halved = false;
            boolean hardCapped = false;
            if (src.softCap() > 0) {
                if (sd.softRemaining > 0) {
                    sd.softRemaining = Math.max(0, sd.softRemaining - v);
                } else {
                    v = Math.max(1, v / 2);
                    halved = v > 0 && raw > 0;
                }
            }
            if (src.hardCap() > 0) {
                long room = src.hardCap() - sd.earnedToday;
                if (room <= 0) {
                    v = 0;
                    hardCapped = true;
                } else if (v > room) {
                    v = room;
                    hardCapped = true;
                }
            }
            if (raw <= 0) v = 0;
            if (v > 0 && !economy.deposit(player, valor, v).ok()) v = 0;
            sd.earnedToday += v;
            long paid = v;
            if (paid > 0) book.addStat("valor-" + src.key(), today, paid);

            // ---- Renown ----
            String target = kingdomId == null ? Treasury.COMMONS : kingdomId.toLowerCase();
            Result result = renown(cfg, src, p, player, playerName, kingdomId, target, sourceKey,
                    level, run, today, now, raw, paid, halved, hardCapped);
            book.changed();
            return result;
          }
        }
    }

    private Result renown(Config cfg, Source src, PlayerBook.Player p, UUID player, String playerName,
                          String kingdomId, String target, String sourceKey, int level, String run,
                          long today, long now, long raw, long paid, boolean halved, boolean hardCapped) {
        // The kingdom the player is in now. A change restarts the probation clock.
        String current = kingdomId == null ? "" : kingdomId.toLowerCase();
        if (!Objects.equals(p.kingdom, current)) {
            p.kingdom = current;
            p.kingdomSince = now;
        }

        if (paid <= 0) {
            return new Result(Status.OK, raw, 0, halved, hardCapped, 0, target,
                    RenownOutcome.NO_VALOR, 0);
        }
        long candidate = Math.round(paid * src.renownMultiplier());
        if (candidate <= 0) {
            return new Result(Status.OK, raw, paid, halved, hardCapped, 0, target,
                    RenownOutcome.NO_VALOR, 0);
        }

        // Eligibility first: probation (kingdoms only), then the player's own daily cap.
        if (kingdomId != null) {
            long probationMs = (long) cfg.probationDays() * DAY_MS;
            long served = now - p.kingdomSince;
            if (served < probationMs) {
                int left = (int) Math.max(1, (probationMs - served + DAY_MS - 1) / DAY_MS);
                return new Result(Status.OK, raw, paid, halved, hardCapped, 0, target,
                        RenownOutcome.PROBATION, left);
            }
        }
        if (p.renownDay != today) {
            p.renownDay = today;
            p.renownToday = 0;
        }
        long room = cfg.perPlayerDailyCap() > 0 ? cfg.perPlayerDailyCap() - p.renownToday : Long.MAX_VALUE;
        if (room <= 0) {
            return new Result(Status.OK, raw, paid, halved, hardCapped, 0, target,
                    RenownOutcome.DAILY_CAP, 0);
        }

        // Only now claim (or top up) the run key.
        pruneClaims(now);
        String claimKey = target + "|" + sourceKey.toLowerCase() + "|"
                + (run != null && !run.isBlank() ? "run:" + run : "level:" + level);
        Claim existing = claims.get(claimKey);
        if (existing != null && (run == null || run.isBlank())
                && now - existing.firstMs() > cfg.runWindowSeconds() * 1000L) {
            existing = null; // a new run of the same level, outside the window
        }
        long already = existing == null ? 0 : existing.amount();
        long needed = Math.max(0, candidate - already);
        if (needed <= 0) {
            return new Result(Status.OK, raw, paid, halved, hardCapped, 0, target,
                    RenownOutcome.DUPLICATE_RUN, 0);
        }
        long credit = Math.min(needed, room);
        if (!treasury.creditRenown(target, credit, "earn", playerName, sourceKey + (existing == null ? "" : " (top-up)"))) {
            return new Result(Status.OK, raw, paid, halved, hardCapped, 0, target,
                    RenownOutcome.NO_VALOR, 0);
        }
        claims.put(claimKey, new Claim(existing == null ? now : existing.firstMs(), already + credit));
        p.renownToday += credit;
        return new Result(Status.OK, raw, paid, halved, hardCapped, credit, target,
                RenownOutcome.CREDITED, 0);
    }

    /** Roll one source's per-day record to today; refill the soft pool with carry-over. */
    private PlayerBook.SourceDay rollSource(PlayerBook.Player p, Source src, long today) {
        PlayerBook.SourceDay sd = p.sources.get(src.key());
        if (sd == null) {
            sd = new PlayerBook.SourceDay();
            sd.day = today;
            sd.softRemaining = src.softCap();
            p.sources.put(src.key(), sd);
            return sd;
        }
        long days = today - sd.day;
        if (days > 0) {
            sd.softRemaining = src.softCap() <= 0 ? 0
                    : Math.min(3 * src.softCap(), sd.softRemaining + days * src.softCap());
            sd.earnedToday = 0;
            sd.day = today;
        }
        return sd;
    }

    private void pruneClaims(long now) {
        if (claims.size() < 256) return;
        claims.values().removeIf(c -> now - c.firstMs() > RUN_KEY_KEEP_MS);
    }
}
