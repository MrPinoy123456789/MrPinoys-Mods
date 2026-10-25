package charter.food;

/** One food: what a single item feeds a garrison, and how it keeps. */
public record FoodEntry(double nourishment, ShelfClass shelf) {

    public FoodEntry {
        if (!(nourishment > 0)) throw new IllegalArgumentException("nourishment must be above 0");
        if (shelf == null) throw new IllegalArgumentException("shelf is required");
    }
}
