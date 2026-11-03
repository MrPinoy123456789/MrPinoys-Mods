package charter.gametest;

import charter.mc.LocatorBar;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Only a holder of charter.locatorbar (an operator, with no permissions mod) sees players on the locator bar. */
public final class LocatorBarGameTest {

    @GameTest(maxTicks = 100)
    public void onlyAPlayerWithTheNodeSeesTheLocatorBar(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer plain = kit.outsider();
            ServerPlayer op = kit.outsider();
            // A player's base value (the attribute's own default is 0); the slow refresh may already have added the modifier.
            double receive = plain.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE).getBaseValue();
            double transmit = plain.getAttributeValue(Attributes.WAYPOINT_TRANSMIT_RANGE);
            helper.assertTrue(receive > 0, "The default receive range is " + receive);
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            try {
                LocatorBar.applyTo(plain);
                LocatorBar.applyTo(op);
                helper.assertTrue(plain.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) == 0,
                        "A player without the node can see " + plain.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) + " blocks");
                helper.assertTrue(plain.getAttributeValue(Attributes.WAYPOINT_TRANSMIT_RANGE) == transmit,
                        "Hiding the bar changed what the player sends");
                helper.assertTrue(op.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) == receive,
                        "An operator's receive range is " + op.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE));

                // Losing the node hides it at the next refresh, without a relog.
                kit.server.getPlayerList().deop(op.nameAndId());
                LocatorBar.applyTo(op);
                helper.assertTrue(op.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) == 0, "A de-opped player still sees the bar");

                kit.rt.knobs.locatorBarAdminsOnly = false;
                LocatorBar.applyTo(plain);
                helper.assertTrue(plain.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) == receive,
                        "With the knob off the default range did not come back");
                helper.succeed();
            } finally {
                kit.rt.knobs.locatorBarAdminsOnly = true;
                kit.server.getPlayerList().deop(op.nameAndId());
            }
        }
    }
}
