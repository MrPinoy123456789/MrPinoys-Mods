package pocketdungeons;

/** The wild marker's look follows its state (owner, 2026-11-04). */
public class WildLookTest {

    public static void main(String[] args) {
        check(WildLook.of(true, WildState.Phase.READY, true) == WildLook.EXIT, "an Exit is always the Exit look");
        check(WildLook.of(false, WildState.Phase.READY, false) == WildLook.READY, "Ready is gold");
        check(WildLook.of(false, WildState.Phase.IN_PROGRESS, false) == WildLook.READY, "in progress stays gold");
        check(WildLook.of(false, WildState.Phase.CLEARED, false) == WildLook.DIM, "cooling is dim");
        check(WildLook.of(false, WildState.Phase.READY, true) == WildLook.OMINOUS, "an omen shows");
        check(WildLook.of(false, WildState.Phase.IN_PROGRESS, true) == WildLook.OMINOUS, "an ominous run in progress");
        for (WildLook look : WildLook.values()) {
            check(look.crystal.startsWith("kingdoms:lodestone_crystal_"), look + " names a Foundry crystal");
        }
        System.out.println("WildLookTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
