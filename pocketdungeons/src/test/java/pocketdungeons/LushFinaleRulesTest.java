package pocketdungeons;

/** Roadmap T23: the split top-up, the slime cap and the boss size ladder of the Lush Caves finale. */
public class LushFinaleRulesTest {

    public static void main(String[] args) {
        check(LushFinaleRules.childSize(8) == 4 && LushFinaleRules.childSize(4) == 2
                && LushFinaleRules.childSize(2) == 1, "children are half the size");
        check(LushFinaleRules.splits(2) && !LushFinaleRules.splits(1), "size 1 does not split");

        // Room and keep under the cap.
        check(LushFinaleRules.room(10, 40) == 30, "room under the cap");
        check(LushFinaleRules.room(50, 40) == 0, "no room over the cap");
        check(LushFinaleRules.keep(4, 0, 40) == 4, "all vanilla children kept with room");
        check(LushFinaleRules.keep(4, 38, 40) == 2, "only two fit");
        check(LushFinaleRules.keep(4, 40, 40) == 0, "none fit at the cap");

        // First split: vanilla gives 2 to 4, the top up doubles it.
        for (int vanilla = 2; vanilla <= 4; vanilla++) {
            int kept = LushFinaleRules.keep(vanilla, 1, 40);
            int extra = LushFinaleRules.topUp(vanilla, kept, 1, 40);
            check(kept + extra == 2 * vanilla, "vanilla " + vanilla + " becomes double: " + (kept + extra));
        }
        check(LushFinaleRules.topUp(4, 4, 36, 40) == 0, "no top up when the cap is already full");
        check(LushFinaleRules.topUp(4, 4, 34, 40) == 2, "the top up is held to what the cap leaves");
        check(LushFinaleRules.topUp(0, 0, 0, 40) == 0, "no vanilla children, no top up");
        check(LushFinaleRules.topUp(3, 3, 0, 4) == 1, "a tiny cap trims the top up");

        // The worst case (every split vanilla max, first split doubled) stays bounded by the cap.
        int alive = 1;
        int cap = 40;
        int size = 8;
        int level = 1;
        int total = 1;
        int wave = 1;
        while (LushFinaleRules.splits(size)) {
            int children = 0;
            for (int i = 0; i < wave; i++) {
                int vanilla = 4;
                int kept = LushFinaleRules.keep(vanilla, Math.min(cap, alive - 1 + children), cap);
                children += kept;
                if (level == 1) {
                    children += LushFinaleRules.topUp(vanilla, kept, alive - 1 + children - kept, cap);
                }
            }
            alive = alive - wave + children;
            check(alive <= cap, "the cap holds, alive " + alive);
            wave = children;
            size = LushFinaleRules.childSize(size);
            level++;
            total += children;
        }
        check(total > 8, "the fight still produces a crowd, got " + total);

        // Boss size ladder.
        check(LushFinaleRules.smallerSize(8) == 6 && LushFinaleRules.smallerSize(3) == 2
                && LushFinaleRules.smallerSize(2) == 2, "the boss steps down but never under 2");
        System.out.println("LushFinaleRulesTest ok");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }
}
