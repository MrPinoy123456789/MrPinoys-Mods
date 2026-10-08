package pocketdungeons;

import java.util.HashSet;
import java.util.Set;

/** Pure-JDK regression for the Plate Relay's rules: party scaling, the light's jump and the wave size. */
public class PlateRelayTest {

    public static void main(String[] args) {
        check(PlateRelayOrdeal.needed(1) == 5, "a solo player needs 5 charges");
        check(PlateRelayOrdeal.needed(2) == 6, "each extra player adds one");
        check(PlateRelayOrdeal.needed(4) == 8, "four players need 8");
        check(PlateRelayOrdeal.needed(9) == PlateRelayOrdeal.MAX_CHARGES, "capped");
        check(PlateRelayOrdeal.needed(0) == 5, "an empty room still reads the base");

        int plates = PlateRelayOrdeal.PLATE_SPOTS.length;
        check(plates == 4, "four corner plates");
        Set<Integer> seen = new HashSet<>();
        for (long key = 0; key < 200; key++) {
            for (int charges = 0; charges < 10; charges++) {
                for (int live = 0; live < plates; live++) {
                    int next = PlateRelayOrdeal.nextLive(live, plates, key, charges);
                    check(next != live && next >= 0 && next < plates, "the light always moves to another plate");
                    seen.add(next);
                }
            }
        }
        check(seen.size() == plates, "every plate gets lit sometimes");
        check(PlateRelayOrdeal.nextLive(1, plates, 77L, 3) == PlateRelayOrdeal.nextLive(1, plates, 77L, 3),
                "the jump is deterministic");

        check(PlateRelayOrdeal.waveSize(1, 1) == 1, "a solo first charge raises one mob");
        check(PlateRelayOrdeal.waveSize(1, 3) == 2, "from the third charge a second mob");
        check(PlateRelayOrdeal.waveSize(3, 1) == 2, "two extra players add one");
        check(PlateRelayOrdeal.waveSize(4, 4) <= PlateRelayOrdeal.ALIVE_CAP, "a wave fits under the alive cap");
        System.out.println("PlateRelayTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
