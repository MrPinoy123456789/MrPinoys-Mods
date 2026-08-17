package cobblebending;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls every player for the hold-and-release state. No Mixin; we use the
 * vanilla CONSUMABLE component on the Focus stacks.
 */
public final class ChargeTracker {

    private static final Map<UUID, Charge> CHARGES = new ConcurrentHashMap<>();
    private static final float BRIDGE_PITCH = 60.0F;

    private ChargeTracker() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ChargeTracker::tick);
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                CHARGES.remove(player.getUUID());
                continue;
            }

            boolean using = player.isUsingItem();
            ItemStack useItem = using ? player.getUseItem() : ItemStack.EMPTY;
            UUID uuid = player.getUUID();
            Charge charge = CHARGES.get(uuid);

            if (using && Focus.is(useItem)) {
                Focus.ensureConsumable(useItem);
                int ticks = player.getTicksUsingItem();
                if (charge == null) {
                    charge = new Charge(useItem, ticks, false);
                    CHARGES.put(uuid, charge);
                } else {
                    charge.stack = useItem;
                    charge.ticks = ticks;
                }
                // Charge tier chimes on crossing 10 and 25 ticks.
                if (charge.ticks == 10 && !charge.mediumChimed) {
                    Chime.chargeMedium(player);
                    charge.mediumChimed = true;
                }
                if (charge.ticks == 25 && !charge.heavyChimed) {
                    Chime.chargeHeavy(player);
                    charge.heavyChimed = true;
                }
                // Wall focus with steep down pitch will fire a bridge on release.
                if (Focus.isWall(useItem) && player.getXRot() > BRIDGE_PITCH) {
                    charge.bridging = true;
                } else if (Focus.isWall(useItem)) {
                    charge.bridging = false;
                }
            } else if (charge != null) {
                // Released.
                CHARGES.remove(uuid);
                if (charge.stack != null && !charge.stack.isEmpty() && Focus.is(charge.stack)) {
                    onRelease(player, charge);
                }
            }
        }
    }

    private static void onRelease(ServerPlayer player, Charge charge) {
        if (Focus.isHurl(charge.stack)) {
            Hurl.fire(player, charge.ticks);
        } else if (Focus.isWall(charge.stack)) {
            if (player.getXRot() > BRIDGE_PITCH || charge.bridging) {
                Bridge.fire(player, charge.ticks);
            } else if (player.isShiftKeyDown()) {
                int refund = BentBlocks.recallWall(player);
                if (refund > 0) {
                    Ammo.give(player, refund);
                    Chime.wallRecalled(player);
                }
            } else {
                Wall.fire(player, charge.ticks);
            }
        }
    }

    private static final class Charge {
        ItemStack stack;
        int ticks;
        boolean bridging;
        boolean mediumChimed;
        boolean heavyChimed;

        Charge(ItemStack stack, int ticks, boolean bridging) {
            this.stack = stack;
            this.ticks = ticks;
            this.bridging = bridging;
        }
    }
}
