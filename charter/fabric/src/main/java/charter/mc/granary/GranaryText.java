package charter.mc.granary;

import charter.food.Mouths;
import charter.food.Provisions;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.Text;
import charter.siege.CampSupply;
import charter.siege.KeepSupply;
import charter.time.Durations;

/**
 * The words on a granary's display and on its screen: the time left, read from
 * what is in the chest, and the state of the siege. The drain and the storage limit
 * come from core; this only divides and words them.
 */
public final class GranaryText {

    private GranaryText() {
    }

    /** Siege hours the provisions last at the current base drain, or -1 when nothing drains. */
    static double hoursLeft(CharterRuntime rt, Core core) {
        Provisions provisions = rt.state.siege.store(core.id).provisions;
        double nourishment = provisions.nourishment(rt.sim.food());
        double perHour = core.isKeep()
                ? Mouths.drainPerHour(rt.knobs, rt.sim.rules().mouths(core, 0))
                : (rt.state.siege.camp(core.id).supply == CampSupply.CUT_OFF ? rt.knobs.campBurnCutOffPerHour : rt.knobs.campBurnPerHour);
        return perHour <= 0 ? -1 : nourishment / perHour;
    }

    /** "2 days, 3 hours", "47 hours", "12 minutes". */
    static String duration(long millis) {
        if (millis <= 0) return Text.get("time.now");
        long days = millis / Durations.DAY;
        long hours = (millis % Durations.DAY) / Durations.HOUR;
        long minutes = (millis % Durations.HOUR) / Durations.MINUTE;
        if (days > 0) return Text.get("time.days_hours", "days", days, "hours", hours);
        if (hours > 0) return Text.get("time.hours_minutes", "hours", hours, "minutes", minutes);
        return Text.get("time.minutes", "minutes", Math.max(1, minutes));
    }

    public static String timeLine(CharterRuntime rt, Core core) {
        Provisions provisions = rt.state.siege.store(core.id).provisions;
        if (provisions.nourishment(rt.sim.food()) <= 0) return Text.get("granary.empty");
        double hours = hoursLeft(rt, core);
        if (hours < 0) return Text.get("granary.time_left", "time", Text.get("granary.no_drain"));
        return Text.get("granary.time_left", "time", duration((long) (hours * Durations.HOUR)));
    }

    /** The display text, one line per fact. */
    static String display(CharterRuntime rt, Core core) {
        StringBuilder out = new StringBuilder();
        out.append(Text.get(core.isKeep() ? "granary.title" : "granary.camp_title")).append('\n');
        if (rt.state.siege.falls.containsKey(core.id)) {
            out.append(Text.get("granary.fallen"));
            return out.toString();
        }
        out.append(timeLine(rt, core));
        if (core.isKeep()) {
            KeepSupply supply = rt.state.siege.keep(core.id).supply;
            if (supply == KeepSupply.BESIEGED) out.append('\n').append(Text.get("granary.besieged"));
            if (supply == KeepSupply.ENCIRCLED) out.append('\n').append(Text.get("granary.encircled"));
            double nourishment = rt.state.siege.store(core.id).provisions.nourishment(rt.sim.food());
            if (nourishment > rt.sim.rules().storageLimit(core)) out.append('\n').append(Text.get("granary.overstocked"));
        } else {
            CampSupply supply = rt.state.siege.camp(core.id).supply;
            if (supply == CampSupply.CUT_OFF) out.append('\n').append(Text.get("granary.cut_off"));
            if (supply == CampSupply.EMPTY) out.append('\n').append(Text.get("granary.camp_empty"));
        }
        return out.toString();
    }
}
