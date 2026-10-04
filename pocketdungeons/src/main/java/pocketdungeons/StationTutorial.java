package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Lemon's station tutorial: every time a player arrives home, Lemon nags them
 * toward the next room station they have unlocked and not yet used. The steps
 * are ranked by their position in {@link Step}, which is also the order their
 * keystone unlock levels climb, so when two are due at once the earlier one
 * comes first and the other waits for the next visit. A step is done the first
 * time the player opens its station, and that is stored for good in the
 * per-player task sidecar ({@link DungeonLog#taskProgress}).
 *
 * <p>The pure half ({@link #next}) takes the log and a level so a headless
 * test can drive it.
 */
final class StationTutorial {

    private StationTutorial() {}

    enum Step {
        SALVAGE("salvage", "salvage bench") {
            int unlockLevel() {
                return PocketDungeonsConfig.salvageUnlockLevel();
            }

            String line() {
                return "Craft a " + blockName(PocketDungeonsConfig.salvageBlock())
                        + " and set it down in your room. It salvages spare gear and keys into emeralds.";
            }
        },
        REROLL("reroll", "reroll station") {
            int unlockLevel() {
                return PocketDungeonsConfig.rerollUnlockLevel();
            }

            String line() {
                return "Craft a " + blockName(PocketDungeonsConfig.rerollBlock())
                        + " for your room. Right-click it holding dungeon gear to reroll an enchantment for lapis."
                        + " A Blacksmith moves in beside it, too.";
            }
        },
        GAMBLE("gamble", "gamble station") {
            int unlockLevel() {
                return PocketDungeonsConfig.gambleUnlockLevel();
            }

            String line() {
                return "Talk to the Blacksmith in your room (it appears beside a smithing table)."
                        + " It trades emeralds for a roll at a gear slot.";
            }
        },
        CUBE("cube", "Cube") {
            int unlockLevel() {
                return PocketDungeonsConfig.cubeUnlockLevel();
            }

            String line() {
                return "Build a " + blockName(PocketDungeonsConfig.cubeBlock())
                        + " in your room: it is the Cube. Extract powers from rare gear and imbue them into your own.";
            }
        };

        final String id;
        final String label;

        Step(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** The keystone level at which the station opens. */
        abstract int unlockLevel();

        /** What Lemon says to nudge the player toward it. */
        abstract String line();

        String key() {
            return "station_" + id;
        }
    }

    private static final Step[] ORDER = Step.values();

    /** A block id as plain words: {@code minecraft:smithing_table} is "smithing table". */
    static String blockName(String blockId) {
        String path = blockId == null ? "" : blockId.substring(blockId.indexOf(':') + 1);
        return path.replace('_', ' ');
    }

    /** The first step in rank order that {@code level} has unlocked and {@code player} has not finished, or null. */
    static Step next(DungeonLog log, UUID player, int level) {
        for (Step step : ORDER) {
            if (level >= step.unlockLevel() && log.taskProgress(player, step.key()) < 1) {
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
        Step step = next(log, player.getUUID(), log.get(player.getUUID()).keystoneLevel());
        if (step != null) {
            Lemon.say(player, step.line());
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
