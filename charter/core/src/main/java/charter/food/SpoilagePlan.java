package charter.food;

import charter.config.Knobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spoilage over a stretch of siege time. Each item type loses its class's daily
 * rate of its count; whatever nourishment is past the storage limit loses the
 * fast rate as well, taken from the most perishable foods first. Losses build a
 * fractional debt per item type and whole items come off as the debt allows.
 * The loss is worked from the counts at the start of the step.
 */
public final class SpoilagePlan {

    private static final double EPS = 1e-9;

    /** Items spoiled and the per-type fractional debt to carry. */
    public record Result(Map<String, Integer> spoiled, Map<String, Double> debts) {
    }

    private SpoilagePlan() {
    }

    public static double ratePerDay(Knobs knobs, ShelfClass shelf) {
        return switch (shelf) {
            case PRESERVED -> knobs.spoilagePerDayPreserved;
            case COOKED -> knobs.spoilagePerDayCooked;
            case FRESH -> knobs.spoilagePerDayFresh;
        };
    }

    /**
     * @param debts        the carried per-type debt (not modified)
     * @param hours        siege hours this step covers
     * @param storageLimit nourishment the store holds without trouble
     */
    public static Result plan(Provisions provisions, Map<String, Double> debts, FoodTable table,
                              Knobs knobs, double hours, double storageLimit) {
        double days = hours / 24.0;
        double excess = Math.max(0, provisions.nourishment(table) - storageLimit);

        List<String> ids = new ArrayList<>();
        for (String id : provisions.counts().keySet()) {
            if (table.isFood(id)) ids.add(id);
        }
        // Most perishable first, so the excess rots from the fresh food up.
        ids.sort(Comparator
                .comparingInt((String id) -> -table.get(id).shelf().ordinal())
                .thenComparing(Comparator.naturalOrder()));

        Map<String, Double> newDebts = new TreeMap<>();
        Map<String, Integer> spoiled = new TreeMap<>();
        for (String id : ids) {
            FoodEntry f = table.get(id);
            int have = provisions.count(id);
            double loss = have * ratePerDay(knobs, f.shelf()) * days;
            double over = Math.min(have * f.nourishment(), excess);
            excess -= over;
            loss += (over / f.nourishment()) * knobs.spoilagePerDayPastLimit * days;

            double debt = debts.getOrDefault(id, 0.0) + loss;
            int whole = Math.min(have, (int) Math.floor(debt + EPS));
            if (whole > 0) {
                spoiled.put(id, whole);
                debt -= whole;
            }
            if (whole >= have) debt = 0;
            if (debt > EPS) newDebts.put(id, debt);
        }
        return new Result(spoiled, newDebts);
    }
}
