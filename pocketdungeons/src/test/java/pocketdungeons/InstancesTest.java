package pocketdungeons;

/**
 * PD-204: {@code interval.dungeonId} stores the namespaced {@code def.id()}
 * ({@code pocketdungeons:kennels}) while callers like {@code HostileWolves}
 * ask with the bare name. The comparison must match on path either way.
 */
public class InstancesTest {

    public static void main(String[] args) {
        check(Instances.sameDungeonId("pocketdungeons:kennels", "kennels"),
                "namespaced stored id must match the bare name callers pass");
        check(Instances.sameDungeonId("pocketdungeons:kennels", "pocketdungeons:kennels"),
                "exact namespaced match must still pass");
        check(Instances.sameDungeonId("kennels", "pocketdungeons:kennels"),
                "a bare stored id must match a namespaced ask");
        check(Instances.sameDungeonId("kennels", "kennels"),
                "identical bare ids must match");
        check(!Instances.sameDungeonId("pocketdungeons:kennels", "mineshaft"),
                "a different dungeon must not match");
        check(!Instances.sameDungeonId("pocketdungeons:endless_mine", "kennels"),
                "different namespaced ids must not match");
        check(!Instances.sameDungeonId("", "kennels"),
                "a lobby interval (empty id) must never match a real dungeon");
        System.out.println("InstancesTest passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
