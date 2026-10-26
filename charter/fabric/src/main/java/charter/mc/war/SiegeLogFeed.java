package charter.mc.war;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.supply.Crossings;
import charter.siege.KeepSupply;
import charter.siege.SiegeBook;
import charter.siege.Store;
import charter.time.Durations;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The parts of the siege log (PLAN.md §8.3) that no history event carries: the
 * hourly {@code drain_hour} snapshot of every keep under siege, a {@code presence}
 * line when a keep's drain multiplier moves, and a {@code crossing} line for every
 * carrier that entered a besieged site or was turned back at a closed side. The
 * debrief tool ({@code charter/tools/siege_report.py}) reads them back.
 *
 * <p>Core computes every number; this only copies what the last pass left on the
 * keep's record.
 */
public final class SiegeLogFeed {

    /** Keep to the last siege hour a snapshot was written for. */
    private static final Map<CoreId, Long> LAST_HOUR = new HashMap<>();
    /** Keep to the multiplier last written, rounded to hundredths. */
    private static final Map<CoreId, Long> LAST_MULTIPLIER = new HashMap<>();

    private SiegeLogFeed() {
    }

    /** Hooks the sim's after-pass listener. Called when the runtime starts. */
    public static void attach(CharterRuntime rt) {
        LAST_HOUR.clear();
        LAST_MULTIPLIER.clear();
        rt.sim.afterPass(() -> afterPass(rt));
    }

    static void afterPass(CharterRuntime rt) {
        long siegeNow = rt.state.siegeClock.now();
        long wall = rt.clock.now();
        long hour = siegeNow / Durations.HOUR;
        for (Core keep : rt.state.cores()) {
            if (!keep.isKeep()) continue;
            SiegeBook.KeepSite site = rt.state.siege.keeps.get(keep.id);
            if (site == null || site.supply == KeepSupply.SUPPLIED) {
                LAST_HOUR.remove(keep.id);
                LAST_MULTIPLIER.remove(keep.id);
                continue;
            }
            long multiplier = Math.round(site.multiplier * 100);
            Long was = LAST_MULTIPLIER.put(keep.id, multiplier);
            if (was == null || was != multiplier) {
                JsonObject line = line(rt, "presence", keep, wall, siegeNow);
                line.addProperty("multiplier", multiplier / 100.0);
                line.addProperty("supply", site.supply.name());
                rt.siegeLog.write(line, wall);
            }
            Long last = LAST_HOUR.put(keep.id, hour);
            if (last != null && last == hour) continue;
            Store store = rt.state.siege.store(keep.id);
            double limit = rt.sim.rules().storageLimit(keep);
            double have = store.provisions.nourishment(rt.sim.food());
            JsonObject line = line(rt, "drain_hour", keep, wall, siegeNow);
            line.addProperty("provisions", round(have));
            line.addProperty("limit", round(limit));
            line.addProperty("percent", limit <= 0 ? 0 : round(100 * have / limit));
            line.addProperty("debt", round(store.eatDebt));
            line.addProperty("perHour", round(site.perHour));
            line.addProperty("multiplier", multiplier / 100.0);
            line.addProperty("mouths", site.mouths);
            line.addProperty("supply", site.supply.name());
            line.addProperty("starving", site.starving);
            rt.siegeLog.write(line, wall);
        }
    }

    /** A carrier's observation: written only when it entered a besieged site or was turned back. */
    public static void crossing(String carrier, String dimension, double x, double z, Crossings.Result result) {
        if (result != Crossings.Result.ENTERED && result != Crossings.Result.CLOSED) return;
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        ChunkKey chunk = new ChunkKey(dimension, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        var ref = rt.claims.at(chunk);
        long wall = rt.clock.now();
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(wall).toString());
        line.addProperty("siegeT", rt.state.siegeClock.now());
        line.addProperty("ev", "crossing");
        if (ref != null) {
            rt.state.claim(ref.claim()).ifPresent(claim -> {
                if (claim.core() != null) line.addProperty("site", claim.core().value().toString());
            });
            line.addProperty("owner", ref.owner().value().toString());
        }
        line.addProperty("carrier", carrier);
        line.addProperty("result", result == Crossings.Result.ENTERED ? "counted" : "refused");
        if (result == Crossings.Result.CLOSED) line.addProperty("reason", "not_open");
        line.addProperty("x", chunk.x());
        line.addProperty("z", chunk.z());
        rt.siegeLog.write(line, wall);
    }

    private static JsonObject line(CharterRuntime rt, String ev, Core keep, long wall, long siegeNow) {
        JsonObject line = new JsonObject();
        line.addProperty("t", Instant.ofEpochMilli(wall).toString());
        line.addProperty("siegeT", siegeNow);
        line.addProperty("ev", ev);
        line.addProperty("site", keep.id.value().toString());
        line.addProperty("owner", keep.owner.value().toString());
        return line;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
