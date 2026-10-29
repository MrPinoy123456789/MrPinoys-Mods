package charter.mc.war;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
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

    private SiegeLogFeed() {
    }

    /** Hooks the sim's after-pass listener. Called when the runtime starts. */
    public static void attach(CharterRuntime rt) {
        LAST_HOUR.clear();
        rt.sim.afterPass(() -> afterPass(rt));
    }

    static void afterPass(CharterRuntime rt) {
        long siegeNow = rt.state.siegeClock.now();
        long wall = rt.clock.now();
        long hour = siegeNow / Durations.HOUR;
        for (Core keep : rt.state.cores()) {
            if (!keep.isKeep()) continue;
            Long last = LAST_HOUR.put(keep.id, hour);
            if (last != null && last == hour) continue;
            Store store = rt.state.siege.store(keep.id);
            double limit = rt.sim.rules().storageLimit(keep);
            double have = store.provisions.nourishment(rt.sim.food());
            double perHour = rt.sim.rules().upkeepPerHour(keep);
            JsonObject line = line(rt, "drain_hour", keep, wall, siegeNow);
            line.addProperty("provisions", round(have));
            line.addProperty("limit", round(limit));
            line.addProperty("percent", limit <= 0 ? 0 : round(100 * have / limit));
            line.addProperty("debt", round(store.eatDebt));
            line.addProperty("perHour", round(perHour));
            line.addProperty("daysLeft", perHour <= 0 ? -1 : round(have / perHour / 24));
            rt.siegeLog.write(line, wall);
        }
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
