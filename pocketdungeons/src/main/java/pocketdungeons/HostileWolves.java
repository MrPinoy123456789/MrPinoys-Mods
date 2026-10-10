package pocketdungeons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;

/**
 * The Kennels\u0027 wolves (design pass 2026-10-09): bred to be aggressive, so every untamed wolf in a Kennels
 * dungeon (and any wolf a room marks) goes for the nearest player in sight and keeps going. Two kinds are
 * exempt: a wolf tagged {@link #CALM} (the Lost Dog, or a pack whose guard has fallen) and any tamed wolf.
 */
final class HostileWolves {

    /** A wolf a room marked hostile outside the Kennels (a finale pack). */
    static final String HOSTILE = PocketDungeonsMod.MOD_ID + ".hostile_wolf";
    /** A wolf that is not to be angered: the Lost Dog, or a pack its guard no longer drives. */
    static final String CALM = PocketDungeonsMod.MOD_ID + ".calm_wolf";
    /** How far from a player a wolf notices them. */
    private static final double SIGHT = 20.0;

    /** Guard tower rooms: the cell origin to the guard that drives the pack. */
    private static final Map<BlockPos, UUID> GUARDS = new HashMap<>();
    private static boolean registered;

    private HostileWolves() {}

    /** Marks {@code wolf} hostile for good. */
    static void mark(Wolf wolf) {
        wolf.addTag(HOSTILE);
        wolf.removeTag(CALM);
    }

    /** Marks {@code wolf} as one that is never angered. */
    static void calm(Wolf wolf) {
        wolf.addTag(CALM);
        wolf.removeTag(HOSTILE);
        wolf.stopBeingAngry();
        wolf.setTarget(null);
    }

    /** A guard now drives the pack in the room at {@code origin}; the pack calms when the guard dies. */
    static void guard(BlockPos origin, Mob guard) {
        GUARDS.put(origin.immutable(), guard.getUUID());
    }

    /** Whether a wolf should be after the party right now. */
    static boolean hostile(Wolf wolf, boolean inKennels) {
        if (wolf.isTame() || wolf.entityTags().contains(CALM)) {
            return false;
        }
        return inKennels || wolf.entityTags().contains(HOSTILE);
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 10 != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            sweepGuards(level);
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                    continue;
                }
                boolean kennels = Instances.inDungeon(player.blockPosition(), "kennels");
                for (Wolf wolf : level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(SIGHT),
                        w -> w.isAlive() && hostile(w, kennels))) {
                    if (wolf.getTarget() == null || !wolf.getTarget().isAlive()) {
                        wolf.setPersistentAngerTarget(EntityReference.of(player));
                        wolf.startPersistentAngerTimer();
                        wolf.setTarget(player);
                    }
                }
            }
        });
    }

    /** A guard that has fallen quiets the wolves of its room. */
    private static void sweepGuards(ServerLevel level) {
        for (Map.Entry<BlockPos, UUID> entry : new ArrayList<>(GUARDS.entrySet())) {
            Entity guard = level.getEntity(entry.getValue());
            if (guard instanceof Mob mob && mob.isAlive()) {
                continue;
            }
            GUARDS.remove(entry.getKey());
            AABB room = CellGeometry.cellBounds(entry.getKey());
            List<Wolf> pack = level.getEntitiesOfClass(Wolf.class, room, Wolf::isAlive);
            for (Wolf wolf : pack) {
                calm(wolf);
            }
            if (!pack.isEmpty()) {
                for (ServerPlayer player : level.players()) {
                    if (room.contains(player.position())) {
                        player.sendOverlayMessage(Component.literal("The pack goes quiet.")
                                .withStyle(ChatFormatting.AQUA));
                    }
                }
            }
        }
    }
}
