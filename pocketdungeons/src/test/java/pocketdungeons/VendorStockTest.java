package pocketdungeons;

import java.util.List;

/**
 * Pure regression for {@link VendorStock} (J5a): the offer plan is one line
 * per category and tier (nine at cap 3, six at cap 2), every line prices to
 * {@link VendorMath} and names a {@code gear/<category>_<tier>} table.
 * Needs the vanilla bootstrap only for {@code LootTables}.
 */
public class VendorStockTest {

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testActGatedCounts();
        testLines();
        System.out.println("VendorStockTest passed");
    }

    private static void testActGatedCounts() {
        check(VendorStock.gearLines(3).size(), 9, "Act 2 stock is nine gear offers");
        check(VendorStock.gearLines(2).size(), 6, "Act 1 stock is six gear offers");
        check(VendorStock.gearLines(1).size(), 3, "a cap of one tier is three offers");
    }

    private static void testLines() {
        List<VendorStock.GearLine> lines = VendorStock.gearLines(3);
        // Category-major ordering: every tier of armour, then weapons, then tools.
        check(lines.get(0).table(), "gear/armour_1", "armour leads");
        check(lines.get(2).table(), "gear/armour_3", "armour runs all tiers first");
        check(lines.get(3).table(), "gear/weapon_1", "weapons follow");
        check(lines.get(6).table(), "gear/tool_1", "tools are last");
        for (VendorStock.GearLine line : lines) {
            check(line.emeralds(), VendorMath.price(categoryOf(line), line.tier()),
                    "price matches VendorMath for " + line.table());
            check(line.table(), "gear/" + categoryOf(line) + "_" + line.tier(),
                    "table path matches category and tier");
        }
    }

    private static String categoryOf(VendorStock.GearLine line) {
        return line.table().substring("gear/".length(), line.table().lastIndexOf('_'));
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
