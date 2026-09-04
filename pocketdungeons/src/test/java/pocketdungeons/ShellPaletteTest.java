package pocketdungeons;

/**
 * Regression for the M24 (plus M26's Alex's Room and PD-53's Stone Brick)
 * shell palette registry on {@code RoomBuilder}: the six shipped palettes
 * resolve by unlock name, the two default shells are always available,
 * unknown names fall back to the default, and the prestige shell names a
 * real palette. Pure data, no server and no bootstrap, like
 * {@link RoomShellTest}.
 */
public class ShellPaletteTest {

    public static void main(String[] args) {
        // Blocks (and through it the registry-backed palette blocks) need the
        // same bootstrap LobbyBrowserTest runs; RoomBuilder's palette constants
        // resolve vanilla ids in their static initializers.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        check(RoomBuilder.SHELL_PALETTES.size(), 6, "six shipped palettes");
        check(RoomBuilder.isDefaultShell("oak"), true, "oak is the always-available default");
        check(RoomBuilder.isDefaultShell("stone_brick"), true,
                "stone brick (PD-53) is also always available: every room starts on it");
        check(RoomBuilder.isDefaultShell("sandstone"), false, "sandstone needs an unlock");
        check(RoomBuilder.palette("stone_brick").displayName(), "Stone Brick",
                "stone brick resolves and is selectable, PD-53");
        check(RoomBuilder.palette("sandstone").displayName(), "Sandstone", "sandstone resolves");
        check(RoomBuilder.palette("deepslate").displayName(), "Deepslate", "deepslate resolves");
        check(RoomBuilder.palette("nether_brick").displayName(), "Nether Brick",
                "nether brick resolves");
        check(RoomBuilder.palette("alexs_room").displayName(), "Alex's Room",
                "alex's room resolves");
        check(RoomBuilder.palette("not_a_shell").name(), "oak",
                "unknown names fall back to the default");
        check(RoomBuilder.SHELL_PALETTES.containsKey(RoomBuilder.PRESTIGE_SHELL), true,
                "the prestige shell is a real palette");
        check(RoomBuilder.shellOrder().size(), 6, "menu order lists every palette");
        check(RoomBuilder.shellOrder().get(0).name(), "oak", "the default leads the menu");
        check(RoomBuilder.shellOrder().get(1).name(), "stone_brick",
                "stone brick follows oak, PD-53");
        check(RoomBuilder.STONE_BRICK.floor(), RoomBuilder.FLOOR,
                "stone brick keeps buildShell's exact floor material (polished andesite)");
        check(RoomBuilder.STONE_BRICK.wall(), RoomBuilder.WALL,
                "stone brick keeps buildShell's exact wall material (stone bricks)");

        System.out.println("ShellPaletteTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
