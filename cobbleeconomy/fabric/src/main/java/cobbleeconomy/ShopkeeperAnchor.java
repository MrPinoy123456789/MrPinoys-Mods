package cobbleeconomy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Locale;

/**
 * Periodically pulls tagged shopkeeper villagers back to the spot they were spawned
 * at, and re-applies their facing there. Without this, a villager with AI disabled
 * ({@link ShopkeeperCommands}) can still be shoved off its mark by other entities or
 * water, and its render-only body/head rotation resets to a default facing on chunk
 * reload since only the base yaw is persisted to disk.
 */
public final class ShopkeeperAnchor {

    static final String HOME_TAG_PREFIX = "cobbleeconomy_home:";

    private static final int CHECK_INTERVAL_TICKS = 100; // 5 seconds
    private static final double DRIFT_TOLERANCE_SQ = 0.25; // ~0.5 blocks

    private ShopkeeperAnchor() {}

    /** Encodes a shopkeeper's home spot as an entity tag, decoded later by the sweep below. */
    static String encodeHome(double x, double y, double z, float yaw) {
        return String.format(Locale.ROOT, HOME_TAG_PREFIX + "%.3f,%.3f,%.3f,%.3f", x, y, z, yaw);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Villager villager)) {
                        continue;
                    }
                    if (villager.entityTags().contains(ShopkeeperInteraction.SHOPKEEPER_TAG)) {
                        normalise(villager);
                    }
                    for (String tag : villager.entityTags()) {
                        if (tag.startsWith(HOME_TAG_PREFIX)) {
                            applyHome(villager, tag);
                            break;
                        }
                    }
                }
            }
        });
    }

    /**
     * Brings a shopkeeper up to current spec.
     *
     * <p>Every one of these is set at spawn too. It is repeated here so a shopkeeper
     * placed by an older build of the mod -- before it stood still, before it was
     * invulnerable -- becomes indistinguishable from a fresh one without the admin
     * having to find and replace it. Setting a flag that already holds costs nothing.
     */
    private static void normalise(Villager villager) {
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        villager.setPersistenceRequired();
    }

    private static void applyHome(Villager villager, String tag) {
        String[] parts = tag.substring(HOME_TAG_PREFIX.length()).split(",");
        if (parts.length != 4) {
            return;
        }

        double x, y, z;
        float yaw;
        try {
            x = Double.parseDouble(parts[0]);
            y = Double.parseDouble(parts[1]);
            z = Double.parseDouble(parts[2]);
            yaw = Float.parseFloat(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }

        double dx = villager.getX() - x;
        double dy = villager.getY() - y;
        double dz = villager.getZ() - z;
        if (dx * dx + dy * dy + dz * dz > DRIFT_TOLERANCE_SQ) {
            villager.setPos(x, y, z);
        }

        // Always re-applied, not just on drift: this is what fixes the visual
        // "faces the wrong way after a restart" glitch, cheaply, every sweep.
        villager.setYRot(yaw);
        villager.setYHeadRot(yaw);
        villager.setYBodyRot(yaw);
    }
}
