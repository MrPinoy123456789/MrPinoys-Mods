package charter.gametest;

import charter.mc.RaceNodes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Kingdom membership as the permission node race:<kingdom>, read the way another mod reads it. */
public final class RaceNodeGameTest {

    private static boolean holds(ServerPlayer player, String kingdomName) {
        return ((PermissionContextOwner) player).checkPermission(RaceNodes.nodeOf(kingdomName), false);
    }

    @GameTest(maxTicks = 100)
    public void aMemberHoldsTheirKingdomsNodeAndNoOthers(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            ServerPlayer stranger = kit.outsider();
            String dwarfKingdom = kit.rt.state.org(kit.orgOf(dwarf)).orElseThrow().name();
            String elfKingdom = kit.rt.state.org(kit.orgOf(elf)).orElseThrow().name();
            helper.assertTrue(holds(dwarf, dwarfKingdom), "a member holds their own kingdom's node");
            helper.assertTrue(!holds(dwarf, elfKingdom), "and not another kingdom's");
            helper.assertTrue(holds(elf, elfKingdom) && !holds(elf, dwarfKingdom), "each kingdom has its own people");
            helper.assertTrue(!holds(stranger, dwarfKingdom), "someone in no kingdom holds none");
            helper.assertTrue(!((PermissionContextOwner) dwarf).checkPermission(
                    net.minecraft.resources.Identifier.parse("other:" + RaceNodes.slug(dwarfKingdom)), false),
                    "only the race namespace is answered");
            helper.succeed();
        }
    }
}
