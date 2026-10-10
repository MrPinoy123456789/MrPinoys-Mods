package pocketdungeons;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * PD-199: {@link PressureSources#forget} is called when a member leaves an instance, and it used to
 * clear only the dwell timer. The Silenced consumable count stayed, so one or two uses in a finished
 * run made the first use in the next run send a wave early, and the map kept an entry for every
 * player who ever used one. The static map is private, so this reads it by reflection.
 */
public class PressureForgetTest {

    public static void main(String[] args) throws Exception {
        Field field = PressureSources.class.getDeclaredField("SILENCED_USES");
        field.setAccessible(true);
        Map<UUID, Integer> uses = Map.class.cast(field.get(null));

        UUID leaver = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID stayer = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        uses.put(leaver, 2);
        uses.put(stayer, 1);

        PressureSources.forget(leaver);

        check(!uses.containsKey(leaver), "forget drops the leaver's Silenced use count");
        check(uses.get(stayer) != null && uses.get(stayer) == 1, "forget leaves everyone else's count alone");
        uses.remove(stayer);
        System.out.println("PressureForgetTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
