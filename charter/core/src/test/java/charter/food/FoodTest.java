package charter.food;

import static charter.Check.check;
import static charter.Check.eq;

import charter.config.Knobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** K3, core half: eating, spoilage, mouths and storage, all as item counts and hours. */
public final class FoodTest {

    private FoodTest() {
    }

    private static FoodTable table() {
        return new FoodTable()
                .put("minecraft:bread", 5, ShelfClass.PRESERVED)
                .put("minecraft:dried_kelp", 1, ShelfClass.PRESERVED)
                .put("minecraft:cooked_beef", 8, ShelfClass.COOKED)
                .put("minecraft:carrot", 3, ShelfClass.FRESH);
    }

    public static void run() {
        eatOrder();
        eatTies();
        eatCarriesDebt();
        eatStepIndependence();
        spoilagePerClass();
        spoilageDebtCarries();
        fastSpoilagePastLimit();
        mouthsAndStorage();
        enduranceOfFullGranary();
    }

    private static void eatOrder() {
        Provisions p = new Provisions()
                .add("minecraft:cooked_beef", 5).add("minecraft:bread", 5)
                .add("minecraft:carrot", 2).add("minecraft:dried_kelp", 2);
        eq("eat order is least nourishing first",
                List.of("minecraft:dried_kelp", "minecraft:carrot", "minecraft:bread", "minecraft:cooked_beef"),
                EatPlan.order(p, table()));
        // Debt 7: both kelp (2), then one carrot (3); the second carrot needs 3 and 2 is left.
        EatPlan.Result r = EatPlan.plan(p, 7, table());
        eq("kelp eaten first", 2, r.eaten().get("minecraft:dried_kelp"));
        eq("one carrot eaten", 1, r.eaten().get("minecraft:carrot"));
        check("bread untouched while cheaper food remains", !r.eaten().containsKey("minecraft:bread"));
        eq("remainder carries as debt", 2.0, r.debt());
        p.apply(r.eaten());
        eq("carrots left after eating", 1, p.count("minecraft:carrot"));
        eq("beef left after eating", 5, p.count("minecraft:cooked_beef"));
    }

    private static void eatTies() {
        FoodTable t = new FoodTable()
                .put("a:pres5", 5, ShelfClass.PRESERVED)
                .put("z:fresh5", 5, ShelfClass.FRESH)
                .put("b:cooked5", 5, ShelfClass.COOKED)
                .put("c:pres5", 5, ShelfClass.PRESERVED);
        Provisions p = new Provisions().add("a:pres5", 1).add("z:fresh5", 1).add("b:cooked5", 1).add("c:pres5", 1);
        eq("ties: faster spoiling first, then item id",
                List.of("z:fresh5", "b:cooked5", "a:pres5", "c:pres5"), EatPlan.order(p, t));
        // Insertion order must not matter.
        Provisions q = new Provisions().add("c:pres5", 1).add("b:cooked5", 1).add("z:fresh5", 1).add("a:pres5", 1);
        eq("tie order ignores insertion order", EatPlan.order(p, t), EatPlan.order(q, t));
        EatPlan.Result r = EatPlan.plan(p, 10, t);
        eq("a debt of 10 eats the fresh then the cooked", Map.of("z:fresh5", 1, "b:cooked5", 1), r.eaten());
    }

    private static void eatCarriesDebt() {
        Provisions p = new Provisions().add("minecraft:bread", 3);
        EatPlan.Result r = EatPlan.plan(p, 4.9, table());
        check("debt below one item eats nothing", r.eaten().isEmpty());
        eq("and carries whole", 4.9, r.debt());
        r = EatPlan.plan(p, 5.0 + 4.9, table());
        eq("debt of 9.9 eats one bread", 1, r.eaten().get("minecraft:bread"));
        // Running dry reports the hunger nobody could feed.
        r = EatPlan.plan(p, 20, table());
        eq("a store that runs out eats all it has", 3, r.eaten().get("minecraft:bread"));
        eq("and reports the unmet hunger", 5.0, r.unmet());
        eq("with no debt left to carry", 0.0, r.debt());
        // Items the table does not know are never eaten.
        Provisions odd = new Provisions().add("minecraft:golden_apple", 4);
        check("non-food is never eaten", EatPlan.plan(odd, 100, table()).eaten().isEmpty());
    }

    private static void eatStepIndependence() {
        Provisions big = mixedStore();
        Provisions small = mixedStore();
        EatPlan.Result one = EatPlan.plan(big, 400, table());
        big.apply(one.eaten());

        double debt = 0;
        for (int i = 0; i < 1600; i++) {
            debt += 0.25;
            EatPlan.Result r = EatPlan.plan(small, debt, table());
            small.apply(r.eaten());
            debt = r.debt();
        }
        eq("1600 small steps remove what one big step does", big.counts(), small.counts());
        eq("and carry the same debt", one.debt(), debt);
    }

    private static Provisions mixedStore() {
        return new Provisions().add("minecraft:dried_kelp", 30).add("minecraft:carrot", 40)
                .add("minecraft:bread", 50).add("minecraft:cooked_beef", 20);
    }

    private static void spoilagePerClass() {
        Provisions p = new Provisions().add("minecraft:bread", 100).add("minecraft:cooked_beef", 100)
                .add("minecraft:carrot", 100);
        SpoilagePlan.Result r = SpoilagePlan.plan(p, Map.of(), table(), new Knobs(), 24, 1e9);
        eq("preserved loses 2% a day", 2, r.spoiled().get("minecraft:bread"));
        eq("cooked loses 10% a day", 10, r.spoiled().get("minecraft:cooked_beef"));
        eq("fresh loses 30% a day", 30, r.spoiled().get("minecraft:carrot"));
    }

    private static void spoilageDebtCarries() {
        Provisions p = new Provisions().add("minecraft:bread", 10);
        Map<String, Double> debts = new HashMap<>();
        int lost = 0;
        for (int hour = 0; hour < 24 * 4; hour++) {
            SpoilagePlan.Result r = SpoilagePlan.plan(p, debts, table(), new Knobs(), 1, 1e9);
            lost += r.spoiled().getOrDefault("minecraft:bread", 0);
            debts = r.debts();
        }
        eq("10 bread at 2% a day: no whole loss in four days", 0, lost);
        for (int hour = 0; hour < 24; hour++) {
            SpoilagePlan.Result r = SpoilagePlan.plan(p, debts, table(), new Knobs(), 1, 1e9);
            lost += r.spoiled().getOrDefault("minecraft:bread", 0);
            debts = r.debts();
        }
        eq("the carried fraction costs one bread on day five", 1, lost);
    }

    private static void fastSpoilagePastLimit() {
        Knobs k = new Knobs();
        Provisions p = new Provisions().add("minecraft:bread", 100).add("minecraft:carrot", 100);
        // 500 + 300 = 800 nourishment against a limit of 500: 300 over, all of it carrots.
        SpoilagePlan.Result over = SpoilagePlan.plan(p, Map.of(), table(), k, 24, 500);
        eq("the excess rots from the fresh food: 30 + 50 carrots", 80, over.spoiled().get("minecraft:carrot"));
        eq("bread only loses its normal 2%", 2, over.spoiled().get("minecraft:bread"));
        SpoilagePlan.Result within = SpoilagePlan.plan(p, Map.of(), table(), k, 24, 800);
        eq("at the limit nothing spoils fast", 30, within.spoiled().get("minecraft:carrot"));

        Provisions bread = new Provisions().add("minecraft:bread", 200);
        SpoilagePlan.Result b = SpoilagePlan.plan(bread, Map.of(), table(), k, 24, 500);
        eq("100 bread over the limit adds 50 lost to the 4 normal", 54, b.spoiled().get("minecraft:bread"));
    }

    private static void mouthsAndStorage() {
        Knobs k = new Knobs();
        eq("a tiny faction still feeds the minimum", 3, Mouths.base(k, 1, 1));
        eq("twelve members, one keep", 12, Mouths.base(k, 12, 1));
        eq("twelve members, two keeps share the load", 6, Mouths.base(k, 12, 2));
        eq("twelve members, six keeps floor at the minimum", 3, Mouths.base(k, 12, 6));
        eq("zero keeps does not divide by zero", 12, Mouths.base(k, 12, 0));
        eq("drain follows mouths", 120.0, Mouths.drainPerHour(k, 12));
        eq("storage is 84 hours of the drain", 10080.0, Mouths.storageLimit(k, 12, 1));
        eq("a second keep halves the storage per keep", 5040.0, Mouths.storageLimit(k, 12, 2));
        eq("more active members raise it", 12600.0, Mouths.storageLimit(k, 15, 1));
        eq("fewer active members lower it", 2520.0, Mouths.storageLimit(k, 2, 1));
        eq("prisoners add mouths", 14, Mouths.withPrisoners(k, 12, 1, 2));
        eq("prisoners raise the drain", 140.0, Mouths.drainPerHour(k, Mouths.withPrisoners(k, 12, 1, 2)));
        eq("but not the storage", 10080.0, Mouths.storageLimit(k, 12, 1));
    }

    /** A granary at its storage limit of preserved food lasts 84 hours within 7%, spoilage included. */
    private static void enduranceOfFullGranary() {
        for (int members : new int[] {3, 30}) {
            Knobs k = new Knobs();
            FoodTable t = table();
            double limit = Mouths.storageLimit(k, members, 1);
            Provisions p = new Provisions().add("minecraft:bread", (int) (limit / 5));
            double drain = Mouths.drainPerHour(k, Mouths.base(k, members, 1));
            double step = 0.25;
            double eatDebt = 0;
            Map<String, Double> spoilDebts = new HashMap<>();
            double hours = 0;
            while (!p.isEmpty() && hours < 200) {
                SpoilagePlan.Result s = SpoilagePlan.plan(p, spoilDebts, t, k, step, limit);
                p.apply(s.spoiled());
                spoilDebts = s.debts();
                EatPlan.Result e = EatPlan.plan(p, eatDebt + drain * step, t);
                p.apply(e.eaten());
                eatDebt = e.debt();
                hours += step;
            }
            check(members + " mouths: lasts at least 93% of 84 hours (got " + hours + ")", hours >= 84 * 0.93);
            check(members + " mouths: spoilage means it does not outlast 84 hours (got " + hours + ")", hours <= 84.5);
        }
    }
}
