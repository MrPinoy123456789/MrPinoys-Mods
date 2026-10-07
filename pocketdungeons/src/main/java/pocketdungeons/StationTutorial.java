package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Lemon's station tutorial: every time a player arrives home, Lemon nags them
 * toward the next station they have not yet placed. J5 made stations crafted
 * furniture with no unlock levels, so every step is due from the start; the
 * enum order is only which one Lemon mentions first when several are due. A
 * step is done the first time the player opens its station (or, for the
 * vendor, the moment a lectern is standing, since the sweep spawns the
 * librarian off the block, not an interaction), and that is stored for good
 * in the per-player task sidecar ({@link DungeonLog#taskProgress}).
 *
 * <p>The pure half ({@link #next}) takes the log so a headless test can
 * drive it.
 */
final class StationTutorial {

    private StationTutorial() {}

    enum Step {
        SALVAGE("salvage", "salvage bench") {
            String line() {
                return "Craft a " + blockName(PocketDungeonsConfig.salvageBlock())
                        + " and set it down in your room. It salvages spare gear and keys into emeralds.";
            }
        },
        REROLL("reroll", "enchanting table") {
            String line() {
                return "Craft an " + blockName(PocketDungeonsConfig.rerollBlock())
                        + " for your room. Right-click it holding dungeon gear to reroll one enchantment for lapis.";
            }
        },
        VENDOR("vendor", "home vendor") {
            String line() {
                return "Craft a lectern and set it down in your room."
                        + " A Librarian moves in beside it and trades emeralds for gear.";
            }
        };

        final String id;
        final String label;

        Step(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** What Lemon says to nudge the player toward it. */
        abstract String line();

        String key() {
            return "station_" + id;
        }

        /** The block that is this station, as a namespaced id, for the room scan to look for. */
        String blockId() {
            return switch (this) {
                case SALVAGE -> PocketDungeonsConfig.salvageBlock();
                case REROLL -> PocketDungeonsConfig.rerollBlock();
                case VENDOR -> "minecraft:lectern";
            };
        }
    }

    private static final Step[] ORDER = Step.values();

    /** A block id as plain words: {@code minecraft:enchanting_table} is "enchanting table". */
    static String blockName(String blockId) {
        String path = blockId == null ? "" : blockId.substring(blockId.indexOf(':') + 1);
        return path.replace('_', ' ');
    }

    /** The first step in rank order that {@code player} has not finished, or null. */
    static Step next(DungeonLog log, UUID player) {
        for (Step step : ORDER) {
            if (log.taskProgress(player, step.key()) < 1) {
                return step;
            }
        }
        return null;
    }

    /** Marks {@code step} done for {@code player}, forever. */
    static void markDone(DungeonLog log, UUID player, Step step) {
        log.setTaskProgress(player, step.key(), 1);
    }

    /** The player opened {@code step}'s station: the tutorial for it is over. */
    static void used(ServerPlayer player, Step step) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            markDone(DungeonLog.forServer(server), player.getUUID(), step);
        }
    }

    /** Lemon's nag, said to {@code player} as they arrive home. One step at a time. */
    static void nag(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        RoomScan.Summary room = RoomScan.latest(player.getUUID());
        Step step = next(log, player.getUUID());
        // A station already standing in the room needs no nudge (playtest 2026-10-03, A9/A5):
        // the scan of the last exit says so, and the step is done for good.
        while (step != null && room != null && room.has(step.blockId())) {
            markDone(log, player.getUUID(), step);
            step = next(log, player.getUUID());
        }
        if (step != null) {
            String tip = RoomScan.tip(room);
            Lemon.tour(player, tip == null ? step.line() : tip + " " + step.line());
        }
    }

    /** Nags every online member of {@code record}. */
    static void nagMembers(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                nag(player);
            }
        }
    }
}
