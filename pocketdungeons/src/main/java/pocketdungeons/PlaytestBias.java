package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Playtest tooling: a runtime weight multiplier per room, so a playtest agent
 * (Lemon's driver, through {@code /dungeon admin bias}) can steer floors
 * toward the rooms a session needs to verify. Playtest 2026-09-29-2: the
 * thicket fix could not be checked because no floor rolled a thicket while
 * the agent could look.
 *
 * <p>A nudge on the selector's weighted pick ({@link RoomSelector}), never a
 * guarantee: a room still has to fit the cell's doors, role and depth, and the
 * repeat preference still applies. Nothing is saved; a restart or
 * {@link #clear} puts every room back on its declared weight. The same
 * multiplication the M71 recipe boost uses, applied beside it.
 *
 * <h2>Holds (PD-90)</h2>
 *
 * <p>Playtest 2026-09-29-3: the player agreed to a bias, then reached the door
 * while the agent was still setting it up. An agent now puts a <em>hold</em>
 * on the player first ({@code bias hold <player> <seconds>}): while it runs
 * the player can neither preview nor commit a door, and the door screen says
 * why with a countdown. Setting or clearing a bias releases every hold and
 * tells the player it is done. A hold that runs out (a stuck agent) releases
 * itself, at most {@link #MAX_HOLD_SECONDS} later, and says so.
 *
 * <p>A door preview plans its floor when it is opened, so a bias set after a
 * preview would not reach that floor. {@link #generation} counts bias changes;
 * the preview records it and the commit refuses a preview planned under an
 * older one, asking for the door to be opened again.
 */
final class PlaytestBias {

    /** The largest multiplier accepted, so one typo cannot swamp a floor with one room. */
    static final int MAX_MULTIPLIER = 50;
    /** A hold asked for without a length. */
    static final int DEFAULT_HOLD_SECONDS = 90;
    /** The longest hold accepted: a stuck agent keeps the player waiting no longer than this. */
    static final int MAX_HOLD_SECONDS = 180;

    /** Namespaced room name to multiplier; absent means 1. */
    private static final Map<String, Integer> MULTIPLIERS = new ConcurrentHashMap<>();
    /** Held player to the wall-clock millisecond their hold ends. */
    private static final Map<UUID, Long> HOLDS = new ConcurrentHashMap<>();
    /** Bumped on every bias change; see the class javadoc. */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static boolean tickRegistered;

    private PlaytestBias() {}

    /** The weight multiplier for {@code roomName}, 1 when no bias is set. */
    static int of(String roomName) {
        if (MULTIPLIERS.isEmpty() || roomName == null) {
            return 1;
        }
        return MULTIPLIERS.getOrDefault(roomName, 1);
    }

    /**
     * Sets {@code roomName}'s multiplier, clamped to 1..{@link #MAX_MULTIPLIER};
     * 1 removes the bias. A bare name is read in the {@code pocketdungeons}
     * namespace, the way the manifest names built-in rooms.
     *
     * @return the namespaced name the bias was stored under
     */
    static String set(String roomName, int multiplier) {
        String name = qualify(roomName);
        int clamped = Math.clamp(multiplier, 1, MAX_MULTIPLIER);
        if (clamped == 1) {
            MULTIPLIERS.remove(name);
        } else {
            MULTIPLIERS.put(name, clamped);
        }
        GENERATION.incrementAndGet();
        PocketDungeonsMod.LOG.info("Playtest bias: {} x{}", name, clamped);
        return name;
    }

    /** Drops every bias. */
    static void clear() {
        MULTIPLIERS.clear();
        GENERATION.incrementAndGet();
        PocketDungeonsMod.LOG.info("Playtest bias cleared");
    }

    /** Every bias in force, sorted by room name. */
    static Map<String, Integer> all() {
        return Collections.unmodifiableMap(new TreeMap<>(MULTIPLIERS));
    }

    /** How many times the biases have changed since the server started. */
    static int generation() {
        return GENERATION.get();
    }

    static String qualify(String roomName) {
        return roomName.contains(":") ? roomName : PocketDungeonsMod.MOD_ID + ":" + roomName;
    }

    /** {@code pocketdungeons:thicket x20, ...} without the built-in namespace, for a screen or a chat line. */
    static String describe() {
        List<String> parts = new ArrayList<>();
        all().forEach((room, mult) -> parts.add(
                room.replaceFirst("^" + PocketDungeonsMod.MOD_ID + ":", "") + " x" + mult));
        return String.join(", ", parts);
    }

    // ---- holds -------------------------------------------------------------------

    /**
     * Holds {@code player}'s next door for {@code seconds}, clamped to
     * 1..{@link #MAX_HOLD_SECONDS}. 0 or less releases it instead.
     *
     * @return the seconds actually held, 0 for a release
     */
    static int hold(UUID player, int seconds) {
        if (seconds <= 0) {
            HOLDS.remove(player);
            return 0;
        }
        int clamped = Math.min(seconds, MAX_HOLD_SECONDS);
        HOLDS.put(player, System.currentTimeMillis() + clamped * 1000L);
        PocketDungeonsMod.LOG.info("Playtest bias hold on {} for {}s", player, clamped);
        return clamped;
    }

    /** Whole seconds left on {@code player}'s hold, 0 when there is none (or it has run out). */
    static int holdSecondsLeft(UUID player) {
        Long until = player == null ? null : HOLDS.get(player);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Releases every hold, returning who was held. */
    static List<UUID> releaseAll() {
        List<UUID> released = new ArrayList<>(HOLDS.keySet());
        HOLDS.clear();
        return released;
    }

    /** Wires the hold timeout and the screen countdown. Call once at mod init. */
    static void register() {
        if (tickRegistered) {
            return;
        }
        tickRegistered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0 || HOLDS.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            List<UUID> expired = new ArrayList<>();
            HOLDS.entrySet().removeIf(e -> {
                if (e.getValue() <= now) {
                    expired.add(e.getKey());
                    return true;
                }
                return false;
            });
            for (UUID id : expired) {
                PocketDungeonsMod.LOG.warn("Playtest bias hold on {} ran out before a bias was set", id);
                tell(server, id, Component.literal("Lemon did not finish setting up the next floor in time. "
                        + "Your doors are open again; the next floor is planned as usual.")
                        .withStyle(ChatFormatting.YELLOW));
            }
            DungeonScreen.refreshDoorScreens(server);
        });
    }

    /**
     * After a bias change: releases every hold, tells each player in a room
     * that can choose a door what changed, and redraws their door screens.
     */
    static void announce(MinecraftServer server, String line) {
        releaseAll();
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (!record.visitInstance && RunSession.canChooseDoor(record)) {
                tell(server, record.owner, Component.literal(line).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
        DungeonScreen.refreshDoorScreens(server);
    }

    static void tell(MinecraftServer server, UUID id, Component line) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            player.sendSystemMessage(line);
        }
    }
}
