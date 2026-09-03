package pocketdungeons;

import java.util.List;
import java.util.Set;

/**
 * Regression for {@link CubeStation#sortedUnlocked} (M42.5): the imbue
 * picker must exclude a power that is already active on the player's worn
 * gear, since imbuing it onto more gear while it is already counted against
 * the equip cap adds nothing but the material cost. Needs the vanilla
 * registry bootstrap the same way {@code LobbyBrowserTest} does, since
 * {@code CubeStation} carries Minecraft type dependencies even though this
 * one method is pure {@code Set}/{@code List} work.
 */
public class CubeStationTest {

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testExcludesActivePowers();
        testAllActiveShowsEmpty();
        testSortedAlphabetically();

        System.out.println("CubeStationTest passed");
    }

    private static void testExcludesActivePowers() {
        Set<String> unlocked = Set.of("warden_ward", "phantom_sight", "frost_walker");
        Set<String> active = Set.of("phantom_sight");
        List<String> offered = CubeStation.sortedUnlocked(unlocked, active);
        check(offered, List.of("frost_walker", "warden_ward"), "active power excluded, rest sorted");
    }

    private static void testAllActiveShowsEmpty() {
        Set<String> unlocked = Set.of("warden_ward");
        Set<String> active = Set.of("warden_ward");
        List<String> offered = CubeStation.sortedUnlocked(unlocked, active);
        check(offered, List.of(), "every unlocked power already active offers nothing");
    }

    private static void testSortedAlphabetically() {
        Set<String> unlocked = Set.of("zephyr", "alpha", "mercy");
        List<String> offered = CubeStation.sortedUnlocked(unlocked, Set.of());
        check(offered, List.of("alpha", "mercy", "zephyr"), "no active powers, full set sorted");
    }

    private static void check(List<String> actual, List<String> expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
