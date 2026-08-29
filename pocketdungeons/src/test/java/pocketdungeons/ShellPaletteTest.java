package pocketdungeons;

/**
 * Regression for the M24 shell palette registry on {@code RoomBuilder}: the
 * four shipped palettes resolve by unlock name, the default is always
 * available, unknown names fall back to it, and the prestige shell names a
 * real palette. Pure data, no server and no bootstrap, like {@link
 * RoomShellTest}.
 */
public class ShellPaletteTest {

    public static void main(String[] args) {
        // Blocks (and through it the registry-backed palette blocks) need the
        // same bootstrap LobbyBrowserTest runs; RoomBuilder's palette constants
        // resolve vanilla ids in their static initializers.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        check(RoomBuilder.SHELL_PALETTES.size(), 4, "four shipped palettes");
        check(RoomBuilder.isDefaultShell("oak"), true, "oak is the always-available default");
        check(RoomBuilder.isDefaultShell("sandstone"), false, "sandstone needs an unlock");
        check(RoomBuilder.palette("sandstone").displayName(), "Sandstone", "sandstone resolves");
        check(RoomBuilder.palette("deepslate").displayName(), "Deepslate", "deepslate resolves");
        check(RoomBuilder.palette("nether_brick").displayName(), "Nether Brick",
                "nether brick resolves");
        check(RoomBuilder.palette("not_a_shell").name(), "oak",
                "unknown names fall back to the default");
        check(RoomBuilder.SHELL_PALETTES.containsKey(RoomBuilder.PRESTIGE_SHELL), true,
                "the prestige shell is a real palette");
        check(RoomBuilder.shellOrder().size(), 4, "menu order lists every palette");
        check(RoomBuilder.shellOrder().get(0).name(), "oak", "the default leads the menu");

        System.out.println("ShellPaletteTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
